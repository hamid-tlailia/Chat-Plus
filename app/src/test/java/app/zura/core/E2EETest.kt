package app.zura.core

import app.zura.core.crypto.E2EE
import app.zura.core.crypto.SecureFile
import app.zura.core.crypto.SoftwareKeyWrapper
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class E2EETest {
    @get:Rule val tmp = TemporaryFolder()

    private val alice = E2EE.generateIdentity()
    private val bob = E2EE.generateIdentity()
    private val mallory = E2EE.generateIdentity()

    @Test fun roundTrip() {
        val msg = "مرحبا Bob 👋".encodeToByteArray()
        val env = E2EE.seal(alice, bob.public, msg)
        assertArrayEquals(msg, E2EE.open(bob, alice.public, env))
    }

    @Test fun everyEnvelopeIsUniqueEvenForSamePlaintext() {
        val m = "same".encodeToByteArray()
        assertFalse(E2EE.seal(alice, bob.public, m).contentEquals(E2EE.seal(alice, bob.public, m)))
    }

    @Test(expected = Exception::class) fun forgedSenderIsRejected() {
        // Mallory encrypts to Bob but claims to be Alice: the static-static ECDH won't match.
        val env = E2EE.seal(mallory, bob.public, "pay me".encodeToByteArray())
        E2EE.open(bob, alice.public, env)
    }

    @Test(expected = Exception::class) fun wrongRecipientCannotRead() {
        val env = E2EE.seal(alice, bob.public, "secret".encodeToByteArray())
        E2EE.open(mallory, alice.public, env)
    }

    @Test(expected = Exception::class) fun tamperingIsDetected() {
        val env = E2EE.seal(alice, bob.public, "hello".encodeToByteArray())
        env[env.size - 1] = (env[env.size - 1].toInt() xor 1).toByte()
        E2EE.open(bob, alice.public, env)
    }

    @Test fun publicKeyEncodingRoundTripsAndSafetyNumberIsSymmetric() {
        val decoded = E2EE.decodePublic(E2EE.encodePublic(alice.public))
        assertArrayEquals(alice.public.encoded, decoded.encoded)
        val ab = E2EE.safetyNumber(alice.public, bob.public)
        assertEquals(ab, E2EE.safetyNumber(bob.public, alice.public))
        assertNotEquals(ab, E2EE.safetyNumber(alice.public, mallory.public))
        assertEquals(12, ab.split(' ').size)
    }

    @Test fun secureFileEncryptsAndShreds() {
        val keys = SoftwareKeyWrapper()
        val f = File(tmp.root, "session.zs")
        val sf = SecureFile(f, keys, "zsess")
        sf.writeText("refresh-token-123")
        assertFalse(String(f.readBytes(), Charsets.ISO_8859_1).contains("refresh-token-123"))
        assertEquals("refresh-token-123", sf.readText())
        // Renaming the file breaks the AAD binding → unreadable.
        val moved = File(tmp.root, "other.zs"); f.copyTo(moved)
        assertNull(SecureFile(moved, keys, "zsess").readText())
        sf.shred()
        assertFalse(f.exists())
        assertNull(sf.readText())
    }
}
