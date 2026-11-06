package app.zura.ui.profile

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.zura.R
import app.zura.core.model.Message
import app.zura.core.model.MsgKind
import app.zura.data.SettingsStore
import app.zura.graph
import app.zura.media.Previews
import app.zura.media.rememberVaultImage
import app.zura.ui.components.ZuraMotion
import app.zura.ui.components.appear
import app.zura.ui.components.rememberHaptics
import app.zura.ui.theme.BubbleStyle
import app.zura.ui.theme.LocalZura
import app.zura.ui.theme.Zura

/**
 * "Chat colours" for ONE chat: the default, the presets, and my Theme Studio style. Each swatch
 * shows their bubble over mine, in the app's current theme. The app theme itself is untouched.
 */
@Composable
fun ChatColorsPicker(chatId: String, onStudio: () -> Unit) {
    val g = LocalContext.current.graph
    val c = Zura.colors
    val haptics = rememberHaptics()
    val styles by g.settings.chatStyles.collectAsStateWithLifecycle()
    val custom by g.settings.customTheme.collectAsStateWithLifecycle()
    val chosen = styles[chatId] // null = default
    val base = LocalZura.current
    data class Opt(val key: String?, val label: String, val mineA: Color, val mineB: Color, val theirs: Color, val radius: Int)
    val opts = buildList {
        val d = g.settings.defaultChatStyle
        val dp = if (d == SettingsStore.STYLE_CUSTOM) custom?.applyTo(base) else runCatching { BubbleStyle.valueOf(d) }.getOrNull()?.applyTo(base)
        add(Opt(null, stringResource(R.string.chat_colors_default), (dp ?: base).bubbleMine, (dp ?: base).bubbleMineAlt, (dp ?: base).bubbleTheirs, 50))
        BubbleStyle.entries.forEach { s -> val p = s.applyTo(base); add(Opt(s.name, stringResource(bubbleName(s)), p.bubbleMine, p.bubbleMineAlt, p.bubbleTheirs, 50)) }
        custom?.let { t -> val p = t.applyTo(base); add(Opt(SettingsStore.STYLE_CUSTOM, t.name.ifBlank { "✨" }, p.bubbleMine, p.bubbleMineAlt, p.bubbleTheirs, t.radius)) }
    }
    Text(stringResource(R.string.chat_colors_hint), style = Zura.type.caption, color = c.textMuted, modifier = Modifier.padding(horizontal = 16.dp))
    LazyRow(contentPadding = PaddingValues(horizontal = 16.dp, vertical = 10.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        items(opts.size) { i ->
            val o = opts[i]
            val sel = o.key == chosen
            val ring by animateDpAsState(if (sel) 2.dp else 0.dp, label = "ring")
            val lift by animateFloatAsState(if (sel) 1.06f else 1f, ZuraMotion.Bouncy, label = "lift")
            Column(Modifier.appear(i).graphicsLayer { scaleX = lift; scaleY = lift }, horizontalAlignment = Alignment.CenterHorizontally) {
                Column(
                    Modifier.size(64.dp, 76.dp).clip(RoundedCornerShape(16.dp)).background(base.bg)
                        .border(ring, c.accent, RoundedCornerShape(16.dp))
                        .clickable { haptics.tick(); g.settings.setChatStyle(chatId, o.key) }.padding(8.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    val r = RoundedCornerShape(o.radius.coerceAtMost(12).dp)
                    Box(Modifier.size(34.dp, 14.dp).clip(r).background(o.theirs))
                    Box(Modifier.align(Alignment.End).size(38.dp, 14.dp).clip(r).background(Brush.linearGradient(listOf(o.mineA, o.mineB))))
                    Box(Modifier.size(26.dp, 14.dp).clip(r).background(o.theirs))
                }
                Text(o.label, style = Zura.type.caption, color = if (sel) c.text else c.textMuted, maxLines = 1, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 6.dp).width(64.dp))
            }
        }
    }
    Text(
        "✨ " + stringResource(R.string.chat_style_edit) + forward(), style = Zura.type.label, color = c.accent,
        modifier = Modifier.padding(horizontal = 16.dp).clip(CircleShape).clickable(onClick = onStudio).padding(vertical = 6.dp),
    )
}

/** " →" that points forward in the reading direction (← in Arabic). */
@Composable
private fun forward() = if (androidx.compose.ui.platform.LocalLayoutDirection.current == androidx.compose.ui.unit.LayoutDirection.Rtl) " ←" else " →"

fun bubbleName(s: BubbleStyle) = when (s) {
    BubbleStyle.THEME -> R.string.bubbles_theme
    BubbleStyle.OCEAN -> R.string.bubbles_ocean
    BubbleStyle.LAVENDER -> R.string.bubbles_lavender
    BubbleStyle.ROSE -> R.string.bubbles_rose
    BubbleStyle.EMERALD -> R.string.bubbles_emerald
    BubbleStyle.SUNSET -> R.string.bubbles_sunset
    BubbleStyle.GRAPHITE -> R.string.bubbles_graphite
}

/**
 * This chat's media, organised by type: chips with counts (photos · videos · voice & audio ·
 * files), each opening the full grid on that type; below, the latest photos & videos.
 */
@Composable
fun ChatMediaStrip(chatId: String, onOpen: (String) -> Unit, onAll: (kind: String?) -> Unit) {
    val g = LocalContext.current.graph
    val c = Zura.colors
    val media by produceState<List<Message>?>(null, chatId) { value = g.chats.mediaOf(chatId) }
    val all = media
    fun isAudio(m: Message) = m.kind == MsgKind.VOICE || m.media?.mime?.startsWith("audio/") == true
    val visual = all.orEmpty().filter { it.kind == MsgKind.IMAGE || it.kind == MsgKind.VIDEO }
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
        Text("🖼️  " + stringResource(R.string.chat_media), style = Zura.type.label, color = c.text, modifier = Modifier.weight(1f))
        if (!all.isNullOrEmpty()) Text(
            all.size.toString() + "  " + stringResource(R.string.chat_media_all) + forward(), style = Zura.type.label, color = c.accent,
            modifier = Modifier.clip(CircleShape).clickable { onAll(null) }.padding(vertical = 4.dp),
        )
    }
    if (all == null) { Box(Modifier.fillMaxWidth().padding(16.dp)); return }
    if (all.isEmpty()) { Text(stringResource(R.string.chat_media_none), style = Zura.type.caption, color = c.textMuted, modifier = Modifier.padding(16.dp)); return }
    // By type.
    val types = listOf(
        Triple("PHOTOS", "📷 " + stringResource(R.string.vault_photos), all.count { it.kind == MsgKind.IMAGE }),
        Triple("VIDEOS", "🎬 " + stringResource(R.string.vault_videos), all.count { it.kind == MsgKind.VIDEO }),
        Triple("AUDIO", "🎙️ " + stringResource(R.string.vault_audio), all.count { isAudio(it) }),
        Triple("FILES", "📄 " + stringResource(R.string.vault_files), all.count { it.kind == MsgKind.FILE && !isAudio(it) }),
    ).filter { it.third > 0 }
    LazyRow(contentPadding = PaddingValues(horizontal = 16.dp, vertical = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        items(types.size) { i ->
            val (key, label, n) = types[i]
            Row(
                Modifier.appear(i).clip(CircleShape).background(c.surfaceHigh).clickable { onAll(key) }.padding(horizontal = 14.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(label, style = Zura.type.label, color = c.text)
                Box(Modifier.padding(start = 8.dp).clip(CircleShape).background(c.accent.copy(alpha = 0.18f)).padding(horizontal = 7.dp, vertical = 1.dp)) {
                    Text(n.toString(), style = Zura.type.caption, color = c.accent)
                }
            }
        }
    }
    if (visual.isNotEmpty()) LazyRow(contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        items(visual.take(30), key = { it.id }) { m ->
            Thumb(chatId, m, Modifier.appear(visual.indexOf(m)).clickable { onOpen(m.id) })
        }
    }
}

@Composable
private fun Thumb(chatId: String, m: Message, modifier: Modifier) {
    val g = LocalContext.current.graph
    val ref = m.media ?: return
    val preview = remember(ref.preview) { ref.preview?.let(Previews::decode)?.asImageBitmap() }
    val full by if (m.kind == MsgKind.IMAGE) rememberVaultImage(g.images, chatId, ref, 240) else remember { androidx.compose.runtime.mutableStateOf(null) }
    Box(modifier.size(84.dp).clip(RoundedCornerShape(14.dp)).background(Zura.colors.surfaceHigh), contentAlignment = Alignment.Center) {
        (full ?: preview)?.let { Image(it, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop) }
        if (m.kind == MsgKind.VIDEO) Box(
            Modifier.size(28.dp).clip(CircleShape).background(Color.Black.copy(alpha = 0.45f)),
            contentAlignment = Alignment.Center,
        ) { Text("▶", color = Color.White, style = Zura.type.caption) }
    }
}
