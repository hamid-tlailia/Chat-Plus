package app.zura.security

import android.app.Activity
import android.app.KeyguardManager
import android.content.Context
import android.content.SharedPreferences
import android.hardware.biometrics.BiometricManager
import android.hardware.biometrics.BiometricPrompt
import android.os.Build
import android.os.CancellationSignal
import app.zura.R
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * App lock: fingerprint / face (Class 3 "strong" biometrics) or the phone's own PIN / pattern.
 * Uses the platform BiometricPrompt directly — no androidx.biometric / Fragment dependency.
 *
 * While locked, no chat content is composed at all (nothing to leak through the UI, accessibility
 * services or the recents thumbnail). Incoming calls still show above the lock, like WhatsApp.
 */
class AppLock(private val context: Context, private val prefs: SharedPreferences) {

    private val _enabled = MutableStateFlow(prefs.getBoolean(K_ENABLED, false))
    val enabled: StateFlow<Boolean> = _enabled.asStateFlow()

    private val _timeout = MutableStateFlow(prefs.getInt(K_TIMEOUT, 0))
    /** Seconds in the background before locking again: 0 = immediately, 60, 300, 1800. */
    val timeoutSec: StateFlow<Int> = _timeout.asStateFlow()

    private val _locked = MutableStateFlow(_enabled.value)
    val locked: StateFlow<Boolean> = _locked.asStateFlow()

    private var backgroundAt = 0L

    /** A lock only makes sense if the phone itself is secured (PIN/pattern/password). */
    fun deviceSecure(): Boolean = context.getSystemService(KeyguardManager::class.java).isDeviceSecure

    fun biometricsAvailable(): Boolean = Build.VERSION.SDK_INT >= 30 &&
        context.getSystemService(BiometricManager::class.java)
            .canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_STRONG) == BiometricManager.BIOMETRIC_SUCCESS

    fun setEnabled(on: Boolean) {
        _enabled.value = on
        prefs.edit().putBoolean(K_ENABLED, on).apply()
        if (!on) _locked.value = false
    }

    fun setTimeout(sec: Int) {
        _timeout.value = sec
        prefs.edit().putInt(K_TIMEOUT, sec).apply()
    }

    fun onBackground() { backgroundAt = System.currentTimeMillis() }

    fun onForeground() {
        if (_enabled.value && backgroundAt > 0 && System.currentTimeMillis() - backgroundAt >= _timeout.value * 1000L) _locked.value = true
    }

    fun lockNow() { if (_enabled.value) _locked.value = true }

    /**
     * Shows the system prompt. API 28+: BiometricPrompt (with PIN/pattern fallback);
     * API 26–27: the keyguard confirm screen via [legacyLauncher].
     */
    fun authenticate(activity: Activity, legacyLauncher: (android.content.Intent) -> Unit, onResult: (Boolean) -> Unit) {
        if (!deviceSecure()) { _locked.value = false; onResult(true); return }
        val title = activity.getString(R.string.unlock_zura)
        if (Build.VERSION.SDK_INT >= 28) {
            val b = BiometricPrompt.Builder(activity).setTitle(title).setSubtitle(activity.getString(R.string.unlock_subtitle))
            if (Build.VERSION.SDK_INT >= 30) {
                b.setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_STRONG or BiometricManager.Authenticators.DEVICE_CREDENTIAL)
            } else {
                @Suppress("DEPRECATION") b.setDeviceCredentialAllowed(true)
            }
            b.build().authenticate(CancellationSignal(), activity.mainExecutor, object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) { _locked.value = false; onResult(true) }
                override fun onAuthenticationError(code: Int, msg: CharSequence) = onResult(false)
            })
        } else {
            val km = activity.getSystemService(KeyguardManager::class.java)
            @Suppress("DEPRECATION")
            km.createConfirmDeviceCredentialIntent(title, null)?.let(legacyLauncher) ?: run { _locked.value = false; onResult(true) }
        }
    }

    /** Result of the API 26–27 keyguard screen. */
    fun legacyResult(ok: Boolean) { if (ok) _locked.value = false }

    private companion object {
        const val K_ENABLED = "app_lock"
        const val K_TIMEOUT = "app_lock_timeout"
    }
}
