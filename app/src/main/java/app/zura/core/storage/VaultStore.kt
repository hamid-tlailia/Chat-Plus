package app.zura.core.storage

import app.zura.core.crypto.Crypto
import app.zura.core.crypto.KeyWrapper
import app.zura.core.crypto.SegmentedCipher
import app.zura.core.model.ChatMeta
import app.zura.core.model.MediaRef
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.util.Base64
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Entry point to all on-device persistence. Owns the open [ChatStore]s and the media vault.
 * Pure JVM (no android.* imports) so the whole storage engine is unit-tested on the host.
 */
/** Chat storage is closed while the phone is locked (chat keys need an unlocked device). */
class VaultLockedException : IllegalStateException("phone locked: chat storage opens after unlock")

class VaultStore(
    val paths: ZuraPaths,
    private val wrapper: KeyWrapper,
    /** True while chat keys can't be used (lock screen up / before first unlock). */
    val isLocked: () -> Boolean = { false },
) {

    private val open = ConcurrentHashMap<String, ChatStore>()


    /**
     * Chat-list index: every chat's meta (title, preview, time, unread…) in ONE small encrypted
     * file. The list renders from it instantly instead of decrypting every chat's whole history
     * at start-up; a chat's log is opened only when the chat itself is needed.
     * Keyed "zc_…": like chat content, it needs an unlocked phone.
     */
    private val indexFile = app.zura.core.crypto.SecureFile(java.io.File(paths.root, "index.zs"), wrapper, INDEX_ALIAS)
    private val indexJson = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }
    private val indexSer = MapSerializer(String.serializer(), ChatMeta.serializer())
    /** False if the index exists but couldn't be read yet (phone locked): never overwrite it blindly. */
    @Volatile private var indexRead = false
    private fun readIndex(): Map<String, ChatMeta>? {
        if (!indexFile.exists()) return emptyMap<String, ChatMeta>().also { indexRead = true }
        val text = indexFile.readText()
        if (text == null) {
            // Its key is gone (e.g. destroyed by an older janitor): unrecoverable → start a fresh
            // index. Key still there but unusable (phone locked) → try again later, never overwrite.
            if (INDEX_ALIAS !in wrapper.aliases()) { SecureEraser.erase(java.io.File(paths.root, "index.zs")); indexRead = true; return emptyMap() }
            return null
        }
        return runCatching { indexJson.decodeFromString(indexSer, text) }.getOrNull().also { indexRead = true } ?: emptyMap()
    }
    private val _index = kotlinx.coroutines.flow.MutableStateFlow(readIndex().orEmpty())
    val metaIndex: kotlinx.coroutines.flow.StateFlow<Map<String, ChatMeta>> = _index

    /**
     * Work that needs the chat keys but came in while the phone was locked (e.g. a call-log
     * line). Persisted under a key that works while locked (it holds no message content —
     * chat id + call summary), so it survives the app being killed or the phone restarting.
     */
    @kotlinx.serialization.Serializable
    data class Deferred(val id: String, val chatId: String, val kind: String, val text: String)

    private val deferredFile = app.zura.core.crypto.SecureFile(java.io.File(paths.root, "deferred.zs"), wrapper, "zdefer")
    private val deferredSer = kotlinx.serialization.builtins.ListSerializer(Deferred.serializer())

    @Synchronized fun deferredWork(): List<Deferred> =
        runCatching { deferredFile.readText()?.let { indexJson.decodeFromString(deferredSer, it) } }.getOrNull().orEmpty()

    @Synchronized fun defer(d: Deferred) {
        val list = deferredWork().filterNot { it.id == d.id } + d
        runCatching { deferredFile.writeText(indexJson.encodeToString(deferredSer, list)) }
    }

    @Synchronized fun completeDeferred(id: String) {
        val list = deferredWork().filterNot { it.id == id }
        runCatching { if (list.isEmpty()) deferredFile.shredFileOnly() else deferredFile.writeText(indexJson.encodeToString(deferredSer, list)) }
    }

    /** Phone unlocked: read the index if it couldn't be read while locked. */
    fun retryIndex() {
        if (indexRead) return
        synchronized(_index) { readIndex()?.let { disk -> _index.value = disk + _index.value } }
    }

    private fun noteMeta(chatId: String, meta: ChatMeta) {
        if (_index.value[chatId] == meta) return
        synchronized(_index) {
            // First successful read after an unreadable start: merge what's on disk before writing.
            if (!indexRead) readIndex()?.let { disk -> _index.value = disk + _index.value }
            _index.value = _index.value + (chatId to meta)
            if (indexRead) runCatching { indexFile.writeText(indexJson.encodeToString(indexSer, _index.value)) }
        }
    }
    private val _chatIds = MutableStateFlow(scanChatIds())
    val chatIds: StateFlow<Set<String>> = _chatIds.asStateFlow()

    /**
     * Opens (or returns the already-open) chat. While the phone is locked, a chat that isn't open
     * yet is NOT attempted: a clean [VaultLockedException] instead of a Keystore failure; the
     * work is redone after unlock.
     */
    fun chat(chatId: String): ChatStore = open[ZuraPaths.safe(chatId)] ?: run {
        if (isLocked()) throw VaultLockedException()
        openChat(chatId)
    }

    private fun openChat(chatId: String): ChatStore {
        val k = ZuraPaths.safe(chatId)
        open[k]?.let { return it }
        val created = ChatStore(chatId, paths.chatDir(chatId), wrapper) { m -> noteMeta(chatId, m) }
        // Two threads may open the same chat at once: only the instance that is KEPT is announced
        // (maintenance on the discarded twin — e.g. a re-key — would break the kept one).
        return open.putIfAbsent(k, created) ?: created.also { _chatIds.value = _chatIds.value + chatId; onOpened?.invoke(it) }
    }

    /** Told about every chat opened (background maintenance, e.g. moving its key off slow hardware). */
    var onOpened: ((ChatStore) -> Unit)? = null

    fun exists(chatId: String) = File(paths.chatDir(chatId), "log.zl").exists()

    suspend fun createChat(meta: ChatMeta): ChatStore = chat(meta.id).also { it.writeMeta(meta) }

    /**
     * Erase an entire conversation: destroy its Keystore KEK (crypto-shred), then zero + unlink
     * the log and unlink every media file. Returns bytes freed.
     */
    fun deleteChat(chatId: String): Long {
        val store = open.remove(chatId)
            ?: if (exists(chatId)) runCatching { ChatStore(chatId, paths.chatDir(chatId), wrapper) }.getOrNull() else null
        store?.destroyKeys()
        val freed = SecureEraser.eraseTree(paths.chatDir(chatId))
        _chatIds.value = _chatIds.value - chatId
        synchronized(_index) {
            if (!indexRead) readIndex()?.let { disk -> _index.value = disk + _index.value }
            _index.value = _index.value - chatId
            if (indexRead) runCatching { indexFile.writeText(indexJson.encodeToString(indexSer, _index.value)) }
        }
        return freed
    }

    // ------------------------------------------------------------- media vault

    /** Encrypt [input] into the chat's vault under a fresh per-file key. */
    fun importMedia(
        chatId: String,
        input: InputStream,
        mime: String,
        width: Int = 0,
        height: Int = 0,
        durationMs: Long = 0,
        waveform: String? = null,
        preview: String? = null,
        originalName: String? = null,
    ): MediaRef {
        val fileKey = Crypto.randomKey()
        val name = UUID.randomUUID().toString().replace("-", "")
        val dst = chat(chatId).mediaFile(name)
        val tmp = File(dst.parentFile, "$name.part")
        val size = tmp.outputStream().buffered(SegmentedCipher.SEGMENT).use { SegmentedCipher.encrypt(fileKey, input, it) }
        check(tmp.renameTo(dst))
        return MediaRef(
            fileName = name, key = B64.encodeToString(fileKey), mime = mime, size = size,
            width = width, height = height, durationMs = durationMs, waveform = waveform,
            preview = preview, originalName = originalName,
        )
    }

    /** Store ciphertext received from the relay as-is (it is already vault format). */
    fun adoptCiphertext(chatId: String, ref: MediaRef, ciphertext: InputStream): MediaRef {
        val dst = chat(chatId).mediaFile(ref.fileName)
        val tmp = File(dst.parentFile, "${ref.fileName}.part")
        tmp.outputStream().use { ciphertext.copyTo(it) }
        check(tmp.renameTo(dst))
        return ref
    }

    fun mediaFile(chatId: String, ref: MediaRef): File = chat(chatId).mediaFile(ref.fileName)

    fun keyOf(ref: MediaRef): ByteArray = Base64.getDecoder().decode(ref.key)

    fun decryptTo(chatId: String, ref: MediaRef, out: OutputStream) =
        mediaFile(chatId, ref).inputStream().buffered(SegmentedCipher.SEGMENT).use { SegmentedCipher.decrypt(keyOf(ref), it, out) }

    fun openReader(chatId: String, ref: MediaRef) = SegmentedCipher.Reader(mediaFile(chatId, ref), keyOf(ref))

    fun totalBytes(): Long = paths.root.walkTopDown().filter { it.isFile }.sumOf { it.length() }

    private fun scanChatIds(): Set<String> =
        paths.chats.listFiles()?.filter { File(it, "log.zl").exists() }?.mapTo(HashSet()) { it.name } ?: emptySet()

    companion object {
        /** Keystore alias of the chat-list index (protected from the janitor's key sweep). */
        const val INDEX_ALIAS = "zc_index"
        private val B64: Base64.Encoder = Base64.getEncoder()
    }
}
