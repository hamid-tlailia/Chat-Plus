package app.zura.data

import android.content.Context
import app.zura.DiagLog
import app.zura.MainActivity
import app.zura.R
import app.zura.core.model.Message
import app.zura.core.model.MsgKind
import app.zura.core.model.MsgStatus
import app.zura.core.storage.VaultStore
import app.zura.core.sync.Frame
import app.zura.core.sync.WireJson
import app.zura.net.Backend
import app.zura.net.SupabaseTransport
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID

/**
 * Restoring a 1:1 chat's history from the contact's phone, when this phone lost it.
 *
 *  requester                              provider (the contact)
 *  ─ HistoryRequest ───────────────────▶  asks its user (sheet + notification)
 *                                         approve → re-uploads recent media (still ciphertext
 *                                         under the per-file keys), sends the chat in batches
 *  ◀─────────────── HistoryChunk × n ──   (each ≤ ~30 KB, end-to-end encrypted like any frame)
 *  imports new ids only, downloads media
 *  ─ HistoryDone ──────────────────────▶  deletes the re-uploaded media from the server
 *
 * Safety: nothing is sent without the contact's explicit approval; never if the requester's
 * identity key changed (a new phone, or someone else signed in to the account — they must not
 * be able to pull old conversations out of a friend's phone); chunks are only accepted for a
 * chat this phone actually asked for.
 */
class HistoryRestore(
    private val app: Context,
    private val vault: VaultStore,
    private val transport: SupabaseTransport,
    private val backend: Backend,
    private val chats: ChatRepository,
    private val me: String,
    private val scope: CoroutineScope,
    private val nameOf: (String) -> String?,
) {
    enum class State { REQUESTED, RECEIVING, DONE, DECLINED, FAILED }

    private val prefs = app.getSharedPreferences("zura_history_${me.replace("-", "").take(12)}", Context.MODE_PRIVATE)

    private val _state = MutableStateFlow<Map<String, State>>(
        prefs.all.keys.filter { it.startsWith(REQ) }.associate { it.removePrefix(REQ) to State.REQUESTED },
    )
    /** Requester side: chatId → where its restore is. */
    val state: StateFlow<Map<String, State>> = _state.asStateFlow()

    private val _incoming = MutableStateFlow(
        prefs.all.filterKeys { it.startsWith(ASK) }.mapNotNull { (k, v) -> (v as? String)?.let { k.removePrefix(ASK) to it } }.toMap(),
    )
    /** Provider side: chatId → contact asking for my copy, waiting for my answer. */
    val incoming: StateFlow<Map<String, String>> = _incoming.asStateFlow()

    // ---------------------------------------------------------------- requester

    suspend fun request(chatId: String): Boolean = withContext(Dispatchers.IO) {
        val peer = vault.chat(chatId).meta.value?.peerIds?.singleOrNull() ?: return@withContext false
        prefs.edit().putLong(REQ + chatId, System.currentTimeMillis()).apply()
        _state.update { it + (chatId to State.REQUESTED) }
        val ok = transport.sendWithRetry(peer, Frame.HistoryRequest(chatId, me, System.currentTimeMillis()), attempts = 3)
        if (ok) scope.launch { backend.push(peer, "sync") } // wake their phone: it pulls the request from its inbox
        else {
            prefs.edit().remove(REQ + chatId).apply()
            _state.update { it + (chatId to State.FAILED) }
        }
        DiagLog.record("history", detail = "restore requested for ${chatId.take(8)} → ${if (ok) "sent" else "failed"}")
        ok
    }

    private val receivedBatches = HashMap<String, Int>()

    private suspend fun onChunk(from: String, f: Frame.HistoryChunk) {
        val asked = prefs.getLong(REQ + f.chatId, 0L)
        val store = vault.chat(f.chatId)
        val peer = store.meta.value?.peerIds?.singleOrNull()
        // Unsolicited, stale (> 7 days) or not from this chat's contact: ignore.
        if (asked == 0L || System.currentTimeMillis() - asked > 7 * 86_400_000L || peer != from || f.from != from) {
            DiagLog.record("history", detail = "unexpected history batch for ${f.chatId.take(8)} ignored"); return
        }
        _state.update { it + (f.chatId to State.RECEIVING) }
        val fresh = f.messages.filter { it.chatId == f.chatId && store.get(it.id) == null && (it.senderId == me || it.senderId == from || it.kind == MsgKind.EVENT) }
        val imported = fresh.map { m ->
            val ref = m.media
            val media = ref?.remoteBlobId?.let { blob ->
                runCatching { backend.download("blobs", blob) { vault.adoptCiphertext(f.chatId, ref, it) } }
                    .onFailure { DiagLog.record("history media", it) }.getOrNull()
            }
            when {
                ref == null -> m.copy(status = MsgStatus.READ)
                media != null -> m.copy(media = media.copy(remoteBlobId = null), status = MsgStatus.READ)
                // Photo / file the contact couldn't send back: keep the message, say so.
                else -> m.copy(
                    kind = MsgKind.TEXT, media = null, status = MsgStatus.READ, link = null,
                    text = listOfNotNull("📎 " + app.getString(R.string.media_not_restored), m.text?.takeIf { it.isNotBlank() }).joinToString("\n"),
                )
            }
        }
        if (imported.isNotEmpty()) store.upsertAll(imported)
        receivedBatches[f.chatId] = (receivedBatches[f.chatId] ?: 0) + 1
        if (f.last) {
            // The "couldn't be recovered" line now says the opposite.
            store.messages.value.filter { it.kind == MsgKind.EVENT && it.text == VaultStore.EVENT_HISTORY_RESET }
                .forEach { store.upsert(it.copy(text = VaultStore.EVENT_HISTORY_RESTORED)) }
            chats.refreshPreview(f.chatId)
            prefs.edit().remove(REQ + f.chatId).apply()
            _state.update { it + (f.chatId to State.DONE) }
            DiagLog.record("history", detail = "restored ${f.chatId.take(8)}: ${store.messages.value.size} messages in ${receivedBatches.remove(f.chatId)} batches")
            scope.launch { transport.sendWithRetry(from, Frame.HistoryDone(f.chatId, me)) }
        }
    }

    // ---------------------------------------------------------------- provider

    private fun onRequest(from: String, f: Frame.HistoryRequest) {
        val chatId = ContactsRepository.dmChatId(me, from)
        if (f.from != from || f.chatId != chatId || !vault.exists(chatId)) return
        val meta = vault.chat(chatId).meta.value ?: return
        // Their identity key changed (new phone, or someone else in their account): never hand
        // over old messages to it.
        if (meta.keyChanged) { DiagLog.record("history", detail = "request from ${from.take(8)} refused: identity key changed"); return }
        prefs.edit().putString(ASK + chatId, from).apply()
        _incoming.update { it + (chatId to from) }
        notifyRequest(chatId, nameOf(from) ?: meta.title)
    }

    /** Approved: send my copy of the chat (recent media re-uploaded), oldest first. */
    fun approve(chatId: String) = scope.launch(Dispatchers.IO) {
        val to = _incoming.value[chatId] ?: return@launch
        forget(chatId)
        val store = vault.chat(chatId)
        val all = store.messages.value.filterNot { it.kind == MsgKind.EVENT && (it.text == VaultStore.EVENT_HISTORY_RESET || it.text == VaultStore.EVENT_HISTORY_RESTORED) }
        // Media goes back through the server again (as ciphertext): the newest ones, within a budget.
        val mediaIds = all.filter { it.media?.isPlaceholder == false }.takeLast(MAX_MEDIA).map { it.id }.toHashSet()
        var budget = MEDIA_BUDGET
        val uploaded = ArrayList<String>()
        val out = all.map { m ->
            val ref = m.media ?: return@map m
            val file = runCatching { vault.mediaFile(chatId, ref) }.getOrNull()
            if (m.id in mediaIds && file != null && file.exists() && file.length() <= MAX_FILE && file.length() <= budget) {
                val path = "$me/${UUID.randomUUID()}"
                if (runCatching { backend.upload("blobs", path, file) }.isSuccess) {
                    uploaded += path; budget -= file.length()
                    return@map m.copy(media = ref.copy(remoteBlobId = path))
                }
            }
            m.copy(media = ref.copy(remoteBlobId = null))
        }
        prefs.edit().putStringSet(BLOBS + chatId, uploaded.toSet()).apply()
        // Batches that fit one inbox envelope (≤ 64 KB once sealed).
        val batches = ArrayList<List<Message>>()
        var cur = ArrayList<Message>(); var size = 0
        for (m in out) {
            val len = WireJson.encodeToString(Message.serializer(), m).length
            if (cur.isNotEmpty() && size + len > BATCH_CHARS) { batches += cur; cur = ArrayList(); size = 0 }
            cur += m; size += len
        }
        batches += cur
        var ok = true
        batches.forEachIndexed { i, b -> if (ok) ok = transport.sendWithRetry(to, Frame.HistoryChunk(chatId, me, i, i == batches.lastIndex, b)) }
        if (ok) scope.launch { backend.push(to, "sync") }
        DiagLog.record("history", detail = "sent ${out.size} messages (${uploaded.size} media) in ${batches.size} batches → ${if (ok) "ok" else "FAILED"}")
        withContext(Dispatchers.Main) {
            android.widget.Toast.makeText(app, if (ok) app.getString(R.string.history_sent_toast, nameOf(to) ?: "") else app.getString(R.string.history_restore_failed), android.widget.Toast.LENGTH_SHORT).show()
        }
    }

    fun decline(chatId: String) = scope.launch(Dispatchers.IO) {
        val to = _incoming.value[chatId] ?: return@launch
        forget(chatId)
        transport.sendWithRetry(to, Frame.HistoryDone(chatId, me, declined = true))
    }

    private fun forget(chatId: String) {
        prefs.edit().remove(ASK + chatId).apply()
        _incoming.update { it - chatId }
        runCatching { app.getSystemService(android.app.NotificationManager::class.java).cancel(("hist$chatId").hashCode()) }
    }

    private suspend fun onDone(from: String, f: Frame.HistoryDone) {
        if (f.from != from) return
        if (f.declined) {
            // My request was declined.
            if (prefs.contains(REQ + f.chatId)) {
                prefs.edit().remove(REQ + f.chatId).apply()
                _state.update { it + (f.chatId to State.DECLINED) }
            }
            return
        }
        // My copy arrived on their phone: remove the media I re-uploaded for it.
        prefs.getStringSet(BLOBS + f.chatId, null)?.forEach { backend.delete("blobs", it) }
        prefs.edit().remove(BLOBS + f.chatId).apply()
    }

    suspend fun handle(from: String, f: Frame) {
        when (f) {
            is Frame.HistoryRequest -> onRequest(from, f)
            is Frame.HistoryChunk -> onChunk(from, f)
            is Frame.HistoryDone -> onDone(from, f)
            else -> Unit
        }
    }

    private fun notifyRequest(chatId: String, name: String) {
        runCatching {
            val nm = app.getSystemService(android.app.NotificationManager::class.java)
            if (android.os.Build.VERSION.SDK_INT >= 26 && nm.getNotificationChannel(CHANNEL) == null) {
                nm.createNotificationChannel(android.app.NotificationChannel(CHANNEL, app.getString(R.string.profile_restore_history), android.app.NotificationManager.IMPORTANCE_DEFAULT))
            }
            val open = android.app.PendingIntent.getActivity(
                app, ("hist$chatId").hashCode(),
                android.content.Intent(app, MainActivity::class.java).setAction(android.content.Intent.ACTION_VIEW)
                    .putExtra(MainActivity.EXTRA_CHAT, chatId).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK or android.content.Intent.FLAG_ACTIVITY_CLEAR_TOP),
                android.app.PendingIntent.FLAG_IMMUTABLE or android.app.PendingIntent.FLAG_UPDATE_CURRENT,
            )
            val n = androidx.core.app.NotificationCompat.Builder(app, CHANNEL).setSmallIcon(R.drawable.ic_stat_zura)
                .setContentTitle(app.getString(R.string.history_request_title))
                .setContentText(app.getString(R.string.history_request_notif, name))
                .setAutoCancel(true).setContentIntent(open).build()
            androidx.core.app.NotificationManagerCompat.from(app).notify(("hist$chatId").hashCode(), n)
        }
    }

    private companion object {
        const val REQ = "req_"
        const val ASK = "ask_"
        const val BLOBS = "blobs_"
        const val CHANNEL = "history"
        const val BATCH_CHARS = 30_000
        const val MAX_MEDIA = 60
        const val MAX_FILE = 25L * 1024 * 1024
        const val MEDIA_BUDGET = 300L * 1024 * 1024
    }
}
