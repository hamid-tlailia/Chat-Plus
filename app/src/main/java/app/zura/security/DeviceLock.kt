package app.zura.security

import android.app.KeyguardManager
import android.content.Context
import android.os.UserManager

/**
 * Can chat keys be used right now? Two different conditions:
 *  - [UserManager.isUserUnlocked]: credential storage unlocked since boot (Direct Boot). Stays
 *    true after the first unlock.
 *  - [KeyguardManager.isDeviceLocked]: the lock screen is up NOW. Chat-content keys are created
 *    with setUnlockedDeviceRequired, so the Keystore refuses them in this state ("device locked").
 * Both must be OK before any ChatStore is opened. Calls never depend on this.
 */
object DeviceLock {
    fun chatKeysUsable(c: Context): Boolean {
        val um = c.getSystemService(UserManager::class.java)
        if (um?.isUserUnlocked == false) return false
        val km = c.getSystemService(KeyguardManager::class.java)
        return km?.isDeviceLocked != true
    }
}
