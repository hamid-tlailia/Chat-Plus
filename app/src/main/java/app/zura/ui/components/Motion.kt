package app.zura.ui.components

import androidx.compose.animation.ContentTransform
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
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

/**
 * Page motion shared by every screen (main navigation and the sign-in flow):
 *  • English (LTR): a new page enters from the RIGHT edge; going back it leaves to the right.
 *  • Arabic (RTL): mirrored — enters from the LEFT edge; going back it leaves to the left.
 * The page underneath drifts a fifth of the way the opposite direction and dims slightly.
 * Offsets are absolute screen pixels, so direction is chosen explicitly from [rtl].
 */
fun pageTransition(forward: Boolean, rtl: Boolean): ContentTransform {
    val dir = if (rtl) -1 else 1
    // Stiffer = the next page settles sooner (~0.3 s instead of ~0.45 s): the app feels quicker.
    val slide = spring(dampingRatio = 1f, stiffness = 700f, visibilityThreshold = IntOffset(1, 1))
    val fade = spring<Float>(stiffness = 700f)
    return if (forward) {
        (slideInHorizontally(slide) { it * dir } togetherWith
            (slideOutHorizontally(slide) { -it / 5 * dir } + fadeOut(fade, targetAlpha = 0.6f)))
            .apply { targetContentZIndex = 1f }
    } else {
        ((slideInHorizontally(slide) { -it / 5 * dir } + fadeIn(fade, initialAlpha = 0.6f)) togetherWith
            slideOutHorizontally(slide) { it * dir })
            .apply { targetContentZIndex = 0f }
    }
}
