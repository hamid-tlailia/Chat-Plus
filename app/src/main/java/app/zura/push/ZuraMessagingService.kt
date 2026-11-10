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
        val received = android.os.SystemClock.elapsedRealtime()
        val data = msg.data
        val kind = data["t"] ?: return
        val from = data["f"] ?: return
        // Stage log: delay since the server sent it, and whether FCM downgraded our priority
        // (it does when high-priority pushes don't lead to a visible notification).
        app.zura.DiagLog.record("push", detail = "$kind received after ${System.currentTimeMillis() - msg.sentTime} ms, prio=${prioName(msg.priority)}" +
            (if (msg.priority != msg.originalPriority) " (DOWNGRADED from ${prioName(msg.originalPriority)})" else "") +
            ", locked=${!app.zura.security.DeviceLock.chatKeysUsable(this)}" + app.zura.push.Push.backgroundState(this))
        // A call: start ringing immediately — before opening the account or touching the network.
        if (kind == "call") app.zura.call.CallService.preRing(this, from)
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
                        val chatId = ContactsRepository.dmChatId(session.uid, from)
                        val name = session.contacts.profile(from)?.displayName ?: getString(app.zura.R.string.app_name)
                        // The text travels E2EE inside the push; the identity key works while locked:
                        // show the real message now (stored after unlock, same notification reused).
                        val f = (data["e"] ?: data["h"])?.let { sealed -> withTimeoutOrNull(4_000) { runCatching { session.transport.openFrom(from, sealed) }.getOrNull() } } as? Frame.Msg
                        if (f != null && f.from == from) {
                            session.notifier.showFromPush(chatId, name, f)
                            // It reached this phone: second tick now, not after unlock.
                            // ONLY for a complete text message: a photo / video / voice still has to be
                            // downloaded, and "delivered" makes the sender delete the file from the server
                            // (0.9.37 did it for photos too: some were deleted before they were fetched).
                            if (data["e"] != null && f.kind == app.zura.core.model.MsgKind.TEXT && f.media == null)
                                session.chats.deliveredFromPush(from, f.chatId, f.id)
                            app.zura.DiagLog.record("push", detail = "msg: locked → shown from push payload")
                        } else {
                            session.notifier.showPending(name, chatId)
                            app.zura.DiagLog.record("push", detail = "msg: locked, no payload → 'new message' shown")
                        }
                        return@runBlocking
                    }
                    // Message carried in the push (E2EE): SHOW it first — before opening the chat's
                    // history (and before downloading anything) — then store it. The stored copy
                    // reuses the same notification (same message id: never a second alert).
                    data["e"]?.let { sealed ->
                        withTimeoutOrNull(4_000) {
                            val f = runCatching { session.transport.openFrom(from, sealed) }.getOrNull() as? Frame.Msg
                            if (f != null && f.from == from) {
                                val chatId = ContactsRepository.dmChatId(session.uid, from)
                                val name = session.contacts.profile(from)?.displayName
                                // A late push (FCM held it) may be for a message already seen live:
                                // then store first — storing only notifies what is really new.
                                val fresh = System.currentTimeMillis() - f.sentAt < 120_000
                                if (fresh && f.chatId == chatId && name != null) {
                                    session.notifier.showFromPush(chatId, name, f)
                                    app.zura.DiagLog.record("push", detail = "msg shown ${android.os.SystemClock.elapsedRealtime() - received} ms after arrival (${app.zura.core.crypto.KeystoreStats.summary()})")
                                }
                                runCatching { session.transport.onFrame(from, f) }
                            }
                        }
                    }
                    // Content-free push with a sealed HINT (photo, voice, long text…): show who + what
                    // now; the message itself is stored from the inbox just below (same notification).
                    if (data["e"] == null) data["h"]?.let { sealed ->
                        withTimeoutOrNull(3_000) {
                            val f = runCatching { session.transport.openFrom(from, sealed) }.getOrNull() as? Frame.Msg
                            val chatId = ContactsRepository.dmChatId(session.uid, from)
                            val name = session.contacts.profile(from)?.displayName
                            if (f != null && f.from == from && f.chatId == chatId && name != null && System.currentTimeMillis() - f.sentAt < 120_000)
                                session.notifier.showFromPush(chatId, name, f)
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
                // Something that isn't a message (moment reaction, history restore…): just pull the
                // inbox — no "new message" notification. A locked phone does it after unlock.
                "sync" -> if (app.zura.security.DeviceLock.chatKeysUsable(this@ZuraMessagingService)) withTimeoutOrNull(8_000) { session.transport.syncInbox() }
                // Invitation sent to me / accepted: refresh the social graph — it notifies what's new.
                "invite", "invite_ok" -> withTimeoutOrNull(8_000) { runCatching { session.contacts.refresh() } }
            }
        } } finally {
            // Call push that didn't become a call (blocked, expired, account not ready): silence it.
            if (kind == "call" && graph.accounts.state.value.let { (it as? AuthState.SignedIn)?.session?.calls?.ui?.value?.inCall } != true) {
                app.zura.call.CallService.cancelPreRing(this)
            }
        }
    }
}
