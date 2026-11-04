package app.zura

import android.app.Application
import android.content.Context
import app.zura.account.AccountManager
import app.zura.account.AccountSession
import app.zura.account.AccountStore
import app.zura.account.AvatarCache
import app.zura.core.crypto.AndroidKeystoreWrapper
import app.zura.core.crypto.KeyWrapper
import app.zura.data.SettingsStore
import app.zura.data.Sounds
import app.zura.net.Backend
import app.zura.security.AppLock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.StateFlow
import java.io.File

/**
 * Hand-written DI graph (no Hilt/Dagger reflection or codegen at startup). Device-level services
 * live here; everything belonging to the signed-in account lives in [AccountSession].
 */
class AppGraph(val app: Application) {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default + kotlinx.coroutines.CoroutineExceptionHandler { _, t -> DiagLog.record("background task", t) })

    val settings by lazy { SettingsStore(app.getSharedPreferences("zura", Context.MODE_PRIVATE)) }
    /** Notification tones, ringtones and vibration (global + per chat / person). */
    val sounds by lazy { Sounds(app.getSharedPreferences("zura_sounds", Context.MODE_PRIVATE)) }
    val keys: KeyWrapper by lazy { keyOverride ?: AndroidKeystoreWrapper() }
    val backend by lazy { Backend() }
    val accountStore by lazy { AccountStore(File(app.filesDir, "zura"), keys) }
    val accounts by lazy { AccountManager(app, backend, accountStore, scope) }
    val appLock by lazy { AppLock(app, app.getSharedPreferences("zura_lock", Context.MODE_PRIVATE)) }
    val avatars by lazy { AvatarCache(backend, File(app.cacheDir, "av"), keys) }

    val session: StateFlow<AccountSession?> get() = accounts.session

    /** The signed-in account. Only called from screens that are shown while signed in. */
    val s: AccountSession get() = session.value ?: error("not signed in")

    // Shortcuts kept for the chat/media UI.
    val chats get() = s.chats
    val vault get() = s.vault
    val moments get() = s.moments
    val images get() = s.images
    val imageCompressor get() = s.imageCompressor
    val videoCompressor get() = s.videoCompressor
    val recorder get() = s.recorder
    val playback get() = s.playback
    val exporter get() = s.exporter

    companion object {
        /** Test hook: JVM screenshot/unit tests have no Android Keystore. */
        @androidx.annotation.VisibleForTesting
        var keyOverride: KeyWrapper? = null
    }
}
