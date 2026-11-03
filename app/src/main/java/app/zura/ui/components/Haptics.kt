package app.zura.ui.components

import android.os.Build
import android.view.HapticFeedbackConstants
import android.view.View
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalView

/**
 * Haptics routed through View.performHapticFeedback: uses the device's tuned waveforms (Pixel /
 * Samsung "crisp" primitives), respects the user's system haptics setting, needs no VIBRATE call.
 */
@Stable
class Haptics(private val view: View) {
    private fun fire(constant: Int) { view.performHapticFeedback(constant) }

    /** Light tick: swipe-to-reply threshold, reaction hover, segmented control. */
    fun tick() = fire(if (Build.VERSION.SDK_INT >= 27) HapticFeedbackConstants.KEYBOARD_RELEASE else HapticFeedbackConstants.CLOCK_TICK)
    /** Long-press opening the action menu. */
    fun heavy() = fire(HapticFeedbackConstants.LONG_PRESS)
    /** Message sent, recording locked. */
    fun confirm() = fire(if (Build.VERSION.SDK_INT >= 30) HapticFeedbackConstants.CONFIRM else HapticFeedbackConstants.VIRTUAL_KEY)
    /** Recording cancelled, destructive action. */
    fun reject() = fire(if (Build.VERSION.SDK_INT >= 30) HapticFeedbackConstants.REJECT else HapticFeedbackConstants.LONG_PRESS)
    /** Recording start. */
    fun start() = fire(if (Build.VERSION.SDK_INT >= 34) HapticFeedbackConstants.DRAG_START else HapticFeedbackConstants.VIRTUAL_KEY)
}

@Composable
fun rememberHaptics(): Haptics {
    val view = LocalView.current
    return remember(view) { Haptics(view) }
}
