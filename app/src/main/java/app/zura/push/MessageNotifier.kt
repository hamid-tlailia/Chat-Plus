package app.zura.push

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.Person
import app.zura.MainActivity
import app.zura.R
import app.zura.core.model.ChatMeta
import app.zura.core.model.Message
import app.zura.core.model.MsgKind

/**
 * Message notifications are built ON the phone from the locally decrypted message — the push that
 * woke us carried nothing readable. Lock screen shows only "New message" unless unlocked.
 */
class MessageNotifier(private val context: Context) {
    private val nm = context.getSystemService(NotificationManager::class.java)
    private val lines = HashMap<String, ArrayList<NotificationCompat.MessagingStyle.Message>>()

    fun show(meta: ChatMeta, m: Message) {
        if (Push.appVisible && Push.openChatId == meta.id) return
        ensureChannel()
        val sender = Person.Builder().setName(meta.title).setKey(m.senderId).build()
        val me = Person.Builder().setName(context.getString(R.string.app_name)).build()
        val text = m.text?.takeIf { it.isNotBlank() && m.kind != MsgKind.CALL } ?: context.getString(
            when (m.kind) {
                MsgKind.IMAGE -> R.string.photo
                MsgKind.VIDEO -> R.string.video
                MsgKind.VOICE -> R.string.voice_message
                MsgKind.FILE -> R.string.file
                MsgKind.CALL -> R.string.missed_voice_call
                MsgKind.TEXT -> R.string.message_hint
            },
        )
        val list = synchronized(lines) { lines.getOrPut(meta.id) { ArrayList() }.apply { add(NotificationCompat.MessagingStyle.Message(text, m.sentAt, sender)); while (size > 6) removeAt(0) }.toList() }
        val style = NotificationCompat.MessagingStyle(me).also { s -> list.forEach(s::addMessage) }
        val open = PendingIntent.getActivity(
            context, meta.id.hashCode(),
            Intent(context, MainActivity::class.java).setAction(Intent.ACTION_VIEW).putExtra(MainActivity.EXTRA_CHAT, meta.id)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val public = NotificationCompat.Builder(context, CHANNEL).setSmallIcon(R.drawable.ic_launcher)
            .setContentTitle(context.getString(R.string.app_name)).setContentText(context.getString(R.string.new_message)).build()
        val n = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_launcher)
            .setStyle(style)
            .setShortcutId(meta.id)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setAutoCancel(true)
            .setContentIntent(open)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setPublicVersion(public)
            .setGroup("zura-messages")
            .build()
        runCatching { NotificationManagerCompat.from(context).notify(meta.id.hashCode(), n) }
    }

    fun clear(chatId: String) {
        synchronized(lines) { lines.remove(chatId) }
        nm.cancel(chatId.hashCode())
    }

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT < 26) return
        nm.createNotificationChannel(NotificationChannel(CHANNEL, context.getString(R.string.messages_channel), NotificationManager.IMPORTANCE_HIGH))
    }

    private companion object { const val CHANNEL = "messages" }
}
