package app.zura.push

import app.zura.account.AuthState
import app.zura.core.sync.Frame
import app.zura.data.ContactsRepository
import app.zura.graph
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Receives FCM wake-ups while Zura is closed or the phone is asleep (high-priority data messages
 * bypass Doze). Runs on a background thread with ~10 s of guaranteed execution.
 *
 *  msg      → restore the remembered session, pull the E2EE inbox over HTTPS, decrypt, notify.
 *  call     → decrypt the sealed invite, ring (full-screen call notification), connect Realtime so
 *             the caller's SDP offer arrives; the call then proceeds exactly as in-app.
 *  call_end → the caller hung up before we answered: stop ringing.
 */
class ZuraMessagingService : FirebaseMessagingService() {

    override fun onNewToken(token: String) {
        graph.session.value?.let { s -> runBlocking { s.registerPush(token) } }
    }

    private fun prioName(p: Int) = when (p) { RemoteMessage.PRIORITY_HIGH -> "high"; RemoteMessage.PRIORITY_NORMAL -> "normal"; else -> "unknown" }

    override fun onMessageReceived(msg: RemoteMessage) {
        val data = msg.data
        val kind = data["t"] ?: return
        val from = data["f"] ?: return
        // Stage log: delay since the server sent it, and whether FCM downgraded our priority
        // (it does when high-priority pushes don't lead to a visible notification).
        app.zura.DiagLog.record("push", detail = "$kind received after ${System.currentTimeMillis() - msg.sentTime} ms, prio=${prioName(msg.priority)}" +
            (if (msg.priority != msg.originalPriority) " (DOWNGRADED from ${prioName(msg.originalPriority)})" else "") +
            ", locked=${!app.zura.security.DeviceLock.chatKeysUsable(this)}")
        // A call: start ringing immediately — before opening the account or touching the network.
        if (kind == "call") app.zura.call.CallService.preRing(this)
        try { runBlocking {
            // Cold start: AccountManager resumes the remembered account on its own; wait for it.
            val session = withTimeoutOrNull(7_000) {
                (graph.accounts.state.first { it is AuthState.SignedIn } as AuthState.SignedIn).session
            } ?: return@runBlocking
            when (kind) {
                "msg" -> {
                    // Phone locked: chat keys refuse to work → don't touch chat storage. Show a
                    // content-free notification; the message stays on the server until unlock.
                    if (!app.zura.security.DeviceLock.chatKeysUsable(this@ZuraMessagingService)) {
                        session.notifier.showPending(session.contacts.profile(from)?.displayName, ContactsRepository.dmChatId(session.uid, from))
                        app.zura.DiagLog.record("push", detail = "msg: locked → pending notification shown")
                        return@runBlocking
                    }
                    // Message carried in the push (E2EE): store + notify right away, no network.
                    data["e"]?.let { sealed ->
                        withTimeoutOrNull(4_000) {
                            val f = session.transport.openFrom(from, sealed) as? Frame.Msg
                            if (f != null && f.from == from) runCatching { session.transport.onFrame(from, f) }
                        }
                    }
                    // Then catch up on anything else (also removes it from the server inbox).
                    withTimeoutOrNull(8_000) { session.transport.syncInbox() }
                }
                "call" -> {
                    val sealed = data["e"] ?: return@runBlocking
                    val invite = session.transport.openFrom(from, sealed) as? Frame.CallInvite ?: return@runBlocking
                    if (invite.from != from || System.currentTimeMillis() - invite.at > 40_000) return@runBlocking
                    session.calls.onPushInvite(invite)
                }
                "call_end" -> data["c"]?.let { session.calls.onPushCancelled(it, from) }
            }
        } } finally {
            // Call push that didn't become a call (blocked, expired, account not ready): silence it.
            if (kind == "call" && graph.accounts.state.value.let { (it as? AuthState.SignedIn)?.session?.calls?.ui?.value?.inCall } != true) {
                app.zura.call.CallService.cancelPreRing(this)
            }
        }
    }
}
