package app.zura.ui.chat

import android.net.Uri
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.input.TextFieldValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.zura.AppGraph
import app.zura.core.model.Message
import app.zura.core.model.MsgKind
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** A row in the reversed chat list: either a bubble or a day separator. */
@Immutable
sealed interface ChatItem {
    val key: String
    data class Bubble(val m: Message, val mine: Boolean, val groupedWithPrev: Boolean, val groupedWithNext: Boolean) : ChatItem {
        override val key get() = m.id
    }
    data class Day(val epochMs: Long) : ChatItem {
        override val key get() = "day-$epochMs"
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class ChatViewModel(private val g: AppGraph, val chatId: String) : ViewModel() {
    val me = g.s.uid

    /** The other participant of a 1:1 chat (null for the notes chat). */
    val peerId: String? get() = meta.value?.peerIds?.singleOrNull()
    /** Last time the contact was in the app — seeded from the server when the chat opens. */
    // Fair play: if I hide my last seen from this person (globally or ghost), I don't see theirs.
    private val seesPeerActivity = kotlinx.coroutines.flow.combine(g.chats.meta(chatId), g.s.privacy.lastSeen, g.s.privacy.ghost) { m, _, _ ->
        m?.peerIds?.singleOrNull()?.let { g.s.privacy.lastSeenTo(it) } == true
    }

    val peerLastSeen = g.chats.meta(chatId).flatMapLatest { m ->
        m?.peerIds?.singleOrNull()?.let { peer ->
            m.peerSeenAt?.let { g.chats.noteLastSeen(peer, it) }
            g.chats.peerLastSeen(peer)
        } ?: kotlinx.coroutines.flow.flowOf(null)
    }.combine(seesPeerActivity) { t, ok -> t.takeIf { ok } }
        .stateIn(viewModelScope, kotlinx.coroutines.flow.SharingStarted.WhileSubscribed(5_000), null)

    val peerOnline = g.chats.meta(chatId).flatMapLatest { m ->
        m?.peerIds?.singleOrNull()?.let { g.chats.peerOnline(it) } ?: kotlinx.coroutines.flow.flowOf(false)
    }.combine(seesPeerActivity) { on, ok -> on && ok }.stateIn(viewModelScope, SharingStarted.Eagerly, false)

    /** Fair play for blue ticks: hiding my read receipts from this person hides theirs from me. */
    val showReadTicks = kotlinx.coroutines.flow.combine(g.chats.meta(chatId), g.s.privacy.readReceipts, g.s.privacy.ghost) { m, _, _ ->
        m?.peerIds?.singleOrNull()?.let { g.s.privacy.receiptsTo(it) } ?: true
    }.stateIn(viewModelScope, SharingStarted.Eagerly, true)
    val online = g.s.transport.online

    fun call(video: Boolean) { peerId?.let { g.s.calls.startCall(it, video) } }
    fun clearKeyChanged() = viewModelScope.launch { g.chats.clearKeyChanged(chatId) }
    fun safetyNumber(): String? = peerId?.let { g.s.contacts.safetyNumber(it, g.s.identity.keyPair.public) }

    val meta = g.chats.meta(chatId).stateIn(viewModelScope, SharingStarted.Eagerly, null)
    val peerTyping = g.chats.peerTyping(chatId).stateIn(viewModelScope, SharingStarted.Eagerly, false)

    /** Grouping and day splitting happen here, off the UI thread, once per change. */
    /** False until this chat's encrypted history has been opened → skeleton bubbles, not an empty chat. */
    val loaded = MutableStateFlow(false)

    val items: StateFlow<List<ChatItem>> = g.chats.messages(chatId).map { ms ->
        loaded.value = true
        val out = ArrayList<ChatItem>(ms.size + 8)
        val zone = java.time.ZoneId.systemDefault()
        fun day(t: Long) = java.time.Instant.ofEpochMilli(t).atZone(zone).toLocalDate()
        for (i in ms.indices.reversed()) {
            val m = ms[i]
            val prev = ms.getOrNull(i - 1)
            val next = ms.getOrNull(i + 1)
            fun near(a: Message?, b: Message) = a != null && a.senderId == b.senderId && kotlin.math.abs(a.sentAt - b.sentAt) < 120_000 && day(a.sentAt) == day(b.sentAt)
            out += ChatItem.Bubble(m, m.senderId == me, near(prev, m), near(next, m))
            if (prev == null || day(prev.sentAt) != day(m.sentAt)) out += ChatItem.Day(m.sentAt)
        }
        out
    }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    var draft by mutableStateOf(TextFieldValue(""))
    var replyTo by mutableStateOf<Message?>(null)
    var editing by mutableStateOf<Message?>(null)
    var focused by mutableStateOf<Message?>(null) // long-pressed → action overlay

    /** Photos / videos / files waiting for an optional caption (replying is off meanwhile). */
    val pending = androidx.compose.runtime.mutableStateListOf<app.zura.share.Attachment>()

    init {
        // Items shared from another app land here, ready for a caption.
        viewModelScope.launch {
            app.zura.share.ShareInbox.pending.collect { m ->
                if (chatId in m) app.zura.share.ShareInbox.take(chatId)?.let { p ->
                    stage(p.attachments)
                    p.text?.takeIf { it.isNotBlank() }?.let { t -> draft = TextFieldValue(t, androidx.compose.ui.text.TextRange(t.length)) }
                }
            }
        }
    }

    fun stage(items: List<app.zura.share.Attachment>) {
        if (items.isEmpty()) return
        replyTo = null; editing = null
        pending.addAll(items)
    }

    fun unstage(a: app.zura.share.Attachment) { pending.remove(a); a.temp?.let { app.zura.core.storage.SecureEraser.erase(it) } }
    fun clearPending() { pending.toList().forEach(::unstage) }

    fun onDraft(v: TextFieldValue) {
        draft = v
        g.chats.setTyping(chatId, v.text.isNotBlank())
    }

    fun send() {
        val text = draft.text.trim()
        if (pending.isNotEmpty()) {
            // Caption goes with the first item (like other messengers); none is fine too.
            val items = pending.toList()
            pending.clear()
            draft = TextFieldValue("")
            g.chats.setTyping(chatId, false)
            items.forEachIndexed { i, a -> sendAttachment(a, text.takeIf { i == 0 && it.isNotEmpty() }) }
            return
        }
        if (text.isEmpty()) return
        val edit = editing
        val reply = replyTo
        draft = TextFieldValue("")
        editing = null; replyTo = null
        g.scope.launch { // app scope: leaving the chat must not cancel a send (link cards take a moment)
            if (edit != null) g.chats.edit(chatId, edit.id, text) else g.chats.sendText(chatId, text, reply?.id)
        }
    }

    fun startEdit(m: Message) {
        editing = m; replyTo = null
        draft = TextFieldValue(m.text.orEmpty(), androidx.compose.ui.text.TextRange(m.text.orEmpty().length))
    }

    /** Multi-select (ids). */
    val selected = androidx.compose.runtime.mutableStateListOf<String>()
    fun toggleSelect(id: String) { if (id in selected) selected.remove(id) else selected.add(id) }

    fun deleteSelected(forEveryone: Boolean) {
        val ids = selected.toList(); selected.clear()
        viewModelScope.launch {
            g.chats.messages(chatId).first().filter { it.id in ids }.forEach { m -> m.media?.let(g.images::evict) }
            if (forEveryone) g.chats.deleteForEveryone(chatId, ids) else g.chats.deleteForMe(chatId, ids)
        }
    }

    /** Message id → translation state (shown under the original in the bubble). */
    val translations = androidx.compose.runtime.mutableStateMapOf<String, Translation>()

    fun translate(m: Message) {
        val text = m.text?.takeIf { it.isNotBlank() } ?: return
        translations[m.id] = Translation.Loading
        viewModelScope.launch {
            translations[m.id] = when (val r = app.zura.i18n.Translator.translate(g.app, text)) {
                is app.zura.i18n.Translator.Result.Ok -> Translation.Done(r.text, r.from, r.detected)
                is app.zura.i18n.Translator.Result.Same -> Translation.Error(app.zura.R.string.already_your_language)
                app.zura.i18n.Translator.Result.Undetected -> Translation.Error(app.zura.R.string.cant_detect)
                is app.zura.i18n.Translator.Result.Failed -> Translation.Error(app.zura.R.string.translate_failed)
            }
        }
    }

    fun react(m: Message, emoji: String) = viewModelScope.launch { g.chats.react(chatId, m.id, emoji) }
    fun deleteForMe(m: Message) = viewModelScope.launch { m.media?.let(g.images::evict); g.chats.deleteForMe(chatId, listOf(m.id)) }
    fun deleteForAll(m: Message) = viewModelScope.launch { m.media?.let(g.images::evict); g.chats.deleteForEveryone(chatId, listOf(m.id)) }
    fun markRead() = viewModelScope.launch { g.chats.markRead(chatId) }
    fun retry(m: Message) = viewModelScope.launch { g.chats.retry(chatId, m.id) }

    /**
     * Photos / videos: the bubble appears immediately; compression + encryption + upload continue
     * behind it. Other files (music, documents, GIFs) are encrypted and sent as files.
     * A temporary copy from the share sheet is wiped afterwards.
     */
    private fun sendAttachment(a: app.zura.share.Attachment, caption: String?) = g.scope.launch {
        try {
            if (a.visual) {
                val placeholder = if (a.video) g.videoCompressor.placeholder(a.uri) else g.imageCompressor.placeholder(a.uri, a.mime)
                val msg = runCatching { g.chats.beginMedia(chatId, if (a.video) MsgKind.VIDEO else MsgKind.IMAGE, placeholder, caption) }.getOrNull() ?: return@launch
                runCatching {
                    val ref = if (a.video) g.videoCompressor.import(chatId, a.uri) else g.imageCompressor.import(chatId, a.uri)
                    g.chats.completeMedia(chatId, msg.id, ref)
                }.onFailure { g.chats.failMedia(chatId, msg.id) }
            } else runCatching {
                val dur = if (a.mime.startsWith("audio/")) app.zura.media.AudioMeta.durationMs(g.app, a.uri) else 0L
                val ref = g.app.contentResolver.openInputStream(a.uri)!!.use {
                    g.vault.importMedia(chatId, it, a.mime, durationMs = dur, originalName = a.name ?: if (a.mime.startsWith("audio/")) "audio" else null)
                }
                g.chats.sendMedia(chatId, MsgKind.FILE, ref, caption)
            }
        } finally {
            a.temp?.let { app.zura.core.storage.SecureEraser.erase(it) }
        }
    }

    // ---- voice
    val recorder get() = g.recorder
    /** @return false if the microphone could not be started (shown to the user, never a crash). */
    fun startRecording(): Boolean {
        runCatching { g.playback.stop() }
        val ok = g.recorder.start()
        if (ok) g.chats.setTyping(chatId, true)
        return ok
    }
    fun cancelRecording() { g.recorder.cancel(); g.chats.setTyping(chatId, false) }
    fun finishRecording() = viewModelScope.launch {
        g.recorder.stop(chatId)?.let { g.chats.sendMedia(chatId, MsgKind.VOICE, it) }
    }

    override fun onCleared() {
        g.chats.setTyping(chatId, false)
        if (g.recorder.live.value.recording) g.recorder.cancel()
    }
}

/** Translation of one message, shown inside its bubble. */
sealed interface Translation {
    data object Loading : Translation
    data class Done(val text: String, val from: String, val detected: Boolean) : Translation
    data class Error(val res: Int) : Translation
}
