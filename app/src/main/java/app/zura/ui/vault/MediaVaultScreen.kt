package app.zura.ui.vault

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.zura.R
import app.zura.core.model.MsgKind
import app.zura.data.ChatRepository
import app.zura.graph
import app.zura.i18n.ZuraFormat
import app.zura.media.Previews
import app.zura.media.rememberVaultImage
import app.zura.ui.ZuraIcons
import app.zura.ui.components.ConfirmAction
import app.zura.ui.components.ConfirmSheet
import app.zura.ui.components.glass
import app.zura.ui.components.rememberHaptics
import app.zura.ui.theme.Zura
import kotlinx.coroutines.launch

private enum class Kind { ALL, PHOTOS, VIDEOS, AUDIO, FILES }
private enum class Sort { NEWEST, OLDEST, LARGEST }

/**
 * Every photo, video, audio and file kept on this phone, across all chats — filter by type,
 * sort by date or size, select several and wipe them (crypto-shredded) to free space.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun MediaVaultScreen(onBack: () -> Unit, onOpenMedia: (chatId: String, messageId: String) -> Unit) {
    val ctx = LocalContext.current
    val g = ctx.graph
    val c = Zura.colors
    val haptics = rememberHaptics()
    val scope = rememberCoroutineScope()
    val fmt = remember { ZuraFormat(java.util.Locale.getDefault()) }
    var all by remember { mutableStateOf<List<ChatRepository.MediaItem>?>(null) }
    var kind by remember { mutableStateOf(Kind.ALL) }
    var sort by remember { mutableStateOf(Sort.NEWEST) }
    var selected by remember { mutableStateOf(setOf<String>()) }
    var confirm by remember { mutableStateOf(false) }
    var reload by remember { mutableStateOf(0) }
    LaunchedEffect(reload) { all = g.chats.allMedia() }

    BackHandler(enabled = selected.isNotEmpty()) { selected = emptySet() }

    fun Kind.matches(i: ChatRepository.MediaItem) = when (this) {
        Kind.ALL -> true
        Kind.PHOTOS -> i.m.kind == MsgKind.IMAGE
        Kind.VIDEOS -> i.m.kind == MsgKind.VIDEO
        Kind.AUDIO -> i.m.kind == MsgKind.VOICE || i.m.media?.mime?.startsWith("audio/") == true
        Kind.FILES -> i.m.kind == MsgKind.FILE && i.m.media?.mime?.startsWith("audio/") != true
    }
    val list = all.orEmpty().filter { kind.matches(it) }.let { l ->
        when (sort) {
            Sort.NEWEST -> l.sortedByDescending { it.m.sentAt }
            Sort.OLDEST -> l.sortedBy { it.m.sentAt }
            Sort.LARGEST -> l.sortedByDescending { it.m.media?.size ?: 0 }
        }
    }
    fun key(i: ChatRepository.MediaItem) = i.chatId + "/" + i.m.id

    Column(Modifier.fillMaxSize().statusBarsPadding()) {
        // Header
        Row(Modifier.fillMaxWidth().height(60.dp).padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { if (selected.isNotEmpty()) selected = emptySet() else onBack() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, null, tint = c.text) }
            Column(Modifier.weight(1f)) {
                Text(
                    if (selected.isEmpty()) stringResource(R.string.media_vault) else stringResource(R.string.n_selected, selected.size),
                    style = Zura.type.headline, color = c.text,
                )
                val total = (if (selected.isEmpty()) list else list.filter { key(it) in selected }).sumOf { it.m.media?.size ?: 0 }
                Text(fmt.size(total), style = Zura.type.caption, color = c.textMuted)
            }
            if (selected.isNotEmpty()) {
                IconButton(onClick = { selected = if (selected.size == list.size) emptySet() else list.map(::key).toSet() }) { Icon(Icons.Default.Check, null, tint = c.text) }
                IconButton(onClick = { haptics.tick(); confirm = true }) { Icon(Icons.Default.Delete, null, tint = c.danger) }
            }
        }
        // Filters + sort
        LazyRow(contentPadding = PaddingValues(horizontal = 14.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(Kind.entries.toList()) { k ->
                val count = all.orEmpty().count { k.matches(it) }
                Chip("${label(k)} · ${fmt.count(count)}", k == kind) { kind = k; selected = emptySet() }
            }
        }
        Spacer(Modifier.height(8.dp))
        LazyRow(contentPadding = PaddingValues(horizontal = 14.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(Sort.entries.toList()) { s -> Chip(sortLabel(s), s == sort, small = true) { sort = s } }
        }
        Spacer(Modifier.height(10.dp))

        when {
            all == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = c.accent) }
            list.isEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(stringResource(R.string.vault_empty), style = Zura.type.label, color = c.textMuted)
            }
            else -> LazyVerticalGrid(
                GridCells.Adaptive(110.dp),
                contentPadding = PaddingValues(start = 10.dp, end = 10.dp, bottom = 40.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier.navigationBarsPadding(),
            ) {
                items(list, key = ::key, span = { i -> if (i.m.kind == MsgKind.IMAGE || i.m.kind == MsgKind.VIDEO) GridItemSpan(1) else GridItemSpan(maxLineSpan) }) { i ->
                    val k = key(i)
                    val sel = k in selected
                    val click = {
                        if (selected.isNotEmpty()) selected = if (sel) selected - k else selected + k
                        else if (i.m.kind == MsgKind.IMAGE || i.m.kind == MsgKind.VIDEO) onOpenMedia(i.chatId, i.m.id)
                        else scope.launch { i.m.media?.let { runCatching { ctx.startActivity(g.exporter.shareOut(i.chatId, it)) } } }
                    }
                    val mod = Modifier.combinedClickable(onClick = { click() }, onLongClick = { haptics.heavy(); selected = selected + k })
                    if (i.m.kind == MsgKind.IMAGE || i.m.kind == MsgKind.VIDEO) Tile(i, sel, fmt, mod)
                    else FileRow(i, sel, fmt, mod)
                }
            }
        }
    }

    if (confirm) ConfirmSheet(
        title = stringResource(R.string.vault_delete_q, selected.size),
        message = stringResource(R.string.vault_delete_hint),
        actions = listOf(ConfirmAction(stringResource(R.string.delete), danger = true) {
            confirm = false
            val doomed = list.filter { key(it) in selected }
            selected = emptySet()
            scope.launch {
                doomed.forEach { it.m.media?.let(g.images::evict) }
                doomed.groupBy { it.chatId }.forEach { (chatId, items) -> g.chats.deleteForMe(chatId, items.map { it.m.id }) }
                reload++
            }
        }),
        cancelLabel = stringResource(R.string.cancel),
        onDismiss = { confirm = false },
    )
}

@Composable
private fun label(k: Kind) = stringResource(
    when (k) {
        Kind.ALL -> R.string.vault_all
        Kind.PHOTOS -> R.string.vault_photos
        Kind.VIDEOS -> R.string.vault_videos
        Kind.AUDIO -> R.string.vault_audio
        Kind.FILES -> R.string.vault_files
    },
)

@Composable
private fun sortLabel(s: Sort) = stringResource(
    when (s) {
        Sort.NEWEST -> R.string.sort_newest
        Sort.OLDEST -> R.string.sort_oldest
        Sort.LARGEST -> R.string.sort_largest
    },
)

@Composable
private fun Chip(text: String, on: Boolean, small: Boolean = false, onClick: () -> Unit) {
    val c = Zura.colors
    Box(
        Modifier.clip(CircleShape).background(if (on) c.accent else c.surfaceHigh).clickable(onClick = onClick)
            .padding(horizontal = if (small) 12.dp else 14.dp, vertical = if (small) 6.dp else 8.dp),
    ) { Text(text, style = if (small) Zura.type.caption else Zura.type.label, color = if (on) c.onAccent else c.text) }
}

@Composable
private fun Tile(i: ChatRepository.MediaItem, sel: Boolean, fmt: ZuraFormat, modifier: Modifier) {
    val c = Zura.colors
    val g = LocalContext.current.graph
    val ref = i.m.media ?: return
    val preview = remember(ref.preview) { ref.preview?.let(Previews::decode)?.asImageBitmap() }
    val full by if (i.m.kind == MsgKind.IMAGE) rememberVaultImage(g.images, i.chatId, ref, 240) else remember { mutableStateOf(null) }
    Box(
        modifier.aspectRatio(1f).clip(RoundedCornerShape(12.dp)).background(c.surfaceHigh)
            .then(if (sel) Modifier.border(3.dp, c.accent, RoundedCornerShape(12.dp)) else Modifier),
    ) {
        (full ?: preview)?.let { Image(it, null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize()) }
        if (i.m.kind == MsgKind.VIDEO) Icon(Icons.Default.PlayArrow, null, tint = Color.White, modifier = Modifier.align(Alignment.Center).size(30.dp).clip(CircleShape).background(Color.Black.copy(alpha = 0.4f)))
        Text(
            fmt.size(ref.size), style = Zura.type.caption, color = Color.White,
            modifier = Modifier.align(Alignment.BottomStart).padding(4.dp).clip(CircleShape).background(Color.Black.copy(alpha = 0.45f)).padding(horizontal = 6.dp),
        )
        if (sel) Box(Modifier.align(Alignment.TopEnd).padding(6.dp).size(22.dp).clip(CircleShape).background(c.accent), contentAlignment = Alignment.Center) {
            Icon(Icons.Default.Check, null, tint = c.onAccent, modifier = Modifier.size(16.dp))
        }
    }
}

@Composable
private fun FileRow(i: ChatRepository.MediaItem, sel: Boolean, fmt: ZuraFormat, modifier: Modifier) {
    val c = Zura.colors
    val ref = i.m.media ?: return
    val audio = i.m.kind == MsgKind.VOICE || ref.mime.startsWith("audio/")
    Row(
        modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(if (sel) c.accent.copy(alpha = 0.16f) else c.surfaceHigh).padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(40.dp).clip(RoundedCornerShape(10.dp)).background(c.accent.copy(alpha = 0.14f)), contentAlignment = Alignment.Center) {
            if (audio) Text("♪", style = Zura.type.headline, color = c.accent) else Icon(ZuraIcons.File, null, tint = c.accent)
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                ref.originalName ?: stringResource(if (i.m.kind == MsgKind.VOICE) R.string.voice_message else R.string.file),
                style = Zura.type.label, color = c.text, maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            Text("${i.chatTitle} · ${fmt.listStamp(i.m.sentAt)}", style = Zura.type.caption, color = c.textMuted, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Text(fmt.size(ref.size), style = Zura.type.caption, color = c.textMuted)
        if (sel) Icon(Icons.Default.Check, null, tint = c.accent, modifier = Modifier.padding(start = 8.dp).size(18.dp))
    }
}
