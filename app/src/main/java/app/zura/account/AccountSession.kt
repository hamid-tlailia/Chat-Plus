package app.zura.account

import android.app.Application
import app.zura.BuildConfig
import app.zura.DiagLog
import app.zura.call.CallManager
import app.zura.core.crypto.E2EE
import app.zura.core.model.ChatMeta
import app.zura.push.MessageNotifier
import app.zura.push.Push
import app.zura.core.crypto.SecureFile
import app.zura.core.storage.StorageJanitor
import app.zura.core.storage.VaultStore
import app.zura.core.storage.ZuraPaths
import app.zura.core.sync.Frame
import app.zura.data.ChatRepository
import app.zura.data.ContactsRepository
import app.zura.data.MomentsRepository
import app.zura.media.ImageCompressor
import app.zura.media.MediaExporter
import app.zura.media.MediaPlayback
import app.zura.media.VaultImages
import app.zura.media.VideoCompressor
import app.zura.media.VoiceRecorder
import app.zura.net.Backend
import app.zura.net.Identity
import app.zura.net.SupabaseTransport
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.postgrest.postgrest
import io.github.jan.supabase.postgrest.rpc
import kotlinx.serialization.json.put
import java.io.File

/**
 * Everything that belongs to ONE signed-in account. Each account has its own directory, its own
 * namespace of hardware-backed keys and its own E2EE identity, so accounts on the same phone can
 * never read each other's data. Created on sign-in, closed on sign-out.
 */
class AccountSession(
    val app: Application,
    val uid: String,
    val email: String,
    private val backend: Backend,
    store: AccountStore,
) {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val keys = store.keysFor(uid)
    private val dir = store.dir(uid)
    private val short = uid.replace("-", "").take(12)

    val vault = VaultStore(ZuraPaths(dir, File(app.noBackupFilesDir, short), File(app.cacheDir, short)), keys)
    val identity = Identity(dir, keys)
    val contacts = ContactsRepository(backend, uid, vault, SecureFile(File(dir, "pins.zs"), keys, "zpins"), scope)
    val transport = SupabaseTransport(backend, uid, identity, contacts::peerKey, scope)
    val chats = ChatRepository(vault, transport, backend, uid, scope)
    val moments = MomentsRepository(vault, chats, transport, backend, uid, { contactIds() }, scope, nameOf = { contacts.profile(it)?.displayName })
    val calls by lazy { CallManager(app, transport, contacts, chats, backend, uid, scope) }

    val images by lazy { VaultImages(vault) }
    val imageCompressor by lazy { ImageCompressor(app, vault) }
    val videoCompressor by lazy { VideoCompressor(app, vault) }
    val recorder by lazy { VoiceRecorder(app, vault, scope) }
    val playback by lazy { MediaPlayback(app, vault, scope) }
    val exporter by lazy { MediaExporter(app, vault) }
    private val janitor by lazy { StorageJanitor(vault, keys) }

    val notifier by lazy { MessageNotifier(app) }
    private var pushToken: String? = null

    private var foreground = false

    init {
        transport.onFrame = { from, f ->
            when (f) {
                is Frame.MomentPost -> moments.handle(from, f)
                is Frame.CallOffer, is Frame.CallAnswer, is Frame.CallIce, is Frame.CallEnd,
                is Frame.CallRinging, is Frame.CallRestartRequest, is Frame.CallMedia -> calls.handle(from, f)
                else -> try {
                    chats.handle(from, f)
                } catch (e: Exception) {
                    // Couldn't store it (phone locked → chat keys unusable). Still tell the user.
                    if (f is Frame.Msg) notifier.showPending(contacts.profile(from)?.displayName)
                    throw e
                }
            }
        }
    }

    /** Register this device for wake-up pushes for this account (replaces any previous owner of the token). */
    suspend fun registerPush(token: String? = null) {
        val t = token ?: Push.token() ?: return
        pushToken = t
        runCatching {
            backend.client.postgrest.rpc("register_device", kotlinx.serialization.json.buildJsonObject { put("p_token", t) })
        }
    }

    /** Stop pushes to this device for this account (on sign-out). */
    suspend fun unregisterPush() {
        val t = pushToken ?: Push.token() ?: return
        runCatching {
            backend.client.postgrest.rpc("unregister_device", kotlinx.serialization.json.buildJsonObject { put("p_token", t) })
        }
    }

    /**
     * On-device health check: everything a message needs to reach a contact, step by step.
     * The report has no content and no keys — safe to copy and send to the developer.
     */
    suspend fun selfTest(): String = kotlinx.coroutines.withContext(Dispatchers.IO) {
        val out = StringBuilder()
        suspend fun step(name: String, block: suspend () -> String?) {
            val r = runCatching { block() }
            out.append(if (r.isSuccess && r.getOrNull() == null) "✓ " else "✗ ").append(name)
            r.exceptionOrNull()?.let { out.append(" — ").append(it.javaClass.simpleName).append(": ").append(it.message?.take(200)) }
            r.getOrNull()?.let { out.append(" — ").append(it) }
            out.append('\n')
        }
        out.append("Zura ").append(BuildConfig.VERSION_NAME).append(" · Android ").append(android.os.Build.VERSION.SDK_INT)
            .append(" · ").append(android.os.Build.MANUFACTURER).append(' ').append(android.os.Build.MODEL).append("\n\n")
        step("Signed in") { if (backend.client.auth.currentSessionOrNull()?.user?.id == uid) null else "session missing or for another user" }
        step("Profile & identity key published") {
            contacts.refresh()
            val mine = contacts.myProfile.value?.identityKey
            when {
                mine == null -> { contacts.publishIdentity(identity.publicB64); "was missing — republished" }
                mine != identity.publicB64 -> { contacts.publishIdentity(identity.publicB64); "did not match this phone — republished" }
                else -> null
            }
        }
        step("End-to-end encryption on this phone") {
            val b = "zura-selftest".encodeToByteArray()
            val back = E2EE.open(identity.keyPair, identity.keyPair.public,
                E2EE.seal(identity.keyPair, identity.keyPair.public, b))
            if (back.contentEquals(b)) null else "mismatch"
        }
        step("Secure storage (hardware keys)") {
            val probe = "zdiag" + System.nanoTime().toString().takeLast(6)
            vault.chat(probe).writeMeta(ChatMeta(probe, probe, emptyList(), System.currentTimeMillis()))
            vault.deleteChat(probe); null
        }
        val cs = contacts.contacts.value
        step("Contacts") { "${cs.size} contact(s)" }
        for (c in cs) {
            val n = c.profile.username
            step("@$n: encryption key") { if (contacts.peerKey(c.profile.id) != null) null else "not available (hasKey=${c.profile.identityKey != null})" }
            step("@$n: send test packet") { if (transport.send(c.profile.id, Frame.Ping)) null else "refused — see log below" }
            step("@$n: conversation on this phone") {
                if (vault.exists(ContactsRepository.dmChatId(uid, c.profile.id))) null else "missing"
            }
        }
        step("Live connection (realtime)") { if (transport.online.value) null else "offline — messages arrive on sync/push only" }
        step("Push (wake when closed)") { if (Push.token() != null) null else "no Firebase token" }
        val log = DiagLog.read().lines().takeLast(20).joinToString("\n")
        if (log.isNotBlank()) out.append("\nRecent issues:\n").append(log)
        out.toString()
    }

    fun contactIds(): List<String> = contacts.contacts.value.filterNot { it.blocked }.map { it.profile.id }

    /** First run after sign-in: publish E2EE identity, start social graph sync. */
    fun bootstrap(notesTitle: String) {
        scope.launch {
            runCatching { contacts.publishIdentity(identity.publicB64) }
            chats.ensureNotesChat(notesTitle)
            registerPush()
        }
        chats.onIncoming = { meta, m -> notifier.show(meta, m) }
        contacts.start()
        scope.launch(Dispatchers.IO) {
            kotlinx.coroutines.delay(4_000)
            runCatching { janitor.sweep() }
            runCatching { moments.expire() }
        }
    }

    fun onForeground() {
        if (foreground) return
        foreground = true
        transport.start()
        chats.startPresence { contactIds() }
        scope.launch { contacts.touchLastSeen() }
    }

    fun onBackground() {
        if (!foreground || calls.ui.value.inCall) return
        foreground = false
        chats.stopPresence(contactIds())
        scope.launch { contacts.touchLastSeen() }
        transport.stop()
        playback.release()
    }

    fun close() {
        runCatching { if (calls.ui.value.inCall) calls.hangup() }
        onBackground()
        transport.stop()
        contacts.stop()
        playback.release()
        scope.cancel()
    }
}
