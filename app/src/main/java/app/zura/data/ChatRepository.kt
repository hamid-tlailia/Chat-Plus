package app.zura.data

import app.zura.core.model.ChatMeta
import app.zura.core.model.MediaRef
import app.zura.core.model.Message
import app.zura.core.model.MomentQuote
import app.zura.core.model.MsgKind
import app.zura.core.model.MsgStatus
import app.zura.core.model.TTL_AFTER_READ
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
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.retryWhen
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withLock
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
    /** Stealth settings; null = reveal everything (tests). */
    val privacy: Privacy? = null,
) {
    // ------------------------------------------------------------------ chat list

    /** Chat list straight from the vault's encrypted index: no chat history is decrypted for it. */
    val chats: StateFlow<List<ChatMeta>> = combine(vault.chatIds, vault.metaIndex) { ids, index ->
        (ids - MomentsRepository.STORE).mapNotNull { index[it] }
            .sortedWith(compareByDescending<ChatMeta> { it.pinned }.thenByDescending { it.updatedAt })
    }.stateIn(scope, SharingStarted.Eagerly, emptyList())

    init {
        // A chat whose key an older version put on slow hardware moves to a fast key, quietly,
        // a few seconds after it's opened (never on the path of showing it).
        vault.onRecovered = { id -> app.zura.DiagLog.record("storage", detail = "chat ${id.take(8)}: log key missing → history reset on this phone") }
        vault.onOpened = { store ->
            // Reset by 0.9.16 (history lost) but the list still advertises the old last message:
            // say so in the chat, and fix the preview.
            val m = store.meta.value
            if (m != null && store.messages.value.isEmpty() && (m.preview != null || m.previewKind != MsgKind.TEXT)) {
                scope.launch(Dispatchers.IO) { runCatching { vault.markHistoryReset(store) } }
            }
            scope.launch(Dispatchers.IO) {
                kotlinx.coroutines.delay(4_000)
                if (!vault.isLocked()) runCatching { store.upgradeKeyIfSlow() }
            }
        }
    }

    /** False only during the one-time build of the index (first start after the update) → skeletons. */
    val chatsLoaded = MutableStateFlow(true)

    // (Start-up replay is triggered by AccountSession.bootstrap, AFTER construction: launching it
    //  from init raced the constructor — the lock it uses wasn't initialised yet → NPE every start.)

    /**
     * Chats missing from the index (older install, or the phone was locked at start): open them
     * once in the background to fill it. Called at start and again after every unlock.
     */
    suspend fun ensureIndex() {
        if (vault.isLocked()) return // redone on unlock (onUnlocked)
        vault.retryIndex()
        val missing = (vault.chatIds.value - MomentsRepository.STORE).filter { it !in vault.metaIndex.value }
        if (missing.isEmpty()) { chatsLoaded.value = true; return }
        chatsLoaded.value = false
        // Several at once; newest chats first so the top of the list fills in quickly.
        val ordered = missing.sortedByDescending { java.io.File(vault.paths.chatDir(it), "log.zl").lastModified() }
        kotlinx.coroutines.coroutineScope {
            ordered.chunked(4).forEach { batch -> batch.map { id -> launch { runCatching { vault.chat(id) } } }.forEach { it.join() } }
        }
        chatsLoaded.value = true
    }

    fun messages(chatId: String): Flow<List<Message>> = flowOf(chatId).flatMapLatest { vault.chat(it).messages }.flowOn(Dispatchers.IO)
    fun meta(chatId: String): Flow<ChatMeta?> = flowOf(chatId).flatMapLatest { vault.chat(it).meta }.flowOn(Dispatchers.IO)

    /** Called for every new incoming message (used for local notifications). */
    var onIncoming: (ChatMeta, Message) -> Unit = { _, _ -> }
    var onReaction: ((ChatMeta, Message, String) -> Unit)? = null

    // ------------------------------------------------------------------ typing & presence (ephemeral)

    private val typingMap = MutableStateFlow<Map<String, Map<String, Long>>>(emptyMap())
    private val presence = MutableStateFlow<Map<String, Long>>(emptyMap()) // userId -> online-until

    fun peerTyping(chatId: String): Flow<Boolean> =
        typingMap.map { m -> m[chatId].orEmpty().values.any { it > System.currentTimeMillis() } }.distinctUntilChanged()

    /** Chats where someone is typing right now — for the chat list (expires on its own). */
    val typingChats: Flow<Set<String>> = typingMap.flatMapLatest { m ->
        kotlinx.coroutines.flow.flow {
            while (true) {
                val now = System.currentTimeMillis()
                val set = m.filterValues { u -> u.values.any { it > now } }.keys
                emit(set)
                if (set.isEmpty()) break
                delay(1_000)
            }
        }
    }.distinctUntilChanged()

    /** When each contact was last in the app (from their presence frames, seeded from the server). */
    private val lastSeen = MutableStateFlow<Map<String, Long>>(emptyMap())
    fun peerLastSeen(userId: String): Flow<Long?> = lastSeen.map { it[userId] }.distinctUntilChanged()
    fun noteLastSeen(userId: String, at: Long) { lastSeen.update { m -> if ((m[userId] ?: 0) >= at) m else m + (userId to at) } }

    fun peerOnline(userId: String): Flow<Boolean> =
        presence.map { (it[userId] ?: 0) > System.currentTimeMillis() }.distinctUntilChanged()

    private var lastTypingSent = 0L

    fun setTyping(chatId: String, active: Boolean) {
        val now = System.currentTimeMillis()
        if (active && now - lastTypingSent < 3_000) return
        lastTypingSent = if (active) now else 0L
        val peers = peersOf(chatId).filter { privacy?.typingTo(it) != false }.ifEmpty { return }
        scope.launch { peers.forEach { transport.signal(it, Frame.Typing(chatId, me, active)) } }
    }

    private var heartbeat: Job? = null

    /** Announce "online" to contacts every 45 s while the app is in the foreground. */
    fun startPresence(contacts: () -> List<String>) {
        heartbeat?.cancel()
        heartbeat = scope.launch {
            while (isActive) {
                contacts().filter { privacy?.lastSeenTo(it) != false }.forEach { transport.signal(it, Frame.Presence(me, true, System.currentTimeMillis())) }
                delay(30_000)
            }
        }
    }

    fun stopPresence(contacts: List<String>) {
        heartbeat?.cancel()
        // "Last seen" travels end-to-end encrypted and durably (inbox), only to contacts allowed
        // to see it — the server no longer stores it in the clear.
        val now = System.currentTimeMillis()
        scope.launch { contacts.filter { privacy?.lastSeenTo(it) != false }.forEach { runCatching { transport.send(it, Frame.Presence(me, false, now)) } } }
    }

    // ------------------------------------------------------------------ lifecycle

    init {
        // Guarded: one failure (e.g. phone locked) must not end this collector for the session.
        scope.launch { transport.online.filter { it }.collect { if (!vault.isLocked()) runCatching { resendPending() } } }
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

    // ---- instant media: the bubble appears at once, the heavy work happens behind it

    /** Shows a photo/video bubble immediately (blurred preview + progress) before compression. */
    suspend fun beginMedia(chatId: String, kind: MsgKind, placeholder: MediaRef, caption: String? = null): Message =
        withContext(Dispatchers.IO) {
            val store = vault.chat(chatId)
            val msg = Message(
                id = UUID.randomUUID().toString(), chatId = chatId, senderId = me, sentAt = now(),
                kind = kind, text = caption, media = placeholder, status = MsgStatus.PENDING,
                ttlMs = store.meta.value?.disappearMs,
            )
            store.upsert(msg)
            touchMeta(store, msg, unreadDelta = 0)
            msg
        }

    /** The encrypted vault file is ready: swap it into the bubble and send. */
    suspend fun completeMedia(chatId: String, id: String, media: MediaRef) = withContext(Dispatchers.IO) {
        val store = vault.chat(chatId)
        val m = store.get(id) ?: return@withContext
        val peers = peersOf(chatId)
        val ready = m.copy(media = media, status = if (peers.isEmpty()) MsgStatus.READ else MsgStatus.PENDING)
        store.upsert(ready)
        if (peers.isNotEmpty()) transmit(store, ready)
    }

    suspend fun failMedia(chatId: String, id: String) = withContext(Dispatchers.IO) {
        val store = vault.chat(chatId)
        store.get(id)?.let { store.upsert(it.copy(status = MsgStatus.FAILED)) }
    }

    /** Call history entry, written on the caller's side and mirrored to the callee. */
    /**
     * Call-log line. Calls never wait for (or fail on) chat storage: on a locked phone the line
     * is persisted and written after unlock. Its message id comes from the call id, so it is
     * written exactly once even if the replay runs twice.
     */
    suspend fun logCall(chatId: String, summary: String, callId: String) {
        val id = "call-$callId"
        if (vault.isLocked()) { vault.defer(app.zura.core.storage.VaultStore.Deferred(id, chatId, "call", summary)); return }
        send(chatId, MsgKind.CALL, summary, null, null, id = id)
    }

    private val unlockLock = kotlinx.coroutines.sync.Mutex()

    /** Phone unlocked (or app started unlocked): finish everything that needed the chat keys — once. */
    suspend fun onUnlocked() = unlockLock.withLock {
        if (vault.isLocked()) return@withLock
        ensureIndex()
        // Chats whose last message is mine, from before the list knew its ticks: fill them in once.
        chats.value.filter { it.previewMine && it.previewStatus == null && it.peerIds.isNotEmpty() }
            .forEach { runCatching { syncPreviewStatus(vault.chat(it.id)) } }
        if (transport.online.value) runCatching { resendPending() } // messages queued while locked
        vault.deferredWork().forEach { d ->
            runCatching {
                if (d.kind == "call") send(d.chatId, MsgKind.CALL, d.text, null, null, id = d.id)
                vault.completeDeferred(d.id)
            }.onFailure { app.zura.DiagLog.record("deferred ${d.kind}", it) }
        }
    }

    data class MediaItem(val chatId: String, val chatTitle: String, val m: Message)

    /** Every finished photo / video / audio / file stored on this phone, across chats (not moments). */
    suspend fun allMedia(): List<MediaItem> = withContext(Dispatchers.IO) {
        chats.value.flatMap { meta ->
            if (!vault.exists(meta.id)) return@flatMap emptyList()
            vault.chat(meta.id).messages.value
                .filter { it.media != null && it.media.isPlaceholder.not() && it.kind != MsgKind.CALL && it.kind != MsgKind.EVENT }
                .map { MediaItem(meta.id, meta.title, it) }
        }
    }

    /** One chat's media (photos, videos, voice, files — ready ones), newest first — for its profile. */
    suspend fun mediaOf(chatId: String): List<Message> = withContext(Dispatchers.IO) {
        if (!vault.exists(chatId)) return@withContext emptyList()
        vault.chat(chatId).messages.value
            .filter { it.media?.isPlaceholder == false && it.kind != MsgKind.CALL && it.kind != MsgKind.EVENT }
            .sortedByDescending { it.sentAt }
    }

    /** Locked chats stay hidden / closed until the user authenticates; re-locks when the app leaves the screen. */
    val lockedRevealed = MutableStateFlow(false)

    /** Pinned chats stay at the top of the list. */
    suspend fun setPinned(chatId: String, on: Boolean) = withContext(Dispatchers.IO) {
        val store = vault.chat(chatId)
        store.meta.value?.let { store.writeMeta(it.copy(pinned = on)) }
    }

    suspend fun setLocked(chatId: String, on: Boolean) = withContext(Dispatchers.IO) {
        val store = vault.chat(chatId)
        store.meta.value?.let { store.writeMeta(it.copy(locked = on)) }
    }

    /** Turn disappearing messages on/off for a chat; both sides switch (announced as a system line). */
    suspend fun setDisappearing(chatId: String, ms: Long?) = withContext(Dispatchers.IO) {
        val store = vault.chat(chatId)
        store.meta.value?.let { store.writeMeta(it.copy(disappearMs = ms)) }
        logEvent(chatId, "$EVENT_TIMER${ms ?: 0}")
    }

    /**
     * Delete expired messages (crypto-shredded: the log is rewritten under a fresh key).
     * [leaving] = the user is leaving [chatId]: "after read" messages that were read go now.
     */
    suspend fun expire(chatId: String? = null, leaving: Boolean = false) = withContext(Dispatchers.IO) {
        val now = now()
        // Periodic sweep: only chats with a timer (cheap). Any chat is also checked when opened/left.
        val ids = if (chatId != null) listOf(chatId) else chats.value.filter { it.disappearMs != null }.map { it.id }
        ids.forEach { id ->
            if (!vault.exists(id)) return@forEach
            val store = vault.chat(id)
            val dead = store.messages.value.filter { m ->
                val ttl = m.ttlMs ?: return@filter false
                when {
                    ttl > 0 -> m.sentAt + ttl <= now
                    ttl == TTL_AFTER_READ -> (leaving && id == chatId && m.status == MsgStatus.READ && m.senderId != me) ||
                        (m.senderId == me && m.status == MsgStatus.READ && leaving && id == chatId) ||
                        now - m.sentAt > 7L * 24 * 3600_000 // safety net: never kept beyond a week
                    else -> false
                }
            }
            if (dead.isNotEmpty()) {
                dead.mapNotNull { it.media }.forEach { runCatching { onEvict?.invoke(it) } }
                deleteForMe(id, dead.map { it.id })
            }
        }
    }

    /** Let the UI drop cached thumbnails of expired media. */
    var onEvict: ((MediaRef) -> Unit)? = null

    private var expiryLoop: Job? = null
    fun startExpiry() {
        expiryLoop?.cancel()
        expiryLoop = scope.launch { while (isActive) { if (!vault.isLocked()) runCatching { expire() }; delay(30_000) } }
    }

    /** A system line both sides see, e.g. [EVENT_SCREENSHOT] when I capture this chat. */
    suspend fun logEvent(chatId: String, code: String) = send(chatId, MsgKind.EVENT, code, null, null)

    /** React / reply to a contact's moment: a normal E2EE message in our chat, carrying a preview of it. */
    /** [thumb] = a sharp small JPEG of the moment (base64); falls back to its blurred micro-preview. */
    suspend fun replyToMoment(ownerId: String, item: Message, text: String, thumb: String? = null) {
        val chatId = ContactsRepository.dmChatId(me, ownerId)
        if (!vault.exists(chatId)) return
        send(chatId, MsgKind.TEXT, text.trim(), null, null, MomentQuote(thumb ?: item.media?.preview, item.text?.take(80), item.kind == MsgKind.VIDEO))
    }

    private suspend fun send(chatId: String, kind: MsgKind, text: String?, media: MediaRef?, replyTo: String?, moment: MomentQuote? = null, id: String? = null) =
        withContext(Dispatchers.IO) {
            val store = vault.chat(chatId)
            if (id != null && store.get(id) != null) return@withContext // already written (exactly-once)
            val peers = peersOf(chatId)
            val msg = Message(
                id = id ?: UUID.randomUUID().toString(), chatId = chatId, senderId = me, sentAt = now(),
                kind = kind, text = text, media = media, replyTo = replyTo,
                status = if (peers.isEmpty()) MsgStatus.READ else MsgStatus.PENDING,
                ttlMs = if (kind == MsgKind.EVENT) null else store.meta.value?.disappearMs,
                moment = moment,
            )
            store.upsert(msg)
            touchMeta(store, msg, unreadDelta = 0)
            if (kind != MsgKind.CALL && kind != MsgKind.EVENT) setTyping(chatId, false)
            // Sent NOW — a link's card (fetched in up to 6 s) used to hold the whole message back.
            if (peers.isNotEmpty()) transmit(store, msg)
            if (kind == MsgKind.TEXT && text != null && app.zura.net.LinkPreviews.find(text).isNotEmpty()) scope.launch(Dispatchers.IO) {
                val lp = app.zura.net.LinkPreviews.fetch(text) ?: return@launch
                val cur = store.update(msg.id) { it.copy(link = lp) } ?: return@launch
                // The card follows as the same message id: the other phone adds it to the bubble.
                val f = Frame.Msg(cur.id, chatId, me, peers, cur.sentAt, cur.kind, cur.text, null, cur.replyTo, lp, cur.ttlMs, cur.moment)
                peers.forEach { p -> transport.sendWithRetry(p, f) }
            }
        }

    private fun transmit(store: ChatStore, msg: Message) = scope.launch(Dispatchers.IO) {
        try {
            transmitOnce(store, msg)
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            app.zura.DiagLog.record("transmit", e)
            store.get(msg.id)?.takeIf { it.status == MsgStatus.PENDING }?.let { store.upsert(it.copy(status = notSent())) }
            runCatching { syncPreviewStatus(store) }
        }
    }

    private suspend fun transmitOnce(store: ChatStore, msg: Message) {
        var wire = msg
        msg.media?.let { ref ->
            if (ref.remoteBlobId == null) {
                // Upload the vault file as-is: it is already ciphertext under a per-file key.
                val path = "$me/${UUID.randomUUID()}"
                runCatching {
                    try { backend.upload("blobs", path, vault.mediaFile(msg.chatId, ref)) { f -> app.zura.media.Transfers.set(msg.id, false, f) } }
                    finally { app.zura.media.Transfers.clear(msg.id) }
                }.onFailure { app.zura.DiagLog.record("blob upload", it); store.upsert(msg.copy(status = notSent())); return }
                wire = msg.copy(media = ref.copy(remoteBlobId = path))
                store.upsert(wire)
            }
        }
        val frame = Frame.Msg(wire.id, wire.chatId, me, peersOf(wire.chatId), wire.sentAt, wire.kind, wire.text, wire.media, wire.replyTo, wire.link, wire.ttlMs, wire.moment)
        // A short text travels INSIDE the push, sealed end-to-end for that peer (FCM/Google see only
        // ciphertext): it doesn't depend on the inbox, so it goes out NOW, in parallel with it.
        val sealedFor = if (frame.kind == MsgKind.TEXT && frame.link == null)
            peersOf(wire.chatId).associateWith { p -> runCatching { transport.sealFor(p, frame) }.getOrNull()?.takeIf { it.length <= 2_600 } }
        else emptyMap()
        // Everything else (photo, voice, file, link, long text…) still gets a small sealed HINT in
        // its wake-up push: who + what kind ("📷 Photo", first words), so even a locked phone shows
        // more than "New message". Never stored from the push — the real message comes from the inbox.
        val hintFor = peersOf(wire.chatId).filter { sealedFor[it] == null }.associateWith { p ->
            val hint = frame.copy(text = frame.text?.let { if (it.length > 160) it.take(160) + "…" else it }, media = null, link = null, replyTo = null)
            runCatching { transport.sealFor(p, hint) }.getOrNull()?.takeIf { it.length <= 2_600 }
        }
        sealedFor.forEach { (peer, sealed) ->
            if (sealed != null) { val online = isOnline(peer); scope.launch { backend.push(peer, "msg", mapOf("e" to sealed), normalPriority = online) } }
        }
        // Contact has Zura open: the live channel too (≈ instant), next to the durable inbox copy
        // (whichever arrives first is stored; the other is recognised by its id).
        peersOf(wire.chatId).filter { isOnline(it) }.forEach { p -> scope.launch { transport.signal(p, frame) } }
        val ok = peersOf(wire.chatId).all { transport.send(it, frame) }
        // Never leave a message on the clock forever: sent → ✓, otherwise → "failed, tap to retry"
        // (also retried automatically on the next reconnect).
        store.update(wire.id) { if (it.status == MsgStatus.PENDING || it.status == MsgStatus.FAILED) it.copy(status = if (ok) MsgStatus.SENT else notSent()) else it }
        syncPreviewStatus(store)
        // Always wake the peer's phone with a push. A short text travels INSIDE the push, sealed
        // end-to-end for that peer (FCM/Google see only ciphertext): their phone decrypts and
        // shows it at once, without first reconnecting and downloading the inbox. Bigger
        // messages (media, link cards) stay content-free; the phone then pulls the inbox.
        // Always a wake-up push ("online" can be stale: an app swiped away may not get its "offline"
        // out). Peers who look online get NORMAL priority — prompt on an awake phone and not held
        // against us if they already had it live; everyone else HIGH (bypasses Doze).
        // An answered call's log line is no news: a silent sync (a locked phone showed "New message").
        val pushKind = if (wire.kind == MsgKind.CALL && wire.text?.contains("missed=1") != true) "sync" else "msg"
        if (ok) peersOf(wire.chatId).filter { sealedFor[it] == null }.forEach { peer ->
            val online = isOnline(peer)
            val data = if (pushKind == "msg") hintFor[peer]?.let { mapOf("h" to it) } ?: emptyMap() else emptyMap()
            scope.launch { backend.push(peer, pushKind, data, normalPriority = online) }
        }
    }

    /**
     * Couldn't send. Offline → stay "waiting" (clock) and go out by itself when the connection
     * returns, like other messengers; online → a real failure (tap to retry, also auto-retried).
     */
    private fun notSent() = if (transport.online.value) MsgStatus.FAILED else MsgStatus.PENDING

    private suspend fun resendPending() = withContext(Dispatchers.IO) {
        vault.chatIds.value.filter { it != MomentsRepository.STORE }.forEach { id ->
            val store = vault.chat(id)
            store.messages.value.filter { it.senderId == me && (it.status == MsgStatus.PENDING || it.status == MsgStatus.FAILED) && it.media?.isPlaceholder != true }
                .forEach { transmit(store, it) }
        }
    }

    suspend fun retry(chatId: String, id: String) = withContext(Dispatchers.IO) {
        val store = vault.chat(chatId)
        // A photo that failed before it was even prepared can't be resent: remove the empty bubble.
        if (store.get(id)?.media?.isPlaceholder == true) { store.purge(listOf(id)); return@withContext }
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
            unread.groupBy { it.senderId }.filterKeys { privacy?.receiptsTo(it) != false }.forEach { (sender, ms) ->
                receipt(sender, Frame.Receipt(chatId, me, ms.map { it.id }, MsgStatus.READ))
            }
        }
        store.meta.value?.takeIf { it.unread > 0 }?.let { store.writeMeta(it.copy(unread = 0)) }
    }

    /**
     * A reply typed straight into a message notification: their messages count as read (receipts
     * as for opening the chat), the reply is sent like any message, and this returns once it has
     * left the phone (or after ~8 s — it is then retried like any unsent message).
     */
    suspend fun replyFromNotification(chatId: String, text: String): Boolean = withContext(Dispatchers.IO) {
        if (text.isBlank() || !vault.exists(chatId)) return@withContext false
        markRead(chatId)
        sendText(chatId, text)
        val store = vault.chat(chatId)
        kotlinx.coroutines.withTimeoutOrNull(8_000) {
            store.messages.first { ms -> ms.none { it.senderId == me && it.status == MsgStatus.PENDING } }
        }
        true
    }

    /**
     * A message shown from its push on a LOCKED phone (stored only after unlock): tell the sender
     * it arrived now — the second tick used to wait until this phone was unlocked.
     */
    suspend fun deliveredFromPush(from: String, chatId: String, id: String) = withContext(Dispatchers.IO) {
        kotlinx.coroutines.withTimeoutOrNull(4_000) { runCatching { transport.send(from, Frame.Receipt(chatId, me, listOf(id), MsgStatus.DELIVERED)) } }
    }

    private val mediaFailures = java.util.concurrent.ConcurrentHashMap<String, Int>()

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
                if (from !in peersOf(f.chatId)) return@withContext
                store.get(f.id)?.let { old ->
                    // Same message again: the link card that followed it, or the second copy (live + inbox).
                    if (f.link != null && old.link == null && old.senderId == from) store.update(f.id) { it.copy(link = f.link) }
                    return@withContext
                }
                // The live copy and the inbox copy can arrive at the same moment: handle one.
                if (!inFlight.add(f.id)) return@withContext
                try {
                // A photo / video whose file couldn't be downloaded (network hiccup, several at once):
                // DON'T store it without its file (that was an empty bubble) nor confirm delivery
                // (the sender then deletes the file) — the inbox copy is kept and retried. Only after
                // repeated failures is it stored without the file (shown as "unavailable").
                val media = f.media?.let { ref ->
                    val blob = ref.remoteBlobId ?: return@let null
                    val r = runCatching { backend.download("blobs", blob) { vault.adoptCiphertext(f.chatId, ref, it) } }
                    r.getOrNull() ?: run {
                        val n = (mediaFailures[f.id] ?: 0) + 1
                        mediaFailures[f.id] = n
                        app.zura.DiagLog.record("media download", detail = "try $n for ${f.kind}: ${r.exceptionOrNull()?.message?.take(80)}")
                        // 400 / 404 = the file is no longer on the server: retrying can't help.
                        val gone = r.exceptionOrNull()?.message.let { it == "download 400" || it == "download 404" }
                        if (n < 6 && !gone) throw IllegalStateException("media not downloaded yet")
                        null
                    }
                }
                val msg = Message(f.id, f.chatId, from, f.sentAt, f.kind, f.text, media, MsgStatus.DELIVERED, replyTo = f.replyTo, link = f.link, ttlMs = f.ttlMs, moment = f.moment)
                // The other side changed this chat's timer: it's announced as a system line.
                if (f.kind == MsgKind.EVENT) parseTimer(f.text)?.let { ms ->
                    store.meta.value?.let { store.writeMeta(it.copy(disappearMs = ms.takeIf { v -> v != 0L })) }
                }
                store.upsert(msg)
                // A call log line from the other phone: only a MISSED call notifies / counts as unread
                // (an answered call used to arrive as "Missed voice call" after hanging up).
                val quietCall = f.kind == MsgKind.CALL && f.text?.contains("missed=1") != true
                touchMeta(store, msg, unreadDelta = if (quietCall) 0 else 1)
                if (!quietCall) store.meta.value?.let { onIncoming(it, msg) }
                typingMap.update { m -> m + (f.chatId to (m[f.chatId].orEmpty() - from)) }
                receipt(from, Frame.Receipt(f.chatId, me, listOf(f.id), MsgStatus.DELIVERED))
                } finally { inFlight.remove(f.id) }
            }
            is Frame.Receipt -> {
                if (f.from != from || !vault.exists(f.chatId)) return@withContext
                val store = vault.chat(f.chatId)
                val updates = f.ids.mapNotNull { store.get(it) }
                    .filter { it.senderId == me && it.status.ordinal < f.status.ordinal }
                    .map { it.copy(status = f.status) }
                store.upsertAll(updates)
                syncPreviewStatus(store)
                // Delivered → the peer has its own copy; remove our ciphertext from the server.
                if (f.status >= MsgStatus.DELIVERED) updates.mapNotNull { it.media?.remoteBlobId }.forEach { backend.delete("blobs", it) }
            }
            is Frame.Typing -> if (f.from == from && from in peersOf(f.chatId)) typingMap.update { m ->
                val users = m[f.chatId].orEmpty()
                m + (f.chatId to if (f.active) users + (from to now() + 6_000) else users - from)
            }
            is Frame.Presence -> if (f.userId == from) {
                presence.update { it + (from to if (f.online) now() + 70_000 else 0L) }
                val at = if (f.online) now() else f.lastSeen.coerceAtMost(now())
                noteLastSeen(from, at)
                if (!f.online) {
                    // Remember it across restarts (the server doesn't keep it any more).
                    val dm = ContactsRepository.dmChatId(me, from)
                    if (vault.exists(dm)) vault.chat(dm).let { s -> s.meta.value?.takeIf { (it.peerSeenAt ?: 0) < at }?.let { s.writeMeta(it.copy(peerSeenAt = at)) } }
                }
            }
            is Frame.Edit -> if (f.from == from && vault.exists(f.chatId)) vault.chat(f.chatId).let { s -> if (s.get(f.id)?.senderId == from) s.edit(f.id, f.text, f.at) }
            is Frame.Delete -> if (f.from == from && vault.exists(f.chatId)) {
                val s = vault.chat(f.chatId)
                s.purge(f.ids.filter { s.get(it)?.senderId == from })
                refreshPreview(f.chatId)
            }
            is Frame.React -> if (f.from == from && vault.exists(f.chatId)) vault.chat(f.chatId).let { s ->
                s.get(f.id)?.let { m ->
                    s.upsert(m.copy(reactions = if (f.emoji == null) m.reactions - from else m.reactions + (from to f.emoji)))
                    // Someone reacted to MY message → tell me, like a message would.
                    if (f.emoji != null && m.senderId == me) s.meta.value?.let { meta -> onReaction?.invoke(meta, m, f.emoji) }
                }
            }
            else -> Unit
        }
    }

    // ------------------------------------------------------------------ helpers

    private fun isOnline(userId: String) = (presence.value[userId] ?: 0) > System.currentTimeMillis()

    fun peersOf(chatId: String): List<String> = vault.chat(chatId).meta.value?.peerIds.orEmpty()

    private suspend fun touchMeta(store: ChatStore, m: Message, unreadDelta: Int) {
        val meta = store.meta.value ?: return
        store.writeMeta(meta.copy(updatedAt = m.sentAt, preview = m.text, previewKind = m.kind, previewMine = m.senderId == me,
            previewStatus = m.status.takeIf { m.senderId == me }, unread = meta.unread + unreadDelta))
    }

    internal suspend fun refreshPreview(chatId: String) {
        val store = vault.chat(chatId)
        val last = store.messages.value.lastOrNull()
        store.meta.value?.let { store.writeMeta(it.copy(preview = last?.text, previewKind = last?.kind ?: MsgKind.TEXT, previewMine = last?.senderId == me, previewStatus = last?.takeIf { l -> l.senderId == me }?.status)) }
    }

    private val inFlight: MutableSet<String> = java.util.concurrent.ConcurrentHashMap.newKeySet()

    /** Ticks: live (instant ✓✓ / seen while they're in the app) + durable copy for when they aren't. */
    private fun receipt(to: String, f: Frame.Receipt) = scope.launch {
        if (isOnline(to)) transport.signal(to, f)
        transport.sendWithRetry(to, f)
    }

    /** My last message's ticks changed (sent → delivered → seen): keep the chat list in step. */
    private suspend fun syncPreviewStatus(store: ChatStore) {
        val last = store.messages.value.lastOrNull() ?: return
        val meta = store.meta.value ?: return
        val want = last.status.takeIf { last.senderId == me }
        if (meta.previewStatus != want && meta.previewMine == (last.senderId == me)) store.writeMeta(meta.copy(previewStatus = want))
    }

    private fun now() = System.currentTimeMillis()

    companion object {
        const val NOTES = "notes"
        const val EVENT_SCREENSHOT = "screenshot"
        const val EVENT_TIMER = "timer:"
        /** "timer:86400000" → 86400000 (0 = off), anything else → null. */
        fun parseTimer(text: String?): Long? = text?.takeIf { it.startsWith(EVENT_TIMER) }?.removePrefix(EVENT_TIMER)?.toLongOrNull()
    }
}
