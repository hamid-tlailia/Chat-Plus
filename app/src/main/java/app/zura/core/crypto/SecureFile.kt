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
            val dataKey = keys.unwrap(alias, all.copyOfRange(2, 2 + n))
            Crypto.open(dataKey, all.copyOfRange(2 + n, all.size), file.name.encodeToByteArray()).also { dataKey.fill(0) }
        }.getOrNull()
    }

    fun write(plain: ByteArray) {
        file.parentFile?.mkdirs()
        val dataKey = Crypto.randomKey()
        val wrapped = keys.wrap(alias, dataKey)
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

    /** Zero, unlink, and destroy the Keystore key — unrecoverable. */
    fun shred() {
        SecureEraser.erase(file)
        keys.destroy(alias)
    }
}
