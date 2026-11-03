package app.zura.core

import app.zura.core.crypto.SegmentedCipher
import app.zura.core.crypto.SoftwareKeyWrapper
import app.zura.core.model.ChatMeta
import app.zura.core.model.Message
import app.zura.core.model.MsgKind
import app.zura.core.model.MsgStatus
import app.zura.core.storage.StorageJanitor
import app.zura.core.storage.VaultStore
import app.zura.core.storage.ZuraPaths
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import kotlin.random.Random

class StorageEngineTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun vault(wrapper: SoftwareKeyWrapper = SoftwareKeyWrapper()): Pair<VaultStore, SoftwareKeyWrapper> {
        val root = tmp.root
        return VaultStore(ZuraPaths(File(root, "files"), File(root, "nb"), File(root, "cache")), wrapper) to wrapper
    }

    private fun msg(id: String, text: String, at: Long = System.nanoTime()) =
        Message(id = id, chatId = "c1", senderId = "me", sentAt = at, kind = MsgKind.TEXT, text = text)

    @Test fun segmentedCipherRoundTripAndRandomAccess() {
        for (size in listOf(0, 1, SegmentedCipher.SEGMENT - 1, SegmentedCipher.SEGMENT, SegmentedCipher.SEGMENT * 3 + 17)) {
            val key = ByteArray(32).also { Random.nextBytes(it) }
            val plain = Random.nextBytes(size)
            val ct = ByteArrayOutputStream().also { SegmentedCipher.encrypt(key, ByteArrayInputStream(plain), it) }.toByteArray()
            val back = ByteArrayOutputStream().also { SegmentedCipher.decrypt(key, ByteArrayInputStream(ct), it) }.toByteArray()
            assertArrayEquals("size=$size", plain, back)

            val f = tmp.newFile().apply { writeBytes(ct) }
            SegmentedCipher.Reader(f, key).use { r ->
                assertEquals(size.toLong(), r.plainSize)
                if (size > 10) {
                    val pos = size / 2L
                    val buf = ByteArray(8)
                    val n = r.read(pos, buf, 0, 8)
                    assertArrayEquals(plain.copyOfRange(pos.toInt(), pos.toInt() + n), buf.copyOf(n))
                }
            }
        }
    }

    @Test(expected = Exception::class) fun truncationIsDetected() {
        val key = ByteArray(32).also { Random.nextBytes(it) }
        val plain = Random.nextBytes(SegmentedCipher.SEGMENT * 2 + 5)
        val ct = ByteArrayOutputStream().also { SegmentedCipher.encrypt(key, ByteArrayInputStream(plain), it) }.toByteArray()
        val truncated = ct.copyOf(SegmentedCipher.HEADER + SegmentedCipher.SEGMENT + 16) // drop last segments
        SegmentedCipher.decrypt(key, ByteArrayInputStream(truncated), ByteArrayOutputStream())
    }

    @Test fun logPersistsAcrossReopenAndNothingIsPlaintext() = runBlocking {
        val (v, w) = vault()
        v.createChat(ChatMeta("c1", "Sara", listOf("u2"), 1L))
        v.chat("c1").upsert(msg("m1", "secret-hello"))
        v.chat("c1").upsert(msg("m1", "secret-hello").copy(status = MsgStatus.READ))

        val raw = File(tmp.root, "files/zura/chats/c1/log.zl").readText()
        assertFalse(raw.contains("secret-hello"))

        val (v2, _) = vault(w) // fresh process, same Keystore
        val reopened = v2.chat("c1")
        assertEquals(1, reopened.messages.value.size)
        assertEquals(MsgStatus.READ, reopened.messages.value.single().status)
        assertEquals("Sara", reopened.meta.value?.title)
    }

    @Test fun purgeRotatesKeyDeletesMediaAndOldKeyIsDestroyed() = runBlocking {
        val (v, w) = vault()
        val store = v.chat("c1")
        val ref = v.importMedia("c1", ByteArrayInputStream(Random.nextBytes(200_000)), "image/webp")
        store.upsert(msg("m1", "photo").copy(kind = MsgKind.IMAGE, media = ref))
        store.upsert(msg("m2", "keep me"))
        val oldAlias = store.currentKeyAlias
        val mediaFile = v.mediaFile("c1", ref)
        assertTrue(mediaFile.exists())

        val freed = store.purge(listOf("m1"))

        assertTrue(freed >= 200_000)
        assertFalse(mediaFile.exists())
        assertFalse(oldAlias in w.aliases())
        assertTrue(store.currentKeyAlias in w.aliases())
        assertNull(store.get("m1"))

        val (v2, _) = vault(w)
        assertEquals(listOf("m2"), v2.chat("c1").messages.value.map { it.id })
    }

    @Test fun editLeavesNoTraceOfOldText() = runBlocking {
        val (v, w) = vault()
        val store = v.chat("c1")
        store.upsert(msg("m1", "typo"))
        store.edit("m1", "fixed", 5L)
        val (v2, _) = vault(w)
        val m = v2.chat("c1").get("m1")!!
        assertEquals("fixed", m.text)
        assertEquals(5L, m.editedAt)
    }

    @Test fun deleteChatErasesEverythingAndJanitorSweepsOrphans() = runBlocking {
        val (v, w) = vault()
        v.chat("c1").upsert(msg("m1", "x"))
        v.importMedia("c1", ByteArrayInputStream(ByteArray(10)), "image/png") // orphan (never referenced)
        v.chat("c2")

        val report = StorageJanitor(v, w).sweep()
        assertEquals(1, report.orphanFiles)

        v.deleteChat("c1")
        assertFalse(File(tmp.root, "files/zura/chats/c1").exists())
        assertTrue(w.aliases().none { it.startsWith("zc_c1_") })
        assertEquals(setOf("c2"), v.chatIds.value)
    }
}
