package app.zura.account

import android.app.Application
import app.zura.call.CallManager
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
    val moments = MomentsRepository(vault, chats, transport, backend, uid, { contactIds() }, scope)
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
                else -> chats.handle(from, f)
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
