package app.zura.core.crypto

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyStore
import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Wraps small secrets (chat keys) under named key-encryption keys that can be *destroyed*.
 * Destroying a KEK is the crypto-shred primitive: every ciphertext ever written under the keys
 * it protected — including stale copies the flash controller still holds — becomes noise.
 */
interface KeyWrapper {
    fun wrap(alias: String, plain: ByteArray): ByteArray
    fun unwrap(alias: String, wrapped: ByteArray): ByteArray
    fun destroy(alias: String)
    fun aliases(): List<String>
}

/**
 * Hardware-backed KEKs (TEE, or StrongBox when present). Key material never leaves secure
 * hardware; deleting an alias erases it inside Keystore. One Keystore round-trip per chat open,
 * one key generation per purge — never per message.
 */
class AndroidKeystoreWrapper : KeyWrapper {
    private val ks: KeyStore by lazy { KeyStore.getInstance(PROVIDER).apply { load(null) } }

    private fun kek(alias: String): SecretKey =
        (ks.getKey(alias, null) as? SecretKey) ?: KeyGenerator
            .getInstance(KeyProperties.KEY_ALGORITHM_AES, PROVIDER)
            .apply {
                init(
                    KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                        .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                        .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                        .setKeySize(256)
                        .build(),
                )
            }
            .generateKey()

    override fun wrap(alias: String, plain: ByteArray): ByteArray {
        val c = Cipher.getInstance(Crypto.GCM)
        c.init(Cipher.ENCRYPT_MODE, kek(alias)) // Keystore picks the IV
        return c.iv + c.doFinal(plain)
    }

    override fun unwrap(alias: String, wrapped: ByteArray): ByteArray {
        val key = ks.getKey(alias, null) as? SecretKey ?: error("KEK $alias destroyed")
        val c = Cipher.getInstance(Crypto.GCM)
        c.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, wrapped, 0, Crypto.NONCE))
        return c.doFinal(wrapped, Crypto.NONCE, wrapped.size - Crypto.NONCE)
    }

    override fun destroy(alias: String) {
        runCatching { ks.deleteEntry(alias) }
    }

    override fun aliases(): List<String> = ks.aliases().toList()

    private companion object { const val PROVIDER = "AndroidKeyStore" }
}

/** In-memory KEKs for JVM unit tests. */
class SoftwareKeyWrapper : KeyWrapper {
    private val keks = ConcurrentHashMap<String, ByteArray>()
    override fun wrap(alias: String, plain: ByteArray) = Crypto.seal(keks.getOrPut(alias) { Crypto.randomKey() }, plain)
    override fun unwrap(alias: String, wrapped: ByteArray) = Crypto.open(keks[alias] ?: error("KEK $alias destroyed"), wrapped)
    override fun destroy(alias: String) { keks.remove(alias) }
    override fun aliases(): List<String> = keks.keys.toList()
}

object Crypto {
    const val GCM = "AES/GCM/NoPadding"
    const val NONCE = 12
    const val TAG = 16
    val rng = SecureRandom()

    fun randomKey(): ByteArray = ByteArray(32).also(rng::nextBytes)

    /** nonce(12) || ciphertext || tag(16) */
    fun seal(key: ByteArray, plain: ByteArray, aad: ByteArray? = null): ByteArray {
        val nonce = ByteArray(NONCE).also(rng::nextBytes)
        val c = Cipher.getInstance(GCM)
        c.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(TAG * 8, nonce))
        aad?.let(c::updateAAD)
        return nonce + c.doFinal(plain)
    }

    fun open(key: ByteArray, sealed: ByteArray, aad: ByteArray? = null): ByteArray {
        val c = Cipher.getInstance(GCM)
        c.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(TAG * 8, sealed, 0, NONCE))
        aad?.let(c::updateAAD)
        return c.doFinal(sealed, NONCE, sealed.size - NONCE)
    }
}
