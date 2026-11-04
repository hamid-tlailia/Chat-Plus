package app.zura.ui.components

import androidx.compose.foundation.background
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.HazeStyle
import dev.chrisbanes.haze.HazeTint
import dev.chrisbanes.haze.hazeEffect
import androidx.compose.ui.platform.LocalContext
import app.zura.account.rememberAvatar
import app.zura.graph
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.zura.ui.theme.Zura

/** The scrolling content that glass surfaces on this screen blur (set by the screen). */
val LocalHaze = androidx.compose.runtime.staticCompositionLocalOf<HazeState?> { null }

/**
 * Glass surface. With a [LocalHaze] source on screen (Android 12+), it is real frosted glass:
 * the content scrolling underneath is blurred live (Haze, RenderEffect on the GPU), tinted with the
 * theme's glass colour, with a hairline highlight on the edge. Without a source — or with [solid]
 * > 0 for surfaces that must stay readable over anything — it falls back to a translucent fill.
 */
@Composable
fun Modifier.glass(shape: Shape = RoundedCornerShape(24.dp), solid: Float = 0f): Modifier {
    val c = Zura.colors
    val haze = LocalHaze.current
    val sheen = remember(c.glassStroke) {
        Brush.verticalGradient(listOf(c.glassStroke.copy(alpha = c.glassStroke.alpha * 2.2f), c.glassStroke.copy(alpha = 0.02f)))
    }
    if (haze != null && solid == 0f) {
        val style = remember(c) {
            HazeStyle(
                backgroundColor = c.bg,
                tint = HazeTint(c.glass.copy(alpha = if (c.isDark) 0.55f else 0.62f)),
                blurRadius = 24.dp,
                noiseFactor = 0.05f,
            )
        }
        return this.clip(shape).hazeEffect(state = haze, style = style).border(1.dp, sheen, shape)
    }
    return this
        .clip(shape)
        .background(c.bg.copy(alpha = solid))
        .background(c.glass)
        .background(Brush.verticalGradient(listOf(Color.White.copy(alpha = if (c.isDark) 0.035f else 0.25f), Color.Transparent)))
        .border(1.dp, sheen, shape)
}

/** App background: two-stop diagonal wash — gives depth to dark themes without any bitmap. */
@Composable
fun Modifier.zuraBackground(): Modifier {
    val c = Zura.colors
    return background(Brush.linearGradient(listOf(c.bg, c.bgAccent, c.bg)))
}

/** Monogram avatar: hue derived from chat id, gradient fill, optional story ring. */
@Composable
fun Avatar(
    name: String,
    hue: Float,
    size: Dp = 52.dp,
    ring: Boolean = false,
    ringSeen: Boolean = false,
    image: androidx.compose.ui.graphics.ImageBitmap? = null,
    content: (@Composable BoxScope.() -> Unit)? = null,
) {
    val c = Zura.colors
    val ringBrush = if (ringSeen) Brush.linearGradient(listOf(c.textMuted.copy(alpha = 0.4f), c.textMuted.copy(alpha = 0.4f)))
    else Brush.sweepGradient(listOf(c.accent, c.accentAlt, c.accent))
    Box(
        Modifier
            .size(size)
            .drawBehind {
                if (ring) drawCircle(ringBrush, radius = this.size.minDimension / 2f, style = Stroke(width = 2.2.dp.toPx()))
            },
        contentAlignment = Alignment.Center,
    ) {
        val inner = if (ring) size - 7.dp else size
        Box(
            Modifier
                .size(inner)
                .clip(CircleShape)
                .background(
                    Brush.linearGradient(
                        listOf(Color.hsv(hue % 360f, 0.45f, 0.75f), Color.hsv((hue + 40f) % 360f, 0.55f, 0.55f)),
                    ),
                ),
            contentAlignment = Alignment.Center,
        ) {
            if (content != null) content()
            else if (image != null) androidx.compose.foundation.Image(
                image, null, Modifier.size(inner), contentScale = androidx.compose.ui.layout.ContentScale.Crop,
            )
            else Text(
                name.trim().firstOrNull()?.uppercase() ?: "•",
                color = Color.White,
                fontSize = (inner.value * 0.4f).sp,
                style = Zura.type.headline,
            )
        }
    }
}

/** Avatar for a Zura user/contact: remote photo (encrypted cache) with monogram fallback. */
@Composable
fun UserAvatar(name: String, hue: Float, path: String?, size: Dp = 52.dp, ring: Boolean = false, ringSeen: Boolean = false) {
    val g = LocalContext.current.graph
    val img = rememberAvatar(g.avatars, path).value
    Avatar(name, hue, size, ring, ringSeen, image = img)
}
