package app.zura.net

import app.zura.account.InboxRow
import app.zura.core.crypto.E2EE
import app.zura.core.sync.Frame
import app.zura.core.sync.WireJson
import io.github.jan.supabase.postgrest.from
import io.github.jan.supabase.postgrest.query.Order
import io.github.jan.supabase.postgrest.query.filter.FilterOperator
import io.github.jan.supabase.realtime.PostgresAction
import io.github.jan.supabase.realtime.Realtime
import io.github.jan.supabase.realtime.RealtimeChannel
import io.github.jan.supabase.realtime.broadcastFlow
import io.github.jan.supabase.realtime.channel
import io.github.jan.supabase.realtime.postgresChangeFlow
import io.github.jan.supabase.realtime.realtime
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.security.PublicKey
import java.util.Base64

/**
 * Message transport over Supabase. Two lanes, both end-to-end encrypted ([E2EE]):
 *
 *  Durable  (messages, receipts, edits, deletes, reactions, moments)
 *    → INSERT into public.inbox (RLS: only to accepted contacts)
 *    → recipient gets it instantly via Realtime postgres_changes, or from the backlog on reconnect
 *    → recipient DELETEs the row once it is safely in the local encrypted log  (server keeps nothing)
 *
 *  Ephemeral (typing, presence, call signalling)
 *    → Realtime broadcast to the private topic "u:<recipient>" — never stored anywhere.
 *
 * The server sees only: who sent an opaque blob to whom, and when.
 */
class SupabaseTransport(
    private val backend: Backend,
    val me: String,
    private val identity: Identity,
    private val peerKey: suspend (String) -> PublicKey?,
    private val scope: CoroutineScope,
) {
    /** Set by the account session; called for every authenticated, decrypted frame. */
    var onFrame: suspend (from: String, frame: Frame) -> Unit = { _, _ -> }

    private val _online = MutableStateFlow(false)
    val online: StateFlow<Boolean> = _online.asStateFlow()

    @androidx.annotation.VisibleForTesting
    internal fun setOnlineForTest(on: Boolean) { _online.value = on }

    private var job: Job? = null
    private var inboxChannel: RealtimeChannel? = null
    private var userChannel: RealtimeChannel? = null
    private val seen = LinkedHashSet<Long>()
    private val drainLock = Mutex()

    /** The previous stop()'s disconnect: a new start waits for it (it used to run AFTER a quick restart and cut the new connection). */
    private var cleanup: Job? = null

    fun start() {
        if (job?.isActive == true) return
        val previous = cleanup
        job = scope.launch {
            previous?.join()
            val rt = backend.client.realtime
            // Socket health first — the banner reflects the connection itself, not the (slower)
            // channel joins below; every (re)connect drains anything that arrived while away.
            launch {
                rt.status.collect { st ->
                    val up = st == Realtime.Status.CONNECTED
                    if (up && !_online.value) launch { drainBacklog() }
                    _online.value = up
                }
            }
            runCatching { rt.connect() }
            val inbox = backend.client.channel("inbox-$me")
            val inserts = inbox.postgresChangeFlow<PostgresAction.Insert>(schema = "public") {
                table = "inbox"
                filter("recipient_id", FilterOperator.EQ, me)
            }
            launch {
                inserts.collect { a ->
                    runCatching { backend.json.decodeFromJsonElement(InboxRow.serializer(), a.record) }.getOrNull()?.let { process(listOf(it)) }
                }
            }
            val user = backend.client.channel("u:$me") { isPrivate = true }
            launch { user.broadcastFlow<JsonObject>(event = EVENT).collect { onSignal(it) } }
            inboxChannel = inbox; userChannel = user
            // Never hang on a join (an expired token at a cold start, a flaky network): the backlog
            // pull below covers the gap, and the library retries the join itself.
            runCatching { kotlinx.coroutines.withTimeoutOrNull(10_000) { inbox.subscribe(blockUntilSubscribed = true) } }
            runCatching { kotlinx.coroutines.withTimeoutOrNull(10_000) { user.subscribe(blockUntilSubscribed = true) } }

            // Safety net: the server's change stream can take a few seconds to warm up after idle,
            // and a message inserted in that window would otherwise wait for the next reconnect.
            // A cheap backlog pull shortly after connecting and every 15 s closes that gap.
            launch {
                delay(3_000)
                while (true) {
                    if (_online.value) runCatching { drainBacklog() }
                    delay(15_000)
                }
            }
        }
    }

    /** The phone got a network back: reconnect now instead of waiting for the library's back-off. */
    fun kick() {
        if (job?.isActive != true || _online.value) return
        val rt = backend.client.realtime
        if (rt.status.value != Realtime.Status.DISCONNECTED) return // already (re)connecting
        scope.launch { runCatching { rt.connect() } }
    }

    fun stop() {
        job?.cancel(); job = null
        cleanup = scope.launch {
            runCatching { inboxChannel?.unsubscribe() }
            runCatching { userChannel?.unsubscribe() }
            runCatching { backend.client.realtime.removeAllChannels() }
            runCatching { backend.client.realtime.disconnect() }
            inboxChannel = null; userChannel = null
            _online.value = false
        }
    }

    // ---------------------------------------------------------------- sending

    /** Durable, E2EE. Returns true once the server has accepted (stored) the envelope. */
    suspend fun send(to: String, frame: Frame): Boolean {
        val key = peerKey(to) ?: run { app.zura.DiagLog.record("send ${frame.javaClass.simpleName}", detail = "no key for ${to.take(8)}"); return false }
        val payload = runCatching { seal(key, frame) }.getOrElse { app.zura.DiagLog.record("seal", it); return false }
        return runCatching {
            backend.insertRow("inbox", buildJsonObject { put("recipient_id", to); put("payload", payload) })
        }.onFailure { app.zura.DiagLog.record("inbox insert → ${to.take(8)}", it) }.isSuccess
    }

    /** Ephemeral, E2EE, best-effort (typing, presence, call signalling). */
    suspend fun signal(to: String, frame: Frame): Boolean {
        val key = peerKey(to) ?: return false
        return runCatching { backend.broadcast(to, EVENT, buildJsonObject { put("f", me); put("p", seal(key, frame)) }) }
            .onFailure { if (it !is kotlinx.coroutines.CancellationException) app.zura.DiagLog.record("broadcast ${frame.javaClass.simpleName}", it) }
            .getOrDefault(false) == true
    }

    /** Several live frames in one request, in order (walkie-talkie audio). */
    suspend fun signalAll(to: String, frames: List<Frame>): Boolean {
        if (frames.isEmpty()) return true
        val key = peerKey(to) ?: return false
        return runCatching { backend.broadcastAll(to, EVENT, frames.map { f -> buildJsonObject { put("f", me); put("p", seal(key, f)) } }) }
            .onFailure { app.zura.DiagLog.record("broadcast walkie x${frames.size}", it) }
            .getOrDefault(false) == true
    }

    /** E2EE-seal a frame for [to] (used for FCM call invites). */
    suspend fun sealFor(to: String, frame: Frame): String? = peerKey(to)?.let { seal(it, frame) }

    /** Open an E2EE payload from [from] that arrived outside the transport (FCM). */
    suspend fun openFrom(from: String, payload: String): Frame? = open(from, payload)

    /** Pull the encrypted inbox over plain HTTPS (used when a push wakes us without a socket). */
    suspend fun syncInbox() = drainBacklog()

    // ---------------------------------------------------------------- receiving

    private suspend fun drainBacklog() = drainLock.withLock {
        backend.ensureFresh()
        while (true) {
            val rows = runCatching {
                backend.client.from("inbox").select {
                    filter { eq("recipient_id", me) }
                    order("id", Order.ASCENDING)
                    limit(200)
                }.data.let { backend.json.decodeFromString(kotlinx.serialization.builtins.ListSerializer(InboxRow.serializer()), it) }
            }.getOrNull() ?: return
            if (rows.isEmpty()) return
            process(rows)
            if (rows.size < 200) return
        }
    }

    private suspend fun process(rows: List<InboxRow>) {
        val done = ArrayList<Long>(rows.size)
        for (r in rows) {
            synchronized(seen) {
                if (!seen.add(r.id)) { done += r.id; return@synchronized null } else Unit
            } ?: continue
            if (seen.size > 2048) synchronized(seen) { repeat(1024) { seen.remove(seen.first()) } }
            // Sender's key not fetchable right now (offline) → keep the row and retry later.
            if (peerKey(r.senderId) == null) { synchronized(seen) { seen.remove(r.id) }; continue }
            val frame = open(r.senderId, r.payload)
            if (frame != null && runCatching { onFrame(r.senderId, frame) }.isFailure) {
                // Not stored locally (e.g. the phone is locked, so chat keys refuse to work):
                // leave it on the server, it is processed on the next sync after unlock.
                synchronized(seen) { seen.remove(r.id) }
                continue
            }
            // Delivered — or permanently undecryptable (forged/corrupt): either way it must not linger server-side.
            done += r.id
        }
        if (done.isNotEmpty()) runCatching {
            backend.client.from("inbox").delete { filter { isIn("id", done) } }
        }
    }

    private suspend fun onSignal(o: JsonObject) {
        val from = o["f"]?.jsonPrimitive?.content ?: return
        val p = o["p"]?.jsonPrimitive?.content ?: return
        // Authenticated decryption: a forged "f" fails because the static-static ECDH won't match.
        open(from, p)?.let { runCatching { onFrame(from, it) } }
    }

    // ---------------------------------------------------------------- crypto

    private fun seal(key: PublicKey, f: Frame): String =
        B64.encodeToString(E2EE.seal(identity.keyPair, key, WireJson.encodeToString(Frame.serializer(), f).encodeToByteArray()))

    private suspend fun open(from: String, payload: String): Frame? {
        val key = peerKey(from) ?: return null
        return runCatching {
            WireJson.decodeFromString(Frame.serializer(), E2EE.open(identity.keyPair, key, B64D.decode(payload)).decodeToString())
        }.getOrNull()
    }

    /** Durable work that must survive a short offline period retries with backoff. */
    suspend fun sendWithRetry(to: String, frame: Frame, attempts: Int = 5): Boolean {
        var wait = 800L
        repeat(attempts) {
            if (send(to, frame)) return true
            delay(wait); wait = (wait * 2).coerceAtMost(15_000)
        }
        return false
    }

    private companion object {
        const val EVENT = "z"
        val B64: Base64.Encoder = Base64.getEncoder()
        val B64D: Base64.Decoder = Base64.getDecoder()
    }
}
