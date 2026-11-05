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
            val n = ((all[0].toInt() and 0xFF) shl 8) or (all[1].toInt() and 0xFF)
            val wrapped = all.copyOfRange(2, 2 + n)
            val dataKey = keys.unwrap(alias, wrapped)
            Crypto.open(dataKey, all.copyOfRange(2 + n, all.size), file.name.encodeToByteArray()).also { plain ->
                dataKey.fill(0)
                // Written under an older per-file key: re-key it to the alias's shared data key, so
                // the next start opens it without another hardware call (e.g. every cached avatar).
                if (keys.isShared(alias)) {
                    val (k, w) = keys.sharedKey(alias)
                    k.fill(0)
                    if (!w.contentEquals(wrapped)) runCatching { write(plain) }
                }
            }
        }.getOrNull()
    }

    fun write(plain: ByteArray) {
        file.parentFile?.mkdirs()
        val (dataKey, wrapped) = keys.sharedKey(alias)
        val sealed = Crypto.seal(dataKey, plain, file.name.encodeToByteArray())
        dataKey.fill(0)
        file.writeAtomically { out ->
            out.write(byteArrayOf((wrapped.size shr 8).toByte(), wrapped.size.toByte()))
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
    }
}
