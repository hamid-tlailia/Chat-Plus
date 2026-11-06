package app.zura.data

import app.zura.account.Contact
import app.zura.account.ContactRow
import app.zura.account.Invite
import app.zura.account.InvitationRow
import app.zura.account.Profile
import app.zura.account.SearchHit
import app.zura.core.crypto.E2EE
import app.zura.core.crypto.SecureFile
import app.zura.core.model.ChatMeta
import app.zura.core.storage.VaultStore
import app.zura.net.Backend
import io.github.jan.supabase.postgrest.from
import io.github.jan.supabase.postgrest.postgrest
import io.github.jan.supabase.postgrest.query.filter.FilterOperator
import io.github.jan.supabase.postgrest.rpc
import io.github.jan.supabase.realtime.PostgresAction
import io.github.jan.supabase.realtime.RealtimeChannel
import io.github.jan.supabase.realtime.channel
import io.github.jan.supabase.realtime.postgresChangeFlow
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.security.MessageDigest
import java.security.PublicKey
import java.util.concurrent.ConcurrentHashMap

/**
 * Social graph: who I can talk to. Messaging is only possible between accepted contacts —
 * enforced by the database (RLS), mirrored here for the UI.
 *
 * Invitation lifecycle:  search @username → send (optional note) → recipient accepts / rejects,
 * sender may cancel while pending. Accepting creates the contact pair and both phones create the
 * conversation automatically (realtime). If both invite each other, the second invite auto-accepts.
 */
@OptIn(FlowPreview::class)
class ContactsRepository(
    private val backend: Backend,
    val me: String,
    private val vault: VaultStore,
    private val pins: SecureFile,
    private val scope: CoroutineScope,
    /** Encrypted local copy of contacts + profiles (null in tests). */
    private val snapshot: SecureFile? = null,
) {
    private val profiles = ConcurrentHashMap<String, Profile>()
    private val keys = ConcurrentHashMap<String, PublicKey>()

    private val _contacts = MutableStateFlow<List<Contact>>(emptyList())
    val contacts: StateFlow<List<Contact>> = _contacts.asStateFlow()

    /** People who blocked ME (server truth via blocked_me(), plus their live Block frame). */
    private val _blockedMe = MutableStateFlow<Set<String>>(emptySet())
    val blockedMe: StateFlow<Set<String>> = _blockedMe.asStateFlow()
    /** Either side blocked the other → no messages, no calls. */
    fun isBlocked(uid: String) = uid in _blockedMe.value || _contacts.value.any { it.profile.id == uid && it.blocked }

    suspend fun refreshBlockedMe() = withContext(Dispatchers.IO) {
        runCatching {
            val arr = backend.json.parseToJsonElement(backend.client.postgrest.rpc("blocked_me").data)
            (arr as? kotlinx.serialization.json.JsonArray)?.mapNotNull { (it as? kotlinx.serialization.json.JsonPrimitive)?.content }?.toSet()
        }.getOrNull()?.let { _blockedMe.value = it }
    }

    fun markBlockedMe(uid: String, on: Boolean) { _blockedMe.value = if (on) _blockedMe.value + uid else _blockedMe.value - uid }

    private val _invites = MutableStateFlow<List<Invite>>(emptyList())
    val invites: StateFlow<List<Invite>> = _invites.asStateFlow()

    private val _me = MutableStateFlow<Profile?>(null)
    val myProfile: StateFlow<Profile?> = _me.asStateFlow()

    private val changes = MutableSharedFlow<Unit>(extraBufferCapacity = 8)
    private var channel: RealtimeChannel? = null

    fun profile(uid: String): Profile? = profiles[uid]

    /** Test hook: populate the social graph without a server. */
    @androidx.annotation.VisibleForTesting
    internal suspend fun seedForTest(meProfile: Profile, contacts: List<Contact>, invites: List<Invite>) {
        (contacts.map { it.profile } + invites.map { it.profile } + meProfile).forEach { profiles[it.id] = it }
        _me.value = meProfile
        _contacts.value = contacts
        _invites.value = invites
        contacts.forEach { ensureChat(it.profile) }
    }

    /** True once contacts are known (from the local copy or the server) — the UI shows skeletons until then. */
    private val _loaded = MutableStateFlow(false)
    val loaded: StateFlow<Boolean> = _loaded.asStateFlow()

    /** Instant: contacts, profiles and keys from the encrypted on-device copy (no network). */
    fun loadLocal() {
        val snap = runCatching { snapshot?.readText()?.let { backend.json.decodeFromString(app.zura.account.ContactsSnapshot.serializer(), it) } }.getOrNull() ?: return
        (snap.contacts.map { it.profile } + listOfNotNull(snap.me)).forEach { p -> profiles.putIfAbsent(p.id, p) }
        if (_me.value == null) _me.value = snap.me
        if (_contacts.value.isEmpty()) _contacts.value = snap.contacts
        if (snap.contacts.isNotEmpty() || snap.me != null) _loaded.value = true
    }

    private fun saveLocal() {
        runCatching {
            snapshot?.writeText(backend.json.encodeToString(app.zura.account.ContactsSnapshot.serializer(), app.zura.account.ContactsSnapshot(_me.value, _contacts.value)))
        }
    }

    fun start() {
        runCatching { loadLocal() }
        scope.launch { changes.debounce(250).collect { runCatching { refresh() } } }
        scope.launch {
            runCatching { refresh() }
            val ch = backend.client.channel("social-$me")
            val a = ch.postgresChangeFlow<PostgresAction>("public") { table = "invitations"; filter("to_id", FilterOperator.EQ, me) }
            val b = ch.postgresChangeFlow<PostgresAction>("public") { table = "invitations"; filter("from_id", FilterOperator.EQ, me) }
            val c = ch.postgresChangeFlow<PostgresAction>("public") { table = "contacts"; filter("owner_id", FilterOperator.EQ, me) }
            launch { merge(a, b, c).collect { changes.tryEmit(Unit) } }
            channel = ch
            runCatching { ch.subscribe(blockUntilSubscribed = true) }
            changes.tryEmit(Unit) // close the gap between first load and subscription
        }
    }

    fun stop() = scope.launch { runCatching { channel?.unsubscribe() }; channel = null }

    // ---------------------------------------------------------------- reads

    suspend fun refresh() = withContext(Dispatchers.IO) {
        val contactRows = decodeList(ContactRow.serializer(), backend.client.from("contacts").select { filter { eq("owner_id", me) } }.data)
        val inviteRows = decodeList(InvitationRow.serializer(), backend.client.from("invitations").select { filter { eq("status", "pending") } }.data)
        val ids = (contactRows.map { it.peerId } + inviteRows.map { if (it.fromId == me) it.toId else it.fromId } + me).distinct()
        fetchProfiles(ids)
        _me.value = profiles[me]
        _contacts.value = contactRows.mapNotNull { r -> profiles[r.peerId]?.let { Contact(it, r.blocked) } }
            .sortedBy { it.profile.displayName.lowercase() }
        _loaded.value = true
        saveLocal()
        _invites.value = inviteRows.mapNotNull { r ->
            val other = if (r.fromId == me) r.toId else r.fromId
            profiles[other]?.let { Invite(r.id, incoming = r.toId == me, profile = it, message = r.message, createdAt = r.createdAt) }
        }.sortedByDescending { it.createdAt }
        refreshBlockedMe()
        runCatching { onRefreshed?.invoke(_contacts.value, _invites.value) }
        // One failing conversation must never block the others.
        _contacts.value.forEach { c -> runCatching { ensureChat(c.profile) }.onFailure { app.zura.DiagLog.record("ensureChat ${c.profile.id.take(8)}", it) } }
    }

    private suspend fun fetchProfiles(ids: List<String>) {
        if (ids.isEmpty()) return
        val list = decodeList(Profile.serializer(), backend.client.from("profiles").select { filter { isIn("id", ids) } }.data)
        list.forEach { p -> profiles[p.id] = p; p.identityKey?.let { k -> acceptKey(p.id, k) } }
    }

    /** The contact's E2EE public key, with trust-on-first-use pinning. */
    suspend fun peerKey(uid: String): PublicKey? {
        keys[uid]?.let { return it }
        // Pinned on this phone already (trust-on-first-use store): no network needed — this is
        // what lets a call push be decrypted the instant a killed app wakes up.
        runCatching { loadPins()[uid]?.let { E2EE.decodePublic(it) } }.getOrNull()?.let { keys[uid] = it; return it }
        // Not cached yet (or an earlier attempt failed): refetch that profile and decode again.
        runCatching { withContext(Dispatchers.IO) { fetchProfiles(listOf(uid)) } }
            .onFailure { app.zura.DiagLog.record("peerKey fetch ${uid.take(8)}", it) }
        if (keys[uid] == null) app.zura.DiagLog.record("peerKey missing ${uid.take(8)}", detail = "profile=${profiles.containsKey(uid)} hasKey=${profiles[uid]?.identityKey != null}")
        return keys[uid]
    }

    private fun acceptKey(uid: String, b64: String) {
        val key = runCatching { E2EE.decodePublic(b64) }
            .onFailure { app.zura.DiagLog.record("decodeKey ${uid.take(8)}", it, "len=${b64.length}") }
            .getOrNull() ?: return
        // Cache first: the contact must be reachable even if persisting the pin fails.
        keys[uid] = key
        val pinned = runCatching { loadPins() }.getOrDefault(emptyMap())
        val old = pinned[uid]
        if (old != null && old != b64) {
            // Identity changed (reinstall / new phone / or an attack). Use the new key but tell the user.
            scope.launch {
                val chatId = dmChatId(me, uid)
                if (vault.exists(chatId)) vault.chat(chatId).let { s -> s.meta.value?.let { s.writeMeta(it.copy(keyChanged = true)) } }
            }
        }
        if (old != b64) runCatching { savePins(pinned + (uid to b64)) }.onFailure { app.zura.DiagLog.record("savePins", it) }
    }

    /** Safety number with [uid] — loads their key (pinned on this phone, else from the server) if it isn't in memory yet. */
    suspend fun safetyNumber(uid: String, mine: PublicKey): String? = peerKey(uid)?.let { withContext(Dispatchers.Default) { E2EE.safetyNumber(mine, it) } }

    // ---------------------------------------------------------------- invitations

    suspend fun search(query: String): List<SearchHit> = withContext(Dispatchers.IO) {
        // Server matches: username prefix, display name (any script), or an exact email address.
        if (query.trim().removePrefix("@").length < 2) return@withContext emptyList()
        val r = backend.client.postgrest.rpc("search_profiles", buildJsonObject { put("p_query", query.trim().removePrefix("@")) })
        decodeList(SearchHit.serializer(), r.data).filter { it.relation != "self" }
    }

    /** Registered people you can invite (newest first). */
    suspend fun discover(): List<SearchHit> = withContext(Dispatchers.IO) {
        decodeList(SearchHit.serializer(), backend.client.postgrest.rpc("discover_profiles").data)
    }

    /** Sent → their phone is woken to show "X invited you" (the server checks the invitation is real and fresh). */
    suspend fun sendInvite(uid: String, message: String?) = rpc("send_invite") { put("p_to", uid); put("p_message", message?.take(280)) }
        .also { r -> if (r.isSuccess) scope.launch { runCatching { backend.push(uid, "invite") } } }

    /** Accepted → the inviter's phone shows "X accepted your invitation". */
    suspend fun respond(inviteId: String, accept: Boolean): Result<Unit> {
        val from = _invites.value.firstOrNull { it.id == inviteId && it.incoming }?.profile?.id
        return rpc("respond_invite") { put("p_id", inviteId); put("p_accept", accept) }
            .also { r -> if (r.isSuccess && accept && from != null) scope.launch { kotlinx.coroutines.delay(800); runCatching { backend.push(from, "invite_ok") } } }
    }

    /** After every refresh: new invitations / accepted ones → notifications (see AccountSession). */
    var onRefreshed: ((contacts: List<Contact>, invites: List<Invite>) -> Unit)? = null
    suspend fun cancel(inviteId: String) = rpc("cancel_invite") { put("p_id", inviteId) }
    suspend fun setBlocked(uid: String, blocked: Boolean) = rpc("set_blocked") { put("p_peer", uid); put("p_blocked", blocked) }
    suspend fun remove(uid: String) = rpc("remove_contact") { put("p_peer", uid) }

    private suspend fun rpc(name: String, args: kotlinx.serialization.json.JsonObjectBuilder.() -> Unit): Result<Unit> =
        withContext(Dispatchers.IO) {
            runCatching { backend.client.postgrest.rpc(name, buildJsonObject(args)); Unit }
                .also { changes.tryEmit(Unit) }
        }

    // ---------------------------------------------------------------- my profile

    suspend fun publishIdentity(publicB64: String) = withContext(Dispatchers.IO) {
        if (profiles[me]?.identityKey == publicB64) return@withContext
        runCatching {
            backend.client.from("profiles").update({ set("identity_key", publicB64) }) { filter { eq("id", me) } }
        }
        fetchProfiles(listOf(me)); _me.value = profiles[me]
    }

    suspend fun setAvatar(bytes: ByteArray) = withContext(Dispatchers.IO) {
        val path = "$me/avatar-${System.currentTimeMillis()}.jpg"
        backend.upload("avatars", path, bytes, "image/jpeg")
        val old = profiles[me]?.avatarPath
        backend.client.from("profiles").update({ set("avatar_path", path) }) { filter { eq("id", me) } }
        old?.let { backend.delete("avatars", it) }
        fetchProfiles(listOf(me)); _me.value = profiles[me]
    }

    suspend fun setDisplayName(name: String) = withContext(Dispatchers.IO) {
        backend.client.from("profiles").update({ set("display_name", name.trim().take(48)) }) { filter { eq("id", me) } }
        fetchProfiles(listOf(me)); _me.value = profiles[me]
    }

    /** Change my @username (unique; letters, digits and _ only, 3–24). */
    suspend fun setUsername(name: String): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            backend.client.from("profiles").update({ set("username", name.trim()) }) { filter { eq("id", me) } }
            fetchProfiles(listOf(me)); _me.value = profiles[me]
            Unit
        }
    }

    /** Make sure a profile is loaded (e.g. opening someone's profile from a moment). */
    /** Fresh "last seen" of a contact from the server (epoch ms), or null. */
    suspend fun fetchLastSeen(uid: String): Long? = withContext(Dispatchers.IO) {
        runCatching {
            val arr = backend.json.parseToJsonElement(backend.client.from("profiles").select(io.github.jan.supabase.postgrest.query.Columns.list("last_seen")) { filter { eq("id", uid) } }.data)
            val v = ((arr as kotlinx.serialization.json.JsonArray).firstOrNull() as? kotlinx.serialization.json.JsonObject)?.get("last_seen") as? kotlinx.serialization.json.JsonPrimitive
            v?.content?.takeIf { it != "null" }?.let { java.time.OffsetDateTime.parse(it).toInstant().toEpochMilli() }
        }.getOrNull()
    }

    suspend fun loadProfile(uid: String): Profile? = withContext(Dispatchers.IO) {
        profiles[uid] ?: runCatching { fetchProfiles(listOf(uid)); profiles[uid] }.getOrNull()
    }

    private var serverSeenCleared = false

    /** Last seen is now shared end-to-end encrypted, per contact: wipe the old plaintext column once. */
    suspend fun clearServerLastSeen() = withContext(Dispatchers.IO) {
        if (serverSeenCleared) return@withContext
        runCatching {
            backend.client.from("profiles").update({ set<String?>("last_seen", null) }) { filter { eq("id", me) } }
            serverSeenCleared = true
        }
    }

    // ---------------------------------------------------------------- conversations

    /** Every accepted contact has exactly one conversation; both phones derive the same id. */
    /** Opening a contact's chat (from People / their profile): recreate it if it was deleted. */
    suspend fun ensureChatWith(uid: String) = withContext(Dispatchers.IO) { profile(uid)?.let { runCatching { ensureChat(it) } } }

    private suspend fun ensureChat(p: Profile) {
        if (vault.isLocked()) return // done on the next refresh after unlock
        val id = dmChatId(me, p.id)
        val hue = (p.id.hashCode() and 0xFFFF) % 360f
        if (!vault.exists(id)) {
            vault.createChat(ChatMeta(id, p.displayName, listOf(p.id), System.currentTimeMillis(), avatarHue = hue, avatarPath = p.avatarPath))
        } else {
            // Compare with the index first: opening the chat (decrypting its whole history) only
            // when the name / photo really changed — this ran for every contact on every start.
            val known = vault.metaIndex.value[id]
            if (known != null && known.title == p.displayName && known.avatarPath == p.avatarPath) return
            val s = vault.chat(id)
            // Opened empty (its screen, right after the chat was deleted): give it its name back.
            val m = s.meta.value ?: run { s.writeMeta(ChatMeta(id, p.displayName, listOf(p.id), System.currentTimeMillis(), avatarHue = hue, avatarPath = p.avatarPath)); return }
            if (m.title != p.displayName || m.avatarPath != p.avatarPath) s.writeMeta(m.copy(title = p.displayName, avatarPath = p.avatarPath))
        }
    }

    private fun loadPins(): Map<String, String> =
        pins.readText()?.let { runCatching { backend.json.decodeFromString(PINS, it) }.getOrNull() }.orEmpty()

    private fun savePins(m: Map<String, String>) = pins.writeText(backend.json.encodeToString(PINS, m))

    private fun <T> decodeList(s: kotlinx.serialization.KSerializer<T>, data: String): List<T> =
        backend.json.decodeFromString(ListSerializer(s), data)

    companion object {
        private val PINS = MapSerializer(String.serializer(), String.serializer())

        fun dmChatId(a: String, b: String): String {
            val (x, y) = listOf(a, b).sorted()
            return MessageDigest.getInstance("SHA-256").digest("zura-dm:$x:$y".encodeToByteArray())
                .joinToString("") { "%02x".format(it) }.take(24)
        }
    }
}
