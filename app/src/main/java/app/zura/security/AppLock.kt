package app.zura.security

import android.app.Activity
import android.app.KeyguardManager
import android.content.Context
import android.content.SharedPreferences
import android.hardware.biometrics.BiometricManager
import android.hardware.biometrics.BiometricPrompt
import android.os.Build
import android.os.CancellationSignal
import android.os.SystemClock
import app.zura.R
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * App lock: fingerprint / face or the phone's own PIN / pattern, via the platform BiometricPrompt
 * (no androidx.biometric / Fragment dependency).
 *
 * The subtle part: showing the system prompt — and especially the PIN/pattern screen, which is a
 * separate system Activity — sends Zura to the background for a moment. Without care, returning
 * from a SUCCESSFUL unlock looks like "came back from background" and re-locks instantly, so the
 * fingerprint seems to do nothing. We ignore background/foreground transitions while a prompt is
 * up and for a short grace period after unlocking.
 */
class AppLock(private val context: Context, private val prefs: SharedPreferences) {

    private val _enabled = MutableStateFlow(prefs.getBoolean(K_ENABLED, false))
    val enabled: StateFlow<Boolean> = _enabled.asStateFlow()

    private val _timeout = MutableStateFlow(prefs.getInt(K_TIMEOUT, 0))
    /** Seconds in the background before locking again: 0 = immediately, 60, 300, 1800. */
    val timeoutSec: StateFlow<Int> = _timeout.asStateFlow()

    private val _locked = MutableStateFlow(_enabled.value)
    val locked: StateFlow<Boolean> = _locked.asStateFlow()

    /** Last prompt error to show on the lock screen (e.g. "Too many attempts"), null when none. */
    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    @Volatile private var authenticating = false
    private var backgroundAt = 0L
    private var unlockedAt = 0L

    fun deviceSecure(): Boolean = context.getSystemService(KeyguardManager::class.java).isDeviceSecure

    fun setEnabled(on: Boolean) {
        _enabled.value = on
        prefs.edit().putBoolean(K_ENABLED, on).apply()
        if (!on) _locked.value = false
    }

    fun setTimeout(sec: Int) {
        _timeout.value = sec
        prefs.edit().putInt(K_TIMEOUT, sec).apply()
    }

    fun onBackground() {
        if (authenticating) return // the prompt itself took us to the background
        backgroundAt = SystemClock.elapsedRealtime()
    }

    fun onForeground() {
        if (!_enabled.value || authenticating || backgroundAt == 0L) return
        val now = SystemClock.elapsedRealtime()
        if (now - unlockedAt < GRACE_MS) return
        if (now - backgroundAt >= _timeout.value * 1000L) _locked.value = true
        backgroundAt = 0L
    }

    fun lockNow() { if (_enabled.value) _locked.value = true }

    /**
     * Shows the system prompt. API 28+: BiometricPrompt (fingerprint/face, with PIN/pattern fallback);
     * API 26–27: the keyguard confirm screen via [legacyLauncher].
     */
    fun authenticate(activity: Activity, legacyLauncher: (android.content.Intent) -> Unit, onResult: (Boolean) -> Unit) {
        if (authenticating) return
        if (!deviceSecure()) { unlock(); onResult(true); return }
        _error.value = null
        authenticating = true
        val title = activity.getString(R.string.unlock_zura)
        try {
            if (Build.VERSION.SDK_INT >= 28) {
                val b = BiometricPrompt.Builder(activity).setTitle(title).setSubtitle(activity.getString(R.string.unlock_subtitle))
                if (Build.VERSION.SDK_INT >= 30) {
                    // WEAK includes STRONG: accepts every enrolled fingerprint and most face unlocks,
                    // plus the phone PIN/pattern as fallback. No crypto is bound to this check.
                    b.setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_WEAK or BiometricManager.Authenticators.DEVICE_CREDENTIAL)
                } else {
                    @Suppress("DEPRECATION") b.setDeviceCredentialAllowed(true)
                }
                b.build().authenticate(CancellationSignal(), activity.mainExecutor, object : BiometricPrompt.AuthenticationCallback() {
                    override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                        authenticating = false; unlock(); onResult(true)
                    }
                    override fun onAuthenticationError(code: Int, msg: CharSequence) {
                        authenticating = false
                        // User cancelled → no message; real errors (lockout, no hardware…) are shown.
                        if (code != BiometricPrompt.BIOMETRIC_ERROR_USER_CANCELED && code != BiometricPrompt.BIOMETRIC_ERROR_CANCELED) _error.value = msg.toString()
                        onResult(false)
                    }
                })
            } else {
                val km = activity.getSystemService(KeyguardManager::class.java)
                @Suppress("DEPRECATION")
                val intent = km.createConfirmDeviceCredentialIntent(title, null)
                if (intent == null) { authenticating = false; unlock(); onResult(true) } else legacyLauncher(intent)
            }
        } catch (e: Exception) {
            authenticating = false
            _error.value = e.message
            onResult(false)
        }
    }

    /** Result of the API 26–27 keyguard screen. */
    fun legacyResult(ok: Boolean) {
        authenticating = false
        if (ok) unlock()
    }

    private fun unlock() {
        _locked.value = false
        unlockedAt = SystemClock.elapsedRealtime()
        backgroundAt = 0L
    }

    private companion object {
        const val K_ENABLED = "app_lock"
        const val K_TIMEOUT = "app_lock_timeout"
        const val GRACE_MS = 2_000L
    }
}
