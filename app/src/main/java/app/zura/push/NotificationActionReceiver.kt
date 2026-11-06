package app.zura.push

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.RemoteInput
import app.zura.DiagLog
import app.zura.account.AuthState
import app.zura.graph
import app.zura.security.DeviceLock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * The buttons on a message notification: "Reply" (text typed in the notification) and "Mark as
 * read". Works with Zura closed: the remembered account is resumed (as for a push), the reply is
 * encrypted and sent like any message, and the notification goes away once it has left.
 */
class NotificationActionReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val chatId = intent.getStringExtra(EXTRA_CHAT) ?: return
        val text = if (intent.action == ACTION_REPLY) RemoteInput.getResultsFromIntent(intent)?.getCharSequence(KEY_TEXT)?.toString()?.trim() else null
        if (intent.action == ACTION_REPLY && text.isNullOrEmpty()) return
        val app = context.applicationContext
        val pending = goAsync()
        scope.launch {
            try {
                val session = withTimeoutOrNull(7_000) {
                    (app.graph.accounts.state.first { it is AuthState.SignedIn } as AuthState.SignedIn).session
                }
                // Chat keys only work while the phone is unlocked (the action asks to unlock first).
                if (session == null || !DeviceLock.chatKeysUsable(app)) {
                    if (text != null) (session?.notifier ?: MessageNotifier(app)).replyFailed(chatId, text)
                    return@launch
                }
                when (intent.action) {
                    ACTION_REPLY -> {
                        val ok = runCatching { session.chats.replyFromNotification(chatId, text!!) }
                            .onFailure { DiagLog.record("notif reply", it) }.getOrDefault(false)
                        if (ok) session.notifier.clear(chatId) else session.notifier.replyFailed(chatId, text!!)
                    }
                    ACTION_READ -> {
                        runCatching { session.chats.markRead(chatId) }.onFailure { DiagLog.record("notif read", it) }
                        session.notifier.clear(chatId)
                    }
                }
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        const val ACTION_REPLY = "app.zura.action.REPLY"
        const val ACTION_READ = "app.zura.action.MARK_READ"
        const val EXTRA_CHAT = "chat"
        const val KEY_TEXT = "reply_text"
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    }
}
