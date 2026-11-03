package app.zura.data

import app.zura.core.model.ChatMeta
import app.zura.core.model.MediaRef
import app.zura.core.model.Message
import app.zura.core.model.MsgKind
import app.zura.core.model.MsgStatus
import app.zura.core.storage.ChatStore
import app.zura.core.storage.VaultStore
import app.zura.core.sync.Frame
import app.zura.net.Backend
import app.zura.net.SupabaseTransport
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID

/**
 * Single source of truth for conversations. Local-first: every action lands in the encrypted
 * on-device log instantly (UI updates in the same frame), then goes out end-to-end encrypted.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ChatRepository(
    private val vault: VaultStore,
    private val transport: SupabaseTransport,
    private val backend: Backend,
    val me: String,
    private val scope: CoroutineScope,
) {
    // ------------------------------------------------------------------ chat list

    val chats: StateFlow<List<ChatMeta>> = vault.chatIds
        .flatMapLatest { ids ->
            val visible = ids - MomentsRepository.STORE
            if (visible.isEmpty()) flowOf(emptyList())
            else combine(visible.map { vault.chat(it).meta }) { metas ->
                metas.filterNotNull().sortedWith(compareByDescending<ChatMeta> { it.pinned }.thenByDescending { it.updatedAt })
            }
        }
        .flowOn(Dispatchers.IO)
        .stateIn(scope, SharingStarted.Eagerly, emptyList())

    fun messages(chatId: String): Flow<List<Message>> = flowOf(chatId).flatMapLatest { vault.chat(it).messages }.flowOn(Dispatchers.IO)
    fun meta(chatId: String): Flow<ChatMeta?> = flowOf(chatId).flatMapLatest { vault.chat(it).meta }.flowOn(Dispatchers.IO)

    /** Called for every new incoming message (used for local notifications). */
    var onIncoming: (ChatMeta, Message) -> Unit = { _, _ -> }

    // ------------------------------------------------------------------ typing & presence (ephemeral)

    private val typingMap = MutableStateFlow<Map<String, Map<String, Long>>>(emptyMap())
    private val presence = MutableStateFlow<Map<String, Long>>(emptyMap()) // userId -> online-until

    fun peerTyping(chatId: String): Flow<Boolean> =
        typingMap.map { m -> m[chatId].orEmpty().values.any { it > System.currentTimeMillis() } }.distinctUntilChanged()

    fun peerOnline(userId: String): Flow<Boolean> =
        presence.map { (it[userId] ?: 0) > System.currentTimeMillis() }.distinctUntilChanged()

    private var lastTypingSent = 0L

    fun setTyping(chatId: String, active: Boolean) {
        val now = System.currentTimeMillis()
        if (active && now - lastTypingSent < 3_000) return
        lastTypingSent = if (active) now else 0L
        val peers = peersOf(chatId).ifEmpty { return }
        scope.launch { peers.forEach { transport.signal(it, Frame.Typing(chatId, me, active)) } }
    }

    private var heartbeat: Job? = null

    /** Announce "online" to contacts every 45 s while the app is in the foreground. */
    fun startPresence(contacts: () -> List<String>) {
        heartbeat?.cancel()
        heartbeat = scope.launch {
            while (isActive) {
                contacts().forEach { transport.signal(it, Frame.Presence(me, true, System.currentTimeMillis())) }
                delay(45_000)
            }
        }
    }

    fun stopPresence(contacts: List<String>) {
        heartbeat?.cancel()
        scope.launch { contacts.forEach { transport.signal(it, Frame.Presence(me, false, System.currentTimeMillis())) } }
    }

    // ------------------------------------------------------------------ lifecycle

    init {
        scope.launch { transport.online.filter { it }.collect { resendPending() } }
        scope.launch {
            while (isActive) {
                delay(1_000)
                val now = System.currentTimeMillis()
                typingMap.update { m -> m.mapValues { (_, u) -> u.filterValues { it > now } }.filterValues { it.isNotEmpty() } }
            }
        }
    }

    suspend fun ensureNotesChat(title: String) = withContext(Dispatchers.IO) {
        if (!vault.exists(NOTES)) vault.createChat(ChatMeta(NOTES, title, emptyList(), now(), pinned = true, avatarHue = 42f))
    }

    // ------------------------------------------------------------------ outgoing

    suspend fun sendText(chatId: String, text: String, replyTo: String? = null) =
        send(chatId, MsgKind.TEXT, text = text.trim(), media = null, replyTo = replyTo)

    suspend fun sendMedia(chatId: String, kind: MsgKind, media: MediaRef, caption: String? = null) =
        send(chatId, kind, caption, media, null)

    /** Call history entry, written on the caller's side and mirrored to the callee. */
    suspend fun logCall(chatId: String, summary: String) = send(chatId, MsgKind.CALL, summary, null, null)

    private suspend fun send(chatId: String, kind: MsgKind, text: String?, media: MediaRef?, replyTo: String?) =
        withContext(Dispatchers.IO) {
            val store = vault.chat(chatId)
            val peers = peersOf(chatId)
            val msg = Message(
                id = UUID.randomUUID().toString(), chatId = chatId, senderId = me, sentAt = now(),
                kind = kind, text = text, media = media, replyTo = replyTo,
                status = if (peers.isEmpty()) MsgStatus.READ else MsgStatus.PENDING,
            )
            store.upsert(msg)
            touchMeta(store, msg, unreadDelta = 0)
            if (kind != MsgKind.CALL) setTyping(chatId, false)
            if (peers.isNotEmpty()) transmit(store, msg)
        }

    private fun transmit(store: ChatStore, msg: Message) = scope.launch(Dispatchers.IO) {
        var wire = msg
        msg.media?.let { ref ->
            if (ref.remoteBlobId == null) {
                // Upload the vault file as-is: it is already ciphertext under a per-file key.
                val path = "$me/${UUID.randomUUID()}"
                runCatching { backend.upload("blobs", path, vault.mediaFile(msg.chatId, ref)) }
                    .onFailure { store.upsert(msg.copy(status = MsgStatus.FAILED)); return@launch }
                wire = msg.copy(media = ref.copy(remoteBlobId = path))
                store.upsert(wire)
            }
        }
        val frame = Frame.Msg(wire.id, wire.chatId, me, peersOf(wire.chatId), wire.sentAt, wire.kind, wire.text, wire.media, wire.replyTo)
        val ok = peersOf(wire.chatId).all { transport.send(it, frame) }
        if (ok) store.get(wire.id)?.takeIf { it.status == MsgStatus.PENDING }?.let { store.upsert(it.copy(status = MsgStatus.SENT)) }
        // Peer not connected right now → wake their phone (content-free push; they pull + decrypt).
        if (ok) peersOf(wire.chatId).filterNot { isOnline(it) }.forEach { backend.push(it, "msg") }
    }

    private suspend fun resendPending() = withContext(Dispatchers.IO) {
        vault.chatIds.value.filter { it != MomentsRepository.STORE }.forEach { id ->
            val store = vault.chat(id)
            store.messages.value.filter { it.senderId == me && it.status == MsgStatus.PENDING }.forEach { transmit(store, it) }
        }
    }

    suspend fun retry(chatId: String, id: String) = withContext(Dispatchers.IO) {
        val store = vault.chat(chatId)
        store.get(id)?.let { store.upsert(it.copy(status = MsgStatus.PENDING)); transmit(store, it) }
    }

    private fun broadcast(chatId: String, frame: Frame) = scope.launch(Dispatchers.IO) {
        peersOf(chatId).forEach { transport.sendWithRetry(it, frame) }
    }

    // ------------------------------------------------------------------ actions

    suspend fun edit(chatId: String, id: String, text: String) = withContext(Dispatchers.IO) {
        val store = vault.chat(chatId)
        val m = store.get(id)?.takeIf { it.senderId == me && it.kind == MsgKind.TEXT } ?: return@withContext
        val at = now()
        store.edit(m.id, text.trim(), at)
        broadcast(chatId, Frame.Edit(chatId, me, id, text.trim(), at))
    }

    suspend fun deleteForMe(chatId: String, ids: Collection<String>): Long = withContext(Dispatchers.IO) {
        vault.chat(chatId).purge(ids).also { refreshPreview(chatId) }
    }

    suspend fun deleteForEveryone(chatId: String, ids: Collection<String>): Long = withContext(Dispatchers.IO) {
        val store = vault.chat(chatId)
        val mine = ids.mapNotNull { store.get(it) }.filter { it.senderId == me }
        if (mine.isNotEmpty()) {
            broadcast(chatId, Frame.Delete(chatId, me, mine.map { it.id }))
            mine.mapNotNull { it.media?.remoteBlobId }.forEach { backend.delete("blobs", it) }
        }
        deleteForMe(chatId, ids)
    }

    suspend fun react(chatId: String, id: String, emoji: String?) = withContext(Dispatchers.IO) {
        val store = vault.chat(chatId)
        val m = store.get(id) ?: return@withContext
        val next = if (emoji == null || emoji == m.reactions[me]) null else emoji
        store.upsert(m.copy(reactions = if (next == null) m.reactions - me else m.reactions + (me to next)))
        broadcast(chatId, Frame.React(chatId, me, id, next))
    }

    suspend fun markRead(chatId: String) = withContext(Dispatchers.IO) {
        val store = vault.chat(chatId)
        val unread = store.messages.value.filter { it.senderId != me && it.status != MsgStatus.READ }
        if (unread.isNotEmpty()) {
            store.upsertAll(unread.map { it.copy(status = MsgStatus.READ) })
            unread.groupBy { it.senderId }.forEach { (sender, ms) ->
                scope.launch { transport.sendWithRetry(sender, Frame.Receipt(chatId, me, ms.map { it.id }, MsgStatus.READ)) }
            }
        }
        store.meta.value?.takeIf { it.unread > 0 }?.let { store.writeMeta(it.copy(unread = 0)) }
    }

    suspend fun deleteChat(chatId: String): Long = withContext(Dispatchers.IO) { vault.deleteChat(chatId) }

    suspend fun clearKeyChanged(chatId: String) = withContext(Dispatchers.IO) {
        val s = vault.chat(chatId); s.meta.value?.let { s.writeMeta(it.copy(keyChanged = false)) }
    }

    // ------------------------------------------------------------------ incoming (already authenticated + decrypted)

    suspend fun handle(from: String, f: Frame) = withContext(Dispatchers.IO) {
        when (f) {
            is Frame.Msg -> {
                if (f.from != from || f.chatId == MomentsRepository.STORE) return@withContext
                if (!vault.exists(f.chatId)) return@withContext // only conversations with accepted contacts exist
                val store = vault.chat(f.chatId)
                if (from !in peersOf(f.chatId) || store.get(f.id) != null) return@withContext
                val media = f.media?.let { ref ->
                    val blob = ref.remoteBlobId ?: return@let null
                    runCatching { backend.download("blobs", blob) { vault.adoptCiphertext(f.chatId, ref, it) } }.getOrNull()
                }
                val msg = Message(f.id, f.chatId, from, f.sentAt, f.kind, f.text, media, MsgStatus.DELIVERED, replyTo = f.replyTo)
                store.upsert(msg)
                touchMeta(store, msg, unreadDelta = 1)
                store.meta.value?.let { onIncoming(it, msg) }
                typingMap.update { m -> m + (f.chatId to (m[f.chatId].orEmpty() - from)) }
                scope.launch { transport.sendWithRetry(from, Frame.Receipt(f.chatId, me, listOf(f.id), MsgStatus.DELIVERED)) }
            }
            is Frame.Receipt -> {
                if (f.from != from || !vault.exists(f.chatId)) return@withContext
                val store = vault.chat(f.chatId)
                val updates = f.ids.mapNotNull { store.get(it) }
                    .filter { it.senderId == me && it.status.ordinal < f.status.ordinal }
                    .map { it.copy(status = f.status) }
                store.upsertAll(updates)
                // Delivered → the peer has its own copy; remove our ciphertext from the server.
                if (f.status >= MsgStatus.DELIVERED) updates.mapNotNull { it.media?.remoteBlobId }.forEach { backend.delete("blobs", it) }
            }
            is Frame.Typing -> if (f.from == from && from in peersOf(f.chatId)) typingMap.update { m ->
                val users = m[f.chatId].orEmpty()
                m + (f.chatId to if (f.active) users + (from to now() + 6_000) else users - from)
            }
            is Frame.Presence -> if (f.userId == from) presence.update { it + (from to if (f.online) now() + 100_000 else 0L) }
            is Frame.Edit -> if (f.from == from && vault.exists(f.chatId)) vault.chat(f.chatId).let { s -> if (s.get(f.id)?.senderId == from) s.edit(f.id, f.text, f.at) }
            is Frame.Delete -> if (f.from == from && vault.exists(f.chatId)) {
                val s = vault.chat(f.chatId)
                s.purge(f.ids.filter { s.get(it)?.senderId == from })
                refreshPreview(f.chatId)
            }
            is Frame.React -> if (f.from == from && vault.exists(f.chatId)) vault.chat(f.chatId).let { s ->
                s.get(f.id)?.let { m -> s.upsert(m.copy(reactions = if (f.emoji == null) m.reactions - from else m.reactions + (from to f.emoji))) }
            }
            else -> Unit
        }
    }

    // ------------------------------------------------------------------ helpers

    private fun isOnline(userId: String) = (presence.value[userId] ?: 0) > System.currentTimeMillis()

    fun peersOf(chatId: String): List<String> = vault.chat(chatId).meta.value?.peerIds.orEmpty()

    private suspend fun touchMeta(store: ChatStore, m: Message, unreadDelta: Int) {
        val meta = store.meta.value ?: return
        store.writeMeta(meta.copy(updatedAt = m.sentAt, preview = m.text, previewKind = m.kind, unread = meta.unread + unreadDelta))
    }

    private suspend fun refreshPreview(chatId: String) {
        val store = vault.chat(chatId)
        val last = store.messages.value.lastOrNull()
        store.meta.value?.let { store.writeMeta(it.copy(preview = last?.text, previewKind = last?.kind ?: MsgKind.TEXT)) }
    }

    private fun now() = System.currentTimeMillis()

    companion object { const val NOTES = "notes" }
}
