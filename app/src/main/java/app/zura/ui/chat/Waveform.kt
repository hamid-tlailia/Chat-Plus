package app.zura.ui.chat

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp

/**
 * Voice-note waveform. Drawn in a single Canvas pass (48 rounded bars, no child nodes).
 * [progress] fills bars in the played color; tap or drag scrubs. Direction-aware for RTL.
 */
@Composable
fun Waveform(
    levels: FloatArray,
    progress: Float,
    played: Color,
    unplayed: Color,
    modifier: Modifier = Modifier,
    onSeek: ((Float) -> Unit)? = null,
) {
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    val gestures = if (onSeek == null) Modifier else Modifier
        .pointerInput(rtl) { detectTapGestures { o -> onSeek(frac(o.x, size.width.toFloat(), rtl)) } }
        .pointerInput(rtl) { detectHorizontalDragGestures { change, _ -> onSeek(frac(change.position.x, size.width.toFloat(), rtl)) } }
    Canvas(modifier.then(gestures)) {
        val n = levels.size.coerceAtLeast(1)
        val gap = 2.dp.toPx()
        val barW = ((size.width - gap * (n - 1)) / n).coerceAtLeast(1.5f)
        val r = CornerRadius(barW / 2, barW / 2)
        val minH = 3.dp.toPx()
        for (i in 0 until n) {
            val h = (levels[i] * size.height).coerceAtLeast(minH)
            val idx = if (rtl) n - 1 - i else i
            val x = idx * (barW + gap)
            val color = if ((i + 0.5f) / n <= progress) played else unplayed
            drawRoundRect(color, Offset(x, (size.height - h) / 2), Size(barW, h), r)
        }
    }
}

/** Live recording waveform: newest sample enters from the end edge and scrolls toward start. */
@Composable
fun LiveWaveform(levels: List<Float>, color: Color, modifier: Modifier = Modifier) {
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    Canvas(modifier) {
        val barW = 3.dp.toPx()
        val gap = 2.dp.toPx()
        val slots = (size.width / (barW + gap)).toInt()
        val shown = levels.takeLast(slots)
        val minH = 3.dp.toPx()
        shown.forEachIndexed { i, l ->
            val fromEnd = shown.size - 1 - i
            val xEnd = size.width - fromEnd * (barW + gap) - barW
            val x = if (rtl) size.width - xEnd - barW else xEnd
            val h = (l * size.height).coerceAtLeast(minH)
            drawRoundRect(color, Offset(x, (size.height - h) / 2), Size(barW, h), CornerRadius(barW / 2, barW / 2))
        }
    }
}

private fun frac(x: Float, w: Float, rtl: Boolean): Float {
    val f = (x / w).coerceIn(0f, 1f)
    return if (rtl) 1f - f else f
}
