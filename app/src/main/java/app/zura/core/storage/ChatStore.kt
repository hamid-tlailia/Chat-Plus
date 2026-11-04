package app.zura.core.storage

import app.zura.core.crypto.Crypto
import app.zura.core.crypto.KeyWrapper
import app.zura.core.model.ChatMeta
import app.zura.core.model.Message
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.io.FileOutputStream
import java.util.Base64

/**
 * One chat = one directory = one encrypted, append-only log file (+ its media vault).
 * No SQLite, no ORM: opening a chat is one sequential read, decrypted on a background thread.
 *
 * log.zl format (UTF-8 lines):
 *   line 0   "K1:<generation>:<base64 wrapped chat key>"   ← key travels WITH the log,
 *                                                             so key+log swap in ONE atomic rename
 *   line 1…  base64( AES-GCM(chatKey, json(Record), aad = chatId) )
 *
 * Write paths:
 *  - send / receive / status / reaction → append one sealed line            O(1)
 *  - edit / delete                      → PURGE: rewrite the log under a brand-new chat key
 *                                         wrapped by a brand-new Keystore KEK, then destroy the
 *                                         old KEK inside Keystore. Any stale flash copy of the
 *                                         old log (and the media keys inside it) is now noise.
 *  - > 2× dead lines                    → compaction (same rewrite, no rotation)
 */
class ChatStore internal constructor(
    val chatId: String,
    private val dir: File,
    private val wrapper: KeyWrapper,
    /** Told about every meta change (and the meta found on open) — feeds the vault's chat-list index. */
    private val onMeta: (ChatMeta) -> Unit = {},
) {
    private val logFile = File(dir, "log.zl")
    val mediaDir = File(dir, "media")

    private val mutex = Mutex()
    private val aad = chatId.encodeToByteArray()
    private var generation = 0
    private lateinit var key: ByteArray
    private var lineCount = 0

    private val byId = LinkedHashMap<String, Message>()
    private val _messages = MutableStateFlow<List<Message>>(emptyList())
    val messages: StateFlow<List<Message>> = _messages.asStateFlow()

    private val _meta = MutableStateFlow<ChatMeta?>(null)
    val meta: StateFlow<ChatMeta?> = _meta.asStateFlow()

    init {
        mediaDir.mkdirs()
        if (logFile.exists() && logFile.length() > 0) replay() else rewriteLocked(rotateKey = true)
        _meta.value?.let(onMeta)
    }

    // ---------------------------------------------------------------- reads

    fun get(id: String): Message? = synchronized(byId) { byId[id] }

    fun mediaFile(name: String): File = File(mediaDir, ZuraPaths.safe(name.removeSuffix(".zv")) + ".zv")

    fun bytesOnDisk(): Long = dir.walkTopDown().filter { it.isFile }.sumOf { it.length() }

    val currentKeyAlias: String get() = alias(generation)

    /** Media files referenced by live records; anything else under media/ is an orphan. */
    fun liveMediaFiles(): Set<String> = synchronized(byId) {
        byId.values.mapNotNullTo(HashSet()) { m -> m.media?.let { mediaFile(it.fileName).name } }
    }

    // ---------------------------------------------------------------- writes

    /** Insert or replace (new message, status, reaction). Append-only fast path. */
    suspend fun upsert(message: Message) = upsertAll(listOf(message))

    suspend fun upsertAll(messages: Collection<Message>) = mutex.withLock {
        if (messages.isEmpty()) return@withLock
        messages.forEach { require(it.chatId == chatId) }
        appendLines(messages.map { seal(Record(put = it)) })
        synchronized(byId) { messages.forEach { byId[it.id] = it } }
        publish()
        if (lineCount > 64 && lineCount > byId.size * 2) rewriteLocked(rotateKey = false)
    }

    suspend fun writeMeta(meta: ChatMeta) = mutex.withLock {
        appendLines(listOf(seal(Record(meta = meta))))
        _meta.value = meta
        onMeta(meta)
    }

    /** Edit: the previous plaintext must not survive anywhere → purge-rewrite with rotation. */
    suspend fun edit(id: String, newText: String, at: Long): Message? = mutex.withLock {
        val old = synchronized(byId) { byId[id] } ?: return@withLock null
        val updated = old.copy(text = newText, editedAt = at)
        synchronized(byId) { byId[id] = updated }
        rewriteLocked(rotateKey = true)
        updated
    }

    /**
     * Permanently delete messages: text record, media key and media file. Returns bytes freed.
     * Order: rewrite log first (destroys the per-file keys), then unlink ciphertext. A crash in
     * between leaves keyless orphan ciphertext, which [StorageJanitor] sweeps on next start.
     */
    suspend fun purge(ids: Collection<String>): Long = mutex.withLock {
        val removed = synchronized(byId) { ids.mapNotNull { byId.remove(it) } }
        if (removed.isEmpty()) return@withLock 0L
        rewriteLocked(rotateKey = true)
        removed.sumOf { m -> m.media?.let { SecureEraser.erase(mediaFile(it.fileName)) } ?: 0L }
    }

    /** Called by VaultStore when the whole chat is erased. */
    internal fun destroyKeys() {
        wrapper.destroy(alias(generation))
        key.fill(0)
    }

    // ---------------------------------------------------------------- internals

    @Serializable
    private data class Record(val put: Message? = null, val meta: ChatMeta? = null)

    private fun alias(gen: Int) = "zc_${chatId}_$gen"

    private fun seal(r: Record): String =
        B64E.encodeToString(Crypto.seal(key, json.encodeToString(Record.serializer(), r).encodeToByteArray(), aad))

    private fun appendLines(lines: List<String>) {
        FileOutputStream(logFile, true).bufferedWriter().use { w -> lines.forEach { w.append(it).append('\n') } }
        lineCount += lines.size
    }

    private fun publish() {
        _messages.value = synchronized(byId) { byId.values.sortedBy { it.sentAt } }
    }

    private fun replay() {
        logFile.bufferedReader().useLines { lines ->
            val it = lines.iterator()
            val header = it.next().split(':')
            require(header.size == 3 && header[0] == "K1") { "Corrupt log header" }
            generation = header[1].toInt()
            key = wrapper.unwrap(alias(generation), B64D.decode(header[2]))
            it.forEachRemaining { line ->
                if (line.isBlank()) return@forEachRemaining
                lineCount++
                // A torn final line (power loss mid-append) fails GCM auth and is skipped.
                val r = runCatching {
                    json.decodeFromString(Record.serializer(), Crypto.open(key, B64D.decode(line), aad).decodeToString())
                }.getOrNull() ?: return@forEachRemaining
                r.put?.let { m -> byId[m.id] = m }
                r.meta?.let { m -> _meta.value = m }
            }
        }
        publish()
    }

    private fun rewriteLocked(rotateKey: Boolean) {
        val oldGen = generation
        val oldKey = if (::key.isInitialized) key else null
        if (rotateKey) {
            generation = if (oldKey == null) 0 else oldGen + 1
            key = Crypto.randomKey()
        }
        val snapshot = synchronized(byId) { byId.values.toList() }
        val meta = _meta.value
        logFile.writeAtomically { out ->
            val w = out.bufferedWriter()
            w.append("K1:$generation:").append(B64E.encodeToString(wrapper.wrap(alias(generation), key))).append('\n')
            meta?.let { w.append(seal(Record(meta = it))).append('\n') }
            snapshot.forEach { w.append(seal(Record(put = it))).append('\n') }
            w.flush()
        }
        lineCount = snapshot.size + (if (meta != null) 1 else 0)
        // Only after the new log is durably in place do we destroy the old KEK.
        if (rotateKey && oldKey != null) {
            wrapper.destroy(alias(oldGen))
            oldKey.fill(0)
        }
        publish()
    }

    companion object {
        internal val json = Json { ignoreUnknownKeys = true; explicitNulls = false }
        private val B64E = Base64.getEncoder().withoutPadding()
        private val B64D = Base64.getDecoder()
        internal const val ALIAS_PREFIX = "zc_"
    }
}
