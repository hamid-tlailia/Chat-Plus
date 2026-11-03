package app.zura.core.storage

import app.zura.core.crypto.Crypto
import app.zura.core.crypto.KeyWrapper
import app.zura.core.crypto.SegmentedCipher
import app.zura.core.model.ChatMeta
import app.zura.core.model.MediaRef
import kotlinx.coroutines.flow.MutableStateFlow
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
class VaultStore(val paths: ZuraPaths, private val wrapper: KeyWrapper) {

    private val open = ConcurrentHashMap<String, ChatStore>()
    private val _chatIds = MutableStateFlow(scanChatIds())
    val chatIds: StateFlow<Set<String>> = _chatIds.asStateFlow()

    fun chat(chatId: String): ChatStore = open.getOrPut(ZuraPaths.safe(chatId)) {
        ChatStore(chatId, paths.chatDir(chatId), wrapper).also { _chatIds.value = _chatIds.value + chatId }
    }

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

    private companion object {
        val B64: Base64.Encoder = Base64.getEncoder()
    }
}
