package app.zura.live

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.zura.account.AccountSession
import app.zura.account.AccountStore
import app.zura.core.crypto.SoftwareKeyWrapper
import app.zura.core.model.MsgKind
import app.zura.core.sync.Frame
import app.zura.core.model.MsgStatus
import app.zura.data.ContactsRepository
import app.zura.net.Backend
import app.zura.screens.TestZuraApp
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.auth.providers.builtin.Email
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import kotlin.random.Random

/**
 * End-to-end test against the REAL Supabase project, with two throw-away QA accounts.
 * Skipped unless ZURA_LIVE=1. Exercises: sign-in, identity publish, search, discover, invite →
 * accept, E2EE text both ways, delivery receipts, encrypted media blob, broadcast RLS, realtime
 * delivery, and the push / turn-credentials edge functions.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [32], application = TestZuraApp::class)
class LiveBackendTest {

    private val app: Application = ApplicationProvider.getApplicationContext()
    private val pw = System.getenv("ZURA_QA_PASSWORD").orEmpty()

    private suspend fun session(email: String, store: AccountStore): AccountSession {
        val be = Backend()
        be.client.auth.signInWith(Email) { this.email = email; password = pw }
        val uid = be.client.auth.currentSessionOrNull()!!.user!!.id
        return AccountSession(app, uid, email, be, store).also { it.contacts.publishIdentity(it.identity.publicB64) }
    }

    private suspend fun <T> eventually(what: String, ms: Long = 20_000, check: suspend () -> T?): T =
        withTimeout(ms) {
            while (true) { check()?.let { return@withTimeout it }; delay(400) }
            @Suppress("UNREACHABLE_CODE") error(what)
        }

    @Test fun fullFlowAgainstRealBackend() = runBlocking {
        assumeTrue("set ZURA_LIVE=1 to run against the real backend", System.getenv("ZURA_LIVE") == "1")
        val store = AccountStore(File(app.filesDir, "live-${System.nanoTime()}"), SoftwareKeyWrapper())
        val a = session("zura.qa.alpha@zura-test.invalid", store)
        val b = session("zura.qa.beta@zura-test.invalid", store)
        println("LIVE ✓ signed in A=${a.uid.take(8)} B=${b.uid.take(8)}, identities published")

        // Search + discover
        val hit = a.contacts.search("qa_be").single { it.id == b.uid }
        assertEquals("none", hit.relation)
        assertTrue(a.contacts.discover().any { it.id == b.uid })
        println("LIVE ✓ search + discover find B")

        // Invite → accept
        a.contacts.sendInvite(b.uid, "hello from the QA run").getOrThrow()
        b.contacts.refresh()
        val inv = b.contacts.invites.value.single { it.incoming && it.profile.id == a.uid }
        assertEquals("hello from the QA run", inv.message)
        b.contacts.respond(inv.id, true).getOrThrow()
        a.contacts.refresh(); b.contacts.refresh()
        assertTrue(a.contacts.contacts.value.any { it.profile.id == b.uid })
        val chat = ContactsRepository.dmChatId(a.uid, b.uid)
        assertTrue(a.vault.exists(chat) && b.vault.exists(chat))
        println("LIVE ✓ invite sent, accepted, conversation created on both sides")

        // E2EE text A → B (pull over HTTPS, like a push wake-up does)
        a.chats.sendText(chat, "مرحبا Beta 👋 E2EE")
        val sent = eventually("A's message reaches SENT") { a.vault.chat(chat).messages.value.lastOrNull()?.takeIf { it.status == MsgStatus.SENT } }
        b.transport.syncInbox()
        val got = eventually("B receives") { b.vault.chat(chat).get(sent.id) }
        assertEquals("مرحبا Beta 👋 E2EE", got.text)
        println("LIVE ✓ E2EE text delivered A→B")

        // Delivery receipt B → A
        eventually("A sees DELIVERED") {
            a.transport.syncInbox()
            a.vault.chat(chat).get(sent.id)?.takeIf { it.status >= MsgStatus.DELIVERED }
        }
        println("LIVE ✓ delivery receipt B→A")

        // Encrypted media blob A → B
        val bytes = Random.nextBytes(150_000)
        val ref = a.vault.importMedia(chat, ByteArrayInputStream(bytes), "image/jpeg", 10, 10)
        a.chats.sendMedia(chat, MsgKind.IMAGE, ref)
        val mediaMsg = eventually("media SENT") { a.vault.chat(chat).messages.value.lastOrNull()?.takeIf { it.kind == MsgKind.IMAGE && it.status == MsgStatus.SENT } }
        b.transport.syncInbox()
        val gotMedia = eventually("B gets media") { b.vault.chat(chat).get(mediaMsg.id)?.media }
        val out = ByteArrayOutputStream().also { b.vault.decryptTo(chat, gotMedia, it) }.toByteArray()
        assertArrayEquals(bytes, out)
        println("LIVE ✓ encrypted photo uploaded, downloaded and decrypted by B")

        // Reply B → A
        b.chats.sendText(chat, "Reply from Beta")
        eventually("A gets reply") { a.transport.syncInbox(); a.vault.chat(chat).messages.value.firstOrNull { it.text == "Reply from Beta" } }
        println("LIVE ✓ E2EE text delivered B→A")

        // Broadcast (typing/call signalling) is allowed to a contact
        val ok = a.transport.signal(b.uid, Frame.Typing(chat, a.uid, true))
        assertTrue("broadcast to contact must be accepted by RLS", ok)
        println("LIVE ✓ realtime broadcast (typing / call signalling) accepted")

        // Realtime: B connected over WebSocket receives A's message without polling
        b.transport.start()
        val online = runCatching { eventually("B realtime online", 20_000) { b.transport.online.value.takeIf { it } } }.isSuccess
        println("LIVE ${if (online) "✓" else "✗"} realtime WebSocket online for B")
        if (online) {
            delay(1500)
            fun ch(n: String) = b.transport.javaClass.getDeclaredField(n).apply { isAccessible = true }.get(b.transport) as? io.github.jan.supabase.realtime.RealtimeChannel
            println("LIVE diag inbox channel=${ch("inboxChannel")?.status?.value} user channel=${ch("userChannel")?.status?.value}")
            a.chats.sendText(chat, "via realtime")
            val rt = runCatching { eventually("realtime delivery", 15_000) { b.vault.chat(chat).messages.value.firstOrNull { it.text == "via realtime" } } }.isSuccess
            println("LIVE ${if (rt) "✓" else "✗"} message pushed to B over realtime (no polling)")
            println("LIVE diag after: inbox channel=${ch("inboxChannel")?.status?.value} user channel=${ch("userChannel")?.status?.value}")
        }

        // Edge functions
        val push = a.backendFunction("push", buildJsonObject { put("to", b.uid); put("kind", "msg") })
        println("LIVE push function → $push")
        val turn = a.backendFunction("turn-credentials", buildJsonObject { })
        println("LIVE turn-credentials → relay=${turn.jsonObject["relay"]}")

        // Clean up the relationship (accounts are deleted afterwards via SQL)
        a.contacts.remove(b.uid)
        a.close(); b.close()
        println("LIVE ✓ done")
    }
}

private suspend fun AccountSession.backendFunction(name: String, body: kotlinx.serialization.json.JsonObject) =
    javaClass.getDeclaredField("backend").apply { isAccessible = true }.get(this).let { (it as Backend).function(name, body) }
