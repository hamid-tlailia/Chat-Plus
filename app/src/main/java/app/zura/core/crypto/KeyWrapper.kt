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

    /**
     * A data key that many small files under [alias] can share: (plain, wrapped). Default: a fresh
     * key per call (one hardware round-trip each). Hardware wrappers wrap it ONCE per process.
     * The caller owns the returned plain copy.
     */
    fun sharedKey(alias: String): Pair<ByteArray, ByteArray> = Crypto.randomKey().let { it to wrap(alias, it) }

    /** True if [sharedKey] really is shared for [alias] (files written under another key are re-keyed to it). */
    fun isShared(alias: String): Boolean = false

    /**
     * Alias that new small-file data for [alias] is written under. Differs when the key behind
     * [alias] is on slow hardware (StrongBox): files move to a fast key the next time they're read.
     */
    fun fastAlias(alias: String): String = alias

    /** True if [alias]'s key sits on slow hardware (a chat log then re-keys itself in the background). */
    fun isSlow(alias: String): Boolean = false
}

/**
 * Hardware-backed KEKs (TEE, or StrongBox when present). Key material never leaves secure
 * hardware; deleting an alias erases it inside Keystore. One Keystore round-trip per chat open,
 * one key generation per purge — never per message.
 */
class AndroidKeystoreWrapper : KeyWrapper {
    private val ks: KeyStore by lazy { KeyStore.getInstance(PROVIDER).apply { load(null) } }

    private fun kek(alias: String): SecretKey =
        (ks.getKey(alias, null) as? SecretKey) ?: generateBest(alias)

    /**
     * Chat-content keys ("…zc_…") additionally require an UNLOCKED phone (API 28+): a seized or
     * stolen phone that is locked cannot decrypt a single chat, even with forensic tools driving
     * the app. Account/session/identity keys stay usable while locked so calls can still ring.
     * Tries the strongest combination first and degrades only if the hardware refuses.
     */
    /*
     * TEE, not StrongBox. StrongBox is a separate secure chip: on the phones that have one each
     * operation measured ~500 ms (a cold start spent 2 s of its 2 s there; every chat opened paid
     * it again). The TEE is still hardware-isolated — the key never leaves it, can't be exported,
     * and chat keys still require an unlocked phone — and answers in a few ms. Keys created by
     * older versions on StrongBox are moved: small files on their next read ([fastAlias]), chat
     * logs by a background re-key ([isSlow]).
     */
    private fun generateBest(alias: String): SecretKey {
        val content = alias.contains("zc_") && Build.VERSION.SDK_INT >= 28
        val attempts = buildList {
            add(false to content)
            if (content) add(false to false)
        }
        var last: Exception? = null
        for ((sb, unlocked) in attempts) {
            try { return generate(alias, sb, unlocked) } catch (e: Exception) { last = e }
        }
        throw last ?: IllegalStateException("key generation failed")
    }

    /**
     * Prefer StrongBox (a dedicated tamper-resistant secure chip, e.g. Titan M) and fall back to
     * the TEE when the device has none. Either way the key material never exists in app memory.
     */
    private fun generate(alias: String, strongBox: Boolean, unlockedOnly: Boolean): SecretKey =
        KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, PROVIDER).apply {
            init(
                KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256)
                    .setRandomizedEncryptionRequired(true)
                    .apply {
                        if (Build.VERSION.SDK_INT >= 28) {
                            if (strongBox) setIsStrongBoxBacked(true)
                            if (unlockedOnly) setUnlockedDeviceRequired(true)
                        }
                    }
                    .build(),
            )
        }.generateKey()

    override fun wrap(alias: String, plain: ByteArray): ByteArray = timed {
        val c = Cipher.getInstance(Crypto.GCM)
        c.init(Cipher.ENCRYPT_MODE, kek(alias)) // Keystore picks the IV
        c.iv + c.doFinal(plain)
    }

    override fun unwrap(alias: String, wrapped: ByteArray): ByteArray {
        if (cacheable(alias)) opened[ck(alias, wrapped)]?.let { return it.copyOf() }
        val plain = timed {
            val key = ks.getKey(alias, null) as? SecretKey ?: error("KEK $alias destroyed")
            val c = Cipher.getInstance(Crypto.GCM)
            c.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, wrapped, 0, Crypto.NONCE))
            c.doFinal(wrapped, Crypto.NONCE, wrapped.size - Crypto.NONCE)
        }
        if (cacheable(alias)) {
            opened[ck(alias, wrapped)] = plain.copyOf()
            shared.putIfAbsent(alias, plain.copyOf() to wrapped.copyOf())
        }
        return plain
    }

    /*
     * Speed: every Keystore call is a round-trip into secure hardware — on phones with StrongBox
     * (a separate chip) that is tens to hundreds of ms EACH, and a cold start used to make one
     * per small file (account, session, identity, chat-list index, contacts, every avatar…) and
     * one more on every write of the chat-list index (= every message). Now, for the small-file
     * keys, each wrapped data key is opened once per process and files under the same alias
     * share one data key, so a cold start costs about one hardware call per alias.
     * Chat-content keys ("zc_…", unlocked-phone-only) are never cached here — except the
     * chat-list index, which is written on every message.
     */
    private val opened = ConcurrentHashMap<String, ByteArray>()
    private val shared = ConcurrentHashMap<String, Pair<ByteArray, ByteArray>>()
    private fun cacheable(alias: String) = !alias.contains("zc_") || alias.contains("zc_index")

    override fun fastAlias(alias: String) = if (alias.endsWith(FAST)) alias else alias + FAST

    override fun isSlow(alias: String): Boolean = Build.VERSION.SDK_INT >= 31 && slow.getOrPut(alias) {
        runCatching {
            val key = ks.getKey(alias, null) as? SecretKey ?: return@runCatching false
            val info = javax.crypto.SecretKeyFactory.getInstance(key.algorithm, PROVIDER).getKeySpec(key, android.security.keystore.KeyInfo::class.java) as android.security.keystore.KeyInfo
            info.securityLevel == KeyProperties.SECURITY_LEVEL_STRONGBOX
        }.getOrDefault(false)
    }
    private val slow = ConcurrentHashMap<String, Boolean>()
    private fun ck(alias: String, wrapped: ByteArray) = alias + "|" + java.util.Base64.getEncoder().encodeToString(wrapped)

    override fun isShared(alias: String) = cacheable(alias)

    override fun sharedKey(alias: String): Pair<ByteArray, ByteArray> {
        if (!cacheable(alias)) return super.sharedKey(alias)
        val (k, w) = shared.computeIfAbsent(alias) { val key = Crypto.randomKey(); key to wrap(alias, key) }
        opened.putIfAbsent(ck(alias, w), k.copyOf())
        return k.copyOf() to w
    }

    override fun destroy(alias: String) {
        shared.remove(alias)?.first?.fill(0)
        opened.keys.filter { it.startsWith("$alias|") }.forEach { opened.remove(it)?.fill(0) }
        runCatching { ks.deleteEntry(alias) }
    }

    private inline fun <T> timed(block: () -> T): T {
        val t = System.nanoTime()
        try { return block() } finally { KeystoreStats.add(System.nanoTime() - t) }
    }

    override fun aliases(): List<String> = ks.aliases().toList()

    private companion object { const val PROVIDER = "AndroidKeyStore"; const val FAST = "_t" }
}

/**
 * Scopes every alias to one account ("a<uid8>_zc_chat_3"), so several accounts on one device never
 * share or clobber each other's keys, and removing an account destroys exactly its keys.
 */
class NamespacedKeyWrapper(private val base: KeyWrapper, private val prefix: String) : KeyWrapper {
    override fun wrap(alias: String, plain: ByteArray) = base.wrap(prefix + alias, plain)
    override fun unwrap(alias: String, wrapped: ByteArray) = base.unwrap(prefix + alias, wrapped)
    override fun destroy(alias: String) = base.destroy(prefix + alias)
    override fun sharedKey(alias: String) = base.sharedKey(prefix + alias)
    override fun isShared(alias: String) = base.isShared(prefix + alias)
    override fun fastAlias(alias: String) = base.fastAlias(prefix + alias).removePrefix(prefix)
    override fun isSlow(alias: String) = base.isSlow(prefix + alias)
    override fun aliases(): List<String> = base.aliases().filter { it.startsWith(prefix) }.map { it.removePrefix(prefix) }
    fun destroyAll() = base.aliases().filter { it.startsWith(prefix) }.forEach(base::destroy)
}

/** Hardware Keystore calls made by this process and the time they took (start-up diagnostics). */
object KeystoreStats {
    private val ops = java.util.concurrent.atomic.AtomicInteger()
    private val nanos = java.util.concurrent.atomic.AtomicLong()
    fun add(ns: Long) { ops.incrementAndGet(); nanos.addAndGet(ns) }
    fun summary() = "keystore ${ops.get()} calls, ${nanos.get() / 1_000_000} ms"
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
