package app.zura.security

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.zura.screens.TestZuraApp
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowSystemClock
import java.time.Duration

@RunWith(AndroidJUnit4::class)
@Config(sdk = [32], application = TestZuraApp::class)
class AppLockTest {
    private val ctx: Context = ApplicationProvider.getApplicationContext()
    private fun lock() = AppLock(ctx, ctx.getSharedPreferences("lock-test-${System.nanoTime()}", Context.MODE_PRIVATE))

    @Test fun unlockingThenReturningFromThePromptDoesNotRelock() {
        val l = lock(); l.setEnabled(true); l.lockNow()
        assertTrue(l.locked.value)
        // The PIN/fingerprint screen sends us to the background, then we come back unlocked.
        l.onBackground()
        l.legacyResult(true)
        l.onForeground()
        assertFalse("must stay unlocked right after a successful unlock", l.locked.value)
    }

    @Test fun leavingTheAppLocksAgainAfterTheTimeout() {
        val l = lock(); l.setEnabled(true); l.setTimeout(0)
        l.legacyResult(true)
        ShadowSystemClock.advanceBy(Duration.ofSeconds(5)) // past the post-unlock grace period
        l.onBackground()
        ShadowSystemClock.advanceBy(Duration.ofSeconds(1))
        l.onForeground()
        assertTrue(l.locked.value)
    }

    @Test fun respectsALongerTimeout() {
        val l = lock(); l.setEnabled(true); l.setTimeout(60)
        l.legacyResult(true)
        ShadowSystemClock.advanceBy(Duration.ofSeconds(5))
        l.onBackground(); ShadowSystemClock.advanceBy(Duration.ofSeconds(30)); l.onForeground()
        assertFalse(l.locked.value)
        l.onBackground(); ShadowSystemClock.advanceBy(Duration.ofSeconds(61)); l.onForeground()
        assertTrue(l.locked.value)
    }

    @Test fun disablingUnlocks() {
        val l = lock(); l.setEnabled(true); l.lockNow()
        l.setEnabled(false)
        assertFalse(l.locked.value)
    }
}
