package app.zura.core.crypto

import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.PublicKey
import java.security.spec.ECGenParameterSpec
import java.security.spec.PKCS8EncodedKeySpec
import java.math.BigInteger
import java.security.interfaces.ECPublicKey
import java.security.spec.ECParameterSpec
import java.security.spec.ECPoint
import java.security.spec.ECPublicKeySpec
import java.util.Base64
import javax.crypto.KeyAgreement
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * End-to-end encryption for everything that leaves the device (messages, receipts, edits,
 * deletions, call signalling). The server only ever stores/relays opaque ciphertext.
 *
 * Construction — authenticated "one-pass" ECDH (Noise-K style), P-256 + HKDF-SHA256 + AES-256-GCM:
 *   e        = fresh ephemeral key pair per message           → forward secrecy for the sender side
 *   k        = HKDF( ECDH(e, R) ‖ ECDH(S, R),
 *                    info = "zura-e2ee-v1" ‖ S_pub ‖ R_pub ‖ e_pub )
 *   envelope = 0x01 ‖ e_pub(65, uncompressed) ‖ AES-GCM(k, plaintext, aad = S_pub ‖ R_pub)
 * The static-static term ECDH(S, R) authenticates the sender: only the holder of S's private key
 * can produce a ciphertext that decrypts under S's public key, so a malicious server cannot forge
 * or re-attribute messages. Keys are pinned on first use and changes are surfaced to the user.
 *
 * P-256 is used (not X25519) because it is available in every Android version's platform crypto
 * provider back to API 26 — no native library, no extra MBs. Public keys travel as raw SEC1 points
 * built from the coordinates, so the format never depends on a provider's encoding.
 */
object E2EE {
    private const val VERSION: Byte = 1
    private const val PUB_LEN = 65
    private val INFO = "zura-e2ee-v1".encodeToByteArray()

    fun generateIdentity(): KeyPair =
        KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair()

    fun encodePublic(k: PublicKey): String = Base64.getEncoder().encodeToString(rawPublic(k))
    fun decodePublic(b64: String): PublicKey = publicFromRaw(Base64.getDecoder().decode(b64))
    fun encodePrivate(k: PrivateKey): ByteArray = k.encoded
    fun decodePrivate(b: ByteArray): PrivateKey = KeyFactory.getInstance("EC").generatePrivate(PKCS8EncodedKeySpec(b))

    fun seal(sender: KeyPair, recipient: PublicKey, plaintext: ByteArray): ByteArray {
        val eph = generateIdentity()
        val ePub = rawPublic(eph.public)
        val k = deriveKey(ecdh(eph.private, recipient) + ecdh(sender.private, recipient), rawPublic(sender.public), rawPublic(recipient), ePub)
        val ct = Crypto.seal(k, plaintext, rawPublic(sender.public) + rawPublic(recipient))
        k.fill(0)
        return byteArrayOf(VERSION) + ePub + ct
    }

    fun open(recipient: KeyPair, sender: PublicKey, envelope: ByteArray): ByteArray {
        require(envelope.size > 1 + PUB_LEN + Crypto.NONCE + Crypto.TAG && envelope[0] == VERSION) { "bad envelope" }
        val ePubRaw = envelope.copyOfRange(1, 1 + PUB_LEN)
        val ePub = publicFromRaw(ePubRaw)
        val k = deriveKey(ecdh(recipient.private, ePub) + ecdh(recipient.private, sender), rawPublic(sender), rawPublic(recipient.public), ePubRaw)
        return try {
            Crypto.open(k, envelope.copyOfRange(1 + PUB_LEN, envelope.size), rawPublic(sender) + rawPublic(recipient.public))
        } finally {
            k.fill(0)
        }
    }

    /** Human-comparable safety number for two identities (same on both phones). */
    fun safetyNumber(a: PublicKey, b: PublicKey): String {
        val (x, y) = listOf(rawPublic(a), rawPublic(b)).sortedWith { p, q -> compare(p, q) }
        val h = MessageDigest.getInstance("SHA-256").digest(INFO + x + y)
        return (0 until 12).joinToString(" ") { i ->
            val v = ((h[i * 2].toInt() and 0xFF) shl 8 or (h[i * 2 + 1].toInt() and 0xFF)) % 100_000
            "%05d".format(v)
        }
    }

    // ---------------------------------------------------------------- internals

    private fun ecdh(priv: PrivateKey, pub: PublicKey): ByteArray =
        KeyAgreement.getInstance("ECDH").run { init(priv); doPhase(pub, true); generateSecret() }

    /** RFC 5869 HKDF-SHA256, 32-byte output. */
    private fun deriveKey(ikm: ByteArray, sPub: ByteArray, rPub: ByteArray, ePub: ByteArray): ByteArray {
        val prk = hmac(ByteArray(32), ikm)
        ikm.fill(0)
        return hmac(prk, INFO + sPub + rPub + ePub + byteArrayOf(1)).also { prk.fill(0) }
    }

    private fun hmac(key: ByteArray, data: ByteArray): ByteArray =
        Mac.getInstance("HmacSHA256").run { init(SecretKeySpec(key, "HmacSHA256")); doFinal(data) }

    /**
     * Wire format of a public key: the uncompressed SEC1 point 0x04 ‖ X(32) ‖ Y(32).
     * Built from the point's coordinates — never from getEncoded(), whose layout is
     * provider-specific and broke key exchange on some devices.
     */
    private fun rawPublic(k: PublicKey): ByteArray {
        val w = (k as ECPublicKey).w
        return byteArrayOf(4) + fixed32(w.affineX) + fixed32(w.affineY)
    }

    /** P-256 domain parameters, taken from a locally generated key (works with every provider). */
    private val P256: ECParameterSpec by lazy { (generateIdentity().public as ECPublicKey).params }

    private fun publicFromRaw(raw: ByteArray): PublicKey {
        require(raw.size == PUB_LEN && raw[0] == 4.toByte()) { "bad public key" }
        val x = BigInteger(1, raw.copyOfRange(1, 33))
        val y = BigInteger(1, raw.copyOfRange(33, 65))
        // Explicit on-curve check — platform KeyFactory implementations do NOT all do it, and an
        // off-curve point enables invalid-curve attacks that leak the private key via ECDH.
        val curve = P256.curve
        val p = (curve.field as java.security.spec.ECFieldFp).p
        require(x.signum() >= 0 && x < p && y.signum() >= 0 && y < p) { "coordinate out of range" }
        require(y.modPow(BigInteger.valueOf(2), p) == x.modPow(BigInteger.valueOf(3), p).add(curve.a.multiply(x)).add(curve.b).mod(p)) { "point not on P-256" }
        return KeyFactory.getInstance("EC").generatePublic(ECPublicKeySpec(ECPoint(x, y), P256))
    }

    private fun fixed32(v: BigInteger): ByteArray {
        val b = v.toByteArray()
        return when {
            b.size == 32 -> b
            b.size > 32 -> b.copyOfRange(b.size - 32, b.size) // drop sign byte
            else -> ByteArray(32 - b.size) + b
        }
    }

    private fun compare(a: ByteArray, b: ByteArray): Int {
        for (i in a.indices) { val d = (a[i].toInt() and 0xFF) - (b[i].toInt() and 0xFF); if (d != 0) return d }
        return 0
    }
}
