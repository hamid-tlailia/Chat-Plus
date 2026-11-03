package app.zura.ui.chat

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Call
import androidx.compose.foundation.clickable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import app.zura.R
import app.zura.core.model.Message
import app.zura.graph
import app.zura.i18n.ZuraFormat
import app.zura.ui.components.Avatar
import app.zura.ui.components.glass
import app.zura.ui.components.rememberHaptics
import app.zura.ui.theme.Zura
import kotlinx.coroutines.launch

@Composable
fun ChatScreen(chatId: String, onBack: () -> Unit, onOpenMedia: (String) -> Unit) {
    val ctx = LocalContext.current
    val g = ctx.graph
    val vm = viewModel(key = chatId) { ChatViewModel(g, chatId) }
    val items by vm.items.collectAsStateWithLifecycle()
    val meta by vm.meta.collectAsStateWithLifecycle()
    val typing by vm.peerTyping.collectAsStateWithLifecycle()
    val locale = LocalConfiguration.current.locales[0]
    val fmt = remember(locale) { ZuraFormat(locale) }
    val haptics = rememberHaptics()
    val list = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val top = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()

    // Read receipts fire while the chat is on screen and resumed.
    LifecycleResumeEffect(items.size) {
        vm.markRead()
        onPauseOrDispose { }
    }
    // Stick to bottom when a new message arrives and we're already near it.
    LaunchedEffect(items.firstOrNull()?.key) { if (list.firstVisibleItemIndex <= 2) list.animateScrollToItem(0) }

    val byId = remember(items) { items.filterIsInstance<ChatItem.Bubble>().associateBy({ it.m.id }, { it.m }) }

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            LazyColumn(
                state = list,
                reverseLayout = true, // newest at the bottom, anchored; no scroll jumps on insert
                contentPadding = PaddingValues(top = top + 72.dp, bottom = 8.dp),
                modifier = Modifier.weight(1f).fillMaxWidth(),
            ) {
                items(items, key = { it.key }, contentType = { if (it is ChatItem.Day) 0 else (it as ChatItem.Bubble).m.kind.ordinal + 1 }) { item ->
                    when (item) {
                        is ChatItem.Day -> DayChip(fmt.daySeparator(ctx, item.epochMs))
                        is ChatItem.Bubble -> BubbleRow(
                            item, fmt, haptics, resolve = byId::get,
                            onReply = { vm.replyTo = it; vm.editing = null },
                            onLongPress = { vm.focused = it },
                            onDoubleTap = { vm.react(it, "❤️") },
                            onOpenMedia = { onOpenMedia(it.id) },
                            onRetry = { vm.retry(it) },
                            modifier = Modifier.animateItem(
                                fadeInSpec = spring(stiffness = 600f),
                                placementSpec = spring(dampingRatio = 0.85f, stiffness = 500f, visibilityThreshold = IntOffset(1, 1)),
                                fadeOutSpec = spring(stiffness = 900f),
                            ),
                        )
                    }
                }
            }
            Composer(vm, fmt, haptics)
        }

        // Glass header
        val peerOnline by vm.peerOnline.collectAsStateWithLifecycle()
        val callVoice = rememberCallPermissions(false) { ok -> if (ok) vm.call(false) }
        val callVideo = rememberCallPermissions(true) { ok -> if (ok) vm.call(true) }
        var showSafety by remember { mutableStateOf(false) }
        Column(Modifier.fillMaxWidth()) {
            Row(
                Modifier.fillMaxWidth().glass(RoundedCornerShape(bottomStart = 22.dp, bottomEnd = 22.dp), solid = 0.88f).padding(top = top).height(64.dp).padding(end = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, null, tint = Zura.colors.text) }
                Box(Modifier.clickable(enabled = vm.peerId != null) { showSafety = true }) {
                    app.zura.ui.components.UserAvatar(meta?.title ?: "", meta?.avatarHue ?: 0f, meta?.avatarPath, 40.dp)
                    if (peerOnline) Box(
                        Modifier.align(Alignment.BottomEnd).size(12.dp).clip(CircleShape).background(Zura.colors.bg).padding(2.dp)
                            .clip(CircleShape).background(androidx.compose.ui.graphics.Color(0xFF2EBD6B)),
                    )
                }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f).clickable(enabled = vm.peerId != null) { showSafety = true }) {
                    Text(meta?.title ?: "", style = Zura.type.headline, color = Zura.colors.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    AnimatedContent(
                        typing,
                        transitionSpec = { (slideInVertically { it } + fadeIn()) togetherWith (slideOutVertically { -it } + fadeOut()) },
                        label = "typing",
                    ) { t ->
                        if (t) TypingLabel() else Text(
                            when {
                                meta?.peerIds.isNullOrEmpty() -> stringResource(R.string.about_privacy).substringBefore('.')
                                peerOnline -> stringResource(R.string.online_now)
                                else -> "🔒 " + stringResource(R.string.e2ee_short)
                            },
                            style = Zura.type.caption, color = if (peerOnline) Zura.colors.accent else Zura.colors.textMuted,
                            maxLines = 1, overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                if (vm.peerId != null) {
                    IconButton(onClick = { haptics.tick(); callVideo(ctx) }) { Icon(app.zura.ui.ZuraIcons.VideoCam, stringResource(R.string.video_call), tint = Zura.colors.text) }
                    IconButton(onClick = { haptics.tick(); callVoice(ctx) }) { Icon(Icons.Default.Call, stringResource(R.string.voice_call), tint = Zura.colors.text) }
                }
            }
            // Security notice: the contact's identity key changed (new phone / reinstall — or an attack).
            if (meta?.keyChanged == true) {
                Row(
                    Modifier.padding(12.dp).fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Zura.colors.surfaceHigh)
                        .clickable { showSafety = true }.padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(app.zura.ui.ZuraIcons.Shield, null, tint = androidx.compose.ui.graphics.Color(0xFFFFC857), modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(10.dp))
                    Text(stringResource(R.string.key_changed, meta?.title.orEmpty()), style = Zura.type.label, color = Zura.colors.text)
                }
            }
        }

        if (showSafety) {
            val number = remember { vm.safetyNumber() }
            androidx.compose.material3.AlertDialog(
                onDismissRequest = { showSafety = false; vm.clearKeyChanged() },
                containerColor = Zura.colors.surfaceHigh,
                icon = { Icon(app.zura.ui.ZuraIcons.Shield, null, tint = Zura.colors.accent) },
                title = { Text(stringResource(R.string.safety_number), color = Zura.colors.text) },
                text = {
                    Column {
                        Text(
                            number?.chunked(18)?.joinToString("\n") ?: "—",
                            style = Zura.type.headline.copy(fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace, textDirection = androidx.compose.ui.text.style.TextDirection.Ltr),
                            color = Zura.colors.text,
                        )
                        Spacer(Modifier.height(12.dp))
                        Text(stringResource(R.string.verify_hint, meta?.title.orEmpty()), style = Zura.type.label, color = Zura.colors.textMuted)
                    }
                },
                confirmButton = { androidx.compose.material3.TextButton(onClick = { showSafety = false; vm.clearKeyChanged() }) { Text("OK", color = Zura.colors.accent) } },
            )
        }

        vm.focused?.let { m ->
            val mine = m.senderId == vm.me
            ActionOverlay(
                m, mine, vm.me, haptics,
                onDismiss = { vm.focused = null },
                onReact = { vm.react(m, it); vm.focused = null },
                onReply = { vm.replyTo = m; vm.focused = null },
                onEdit = { vm.startEdit(m); vm.focused = null },
                onCopy = { copy(ctx, m); vm.focused = null },
                onDownload = {
                    vm.focused = null
                    scope.launch {
                        val ok = m.media?.let { g.exporter.saveToDevice(chatId, it) } == true
                        if (ok) Toast.makeText(ctx, R.string.saved_to_gallery, Toast.LENGTH_SHORT).show()
                    }
                },
                onDeleteMe = { vm.deleteForMe(m); vm.focused = null },
                onDeleteAll = { vm.deleteForAll(m); vm.focused = null },
            )
        }
    }
}

@Composable
private fun DayChip(label: String) {
    Box(Modifier.fillMaxWidth().padding(vertical = 10.dp), contentAlignment = Alignment.Center) {
        Text(
            label, style = Zura.type.caption, color = Zura.colors.textMuted,
            modifier = Modifier.clip(CircleShape).background(Zura.colors.surface.copy(alpha = 0.7f)).padding(horizontal = 12.dp, vertical = 4.dp),
        )
    }
}

/** "typing…" with three dots breathing in sequence. */
@Composable
private fun TypingLabel() {
    val t = rememberInfiniteTransition(label = "dots")
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(3.dp)) {
        Text(stringResource(R.string.typing), style = Zura.type.caption, color = Zura.colors.accent)
        repeat(3) { i ->
            val a by t.animateFloat(0.25f, 1f, infiniteRepeatable(tween(420, delayMillis = i * 140), RepeatMode.Reverse), label = "d$i")
            Box(Modifier.size(4.dp).graphicsLayer { alpha = a }.clip(CircleShape).background(Zura.colors.accent))
        }
    }
}

private fun copy(ctx: Context, m: Message) {
    val cm = ctx.getSystemService(ClipboardManager::class.java)
    cm.setPrimaryClip(ClipData.newPlainText("zura", m.text.orEmpty()))
}
