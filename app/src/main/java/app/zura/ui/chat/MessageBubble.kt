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
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.Done
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
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
    val sel = LocalSelection.current
    if (sel.ids.isNotEmpty() && m.kind != MsgKind.EVENT) {
        // Selection mode: the whole row toggles; no swipe / menu / media while selecting.
        val on = m.id in sel.ids
        Box(modifier.fillMaxWidth().background(if (on) Zura.colors.accent.copy(alpha = 0.14f) else Color.Transparent)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier.padding(start = 12.dp).size(22.dp).clip(CircleShape)
                        .background(if (on) Zura.colors.accent else Zura.colors.text.copy(alpha = 0.12f)),
                    contentAlignment = Alignment.Center,
                ) { if (on) Icon(Icons.Default.Done, null, tint = Zura.colors.onAccent, modifier = Modifier.size(15.dp)) }
                Box(Modifier.weight(1f)) {
                    if (m.kind == MsgKind.CALL) CallLogRow(m, item.mine, fmt, {}, Modifier)
                    else Row(
                        Modifier.fillMaxWidth().padding(top = if (item.groupedWithPrev) 2.dp else 8.dp).padding(horizontal = 10.dp),
                        horizontalArrangement = if (item.mine) Arrangement.End else Arrangement.Start,
                    ) { Bubble(item, fmt, resolve, onLongPress = {}, onDoubleTap = {}, onOpenMedia = {}, onRetry = {}) }
                }
            }
            // On top of the bubble (which has its own tap handling): the whole row toggles.
            Box(Modifier.matchParentSize().pointerInput(m.id) { detectTapGestures { haptics.tick(); sel.toggle(m.id) } })
        }
        return
    }
    if (m.kind == MsgKind.CALL) { CallLogRow(m, item.mine, fmt, onLongPress, modifier); return }
    if (m.kind == MsgKind.EVENT) { EventRow(m, item.mine, fmt, modifier); return }
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
            // offset{} already mirrors in RTL: + = toward the end edge (right in English, LEFT in Arabic),
            // uncovering the reply arrow on the start side. (A manual flip here cancelled that out.)
            Modifier.fillMaxWidth().offset { IntOffset(drag.value.roundToInt(), 0) }
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
    val big = app.zura.ui.theme.LocalBubbleRadius.current.dp
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
                // A photo/video bubble is exactly as wide as the picture: a long caption wraps
                // under it instead of stretching the bubble and leaving a gap beside the image.
                .then(if (isVisual) Modifier.width(248.dp) else Modifier.widthIn(max = 300.dp))
                .clip(shape)
                .background(fill)
                .pointerInput(m.id) {
                    detectTapGestures(
                        onLongPress = { onLongPress() },
                        onDoubleTap = { onDoubleTap() },
                        onTap = {
                            when {
                                m.status == MsgStatus.FAILED -> onRetry()
                                isVisual && m.media?.isPlaceholder != true -> onOpenMedia()
                                // Documents, APKs…: open in the matching app (audio/theme cards have their own buttons).
                                m.kind == MsgKind.FILE && m.media?.mime?.startsWith("audio/") != true &&
                                    m.media?.originalName?.endsWith(app.zura.ui.theme.CustomTheme.EXT, true) != true -> onOpenMedia()
                            }
                        },
                    )
                }
                .padding(if (isVisual) 4.dp else 0.dp),
        ) {
            m.replyTo?.let { rid -> resolve(rid) }?.let { q -> Quote(q, onFill) }
            when (m.kind) {
                MsgKind.TEXT -> Unit
                MsgKind.IMAGE, MsgKind.VIDEO -> VisualContent(m, fmt, RoundedCornerShape(big - 4.dp))
                // Shared music / audio files (they carry a file name) get a player, not the voice-note look.
                MsgKind.VOICE -> if (m.media?.originalName != null) AudioContent(m, item.mine, fmt) else VoiceContent(m, item.mine, fmt)
                MsgKind.FILE -> when {
                    m.media?.mime?.startsWith("audio/") == true -> AudioContent(m, item.mine, fmt)
                    m.media?.originalName?.endsWith(app.zura.ui.theme.CustomTheme.EXT, ignoreCase = true) == true || m.media?.mime == app.zura.ui.theme.CustomTheme.MIME -> ThemeFileCard(m, onFill)
                    else -> FileContent(m, onFill, fmt)
                }
                MsgKind.CALL, MsgKind.EVENT -> Unit
            }
            m.moment?.let { MomentQuoteCard(it, item.mine, onFill) }
            m.link?.let { LinkCard(it, onFill, item.mine) }
            val text = m.text
            if (!text.isNullOrEmpty()) {
                val linkColor = if (item.mine) onFill else c.accent
                val annotated = remember(text, linkColor) { linkify(text, linkColor) }
                Text(
                    annotated,
                    // Content-based direction: an Arabic line in an English UI (and vice versa) aligns correctly.
                    style = Zura.type.body.copy(textDirection = TextDirection.Content, fontFamily = app.zura.ui.theme.LocalChatFont.current ?: Zura.type.body.fontFamily),
                    color = onFill,
                    modifier = Modifier.padding(start = 14.dp, end = 14.dp, top = if (isVisual) 6.dp else 9.dp),
                )
            }
            val retranslate = LocalRetranslate.current
            LocalTranslations.current?.let { map -> map[m.id]?.let { t -> TranslationBlock(t, onFill, onHide = { map.remove(m.id) }, onRetry = { retranslate(m) }) } }
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
        val thumb = remember(q.media?.preview) { q.media?.preview?.let(Previews::decode)?.asImageBitmap() }
        if (thumb != null) {
            Image(thumb, null, Modifier.size(32.dp).clip(RoundedCornerShape(6.dp)), contentScale = ContentScale.Crop)
            Spacer(Modifier.width(8.dp))
        }
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
        if (m.ttlMs != null) Text("⏱", style = Zura.type.caption, color = muted)
        if (m.editedAt != null) Text(stringResource(R.string.edited), style = Zura.type.caption, color = muted)
        Text(fmt.time(m.sentAt), style = Zura.type.caption, color = muted)
        if (mine) StatusTick(m.status, muted)
    }
}

/** Ticks morph through states with a cross-scale instead of popping. */
/** Multi-select: ids picked so far + toggle. Empty = normal mode. */
class Selection(val ids: Set<String>, val toggle: (String) -> Unit)
val LocalSelection = androidx.compose.runtime.staticCompositionLocalOf { Selection(emptySet()) {} }

/** Re-run a message's translation (after the user changed the languages). */
val LocalRetranslate = androidx.compose.runtime.staticCompositionLocalOf<(Message) -> Unit> { {} }

/** Translations of this chat's messages (id → state); a tap on one hides it. */
val LocalTranslations = androidx.compose.runtime.staticCompositionLocalOf<androidx.compose.runtime.snapshots.SnapshotStateMap<String, Translation>?> { null }

/** False when I hide my read receipts from this chat's contact (then I don't see theirs). */
val LocalShowReadTicks = androidx.compose.runtime.staticCompositionLocalOf { true }

@Composable
private fun StatusTick(s: MsgStatus, muted: Color) {
    val showRead = LocalShowReadTicks.current
    val c = Zura.colors
    val scale by animateFloatAsState(1f, ZuraMotion.Bouncy, label = "tick")
    val (icon, tint) = when (s) {
        MsgStatus.PENDING -> ZuraIcons.Clock to muted
        MsgStatus.SENT -> Icons.Default.Done to muted
        MsgStatus.DELIVERED -> ZuraIcons.DoneAll to muted
        MsgStatus.READ -> ZuraIcons.DoneAll to if (showRead) c.read else muted
        MsgStatus.FAILED -> Icons.Default.Warning to c.danger
    }
    Icon(icon, s.name, tint = tint, modifier = Modifier.size(15.dp).graphicsLayer { scaleX = scale; scaleY = scale })
}

@Composable
private fun VisualContent(m: Message, fmt: ZuraFormat, shape: RoundedCornerShape) {
    val ref = m.media ?: return
    val g = LocalContext.current.graph
    // Bounded like other messengers: ≤ 240 dp wide and ≤ ~300 dp tall (tall shots are cropped).
    val ratio = if (ref.width > 0 && ref.height > 0) (ref.width.toFloat() / ref.height).coerceIn(0.8f, 1.78f) else 1f
    val preview = remember(ref.preview) { ref.preview?.let(Previews::decode)?.asImageBitmap() }
    val pending = ref.isPlaceholder
    val full by if (m.kind == MsgKind.IMAGE && !pending) rememberVaultImage(g.images, m.chatId, ref, 720) else remember { androidx.compose.runtime.mutableStateOf(null) }
    val fade by animateFloatAsState(if (full != null) 1f else 0f, label = "img")
    Box(Modifier.width(240.dp).aspectRatio(ratio).clip(shape).background(Zura.colors.surfaceHigh)) {
        // 1) micro-preview painted on frame 1 (from the record, zero I/O), softened
        preview?.let {
            Image(
                it, null, contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize().then(if (Build.VERSION.SDK_INT >= 31) Modifier.blur(14.dp) else Modifier),
            )
        }
        // 2) full image cross-fades over it once decrypted + decoded off-thread
        full?.let { Image(it, null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize().graphicsLayer { alpha = fade }) }
        if (pending && m.status != MsgStatus.FAILED) {
            // Compressing + encrypting: the photo is already in the chat, just not sent yet.
            androidx.compose.material3.CircularProgressIndicator(
                Modifier.align(Alignment.Center).size(44.dp).clip(CircleShape).background(Color.Black.copy(alpha = 0.35f)).padding(8.dp),
                color = Color.White, strokeWidth = 2.5.dp,
            )
        } else if (m.kind == MsgKind.VIDEO) {
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
private fun TranslationBlock(t: Translation, onFill: Color, onHide: () -> Unit, onRetry: () -> Unit) {
    val ctx = LocalContext.current
    var pick by remember { androidx.compose.runtime.mutableStateOf<String?>(null) } // "from" | "to"
    Column(
        Modifier.padding(start = 14.dp, end = 14.dp, top = 6.dp).widthIn(max = 270.dp)
            .clip(RoundedCornerShape(12.dp)).background(onFill.copy(alpha = 0.08f)).padding(10.dp),
    ) {
        when (t) {
            Translation.Loading -> Row(verticalAlignment = Alignment.CenterVertically) {
                androidx.compose.material3.CircularProgressIndicator(Modifier.size(14.dp), color = onFill, strokeWidth = 2.dp)
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.translating), style = Zura.type.caption, color = onFill.copy(alpha = 0.75f))
            }
            is Translation.Done -> Text(t.text, style = Zura.type.body.copy(textDirection = TextDirection.Content), color = onFill, modifier = Modifier.clickable(onClick = onHide))
            is Translation.Error -> Text(stringResource(t.res), style = Zura.type.caption, color = onFill.copy(alpha = 0.75f))
        }
        if (t != Translation.Loading) {
            // Language bar: "English (detected) → العربية" — tap either side to change; remembered.
            val from = app.zura.i18n.Translator.from(ctx)
            val fromLabel = when {
                t is Translation.Done && t.detected -> stringResource(R.string.detected, app.zura.i18n.Translator.name(ctx, t.from))
                from == app.zura.i18n.Translator.AUTO -> stringResource(R.string.detect_auto)
                else -> app.zura.i18n.Translator.name(ctx, from)
            }
            Row(Modifier.padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("🌐 $fromLabel", style = Zura.type.caption, color = onFill.copy(alpha = 0.7f), textDecoration = androidx.compose.ui.text.style.TextDecoration.Underline,
                    modifier = Modifier.clickable { pick = "from" })
                Text("  →  ", style = Zura.type.caption, color = onFill.copy(alpha = 0.5f))
                Text(app.zura.i18n.Translator.name(ctx, app.zura.i18n.Translator.to(ctx)), style = Zura.type.caption, color = onFill.copy(alpha = 0.7f),
                    textDecoration = androidx.compose.ui.text.style.TextDecoration.Underline, modifier = Modifier.clickable { pick = "to" })
            }
        }
    }
    pick?.let { which ->
        val langs = remember { app.zura.i18n.Translator.languages(ctx) }
        val options = if (which == "from") listOf(app.zura.i18n.Translator.AUTO to stringResource(R.string.detect_auto)) + langs else langs
        app.zura.ui.components.PickerSheet(
            title = stringResource(if (which == "from") R.string.translate_from else R.string.translate_to), hint = null,
            options = options,
            selected = if (which == "from") app.zura.i18n.Translator.from(ctx) else app.zura.i18n.Translator.to(ctx),
            onPick = { code ->
                pick = null
                if (which == "from") app.zura.i18n.Translator.setFrom(ctx, code) else app.zura.i18n.Translator.setTo(ctx, code)
                onRetry()
            },
            onDismiss = { pick = null },
        )
    }
}

/** Makes every URL in [text] tappable (opens in the browser), underlined. */
private fun linkify(text: String, color: Color): androidx.compose.ui.text.AnnotatedString {
    val links = app.zura.net.LinkPreviews.find(text)
    return androidx.compose.ui.text.buildAnnotatedString {
        append(text)
        val style = androidx.compose.ui.text.TextLinkStyles(
            androidx.compose.ui.text.SpanStyle(color = color, textDecoration = androidx.compose.ui.text.style.TextDecoration.Underline),
        )
        links.forEach { (range, url) -> addLink(androidx.compose.ui.text.LinkAnnotation.Url(url, style), range.first, range.last + 1) }
    }
}

/** "Reacted to your moment" with a tiny preview of that moment. */
@Composable
private fun MomentQuoteCard(q: app.zura.core.model.MomentQuote, mine: Boolean, onFill: Color) {
    val thumb = remember(q.preview) { q.preview?.let(Previews::decode)?.asImageBitmap() }
    Row(
        Modifier.padding(start = 8.dp, end = 8.dp, top = 8.dp).clip(RoundedCornerShape(12.dp)).background(onFill.copy(alpha = 0.1f)).padding(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(width = 46.dp, height = 70.dp).clip(RoundedCornerShape(10.dp)).background(onFill.copy(alpha = 0.15f))) {
            thumb?.let { Image(it, null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize()) }
        }
        Spacer(Modifier.width(8.dp))
        Column {
            Text(stringResource(if (mine) R.string.moment_quote_mine else R.string.moment_quote_theirs), style = Zura.type.caption, color = onFill.copy(alpha = 0.75f))
            q.caption?.let { Text(it, style = Zura.type.caption, color = onFill.copy(alpha = 0.6f), maxLines = 1, overflow = TextOverflow.Ellipsis) }
        }
    }
}

/** WhatsApp-style card: big image, title, site — tap opens the link. */
@Composable
private fun LinkCard(lp: app.zura.core.model.LinkPreview, onFill: Color, mine: Boolean) {
    val uri = androidx.compose.ui.platform.LocalUriHandler.current
    val img = remember(lp.image) {
        lp.image?.let { runCatching { android.util.Base64.decode(it, android.util.Base64.NO_WRAP) }.getOrNull() }
            ?.let { BitmapFactoryCompat.decode(it) }
    }
    Column(
        Modifier.padding(start = 4.dp, end = 4.dp, top = 4.dp).width(260.dp).clip(RoundedCornerShape(16.dp))
            .background(if (mine) Color.Black.copy(alpha = 0.14f) else onFill.copy(alpha = 0.06f))
            .clickable { runCatching { uri.openUri(lp.url) } },
    ) {
        img?.let {
            val r = (it.width.toFloat() / it.height).coerceIn(0.8f, 1.91f)
            Image(it, null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxWidth().aspectRatio(r))
        }
        Column(Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
            lp.title?.let {
                Text(it, style = Zura.type.label.copy(textDirection = TextDirection.Content), color = onFill, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            lp.description?.takeIf { lp.title == null || img == null }?.let {
                Text(it, style = Zura.type.caption.copy(textDirection = TextDirection.Content), color = onFill.copy(alpha = 0.7f), maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            lp.site?.let {
                Text("🔗 $it", style = Zura.type.caption, color = onFill.copy(alpha = 0.6f), maxLines = 1, modifier = Modifier.padding(top = 4.dp))
            }
        }
    }
}

private object BitmapFactoryCompat {
    fun decode(b: ByteArray) = runCatching { android.graphics.BitmapFactory.decodeByteArray(b, 0, b.size)?.asImageBitmap() }.getOrNull()
}

/** Shared music / audio file: ♪ play button, name, position / length, seek bar. */
@Composable
private fun AudioContent(m: Message, mine: Boolean, fmt: ZuraFormat) {
    val ref = m.media ?: return
    val g = LocalContext.current.graph
    val c = Zura.colors
    val state by g.playback.state.collectAsStateWithLifecycle()
    val active = state.key == m.id
    val total = if (active && state.durationMs > 0) state.durationMs else ref.durationMs
    val progress = if (active && total > 0) (state.positionMs.toFloat() / total).coerceIn(0f, 1f) else 0f
    val onFill = if (mine) c.onBubbleMine else c.onBubbleTheirs
    val title = ref.originalName?.substringBeforeLast('.')?.ifBlank { null } ?: stringResource(R.string.audio)
    Row(Modifier.padding(start = 8.dp, end = 12.dp, top = 8.dp).width(260.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier.size(46.dp).clip(RoundedCornerShape(14.dp)).background(if (mine) onFill.copy(alpha = 0.18f) else c.accent)
                .clickable { g.playback.toggle(m.chatId, m.id, ref) },
            contentAlignment = Alignment.Center,
        ) {
            if (active && state.playing) Icon(ZuraIcons.Pause, null, tint = if (mine) onFill else c.onAccent)
            else Text("♪", style = Zura.type.headline, color = if (mine) onFill else c.onAccent)
        }
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = Zura.type.label.copy(textDirection = TextDirection.Content), color = onFill, maxLines = 1, overflow = TextOverflow.Ellipsis)
            androidx.compose.material3.Slider(
                value = progress,
                onValueChange = { f -> if (active) g.playback.seek(f) },
                enabled = active,
                modifier = Modifier.fillMaxWidth().height(22.dp),
                colors = androidx.compose.material3.SliderDefaults.colors(
                    thumbColor = if (mine) onFill else c.accent, activeTrackColor = if (mine) onFill else c.accent,
                    inactiveTrackColor = onFill.copy(alpha = 0.25f),
                    disabledThumbColor = onFill.copy(alpha = 0.5f), disabledInactiveTrackColor = onFill.copy(alpha = 0.25f),
                ),
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    if (active) "${fmt.duration(state.positionMs)} / ${fmt.duration(total)}" else if (total > 0) fmt.duration(total) else fmt.size(ref.size),
                    style = Zura.type.caption, color = onFill.copy(alpha = 0.7f), modifier = Modifier.weight(1f),
                )
                if (active) Text(
                    "${state.speed}×", style = Zura.type.caption, color = onFill,
                    modifier = Modifier.clip(CircleShape).background(onFill.copy(alpha = 0.15f)).clickable { g.playback.cycleSpeed() }.padding(horizontal = 6.dp),
                )
            }
        }
    }
}

/** A shared .zuratheme file: preview strip + "Apply". */
@Composable
private fun ThemeFileCard(m: Message, onFill: Color) {
    val ref = m.media ?: return
    val ctx = LocalContext.current
    val g = ctx.graph
    val scope = rememberCoroutineScope()
    var busy by remember { androidx.compose.runtime.mutableStateOf(false) }
    Row(Modifier.padding(start = 10.dp, end = 12.dp, top = 10.dp).widthIn(min = 230.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(42.dp).clip(RoundedCornerShape(12.dp)).background(onFill.copy(alpha = 0.14f)), contentAlignment = Alignment.Center) { Text("🎨", style = Zura.type.headline) }
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(ref.originalName?.removeSuffix(app.zura.ui.theme.CustomTheme.EXT) ?: stringResource(R.string.theme_file), style = Zura.type.label, color = onFill, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(stringResource(R.string.theme_file), style = Zura.type.caption, color = onFill.copy(alpha = 0.65f))
        }
        Box(
            Modifier.clip(CircleShape).background(onFill.copy(alpha = 0.18f)).clickable(enabled = !busy) {
                busy = true
                scope.launch {
                    val t = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                        runCatching {
                            val bytes = java.io.ByteArrayOutputStream().also { g.vault.decryptTo(m.chatId, ref, it) }.toByteArray()
                            require(bytes.size < 8_000_000)
                            app.zura.ui.theme.CustomTheme.import(ctx, bytes)
                        }.getOrNull()
                    }
                    busy = false
                    if (t != null) {
                        g.settings.saveCustom(t); g.settings.themeFiles.value++
                        android.widget.Toast.makeText(ctx, R.string.theme_applied, android.widget.Toast.LENGTH_SHORT).show()
                    } else android.widget.Toast.makeText(ctx, R.string.theme_bad_file, android.widget.Toast.LENGTH_SHORT).show()
                }
            }.padding(horizontal = 14.dp, vertical = 7.dp),
        ) { Text(stringResource(R.string.theme_apply_short), style = Zura.type.label, color = onFill) }
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

/** Centered call-history entry: "📞 Voice call · 2:31" / "Missed video call". */
@Composable
private fun EventRow(m: Message, mine: Boolean, fmt: ZuraFormat, modifier: Modifier) {
    val c = Zura.colors
    val ctx = LocalContext.current
    val label = eventText(ctx, m.text, mine)
    Box(modifier.fillMaxWidth().padding(vertical = 6.dp), contentAlignment = Alignment.Center) {
        Row(
            Modifier.clip(CircleShape).background(c.surface.copy(alpha = 0.8f)).padding(horizontal = 14.dp, vertical = 7.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("${eventIcon(m.text)}  $label", style = Zura.type.label, color = c.textMuted)
            Spacer(Modifier.width(8.dp))
            Text(fmt.time(m.sentAt), style = Zura.type.caption, color = c.textMuted)
        }
    }
}

@Composable
private fun CallLogRow(m: Message, mine: Boolean, fmt: ZuraFormat, onLongPress: (Message) -> Unit, modifier: Modifier) {
    val c = Zura.colors
    val info = parseCall(m.text)
    val label = when {
        info.missed && info.reason == "declined" -> stringResource(R.string.declined_call)
        info.missed -> stringResource(if (info.video) R.string.missed_video_call else R.string.missed_voice_call)
        else -> stringResource(if (info.video) R.string.video_call else R.string.voice_call) + " · " + fmt.duration(info.seconds * 1000)
    }
    val tint = if (info.missed && !mine) c.danger else c.textMuted
    Box(modifier.fillMaxWidth().padding(vertical = 6.dp), contentAlignment = Alignment.Center) {
        Row(
            Modifier.clip(CircleShape).background(c.surface.copy(alpha = 0.8f))
                .pointerInput(m.id) { detectTapGestures(onLongPress = { onLongPress(m) }) }
                .padding(horizontal = 14.dp, vertical = 7.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(if (info.video) ZuraIcons.VideoCam else Icons.Default.Call, null, tint = tint, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(8.dp))
            Text(label, style = Zura.type.label, color = tint)
            Spacer(Modifier.width(8.dp))
            Text(fmt.time(m.sentAt), style = Zura.type.caption, color = c.textMuted)
        }
    }
}

data class CallInfo(val video: Boolean, val seconds: Long, val missed: Boolean, val reason: String)

fun parseCall(text: String?): CallInfo {
    val parts = text.orEmpty().split(';')
    val kv = parts.drop(1).mapNotNull { p -> p.split('=', limit = 2).takeIf { it.size == 2 }?.let { it[0] to it[1] } }.toMap()
    return CallInfo(parts.firstOrNull() == "video", kv["dur"]?.toLongOrNull() ?: 0, kv["missed"] == "1", kv["reason"].orEmpty())
}

fun kindLabel(k: MsgKind) = when (k) {
    MsgKind.CALL -> R.string.voice_call
    MsgKind.EVENT -> R.string.screenshot_event
    MsgKind.IMAGE -> R.string.photo
    MsgKind.VIDEO -> R.string.video
    MsgKind.VOICE -> R.string.voice_message
    MsgKind.FILE -> R.string.file
    MsgKind.TEXT -> R.string.message_hint
}

