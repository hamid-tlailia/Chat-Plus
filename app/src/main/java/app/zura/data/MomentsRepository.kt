package app.zura.data

import androidx.compose.runtime.Immutable
import app.zura.core.model.ChatMeta
import app.zura.core.model.MediaRef
import app.zura.core.model.Message
import app.zura.core.model.MsgKind
import app.zura.core.model.MsgStatus
import app.zura.core.storage.VaultStore
import app.zura.core.sync.Frame
import app.zura.net.Backend
import app.zura.net.SupabaseTransport
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.retryWhen
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID

@Immutable
data class MomentGroup(val ownerId: String, val name: String, val hue: Float, val items: List<Message>, val allSeen: Boolean, val mine: Boolean)

/**
 * Moments (24 h stories) reuse the chat storage engine: they live in a hidden vault chat
 * "moments", so they get the same encryption and crypto-shred purge. Shared only with contacts,
 * end-to-end encrypted; expiry is a plain purge of records older than 24 h.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class MomentsRepository(
    private val vault: VaultStore,
    private val chats: ChatRepository,
    private val transport: SupabaseTransport,
    private val backend: Backend,
    private val me: String,
    private val contacts: () -> List<String>,
    private val scope: CoroutineScope,
    /** Display name for a user id (contacts and me), so a moment never shows a raw id. */
    private val nameOf: (String) -> String? = { null },
    /** May this person learn that I saw their moment? (stealth mode / ghost → no) */
    private val tellSeen: (String) -> Boolean = { true },
) {
    private val store by lazy {
        vault.chat(STORE).also { s ->
            if (s.meta.value == null) scope.launch { s.writeMeta(ChatMeta(STORE, STORE, emptyList(), System.currentTimeMillis())) }
        }
    }

    val groups: StateFlow<List<MomentGroup>> = flowOf(Unit)
        .flatMapLatest { combine(store.messages, chats.chats) { ms, metas -> ms to metas } }
        // Locked phone: the moments store opens after unlock — retry instead of dying.
        .retryWhen { _, _ -> kotlinx.coroutines.delay(3_000); true }
        .map { (ms, metas) ->
            val now = System.currentTimeMillis()
            val list = ms.filter { now - it.sentAt < TTL_MS }.groupBy { it.senderId }.map { (owner, items) ->
                val meta = metas.firstOrNull { it.peerIds == listOf(owner) }
                MomentGroup(
                    ownerId = owner, name = meta?.title ?: nameOf(owner) ?: "…", hue = meta?.avatarHue ?: (owner.hashCode() and 0xFFFF) % 360f,
                    items = items.sortedBy { it.sentAt }, allSeen = owner == me || items.all { it.status == MsgStatus.READ },
                    mine = owner == me,
                )
            }
            list.sortedWith(compareByDescending<MomentGroup> { it.mine }.thenBy { it.allSeen }.thenByDescending { g -> g.items.last().sentAt })
        }
        .flowOn(Dispatchers.IO)
        .retryWhen { _, _ -> kotlinx.coroutines.delay(3_000); true }
        .stateIn(scope, SharingStarted.Lazily, emptyList())

    suspend fun post(media: MediaRef, kind: MsgKind, caption: String?) = withContext(Dispatchers.IO) {
        val path = "$me/${UUID.randomUUID()}"
        // The server path is kept on my copy: deleting the moment removes it from the server too.
        val m = Message(UUID.randomUUID().toString(), STORE, me, System.currentTimeMillis(), kind, caption, media.copy(remoteBlobId = path), MsgStatus.READ)
        store.upsert(m)
        runCatching {
            backend.upload("blobs", path, vault.mediaFile(STORE, media))
            val frame = Frame.MomentPost(m.id, me, "", m.sentAt, media.copy(remoteBlobId = path), caption)
            contacts().forEach { scope.launch { transport.sendWithRetry(it, frame) } }
        }
    }

    suspend fun markSeen(id: String) = withContext(Dispatchers.IO) {
        val m = store.get(id)?.takeIf { it.status != MsgStatus.READ } ?: return@withContext
        store.upsert(m.copy(status = MsgStatus.READ))
        // Tell the owner (their view count) — unless I hide my read receipts from them.
        if (m.senderId != me && tellSeen(m.senderId)) scope.launch { transport.sendWithRetry(m.senderId, Frame.MomentSeen(id, me, System.currentTimeMillis())) }
    }

    /** Delete one of MY moments: here, on my contacts' phones, and its file on the server. */
    suspend fun delete(id: String) = withContext(Dispatchers.IO) {
        val m = store.get(id)
        store.purge(listOf(id))
        if (m != null && m.senderId == me) {
            m.media?.remoteBlobId?.let { scope.launch { backend.delete("blobs", it) } }
            contacts().forEach { c -> scope.launch { transport.sendWithRetry(c, Frame.MomentDelete(id, me)) } }
        }
    }

    suspend fun expire(): Long = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        val old = store.messages.value.filter { now - it.sentAt >= TTL_MS }
        // My expired moments: their server copies go too.
        old.filter { it.senderId == me }.mapNotNull { it.media?.remoteBlobId }.forEach { backend.delete("blobs", it) }
        store.purge(old.map { it.id })
    }

    /** A contact saw my moment. */
    suspend fun onSeen(from: String, f: Frame.MomentSeen) = withContext(Dispatchers.IO) {
        if (f.from != from || from !in contacts()) return@withContext
        store.update(f.id) { m -> if (m.senderId == me && from !in m.seenBy) m.copy(seenBy = m.seenBy + (from to f.at.coerceAtMost(System.currentTimeMillis()))) else m }
    }

    /** A contact deleted their moment. */
    suspend fun onDeleted(from: String, f: Frame.MomentDelete) = withContext(Dispatchers.IO) {
        if (f.from != from) return@withContext
        if (store.get(f.id)?.senderId == from) store.purge(listOf(f.id))
    }

    fun nameFor(uid: String) = nameOf(uid)

    suspend fun handle(from: String, f: Frame.MomentPost) = withContext(Dispatchers.IO) {
        if (f.from != from || from !in contacts()) return@withContext
        if (store.get(f.id) != null || System.currentTimeMillis() - f.createdAt >= TTL_MS) return@withContext
        val blob = f.media.remoteBlobId ?: return@withContext
        val ref = backend.download("blobs", blob) { vault.adoptCiphertext(STORE, f.media, it) }
        val kind = if (ref.mime.startsWith("video")) MsgKind.VIDEO else MsgKind.IMAGE
        store.upsert(Message(f.id, STORE, from, f.createdAt, kind, f.caption, ref, MsgStatus.DELIVERED))
    }

    companion object {
        const val STORE = "moments"
        const val TTL_MS = 24L * 60 * 60 * 1000
    }
}
