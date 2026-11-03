package app.zura.data

import app.zura.core.model.ChatMeta
import app.zura.core.model.MediaRef
import app.zura.core.model.Message
import app.zura.core.model.MsgKind
import app.zura.core.model.MsgStatus
import app.zura.core.storage.VaultStore
import app.zura.core.sync.BlobClient
import app.zura.core.sync.Frame
import app.zura.core.sync.RelayClient
import androidx.compose.runtime.Immutable
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID

@Immutable
data class MomentGroup(val ownerId: String, val name: String, val hue: Float, val items: List<Message>, val allSeen: Boolean, val mine: Boolean)

/**
 * Moments (24 h stories) reuse the chat storage engine wholesale: they live in a hidden vault
 * chat "moments", so they get the same encryption, key rotation and crypto-shred purge for free.
 * Expiry is a plain [purge] of records older than 24 h — media and keys vanish with them.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class MomentsRepository(
    private val vault: VaultStore,
    private val chats: ChatRepository,
    private val relay: RelayClient,
    private val blobs: BlobClient,
    private val me: String,
    private val scope: CoroutineScope,
) {
    private val store by lazy {
        vault.chat(STORE).also { s ->
            if (s.meta.value == null) scope.launch { s.writeMeta(ChatMeta(STORE, STORE, emptyList(), System.currentTimeMillis())) }
        }
    }

    val groups: StateFlow<List<MomentGroup>> = flowOf(Unit)
        .flatMapLatest { combine(store.messages, chats.chats) { ms, metas -> ms to metas } }
        .map { (ms, metas) ->
            val now = System.currentTimeMillis()
            val live = ms.filter { now - it.sentAt < TTL_MS }
            val byOwner = live.groupBy { it.senderId }
            val list = byOwner.map { (owner, items) ->
                val meta = metas.firstOrNull { it.peerIds == listOf(owner) }
                MomentGroup(
                    ownerId = owner, name = meta?.title ?: owner.take(8), hue = meta?.avatarHue ?: 200f,
                    items = items.sortedBy { it.sentAt }, allSeen = owner == me || items.all { it.status == MsgStatus.READ },
                    mine = owner == me,
                )
            }
            list.sortedWith(compareByDescending<MomentGroup> { it.mine }.thenBy { it.allSeen }.thenByDescending { g -> g.items.last().sentAt })
        }
        .flowOn(Dispatchers.IO)
        .stateIn(scope, SharingStarted.Lazily, emptyList())

    init {
        scope.launch {
            relay.incoming.collect { f ->
                if (f is Frame.MomentPost) runCatching { receive(f) }
            }
        }
    }

    suspend fun post(media: MediaRef, kind: MsgKind, caption: String?) = withContext(Dispatchers.IO) {
        val m = Message(UUID.randomUUID().toString(), STORE, me, System.currentTimeMillis(), kind, caption, media, MsgStatus.READ)
        store.upsert(m)
        val blobId = UUID.randomUUID().toString()
        runCatching {
            blobs.upload(blobId, vault.mediaFile(STORE, media))
            relay.send(Frame.MomentPost(m.id, me, "", m.sentAt, media.copy(remoteBlobId = blobId), caption))
        }
    }

    suspend fun markSeen(id: String) = withContext(Dispatchers.IO) {
        store.get(id)?.takeIf { it.status != MsgStatus.READ }?.let { store.upsert(it.copy(status = MsgStatus.READ)) }
    }

    suspend fun delete(id: String) = withContext(Dispatchers.IO) { store.purge(listOf(id)) }

    /** Purge expired moments: records, media files and their keys. */
    suspend fun expire(): Long = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        store.purge(store.messages.value.filter { now - it.sentAt >= TTL_MS }.map { it.id })
    }

    private suspend fun receive(f: Frame.MomentPost) = withContext(Dispatchers.IO) {
        if (store.get(f.id) != null || System.currentTimeMillis() - f.createdAt >= TTL_MS) return@withContext
        val blob = f.media.remoteBlobId ?: return@withContext
        val ref = blobs.download(blob) { vault.adoptCiphertext(STORE, f.media, it) }
        val kind = if (ref.mime.startsWith("video")) MsgKind.VIDEO else MsgKind.IMAGE
        store.upsert(Message(f.id, STORE, f.from, f.createdAt, kind, f.caption, ref, MsgStatus.DELIVERED))
    }

    companion object {
        const val STORE = "moments"
        const val TTL_MS = 24L * 60 * 60 * 1000
    }
}
