package app.zura.ui.components

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.zura.ui.theme.Zura

/** Soft, theme-tinted shimmer for placeholders while content loads. */
@Composable
fun shimmerBrush(): Brush {
    val base = Zura.colors.text.copy(alpha = 0.07f)
    val hi = Zura.colors.text.copy(alpha = 0.14f)
    val x by rememberInfiniteTransition(label = "shimmer").animateFloat(-400f, 1400f, infiniteRepeatable(tween(1300), RepeatMode.Restart), label = "x")
    return Brush.linearGradient(listOf(base, hi, base), start = androidx.compose.ui.geometry.Offset(x, 0f), end = androidx.compose.ui.geometry.Offset(x + 400f, 120f))
}

@Composable
fun SkeletonBox(width: Dp?, height: Dp, brush: Brush, modifier: Modifier = Modifier, round: Dp = 8.dp) {
    Box((if (width != null) modifier.size(width, height) else modifier.fillMaxWidth().height(height)).clip(RoundedCornerShape(round)).background(brush))
}

/** Placeholder for a chat-list / people row: avatar circle + two text lines. */
@Composable
fun SkeletonRow(brush: Brush) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 11.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(52.dp).clip(CircleShape).background(brush))
        Column(Modifier.padding(start = 14.dp).weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            SkeletonBox(150.dp, 14.dp, brush)
            SkeletonBox(null, 11.dp, brush, Modifier.padding(end = 60.dp))
        }
    }
}

/** Placeholder bubbles while a chat's messages are being opened. */
@Composable
fun SkeletonBubbles(brush: Brush, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth().padding(horizontal = 12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        listOf(false to 180, true to 140, false to 220, true to 200, false to 120, true to 170).forEach { (mine, w) ->
            Box(Modifier.fillMaxWidth(), contentAlignment = if (mine) Alignment.CenterEnd else Alignment.CenterStart) {
                SkeletonBox(w.dp, 42.dp, brush, round = 18.dp)
            }
        }
    }
}

