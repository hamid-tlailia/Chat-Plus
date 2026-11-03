package app.zura

import android.app.Application
import android.content.Context
import app.zura.core.crypto.AndroidKeystoreWrapper
import app.zura.core.crypto.KeyWrapper
import app.zura.core.storage.StorageJanitor
import app.zura.core.storage.VaultStore
import app.zura.core.storage.ZuraPaths
import app.zura.core.sync.BlobClient
import app.zura.core.sync.Frame
import app.zura.core.sync.RelayClient
import app.zura.data.ChatRepository
import app.zura.data.MomentsRepository
import app.zura.data.SettingsStore
import app.zura.media.ImageCompressor
import app.zura.media.MediaExporter
import app.zura.media.MediaPlayback
import app.zura.media.VaultImages
import app.zura.media.VideoCompressor
import app.zura.media.VoiceRecorder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/**
 * Hand-written DI graph. No Hilt/Dagger: zero annotation processing, zero generated factories,
 * zero reflection at startup. Every dependency is `lazy`, so cold start constructs only what the
 * first frame needs (settings + chat list); media, playback and Keystore spin up on first use.
 */
class AppGraph(val app: Application) {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val settings by lazy { SettingsStore(app.getSharedPreferences("zura", Context.MODE_PRIVATE)) }
    val keys: KeyWrapper by lazy { keyOverride ?: AndroidKeystoreWrapper() }
    val vault by lazy { VaultStore(ZuraPaths(app.filesDir, app.noBackupFilesDir, app.cacheDir), keys) }
    val relay by lazy {
        RelayClient(BuildConfig.RELAY_URL, scope) { Frame.Hello(settings.userId, token = "", since = settings.lastSeq) }
    }
    val blobs by lazy { BlobClient(BuildConfig.BLOB_URL) }
    val chats by lazy { ChatRepository(vault, relay, blobs, settings.userId, scope) }
    val moments by lazy { MomentsRepository(vault, chats, relay, blobs, settings.userId, scope) }

    val images by lazy { VaultImages(vault) }
    val imageCompressor by lazy { ImageCompressor(app, vault) }
    val videoCompressor by lazy { VideoCompressor(app, vault) }
    val recorder by lazy { VoiceRecorder(app, vault, scope) }
    val playback by lazy { MediaPlayback(app, vault, scope) }
    val exporter by lazy { MediaExporter(app, vault) }
    val janitor by lazy { StorageJanitor(vault, keys) }

    companion object {
        /** Test hook: JVM screenshot/unit tests have no Android Keystore. */
        @androidx.annotation.VisibleForTesting
        var keyOverride: KeyWrapper? = null
    }
}
