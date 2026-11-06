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

    /**
     * What Android / the phone maker currently allows us in the background — the usual reason a
     * high-priority push arrives hours late: the app was put in the "restricted" standby bucket
     * (rarely used / Samsung "deep sleeping apps") or its background use was restricted.
     */
    fun backgroundState(c: android.content.Context): String {
        val bucket = if (android.os.Build.VERSION.SDK_INT >= 28) runCatching {
            when (val b = c.getSystemService(android.app.usage.UsageStatsManager::class.java).appStandbyBucket) {
                android.app.usage.UsageStatsManager.STANDBY_BUCKET_ACTIVE -> "active"
                android.app.usage.UsageStatsManager.STANDBY_BUCKET_WORKING_SET -> "working"
                android.app.usage.UsageStatsManager.STANDBY_BUCKET_FREQUENT -> "frequent"
                android.app.usage.UsageStatsManager.STANDBY_BUCKET_RARE -> "RARE"
                45 -> "RESTRICTED" // STANDBY_BUCKET_RESTRICTED (API 30)
                else -> b.toString()
            }
        }.getOrDefault("?") else "n/a"
        val restricted = android.os.Build.VERSION.SDK_INT >= 28 &&
            runCatching { c.getSystemService(android.app.ActivityManager::class.java).isBackgroundRestricted }.getOrDefault(false)
        val unoptimized = runCatching { c.getSystemService(android.os.PowerManager::class.java).isIgnoringBatteryOptimizations(c.packageName) }.getOrDefault(false)
        return ", bucket=$bucket" + (if (restricted) ", BACKGROUND RESTRICTED" else "") + (if (unoptimized) ", battery=unrestricted" else ", battery=optimized")
    }

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
