package app.zura.core.crypto

import app.zura.core.storage.SecureEraser
import app.zura.core.storage.writeAtomically
import java.io.File

/**
 * A small file (account list, auth session, identity key, cached avatar) encrypted at rest with
 * AES-256-GCM under a dedicated hardware-backed Keystore key. Format: wrappedDataKey(len-prefixed) ‖ sealed.
 * The file name is bound as AAD, so swapping two encrypted files on disk is detected.
 */
class SecureFile(private val file: File, private val keys: KeyWrapper, private val alias: String) {

    fun exists() = file.exists()

    fun read(): ByteArray? {
        if (!file.exists()) return null
        return runCatching {
            val all = file.readBytes()
            val raw = ((all[0].toInt() and 0xFF) shl 8) or (all[1].toInt() and 0xFF)
            val onFast = raw and FAST_FLAG != 0 // written under the fast key (see [fast])
            val n = raw and FAST_FLAG.inv()
            val wrapped = all.copyOfRange(2, 2 + n)
            val dataKey = keys.unwrap(if (onFast) fast else alias, wrapped)
            Crypto.open(dataKey, all.copyOfRange(2 + n, all.size), file.name.encodeToByteArray()).also { plain ->
                dataKey.fill(0)
                // Written under an older per-file key, or under the old (slow) key: re-key it to the
                // fast alias's shared data key, so the next start opens it without another slow
                // hardware call (e.g. every cached avatar).
                if (fast != alias && !onFast) runCatching { write(plain) }
                else if (keys.isShared(fast)) {
                    val (k, w) = keys.sharedKey(fast)
                    k.fill(0)
                    if (!w.contentEquals(wrapped)) runCatching { write(plain) }
                }
            }
        }.getOrNull()
    }

    fun write(plain: ByteArray) {
        file.parentFile?.mkdirs()
        val (dataKey, wrapped) = keys.sharedKey(fast)
        val sealed = Crypto.seal(dataKey, plain, file.name.encodeToByteArray())
        dataKey.fill(0)
        file.writeAtomically { out ->
            val len = wrapped.size or (if (fast != alias) FAST_FLAG else 0)
            out.write(byteArrayOf((len shr 8).toByte(), len.toByte()))
            out.write(wrapped)
            out.write(sealed)
        }
    }

    fun readText(): String? = read()?.decodeToString()
    fun writeText(s: String) = write(s.encodeToByteArray())

    /** Zero + unlink the file but keep the key (the file may be written again later). */
    fun shredFileOnly() { SecureEraser.erase(file) }

    /** Zero, unlink, and destroy the Keystore key — unrecoverable. */
    fun shred() {
        SecureEraser.erase(file)
        keys.destroy(alias)
        if (fast != alias) keys.destroy(fast)
    }

    /** Where new data goes (the hardware wrapper's fast key; the same alias elsewhere). */
    private val fast get() = keys.fastAlias(alias)

    private companion object { const val FAST_FLAG = 0x8000 }
}
