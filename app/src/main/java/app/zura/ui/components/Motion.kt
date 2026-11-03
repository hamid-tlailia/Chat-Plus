package app.zura.ui.components

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.IntOffset

/**
 * Motion vocabulary. Everything interactive uses springs (velocity-preserving, interruptible —
 * a flick mid-animation never "jumps"), never fixed-duration easing curves.
 */
object ZuraMotion {
    /** UI chrome, sheets, page transitions: quick, no overshoot. */
    val Snappy = spring<Float>(dampingRatio = 0.9f, stiffness = 700f)
    /** Bubbles popping in, reactions: a hint of overshoot reads as "alive". */
    val Bouncy = spring<Float>(dampingRatio = 0.62f, stiffness = 520f)
    /** Press feedback: very stiff so it tracks the finger. */
    val Press = spring<Float>(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = 1600f)
    val SnappyOffset = spring(dampingRatio = 0.9f, stiffness = 700f, visibilityThreshold = IntOffset(1, 1))
}

/** Scales content to 96% while pressed — applied in the draw layer, so zero relayout. */
@Composable
fun Modifier.pressScale(interaction: MutableInteractionSource, pressed: Float = 0.96f): Modifier {
    val isPressed by interaction.collectIsPressedAsState()
    val s by animateFloatAsState(if (isPressed) pressed else 1f, ZuraMotion.Press, label = "press")
    return graphicsLayer { scaleX = s; scaleY = s }
}
