package app.zura.core.crypto

import android.os.Build
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
        (ks.getKey(alias, null) as? SecretKey) ?: generate(alias, strongBox = Build.VERSION.SDK_INT >= 28)

    /**
     * Prefer StrongBox (a dedicated tamper-resistant secure chip, e.g. Titan M) and fall back to
     * the TEE when the device has none. Either way the key material never exists in app memory.
     */
    private fun generate(alias: String, strongBox: Boolean): SecretKey = try {
        KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, PROVIDER).apply {
            init(
                KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256)
                    .setRandomizedEncryptionRequired(true)
                    .apply { if (strongBox && Build.VERSION.SDK_INT >= 28) setIsStrongBoxBacked(true) }
                    .build(),
            )
        }.generateKey()
    } catch (e: Exception) {
        if (strongBox && Build.VERSION.SDK_INT >= 28 && e is android.security.keystore.StrongBoxUnavailableException) generate(alias, false)
        else throw e
    }

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

/**
 * Scopes every alias to one account ("a<uid8>_zc_chat_3"), so several accounts on one device never
 * share or clobber each other's keys, and removing an account destroys exactly its keys.
 */
class NamespacedKeyWrapper(private val base: KeyWrapper, private val prefix: String) : KeyWrapper {
    override fun wrap(alias: String, plain: ByteArray) = base.wrap(prefix + alias, plain)
    override fun unwrap(alias: String, wrapped: ByteArray) = base.unwrap(prefix + alias, wrapped)
    override fun destroy(alias: String) = base.destroy(prefix + alias)
    override fun aliases(): List<String> = base.aliases().filter { it.startsWith(prefix) }.map { it.removePrefix(prefix) }
    fun destroyAll() = base.aliases().filter { it.startsWith(prefix) }.forEach(base::destroy)
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
