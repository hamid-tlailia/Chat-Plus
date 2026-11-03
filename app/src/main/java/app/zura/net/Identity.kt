package app.zura.net

import app.zura.core.crypto.E2EE
import app.zura.core.crypto.KeyWrapper
import app.zura.core.crypto.SecureFile
import java.io.File
import java.security.KeyPair

/**
 * This account's long-term E2EE identity (P-256). The private key lives only on this device,
 * encrypted under a hardware-backed key; the public half is published in profiles.identity_key.
 */
class Identity(dir: File, keys: KeyWrapper) {
    private val priv = SecureFile(File(dir, "identity.zs"), keys, "zid")
    private val pub = SecureFile(File(dir, "identity_pub.zs"), keys, "zidp")

    val keyPair: KeyPair by lazy {
        val p = priv.read()
        val q = pub.readText()
        if (p != null && q != null) KeyPair(E2EE.decodePublic(q), E2EE.decodePrivate(p))
        else E2EE.generateIdentity().also {
            priv.write(E2EE.encodePrivate(it.private))
            pub.writeText(E2EE.encodePublic(it.public))
        }
    }

    val publicB64: String get() = E2EE.encodePublic(keyPair.public)

}
