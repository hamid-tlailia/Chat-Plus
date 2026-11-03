package app.zura.ui.components

import androidx.compose.foundation.background
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

/**
 * Glass surface: translucent tinted fill + 1 px top-lit hairline (light catching the edge) +
 * a faint inner gradient. Deliberately *not* a real-time backdrop blur: RenderEffect blur over a
 * scrolling list costs 2–4 ms/frame on mid-range GPUs and breaks the 8.3 ms budget at 120 Hz.
 * This reads as frosted glass at a fraction of the cost.
 */
@Composable
fun Modifier.glass(shape: Shape = RoundedCornerShape(24.dp), solid: Float = 0f): Modifier {
    val c = Zura.colors
    val sheen = remember(c.glassStroke) {
        Brush.verticalGradient(listOf(c.glassStroke.copy(alpha = c.glassStroke.alpha * 2.2f), c.glassStroke.copy(alpha = 0.02f)))
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
            else Text(
                name.trim().firstOrNull()?.uppercase() ?: "•",
                color = Color.White,
                fontSize = (inner.value * 0.4f).sp,
                style = Zura.type.headline,
            )
        }
    }
}
