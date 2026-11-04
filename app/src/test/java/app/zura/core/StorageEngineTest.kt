package app.zura.core

import app.zura.core.crypto.SegmentedCipher
import app.zura.core.crypto.SoftwareKeyWrapper
import app.zura.core.model.ChatMeta
import app.zura.core.model.Message
import app.zura.core.model.MsgKind
import app.zura.core.model.MsgStatus
import app.zura.core.storage.StorageJanitor
import app.zura.core.storage.VaultStore
import app.zura.core.storage.VaultLockedException
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

    @Test fun tempFilesCanBeCreated() {
        // Regression: a 1-char prefix made File.createTempFile throw, crashing voice recording.
        val (v, _) = vault()
        val f = v.paths.newTemp("ogg")
        assertTrue(f.exists() && f.name.endsWith(".ogg"))
    }

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

    @Test fun chatListIndexIsEncryptedSurvivesRestartWithoutOpeningLogsAndForgetsDeletedChats() = runBlocking {
        val (v, w) = vault()
        v.createChat(ChatMeta("c1", "Sara", listOf("u2"), 1L, preview = "secret-preview"))
        v.createChat(ChatMeta("c2", "Omar", listOf("u3"), 2L))
        assertEquals(setOf("c1", "c2"), v.metaIndex.value.keys)

        val raw = File(tmp.root, "files/zura/index.zs").readBytes().decodeToString()
        assertFalse(raw.contains("secret-preview")) // encrypted at rest

        val (v2, _) = vault(w) // fresh process: the list is known before any chat log is opened
        assertEquals("Sara", v2.metaIndex.value["c1"]?.title)
        assertEquals("secret-preview", v2.metaIndex.value["c1"]?.preview)

        v2.deleteChat("c2")
        val (v3, _) = vault(w)
        assertEquals(setOf("c1"), v3.metaIndex.value.keys)
    }

    @Test fun lockedPhoneRefusesToOpenChatsCleanlyButKeepsOpenOnes() = runBlocking {
        var locked = false
        val w = SoftwareKeyWrapper()
        val root = tmp.root
        val v = VaultStore(ZuraPaths(File(root, "files"), File(root, "nb"), File(root, "cache")), w) { locked }
        v.createChat(ChatMeta("c1", "Sara", listOf("u2"), 1L))
        v.createChat(ChatMeta("c2", "Omar", listOf("u3"), 1L))
        val v2 = VaultStore(ZuraPaths(File(root, "files"), File(root, "nb"), File(root, "cache")), w) { locked }
        v2.chat("c1") // opened while unlocked
        locked = true
        assertEquals("Sara", v2.chat("c1").meta.value?.title) // stays usable
        val err = runCatching { v2.chat("c2") }.exceptionOrNull()
        assertTrue(err is VaultLockedException) // clean refusal, no Keystore call
        locked = false
        assertEquals("Omar", v2.chat("c2").meta.value?.title) // after unlock: normal
    }

    @Test fun janitorNeverDestroysTheChatListIndexKey() = runBlocking {
        val (v, w) = vault()
        v.createChat(ChatMeta("c1", "Sara", listOf("u2"), 1L))
        StorageJanitor(v, w).sweep() // used to delete "zc_index" → index unreadable on every next start
        val (v2, _) = vault(w)
        assertEquals("Sara", v2.metaIndex.value["c1"]?.title)
    }

    @Test fun indexWithDestroyedKeyIsRebuiltNotStuck() = runBlocking {
        val (v, w) = vault()
        v.createChat(ChatMeta("c1", "Sara", listOf("u2"), 1L))
        w.destroy(VaultStore.INDEX_ALIAS)
        val (v2, _) = vault(w)
        assertEquals(emptyMap<String, ChatMeta>(), v2.metaIndex.value)
        v2.chat("c1") // opening the chat refills and REWRITES the index under a fresh key
        val (v3, _) = vault(w)
        assertEquals("Sara", v3.metaIndex.value["c1"]?.title)
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
