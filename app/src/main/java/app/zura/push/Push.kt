package app.zura.push

import android.app.Application
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ProcessLifecycleOwner
import app.zura.BuildConfig
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions
import com.google.firebase.messaging.FirebaseMessaging
import kotlinx.coroutines.tasks.await

/**
 * Firebase Cloud Messaging bootstrap. Initialised by hand from BuildConfig (filled from
 * app/google-services.json at build time) — no Google Services plugin, no auto-init surprises.
 */
object Push {
    @Volatile var enabled = false
        private set

    fun init(app: Application) {
        if (BuildConfig.FIREBASE_APP_ID.isBlank() || BuildConfig.FIREBASE_API_KEY.isBlank()) return
        runCatching {
            if (FirebaseApp.getApps(app).isEmpty()) {
                FirebaseApp.initializeApp(
                    app,
                    FirebaseOptions.Builder()
                        .setApplicationId(BuildConfig.FIREBASE_APP_ID)
                        .setApiKey(BuildConfig.FIREBASE_API_KEY)
                        .setProjectId(BuildConfig.FIREBASE_PROJECT_ID)
                        .setGcmSenderId(BuildConfig.FIREBASE_SENDER_ID)
                        .build(),
                )
            }
            enabled = true
        }
    }

    suspend fun token(): String? = if (!enabled) null else runCatching { FirebaseMessaging.getInstance().token.await() }.getOrNull()

    /** True while any Zura screen is visible. */
    val appVisible: Boolean get() = ProcessLifecycleOwner.get().lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)

    /** The chat currently on screen (no notification for it). Set by ChatScreen. */
    @Volatile var openChatId: String? = null
}
