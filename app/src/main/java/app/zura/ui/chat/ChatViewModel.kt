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
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
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
    val peerOnline = g.chats.meta(chatId).flatMapLatest { m ->
        m?.peerIds?.singleOrNull()?.let { g.chats.peerOnline(it) } ?: kotlinx.coroutines.flow.flowOf(false)
    }.stateIn(viewModelScope, SharingStarted.Eagerly, false)
    val online = g.s.transport.online

    fun call(video: Boolean) { peerId?.let { g.s.calls.startCall(it, video) } }
    fun clearKeyChanged() = viewModelScope.launch { g.chats.clearKeyChanged(chatId) }
    fun safetyNumber(): String? = peerId?.let { g.s.contacts.safetyNumber(it, g.s.identity.keyPair.public) }

    val meta = g.chats.meta(chatId).stateIn(viewModelScope, SharingStarted.Eagerly, null)
    val peerTyping = g.chats.peerTyping(chatId).stateIn(viewModelScope, SharingStarted.Eagerly, false)

    /** Grouping and day splitting happen here, off the UI thread, once per change. */
    val items: StateFlow<List<ChatItem>> = g.chats.messages(chatId).map { ms ->
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

    fun onDraft(v: TextFieldValue) {
        draft = v
        g.chats.setTyping(chatId, v.text.isNotBlank())
    }

    fun send() {
        val text = draft.text.trim()
        if (text.isEmpty()) return
        val edit = editing
        val reply = replyTo
        draft = TextFieldValue("")
        editing = null; replyTo = null
        viewModelScope.launch {
            if (edit != null) g.chats.edit(chatId, edit.id, text) else g.chats.sendText(chatId, text, reply?.id)
        }
    }

    fun startEdit(m: Message) {
        editing = m; replyTo = null
        draft = TextFieldValue(m.text.orEmpty(), androidx.compose.ui.text.TextRange(m.text.orEmpty().length))
    }

    fun react(m: Message, emoji: String) = viewModelScope.launch { g.chats.react(chatId, m.id, emoji) }
    fun deleteForMe(m: Message) = viewModelScope.launch { m.media?.let(g.images::evict); g.chats.deleteForMe(chatId, listOf(m.id)) }
    fun deleteForAll(m: Message) = viewModelScope.launch { m.media?.let(g.images::evict); g.chats.deleteForEveryone(chatId, listOf(m.id)) }
    fun markRead() = viewModelScope.launch { g.chats.markRead(chatId) }
    fun retry(m: Message) = viewModelScope.launch { g.chats.retry(chatId, m.id) }

    /** The bubble appears immediately; compression + encryption + upload continue behind it. */
    fun sendMedia(uri: Uri, mime: String) = g.scope.launch {
        val video = mime.startsWith("video")
        val placeholder = if (video) g.videoCompressor.placeholder(uri) else g.imageCompressor.placeholder(uri, mime)
        val msg = runCatching { g.chats.beginMedia(chatId, if (video) MsgKind.VIDEO else MsgKind.IMAGE, placeholder) }.getOrNull() ?: return@launch
        runCatching {
            val ref = if (video) g.videoCompressor.import(chatId, uri) else g.imageCompressor.import(chatId, uri)
            g.chats.completeMedia(chatId, msg.id, ref)
        }.onFailure { g.chats.failMedia(chatId, msg.id) }
    }

    fun sendFile(uri: Uri, mime: String, name: String?) = g.scope.launch {
        runCatching {
            val ref = g.vault.importMedia(chatId, g.app.contentResolver.openInputStream(uri)!!, mime, originalName = name)
            g.chats.sendMedia(chatId, MsgKind.FILE, ref)
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
