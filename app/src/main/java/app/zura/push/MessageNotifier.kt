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

    /** Message ids already notified (push-decrypted first, stored later → never twice). */
    private val notified = LinkedHashSet<String>()
    private val prefs = context.getSharedPreferences("zura_notif", Context.MODE_PRIVATE)

    /**
     * Locked chats, remembered outside the encrypted index (only chat ids — no content), so a
     * notification decrypted from a push on a LOCKED phone still hides who / what for them.
     */
    fun rememberLocked(ids: Set<String>) { prefs.edit().putStringSet("locked", ids).apply() }
    private fun isLockedChat(chatId: String) = chatId in prefs.getStringSet("locked", emptySet()).orEmpty()

    fun show(meta: ChatMeta, m: Message) =
        post(meta.id, meta.title, m.senderId, m.id, textOf(m.kind, m.text, m.moment != null), m.sentAt, meta.locked)

    /**
     * Phone locked: the message came INSIDE the push (end-to-end encrypted; the identity key works
     * while locked) — show it right away like any message, without touching chat storage. When
     * it's stored after unlock, the same notification is reused (same id; no second alert).
     */
    fun showFromPush(chatId: String, title: String, f: app.zura.core.sync.Frame.Msg) =
        post(chatId, title, f.from, f.id, textOf(f.kind, f.text, f.moment != null), f.sentAt, isLockedChat(chatId))

    private fun textOf(kind: MsgKind, text: String?, moment: Boolean): String {
        val t = if (kind == MsgKind.EVENT) app.zura.ui.chat.eventText(context, text, false) else text?.takeIf { it.isNotBlank() && kind != MsgKind.CALL } ?: context.getString(
            when (kind) {
                MsgKind.IMAGE -> R.string.photo
                MsgKind.VIDEO -> R.string.video
                MsgKind.VOICE -> R.string.voice_message
                MsgKind.FILE -> R.string.file
                MsgKind.CALL -> if (text?.contains("video") == true) R.string.missed_video_call else R.string.missed_voice_call
                MsgKind.EVENT -> R.string.screenshot_event
                MsgKind.TEXT -> R.string.message_hint
            },
        )
        return if (moment) context.getString(R.string.moment_notif, t) else t
    }

    private fun post(chatId: String, title: String, senderId: String, msgId: String, shown: String, sentAt: Long, lockedChat: Boolean) {
        // Remembered even when not shown (chat open on screen): a late push for a message already
        // read live must not pop up afterwards.
        val duplicate = synchronized(notified) {
            val d = !notified.add(msgId)
            while (notified.size > 500) notified.remove(notified.first())
            d
        }
        if (Push.appVisible && Push.openChatId == chatId) return
        if (duplicate) return // already on screen (from the push) — don't add it or ring again
        ensureChannel()
        val sender = Person.Builder().setName(title).setKey(senderId).build()
        val me = Person.Builder().setName(context.getString(R.string.app_name)).build()
        // App lock on / locked chat → never reveal who wrote what in the notification shade.
        if ((context.applicationContext as app.zura.ZuraApp).graph.appLock.enabled.value || lockedChat) {
            val n = NotificationCompat.Builder(context, channelFor(chatId)).setSmallIcon(R.drawable.ic_stat_zura)
                .setContentTitle(context.getString(R.string.app_name)).setContentText(context.getString(R.string.new_message))
                .setAutoCancel(true).setCategory(NotificationCompat.CATEGORY_MESSAGE)
                .setContentIntent(openChat(chatId))
                .build()
            runCatching { NotificationManagerCompat.from(context).notify(LOCKED_ID, n) }
            return
        }
        val list = synchronized(lines) { lines.getOrPut(chatId) { ArrayList() }.apply { add(NotificationCompat.MessagingStyle.Message(shown, sentAt, sender)); while (size > 6) removeAt(0) }.toList() }
        val style = NotificationCompat.MessagingStyle(me).also { s -> list.forEach(s::addMessage) }
        val public = NotificationCompat.Builder(context, channelFor(chatId)).setSmallIcon(R.drawable.ic_stat_zura)
            .setContentTitle(context.getString(R.string.app_name)).setContentText(context.getString(R.string.new_message)).build()
        val n = NotificationCompat.Builder(context, channelFor(chatId))
            .setSmallIcon(R.drawable.ic_stat_zura)
            .setStyle(style)
            .setShortcutId(chatId)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setAutoCancel(true)
            .setContentIntent(openChat(chatId))
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setPublicVersion(public)
            .setGroup("zura-messages")
            // Reply right from the notification (and "mark as read"), without opening the app.
            .addAction(replyAction(chatId))
            .addAction(markReadAction(chatId))
            .build()
        runCatching { NotificationManagerCompat.from(context).notify(chatId.hashCode(), n) }
    }

    private fun actionIntent(action: String, chatId: String, mutable: Boolean): PendingIntent = PendingIntent.getBroadcast(
        context, chatId.hashCode(),
        Intent(context, NotificationActionReceiver::class.java).setAction(action)
            .setData(android.net.Uri.parse("zura://chat/" + android.net.Uri.encode(chatId)))
            .putExtra(NotificationActionReceiver.EXTRA_CHAT, chatId),
        PendingIntent.FLAG_UPDATE_CURRENT or (if (mutable && Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else if (mutable) 0 else PendingIntent.FLAG_IMMUTABLE),
    )

    /** "Reply" with a text field (RemoteInput: the typed text has to be filled in → mutable). */
    private fun replyAction(chatId: String): NotificationCompat.Action {
        val input = androidx.core.app.RemoteInput.Builder(NotificationActionReceiver.KEY_TEXT)
            .setLabel(context.getString(R.string.message_hint)).build()
        return NotificationCompat.Action.Builder(R.drawable.ic_stat_zura, context.getString(R.string.action_reply), actionIntent(NotificationActionReceiver.ACTION_REPLY, chatId, mutable = true))
            .addRemoteInput(input)
            .setAllowGeneratedReplies(true)
            .setSemanticAction(NotificationCompat.Action.SEMANTIC_ACTION_REPLY)
            .setShowsUserInterface(false)
            // On the lock screen the phone is unlocked first: chat keys only work unlocked.
            .setAuthenticationRequired(true)
            .build()
    }

    private fun markReadAction(chatId: String): NotificationCompat.Action =
        NotificationCompat.Action.Builder(R.drawable.ic_stat_zura, context.getString(R.string.notif_mark_read), actionIntent(NotificationActionReceiver.ACTION_READ, chatId, mutable = false))
            .setSemanticAction(NotificationCompat.Action.SEMANTIC_ACTION_MARK_AS_READ)
            .setShowsUserInterface(false)
            .setAuthenticationRequired(true)
            .build()

    /** A reply from the notification couldn't be sent (phone locked / account not ready): say so. */
    fun replyFailed(chatId: String, text: String) {
        ensureChannel()
        synchronized(lines) { lines.remove(chatId) }
        val n = NotificationCompat.Builder(context, channelFor(chatId)).setSmallIcon(R.drawable.ic_stat_zura)
            .setContentTitle(context.getString(R.string.notif_reply_failed))
            .setContentText(text)
            .setOnlyAlertOnce(true).setSilent(true)
            .setAutoCancel(true).setCategory(NotificationCompat.CATEGORY_ERROR)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setContentIntent(openChat(chatId))
            .build()
        runCatching { NotificationManagerCompat.from(context).notify(chatId.hashCode(), n) }
    }

    /** A message arrived but stays encrypted until the phone is unlocked. */
    /** "Hamid reacted 😢 to: Hi" — reactions reach you like messages do. */
    fun showReaction(meta: ChatMeta, m: Message, emoji: String) {
        if (Push.appVisible && Push.openChatId == meta.id) return
        ensureChannel()
        val locked = (context.applicationContext as app.zura.ZuraApp).graph.appLock.enabled.value || meta.locked
        val about = m.text?.takeIf { it.isNotBlank() && m.kind == MsgKind.TEXT } ?: context.getString(app.zura.ui.chat.kindLabel(m.kind))
        val n = NotificationCompat.Builder(context, channelFor(meta.id)).setSmallIcon(R.drawable.ic_stat_zura)
            .setContentTitle(if (locked) context.getString(R.string.app_name) else meta.title)
            .setContentText(if (locked) context.getString(R.string.new_message) else context.getString(R.string.reacted_to, emoji, about))
            .setAutoCancel(true).setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setContentIntent(openChat(meta.id))
            .build()
        runCatching { NotificationManagerCompat.from(context).notify(("react" + meta.id).hashCode(), n) }
    }

    /** Tap → straight into that chat (the app lock, if on, still asks first). */
    private fun openChat(chatId: String?): PendingIntent = PendingIntent.getActivity(
        context, chatId?.hashCode() ?: 0,
        Intent(context, MainActivity::class.java).setAction(Intent.ACTION_VIEW)
            .apply { chatId?.let { putExtra(MainActivity.EXTRA_CHAT, it) } }
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    fun showPending(sender: String?, chatId: String? = null) {
        ensureChannel()
        val n = NotificationCompat.Builder(context, channelFor(chatId)).setSmallIcon(R.drawable.ic_stat_zura)
            .setContentTitle(sender ?: context.getString(R.string.app_name))
            .setContentText(context.getString(R.string.new_message))
            .setAutoCancel(true).setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setContentIntent(openChat(chatId))
            .build()
        runCatching { NotificationManagerCompat.from(context).notify(chatId?.hashCode() ?: LOCKED_ID, n) }
    }

    fun clear(chatId: String) {
        synchronized(lines) { lines.remove(chatId) }
        nm.cancel(chatId.hashCode())
    }

    // ---------------------------------------------------------------- social (no message content)

    /** "X invited you" → opens People (invitations). */
    fun showInvite(inviteId: String, name: String, note: String?) =
        social(("inv$inviteId").hashCode(), context.getString(R.string.notif_invite, name), note?.takeIf { it.isNotBlank() } ?: context.getString(R.string.notif_invite_body), openPeople())

    /** "X accepted your invitation" → opens our new chat. */
    fun showInviteAccepted(peerId: String, name: String, chatId: String) =
        social(("ok$peerId").hashCode(), context.getString(R.string.notif_invite_ok, name), context.getString(R.string.notif_invite_ok_body), openChat(chatId))

    /** "X reacted ❤️ to your moment". */
    fun showMomentReaction(name: String, emoji: String, momentId: String) =
        social(("mr$momentId$name").hashCode(), context.getString(R.string.notif_moment_react, name, emoji), null, openChat(null))

    private fun social(id: Int, title: String, text: String?, open: PendingIntent) {
        if (Build.VERSION.SDK_INT >= 26) nm.createNotificationChannel(NotificationChannel(SOCIAL, context.getString(R.string.social_channel), NotificationManager.IMPORTANCE_HIGH))
        val n = NotificationCompat.Builder(context, SOCIAL).setSmallIcon(R.drawable.ic_stat_zura)
            .setContentTitle(title).apply { text?.let { setContentText(it) } }
            .setAutoCancel(true).setCategory(NotificationCompat.CATEGORY_SOCIAL)
            .setContentIntent(open).build()
        runCatching { NotificationManagerCompat.from(context).notify(id, n) }
    }

    private fun openPeople(): PendingIntent = PendingIntent.getActivity(
        context, "people".hashCode(),
        Intent(context, MainActivity::class.java).setAction(Intent.ACTION_VIEW).putExtra(MainActivity.EXTRA_OPEN, MainActivity.OPEN_PEOPLE)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    /** Messages channel with this chat's tone / vibration (Settings → Sounds, or the contact's page). */
    private fun channelFor(chatId: String?): String =
        runCatching { (context.applicationContext as app.zura.ZuraApp).graph.sounds.messageChannel(context, chatId, CHANNEL) }.getOrDefault(CHANNEL)

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT < 26) return
        nm.createNotificationChannel(NotificationChannel(CHANNEL, context.getString(R.string.messages_channel), NotificationManager.IMPORTANCE_HIGH))
    }

    private companion object {
        const val CHANNEL = "messages"
        const val SOCIAL = "social"
        const val LOCKED_ID = 7100
    }
}
