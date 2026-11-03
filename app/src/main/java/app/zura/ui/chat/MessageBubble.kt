package app.zura.ui.chat

import android.os.Build
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Done
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.zura.R
import app.zura.core.model.Message
import app.zura.core.model.MsgKind
import app.zura.core.model.MsgStatus
import app.zura.graph
import app.zura.i18n.ZuraFormat
import app.zura.media.Previews
import app.zura.media.rememberVaultImage
import app.zura.ui.ZuraIcons
import app.zura.ui.components.Haptics
import app.zura.ui.components.ZuraMotion
import app.zura.ui.theme.Zura
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

@Composable
fun BubbleRow(
    item: ChatItem.Bubble,
    fmt: ZuraFormat,
    haptics: Haptics,
    resolve: (String) -> Message?,
    onReply: (Message) -> Unit,
    onLongPress: (Message) -> Unit,
    onDoubleTap: (Message) -> Unit,
    onOpenMedia: (Message) -> Unit,
    onRetry: (Message) -> Unit,
    modifier: Modifier = Modifier,
) {
    val m = item.m
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    val density = LocalDensity.current
    val threshold = with(density) { 64.dp.toPx() }
    val scope = rememberCoroutineScope()

    // Swipe-to-reply: rubber-banded drag toward the end edge, haptic tick at threshold, spring home.
    val drag = remember { Animatable(0f) }
    val armed = remember { booleanArrayOf(false) }

    // Freshly sent bubbles pop in with a slight overshoot.
    val pop = remember { Animatable(if (item.mine && System.currentTimeMillis() - m.sentAt < 1200) 0.86f else 1f) }
    LaunchedEffect(Unit) { if (pop.value < 1f) pop.animateTo(1f, ZuraMotion.Bouncy) }

    Box(
        modifier
            .fillMaxWidth()
            .padding(top = if (item.groupedWithPrev) 2.dp else 8.dp)
            .pointerInput(rtl) {
                detectHorizontalDragGestures(
                    onDragEnd = {
                        if (armed[0]) onReply(m)
                        armed[0] = false
                        scope.launch { drag.animateTo(0f, ZuraMotion.Snappy) }
                    },
                    onDragCancel = { armed[0] = false; scope.launch { drag.animateTo(0f, ZuraMotion.Snappy) } },
                ) { change, dx ->
                    val towardEnd = if (rtl) -dx else dx
                    val next = (drag.value + towardEnd * if (drag.value > threshold) 0.35f else 1f).coerceIn(0f, threshold * 1.6f)
                    if (!armed[0] && next >= threshold) { armed[0] = true; haptics.tick() }
                    if (armed[0] && next < threshold * 0.8f) armed[0] = false
                    change.consume()
                    scope.launch { drag.snapTo(next) }
                }
            },
    ) {
        // Reply glyph revealed behind the bubble
        Icon(
            ZuraIcons.Reply, null, tint = Zura.colors.textMuted,
            modifier = Modifier.align(Alignment.CenterStart).padding(start = 14.dp).size(20.dp)
                .graphicsLayer { alpha = (drag.value / threshold).coerceIn(0f, 1f); scaleX = alpha; scaleY = alpha },
        )
        Row(
            Modifier.fillMaxWidth().offset { IntOffset(((if (rtl) -1 else 1) * drag.value).roundToInt(), 0) }
                .padding(horizontal = 10.dp),
            horizontalArrangement = if (item.mine) Arrangement.End else Arrangement.Start,
        ) {
            Box(Modifier.graphicsLayer { scaleX = pop.value; scaleY = pop.value; transformOrigin = androidx.compose.ui.graphics.TransformOrigin(if (item.mine xor rtl) 1f else 0f, 1f) }) {
                Bubble(item, fmt, resolve, onLongPress = { haptics.heavy(); onLongPress(m) }, onDoubleTap = { haptics.tick(); onDoubleTap(m) }, onOpenMedia = { onOpenMedia(m) }, onRetry = { onRetry(m) })
            }
        }
    }
}

@Composable
private fun Bubble(
    item: ChatItem.Bubble,
    fmt: ZuraFormat,
    resolve: (String) -> Message?,
    onLongPress: () -> Unit,
    onDoubleTap: () -> Unit,
    onOpenMedia: () -> Unit,
    onRetry: () -> Unit,
) {
    val c = Zura.colors
    val m = item.m
    val big = 20.dp
    val small = 6.dp
    // Corners tighten on the sender's side when consecutive bubbles stack (iMessage-like rhythm).
    val shape = if (item.mine) RoundedCornerShape(big, if (item.groupedWithPrev) small else big, if (item.groupedWithNext) small else big, big)
    else RoundedCornerShape(if (item.groupedWithPrev) small else big, big, big, if (item.groupedWithNext) small else big)
    val fill = if (item.mine) Brush.linearGradient(listOf(c.bubbleMine, c.bubbleMineAlt)) else Brush.linearGradient(listOf(c.bubbleTheirs, c.bubbleTheirs))
    val onFill = if (item.mine) c.onBubbleMine else c.onBubbleTheirs
    val isVisual = m.kind == MsgKind.IMAGE || m.kind == MsgKind.VIDEO

    Column(horizontalAlignment = if (item.mine) Alignment.End else Alignment.Start) {
        Column(
            Modifier
                .widthIn(max = 300.dp)
                .clip(shape)
                .background(fill)
                .pointerInput(m.id) {
                    detectTapGestures(
                        onLongPress = { onLongPress() },
                        onDoubleTap = { onDoubleTap() },
                        onTap = { if (m.status == MsgStatus.FAILED) onRetry() else if (isVisual) onOpenMedia() },
                    )
                }
                .padding(if (isVisual) 4.dp else 0.dp),
        ) {
            m.replyTo?.let { rid -> resolve(rid) }?.let { q -> Quote(q, onFill) }
            when (m.kind) {
                MsgKind.TEXT -> Unit
                MsgKind.IMAGE, MsgKind.VIDEO -> VisualContent(m, fmt, RoundedCornerShape(big - 4.dp))
                MsgKind.VOICE -> VoiceContent(m, item.mine, fmt)
                MsgKind.FILE -> FileContent(m, onFill, fmt)
            }
            val text = m.text
            if (!text.isNullOrEmpty()) {
                Text(
                    text,
                    // Content-based direction: an Arabic line in an English UI (and vice versa) aligns correctly.
                    style = Zura.type.body.copy(textDirection = TextDirection.Content),
                    color = onFill,
                    modifier = Modifier.padding(start = 14.dp, end = 14.dp, top = if (isVisual) 6.dp else 9.dp),
                )
            }
            Footer(m, item.mine, onFill, fmt, Modifier.align(Alignment.End).padding(start = 14.dp, end = 10.dp, bottom = 6.dp, top = 2.dp))
        }
        if (m.reactions.isNotEmpty()) Reactions(m, fmt)
    }
}

@Composable
private fun Quote(q: Message, onFill: Color) {
    Row(
        Modifier.padding(start = 6.dp, end = 6.dp, top = 6.dp).clip(RoundedCornerShape(12.dp)).background(onFill.copy(alpha = 0.10f)).padding(8.dp),
    ) {
        Box(Modifier.width(3.dp).height(32.dp).clip(CircleShape).background(onFill.copy(alpha = 0.6f)))
        Spacer(Modifier.width(8.dp))
        Text(
            q.text ?: stringResource(kindLabel(q.kind)), color = onFill.copy(alpha = 0.85f),
            style = Zura.type.label.copy(textDirection = TextDirection.Content), maxLines = 2, overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun Footer(m: Message, mine: Boolean, onFill: Color, fmt: ZuraFormat, modifier: Modifier) {
    val muted = onFill.copy(alpha = 0.62f)
    Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        if (m.editedAt != null) Text(stringResource(R.string.edited), style = Zura.type.caption, color = muted)
        Text(fmt.time(m.sentAt), style = Zura.type.caption, color = muted)
        if (mine) StatusTick(m.status, muted)
    }
}

/** Ticks morph through states with a cross-scale instead of popping. */
@Composable
private fun StatusTick(s: MsgStatus, muted: Color) {
    val c = Zura.colors
    val scale by animateFloatAsState(1f, ZuraMotion.Bouncy, label = "tick")
    val (icon, tint) = when (s) {
        MsgStatus.PENDING -> ZuraIcons.Clock to muted
        MsgStatus.SENT -> Icons.Default.Done to muted
        MsgStatus.DELIVERED -> ZuraIcons.DoneAll to muted
        MsgStatus.READ -> ZuraIcons.DoneAll to c.read
        MsgStatus.FAILED -> Icons.Default.Warning to c.danger
    }
    Icon(icon, s.name, tint = tint, modifier = Modifier.size(15.dp).graphicsLayer { scaleX = scale; scaleY = scale })
}

@Composable
private fun VisualContent(m: Message, fmt: ZuraFormat, shape: RoundedCornerShape) {
    val ref = m.media ?: return
    val g = LocalContext.current.graph
    val ratio = if (ref.width > 0 && ref.height > 0) (ref.width.toFloat() / ref.height).coerceIn(0.62f, 1.7f) else 1f
    val preview = remember(ref.preview) { ref.preview?.let(Previews::decode)?.asImageBitmap() }
    val full by if (m.kind == MsgKind.IMAGE) rememberVaultImage(g.images, m.chatId, ref, 720) else remember { androidx.compose.runtime.mutableStateOf(null) }
    val fade by animateFloatAsState(if (full != null) 1f else 0f, label = "img")
    Box(Modifier.width(260.dp).aspectRatio(ratio).clip(shape).background(Zura.colors.surfaceHigh)) {
        // 1) micro-preview painted on frame 1 (from the record, zero I/O), softened
        preview?.let {
            Image(
                it, null, contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize().then(if (Build.VERSION.SDK_INT >= 31) Modifier.blur(14.dp) else Modifier),
            )
        }
        // 2) full image cross-fades over it once decrypted + decoded off-thread
        full?.let { Image(it, null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize().graphicsLayer { alpha = fade }) }
        if (m.kind == MsgKind.VIDEO) {
            Box(Modifier.align(Alignment.Center).size(52.dp).clip(CircleShape).background(Color.Black.copy(alpha = 0.45f)), contentAlignment = Alignment.Center) {
                Icon(Icons.Default.PlayArrow, null, tint = Color.White, modifier = Modifier.size(30.dp))
            }
            Text(
                fmt.duration(ref.durationMs), style = Zura.type.caption, color = Color.White,
                modifier = Modifier.align(Alignment.BottomStart).padding(8.dp).clip(CircleShape).background(Color.Black.copy(alpha = 0.45f)).padding(horizontal = 8.dp, vertical = 2.dp),
            )
        }
    }
}

@Composable
private fun VoiceContent(m: Message, mine: Boolean, fmt: ZuraFormat) {
    val ref = m.media ?: return
    val g = LocalContext.current.graph
    val c = Zura.colors
    val state by g.playback.state.collectAsStateWithLifecycle()
    val active = state.key == m.id
    val progress = if (active && state.durationMs > 0) state.positionMs.toFloat() / state.durationMs else 0f
    val levels = remember(ref.waveform) { Previews.decodeWaveform(ref.waveform) }
    val onFill = if (mine) c.onBubbleMine else c.onBubbleTheirs
    Row(Modifier.padding(start = 8.dp, end = 12.dp, top = 8.dp).width(250.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier.size(42.dp).clip(CircleShape).background(if (mine) onFill.copy(alpha = 0.18f) else c.accent)
                .clickable { g.playback.toggle(m.chatId, m.id, ref) },
            contentAlignment = Alignment.Center,
        ) {
            Icon(if (active && state.playing) ZuraIcons.Pause else Icons.Default.PlayArrow, null, tint = if (mine) onFill else c.onAccent)
        }
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Waveform(
                levels, progress, played = if (mine) onFill else c.accent, unplayed = onFill.copy(alpha = 0.32f),
                modifier = Modifier.fillMaxWidth().height(30.dp),
                onSeek = if (active) g.playback::seek else null,
            )
            Row {
                Text(fmt.duration(if (active) state.positionMs else ref.durationMs), style = Zura.type.caption, color = onFill.copy(alpha = 0.7f), modifier = Modifier.weight(1f))
                if (active) Text(
                    "${state.speed}×", style = Zura.type.caption, color = onFill,
                    modifier = Modifier.clip(CircleShape).background(onFill.copy(alpha = 0.15f)).clickable { g.playback.cycleSpeed() }.padding(horizontal = 6.dp),
                )
            }
        }
    }
}

@Composable
private fun FileContent(m: Message, onFill: Color, fmt: ZuraFormat) {
    val ref = m.media ?: return
    Row(Modifier.padding(start = 10.dp, end = 14.dp, top = 10.dp).widthIn(min = 200.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(40.dp).clip(RoundedCornerShape(10.dp)).background(onFill.copy(alpha = 0.14f)), contentAlignment = Alignment.Center) {
            Icon(ZuraIcons.File, null, tint = onFill)
        }
        Spacer(Modifier.width(10.dp))
        Column {
            Text(ref.originalName ?: stringResource(R.string.file), style = Zura.type.label, color = onFill, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(fmt.size(ref.size), style = Zura.type.caption, color = onFill.copy(alpha = 0.65f))
        }
    }
}

@Composable
private fun Reactions(m: Message, fmt: ZuraFormat) {
    val c = Zura.colors
    val counts = remember(m.reactions) { m.reactions.values.groupingBy { it }.eachCount().entries.sortedByDescending { it.value } }
    val scale = remember { Animatable(0.4f) }
    LaunchedEffect(m.reactions) { scale.snapTo(0.7f); scale.animateTo(1f, ZuraMotion.Bouncy) }
    Row(
        Modifier.offset(y = (-6).dp).padding(horizontal = 8.dp).graphicsLayer { scaleX = scale.value; scaleY = scale.value }
            .clip(CircleShape).background(c.surfaceHigh).padding(horizontal = 8.dp, vertical = 3.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        counts.forEach { (e, n) -> Text(if (n > 1) "$e ${fmt.count(n)}" else e, style = Zura.type.label, color = c.text) }
    }
}

fun kindLabel(k: MsgKind) = when (k) {
    MsgKind.IMAGE -> R.string.photo
    MsgKind.VIDEO -> R.string.video
    MsgKind.VOICE -> R.string.voice_message
    MsgKind.FILE -> R.string.file
    MsgKind.TEXT -> R.string.message_hint
}

