package app.zura.data

import app.zura.core.model.ChatMeta
import app.zura.core.model.MediaRef
import app.zura.core.model.Message
import app.zura.core.model.MsgKind
import app.zura.core.model.MsgStatus
import app.zura.core.storage.ChatStore
import app.zura.core.storage.VaultStore
import app.zura.core.sync.BlobClient
import app.zura.core.sync.Frame
import app.zura.core.sync.RelayClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
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
 * Single source of truth for chats. UI reads StateFlows backed by in-memory indexes of the
 * encrypted logs; every mutation is local-first (instant UI), then mirrored to the relay.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ChatRepository(
    private val vault: VaultStore,
    private val relay: RelayClient,
    private val blobs: BlobClient,
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

    fun messages(chatId: String): Flow<List<Message>> =
        flowOf(chatId).flatMapLatest { vault.chat(it).messages }.flowOn(Dispatchers.IO)

    fun meta(chatId: String): Flow<ChatMeta?> = flowOf(chatId).flatMapLatest { vault.chat(it).meta }.flowOn(Dispatchers.IO)

    // ------------------------------------------------------------------ typing & presence

    /** chatId -> (userId -> expiry). Remote typing auto-expires so a dropped "stop" never sticks. */
    private val typingMap = MutableStateFlow<Map<String, Map<String, Long>>>(emptyMap())

    fun peerTyping(chatId: String): Flow<Boolean> =
        typingMap.map { m -> m[chatId].orEmpty().values.any { it > System.currentTimeMillis() } }.distinctUntilChanged()

    private var lastTypingSent = 0L

    fun setTyping(chatId: String, active: Boolean) {
        val now = System.currentTimeMillis()
        if (active && now - lastTypingSent < 3_000) return // throttle: ≤1 frame / 3 s
        lastTypingSent = if (active) now else 0L
        peersOf(chatId).takeIf { it.isNotEmpty() } ?: return
        relay.send(Frame.Typing(chatId, me, active))
    }

    // ------------------------------------------------------------------ lifecycle

    init {
        scope.launch { relay.incoming.collect { runCatching { handle(it) } } }
        scope.launch {
            relay.state.filter { it == RelayClient.State.ONLINE }.collect { resendPending() }
        }
        scope.launch { // expire stale typing indicators
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

    suspend fun createChat(title: String, peerIds: List<String>): String = withContext(Dispatchers.IO) {
        val id = UUID.randomUUID().toString().replace("-", "").take(20)
        vault.createChat(ChatMeta(id, title, peerIds, now(), avatarHue = (id.hashCode() and 0xFFFF) % 360f))
        id
    }

    // ------------------------------------------------------------------ outgoing

    suspend fun sendText(chatId: String, text: String, replyTo: String? = null) =
        send(chatId, MsgKind.TEXT, text = text.trim(), media = null, replyTo = replyTo)

    suspend fun sendMedia(chatId: String, kind: MsgKind, media: MediaRef, caption: String? = null) =
        send(chatId, kind, caption, media, null)

    private suspend fun send(chatId: String, kind: MsgKind, text: String?, media: MediaRef?, replyTo: String?) =
        withContext(Dispatchers.IO) {
            val store = vault.chat(chatId)
            val peers = peersOf(chatId)
            val msg = Message(
                id = UUID.randomUUID().toString(), chatId = chatId, senderId = me, sentAt = now(),
                kind = kind, text = text, media = media, replyTo = replyTo,
                status = if (peers.isEmpty()) MsgStatus.READ else MsgStatus.PENDING,
            )
            store.upsert(msg)                     // 1. durable + on screen (<1 frame)
            touchMeta(store, msg, unreadDelta = 0)
            setTyping(chatId, false)
            if (peers.isNotEmpty()) transmit(store, msg)  // 2. network, async
        }

    private fun transmit(store: ChatStore, msg: Message) = scope.launch(Dispatchers.IO) {
        var wire = msg
        msg.media?.let { ref ->
            if (ref.remoteBlobId == null) {
                val blobId = UUID.randomUUID().toString()
                runCatching { blobs.upload(blobId, vault.mediaFile(msg.chatId, ref)) }
                    .onFailure { store.upsert(msg.copy(status = MsgStatus.FAILED)); return@launch }
                wire = msg.copy(media = ref.copy(remoteBlobId = blobId))
                store.upsert(wire)
            }
        }
        relay.send(
            Frame.Msg(wire.id, wire.chatId, me, peersOf(wire.chatId), wire.sentAt, wire.kind, wire.text,
                wire.media?.copy(fileName = wire.media.fileName), wire.replyTo),
        )
    }

    private suspend fun resendPending() = withContext(Dispatchers.IO) {
        vault.chatIds.value.forEach { id ->
            val store = vault.chat(id)
            store.messages.value.filter { it.senderId == me && it.status == MsgStatus.PENDING }.forEach { transmit(store, it) }
        }
    }

    suspend fun retry(chatId: String, id: String) = withContext(Dispatchers.IO) {
        val store = vault.chat(chatId)
        store.get(id)?.let { store.upsert(it.copy(status = MsgStatus.PENDING)); transmit(store, it) }
    }

    // ------------------------------------------------------------------ actions

    suspend fun edit(chatId: String, id: String, text: String) = withContext(Dispatchers.IO) {
        val store = vault.chat(chatId)
        val m = store.get(id)?.takeIf { it.senderId == me && it.kind == MsgKind.TEXT } ?: return@withContext
        val at = now()
        store.edit(m.id, text.trim(), at)
        if (peersOf(chatId).isNotEmpty()) relay.send(Frame.Edit(chatId, me, id, text.trim(), at))
    }

    /** Local purge: record + media + key rotation. Returns bytes freed. */
    suspend fun deleteForMe(chatId: String, ids: Collection<String>): Long = withContext(Dispatchers.IO) {
        vault.chat(chatId).purge(ids).also { refreshPreview(chatId) }
    }

    /** Purge locally and instruct every peer device to purge its copy (own messages only). */
    suspend fun deleteForEveryone(chatId: String, ids: Collection<String>): Long = withContext(Dispatchers.IO) {
        val store = vault.chat(chatId)
        val mine = ids.filter { store.get(it)?.senderId == me }
        if (mine.isNotEmpty() && peersOf(chatId).isNotEmpty()) relay.send(Frame.Delete(chatId, me, mine))
        deleteForMe(chatId, ids)
    }

    suspend fun react(chatId: String, id: String, emoji: String?) = withContext(Dispatchers.IO) {
        val store = vault.chat(chatId)
        val m = store.get(id) ?: return@withContext
        val current = m.reactions[me]
        val next = if (emoji == null || emoji == current) null else emoji // tap same emoji = toggle off
        store.upsert(m.copy(reactions = if (next == null) m.reactions - me else m.reactions + (me to next)))
        if (peersOf(chatId).isNotEmpty()) relay.send(Frame.React(chatId, me, id, next))
    }

    suspend fun markRead(chatId: String) = withContext(Dispatchers.IO) {
        val store = vault.chat(chatId)
        val unread = store.messages.value.filter { it.senderId != me && it.status != MsgStatus.READ }
        if (unread.isNotEmpty()) {
            store.upsertAll(unread.map { it.copy(status = MsgStatus.READ) })
            unread.groupBy { it.senderId }.forEach { (_, ms) ->
                relay.send(Frame.Receipt(chatId, me, ms.map { it.id }, MsgStatus.READ))
            }
        }
        store.meta.value?.takeIf { it.unread > 0 }?.let { store.writeMeta(it.copy(unread = 0)) }
    }

    suspend fun deleteChat(chatId: String): Long = withContext(Dispatchers.IO) { vault.deleteChat(chatId) }

    suspend fun setPinned(chatId: String, pinned: Boolean) = withContext(Dispatchers.IO) {
        val store = vault.chat(chatId)
        store.meta.value?.let { store.writeMeta(it.copy(pinned = pinned)) }
    }

    // ------------------------------------------------------------------ incoming

    private suspend fun handle(f: Frame) = withContext(Dispatchers.IO) {
        when (f) {
            is Frame.Msg -> {
                if (f.chatId == MomentsRepository.STORE) return@withContext
            if (!vault.exists(f.chatId)) vault.createChat(ChatMeta(f.chatId, f.from, listOf(f.from), now()))
                val store = vault.chat(f.chatId)
                if (store.get(f.id) != null) return@withContext // idempotent: relay may redeliver
                val media = f.media?.let { ref ->
                    val blob = ref.remoteBlobId ?: return@let null
                    blobs.download(blob) { vault.adoptCiphertext(f.chatId, ref, it) }
                }
                val msg = Message(f.id, f.chatId, f.from, f.sentAt, f.kind, f.text, media, MsgStatus.DELIVERED, replyTo = f.replyTo)
                store.upsert(msg)
                touchMeta(store, msg, unreadDelta = 1)
                typingMap.update { m -> m + (f.chatId to (m[f.chatId].orEmpty() - f.from)) }
                relay.send(Frame.Receipt(f.chatId, me, listOf(f.id), MsgStatus.DELIVERED))
            }
            is Frame.Ack -> vault.chatIds.value.firstNotNullOfOrNull { id -> vault.chat(id).get(f.id)?.let { id to it } }
                ?.let { (id, m) -> if (m.status == MsgStatus.PENDING) vault.chat(id).upsert(m.copy(status = MsgStatus.SENT)) }
            is Frame.Receipt -> {
                val store = vault.chat(f.chatId)
                val updates = f.ids.mapNotNull { store.get(it) }
                    .filter { it.senderId == me && it.status.ordinal < f.status.ordinal }
                    .map { it.copy(status = f.status) }
                store.upsertAll(updates)
            }
            is Frame.Typing -> typingMap.update { m ->
                val users = m[f.chatId].orEmpty()
                m + (f.chatId to if (f.active) users + (f.from to now() + 6_000) else users - f.from)
            }
            is Frame.Edit -> vault.chat(f.chatId).let { s -> if (s.get(f.id)?.senderId == f.from) s.edit(f.id, f.text, f.at) }
            is Frame.Delete -> {
                val s = vault.chat(f.chatId)
                s.purge(f.ids.filter { s.get(it)?.senderId == f.from }) // peers can only erase their own
                refreshPreview(f.chatId)
            }
            is Frame.React -> vault.chat(f.chatId).let { s ->
                s.get(f.id)?.let { m ->
                    s.upsert(m.copy(reactions = if (f.emoji == null) m.reactions - f.from else m.reactions + (f.from to f.emoji)))
                }
            }
            else -> Unit
        }
    }

    // ------------------------------------------------------------------ helpers

    private fun peersOf(chatId: String): List<String> = vault.chat(chatId).meta.value?.peerIds.orEmpty()

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
