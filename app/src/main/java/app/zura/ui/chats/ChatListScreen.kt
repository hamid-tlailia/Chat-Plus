package app.zura.ui.chats

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
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
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.offset
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import dev.chrisbanes.haze.hazeSource
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.zura.R
import app.zura.core.model.ChatMeta
import app.zura.core.model.MsgKind
import app.zura.graph
import app.zura.core.model.MsgStatus
import androidx.compose.material.icons.filled.Done
import androidx.compose.material.icons.filled.Warning
import app.zura.i18n.ZuraFormat
import app.zura.ui.components.Avatar
import app.zura.ui.components.glass
import app.zura.ui.components.pressScale
import app.zura.ui.components.rememberHaptics
import app.zura.ui.moments.MomentsRow
import app.zura.ui.theme.Zura
import kotlinx.coroutines.launch

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ChatListScreen(onOpen: (String) -> Unit, onSettings: () -> Unit, onMoments: (String) -> Unit, onPeople: () -> Unit = {}, onProfile: (String) -> Unit = {}) {
    val ctx = LocalContext.current
    val g = ctx.graph
    val chats by g.chats.chats.collectAsStateWithLifecycle()
    val moments by g.moments.groups.collectAsStateWithLifecycle()
    val locale = LocalConfiguration.current.locales[0]
    val fmt = remember(locale) { ZuraFormat(locale) }
    val scope = rememberCoroutineScope()
    val haptics = rememberHaptics()
    val list = rememberLazyListState()
    var confirmDelete by remember { mutableStateOf<ChatMeta?>(null) }
    val invites by g.s.contacts.invites.collectAsStateWithLifecycle()
    val incomingInvites = invites.count { it.incoming }
    val online by g.s.transport.online.collectAsStateWithLifecycle()
    val typing by g.chats.typingChats.collectAsStateWithLifecycle(emptySet())
    val revealed by g.chats.lockedRevealed.collectAsStateWithLifecycle()
    val chatsLoaded by g.chats.chatsLoaded.collectAsStateWithLifecycle()
    val skeleton = app.zura.ui.components.shimmerBrush()
    val deviceAuth = app.zura.security.rememberDeviceAuth()

    val pickMoment = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        g.scope.launch {
            val mime = ctx.contentResolver.getType(uri).orEmpty()
            val isVideo = mime.startsWith("video")
            val ref = if (isVideo) g.videoCompressor.import(app.zura.data.MomentsRepository.STORE, uri)
            else g.imageCompressor.import(app.zura.data.MomentsRepository.STORE, uri, maxEdge = 1920)
            g.moments.post(ref, if (isVideo) MsgKind.VIDEO else MsgKind.IMAGE, null)
        }
    }

    // Title collapses into the glass bar as the list scrolls (pure graphicsLayer, no relayout).
    val collapsed by remember { derivedStateOf { list.firstVisibleItemIndex > 0 || list.firstVisibleItemScrollOffset > 40 } }
    val top = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()

    val haze = remember { dev.chrisbanes.haze.HazeState() }
    androidx.compose.runtime.CompositionLocalProvider(app.zura.ui.components.LocalHaze provides haze) {
    Box(Modifier.fillMaxSize()) {
        LazyColumn(
            state = list,
            contentPadding = PaddingValues(top = top + 64.dp, bottom = 120.dp),
            modifier = Modifier.fillMaxSize().hazeSource(haze),
        ) {
            item(key = "title") {
                Text(
                    stringResource(R.string.chats), style = Zura.type.title, color = Zura.colors.text,
                    modifier = Modifier.padding(start = 20.dp, bottom = 14.dp).graphicsLayer { alpha = if (collapsed) 0f else 1f },
                )
            }
            item(key = "moments") {
                MomentsRow(
                    moments, myName = stringResource(R.string.your_moment),
                    onAdd = { pickMoment.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageAndVideo)) },
                    onOpen = onMoments,
                )
                Spacer(Modifier.height(10.dp))
            }
            // Locked chats live behind one row; revealed after fingerprint / face / PIN.
            val lockedCount = chats.count { it.locked }
            if (lockedCount > 0) item(key = "locked") {
                Row(
                    Modifier.fillMaxWidth().clickable {
                        if (revealed) g.chats.lockedRevealed.value = false
                        else deviceAuth { ok -> if (ok) g.chats.lockedRevealed.value = true }
                    }.padding(horizontal = 18.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(Modifier.size(52.dp).clip(CircleShape).background(Zura.colors.surfaceHigh), contentAlignment = Alignment.Center) { Text("🔒", style = Zura.type.headline) }
                    Spacer(Modifier.width(14.dp))
                    Text(stringResource(R.string.locked_chats), style = Zura.type.headline, color = Zura.colors.text, modifier = Modifier.weight(1f))
                    Text(if (revealed) "▲" else fmt.count(lockedCount), style = Zura.type.label, color = Zura.colors.textMuted)
                }
            }
            // One-time index build after an update: elegant placeholders instead of an empty list.
            items(chats.filter { !it.locked || revealed }, key = { it.id }, contentType = { "chat" }) { c ->
                SwipeToPin(c.pinned, onToggle = { haptics.confirm(); g.scope.launch { runCatching { g.chats.setPinned(c.id, !c.pinned) } } }, modifier = Modifier.animateItem()) {
                    ChatRow(
                        c, fmt, typing = c.id in typing,
                        onClick = { onOpen(c.id) },
                        onLongClick = { haptics.heavy(); confirmDelete = c },
                        onAvatar = c.peerIds.singleOrNull()?.let { peer -> { onProfile(peer) } },
                        modifier = Modifier,
                    )
                }
            }
            // Index still being built (first start after an update): placeholders BELOW what's known,
            // so a lone "Saved notes" never looks like the whole list.
            if (!chatsLoaded) items(5, key = { "sk$it" }) { app.zura.ui.components.SkeletonRow(skeleton) }
        }

        // Floating glass top bar
        Row(
            Modifier.fillMaxWidth().glass(RoundedCornerShape(bottomStart = 22.dp, bottomEnd = 22.dp))
                .padding(top = top).height(56.dp).padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val me by g.s.contacts.myProfile.collectAsStateWithLifecycle()
            Box(Modifier.padding(start = 8.dp).clip(CircleShape).clickable { onProfile(g.s.uid) }) {
                app.zura.ui.components.UserAvatar(me?.displayName ?: "", (g.s.uid.hashCode() and 0xFFFF) % 360f, me?.avatarPath, 36.dp)
            }
            Text(
                stringResource(R.string.app_name), style = Zura.type.headline, color = Zura.colors.text,
                modifier = Modifier.padding(start = 12.dp).weight(1f),
            )
            if (!online) Text(stringResource(R.string.offline_banner), style = Zura.type.caption, color = Zura.colors.textMuted, modifier = Modifier.padding(end = 6.dp))
            Box {
                IconButton(onClick = onPeople) { Icon(app.zura.ui.ZuraIcons.People, stringResource(R.string.contacts), tint = Zura.colors.text) }
                if (incomingInvites > 0) Box(
                    Modifier.align(Alignment.TopEnd).padding(6.dp).size(18.dp).clip(CircleShape).background(Zura.colors.danger),
                    contentAlignment = Alignment.Center,
                ) { Text(fmt.count(incomingInvites), style = Zura.type.caption, color = androidx.compose.ui.graphics.Color.White) }
            }
            IconButton(onClick = onSettings) { Icon(Icons.Default.Settings, stringResource(R.string.settings), tint = Zura.colors.text) }
        }

        // New chat button
        val fab = remember { MutableInteractionSource() }
        AnimatedVisibility(
            visible = !list.isScrollInProgress, enter = scaleIn(), exit = scaleOut(),
            modifier = Modifier.align(Alignment.BottomEnd).navigationBarsPadding().padding(20.dp),
        ) {
            Box(
                Modifier.size(58.dp).pressScale(fab).clip(CircleShape).background(Zura.colors.accent)
                    .combinedClickable(fab, null, onClick = { haptics.tick(); onPeople() }),
                contentAlignment = Alignment.Center,
            ) { Icon(Icons.Default.Add, null, tint = Zura.colors.onAccent) }
        }
    }
    }

    confirmDelete?.let { c ->
        app.zura.ui.components.ConfirmSheet(
            title = stringResource(R.string.delete_chat),
            message = stringResource(R.string.delete_chat_confirm),
            actions = listOf(app.zura.ui.components.ConfirmAction(stringResource(R.string.delete), danger = true) {
                confirmDelete = null
                scope.launch { g.chats.deleteChat(c.id) }
            }),
            cancelLabel = stringResource(R.string.cancel),
            onDismiss = { confirmDelete = null },
        )
    }

}

/**
 * Swipe a chat to the LEFT to pin / unpin it: a pin badge appears on the right edge; past the
 * threshold it ticks, and letting go toggles. Short drags spring back without doing anything.
 */
@Composable
private fun SwipeToPin(pinned: Boolean, onToggle: () -> Unit, modifier: Modifier, content: @Composable () -> Unit) {
    val scope = rememberCoroutineScope()
    val haptics = rememberHaptics()
    val threshold = with(androidx.compose.ui.platform.LocalDensity.current) { 84.dp.toPx() }
    val drag = remember { androidx.compose.animation.core.Animatable(0f) } // ≤ 0 = dragged left
    var armed by remember { mutableStateOf(false) }
    val col = Zura.colors
    Box(
        modifier.fillMaxWidth().pointerInput(pinned) {
            detectHorizontalDragGestures(
                onDragEnd = {
                    if (armed) onToggle()
                    armed = false
                    scope.launch { drag.animateTo(0f, app.zura.ui.components.ZuraMotion.Snappy) }
                },
                onDragCancel = { armed = false; scope.launch { drag.animateTo(0f, app.zura.ui.components.ZuraMotion.Snappy) } },
            ) { change, dx ->
                val next = (drag.value + dx * if (-drag.value > threshold) 0.35f else 1f).coerceIn(-threshold * 1.6f, 0f)
                if (!armed && -next >= threshold) { armed = true; haptics.tick() }
                if (armed && -next < threshold * 0.8f) armed = false
                change.consume()
                scope.launch { drag.snapTo(next) }
            }
        },
    ) {
        // The badge behind the row, on the physical right edge (swiping left uncovers it).
        Box(Modifier.matchParentSize().padding(horizontal = 22.dp), contentAlignment = if (androidx.compose.ui.platform.LocalLayoutDirection.current == androidx.compose.ui.unit.LayoutDirection.Rtl) Alignment.CenterStart else Alignment.CenterEnd) {
            val p = (-drag.value / threshold).coerceIn(0f, 1f)
            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.graphicsLayer { alpha = p; scaleX = 0.6f + 0.4f * p; scaleY = scaleX }) {
                Box(Modifier.size(40.dp).clip(CircleShape).background(if (armed) col.accent else col.surfaceHigh), contentAlignment = Alignment.Center) {
                    Text("📌", style = Zura.type.label)
                }
                Text(stringResource(if (pinned) R.string.unpin_chat else R.string.pin_chat), style = Zura.type.caption, color = col.textMuted, modifier = Modifier.padding(top = 3.dp))
            }
        }
        Box(Modifier.offset { androidx.compose.ui.unit.IntOffset(drag.value.toInt(), 0) }.background(col.bg.copy(alpha = if (drag.value != 0f) 0.92f else 0f))) { content() }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ChatRow(c: ChatMeta, fmt: ZuraFormat, typing: Boolean = false, onClick: () -> Unit, onLongClick: () -> Unit, modifier: Modifier, onAvatar: (() -> Unit)? = null) {
    val src = remember { MutableInteractionSource() }
    val col = Zura.colors
    Row(
        modifier.fillMaxWidth().pressScale(src, 0.98f)
            .combinedClickable(src, null, onClick = onClick, onLongClick = onLongClick)
            .padding(horizontal = 18.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Box(Modifier.clip(CircleShape).then(if (onAvatar != null) Modifier.clickable(onClick = onAvatar) else Modifier)) {
            app.zura.ui.components.UserAvatar(c.title, c.avatarHue, c.avatarPath)
        }
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text((if (c.locked) "🔒 " else "") + c.title, style = Zura.type.headline, color = col.text, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                if (c.pinned && c.peerIds.isNotEmpty()) Text("📌 ", style = Zura.type.caption)
                Text(fmt.listStamp(c.updatedAt), style = Zura.type.caption, color = if (c.unread > 0) col.accent else col.textMuted)
            }
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 2.dp)) {
                val preview = c.preview?.takeIf { it.isNotBlank() && c.previewKind != MsgKind.CALL && c.previewKind != MsgKind.EVENT } ?: when (c.previewKind) {
                    MsgKind.IMAGE -> stringResource(R.string.photo)
                    MsgKind.VIDEO -> stringResource(R.string.video)
                    MsgKind.VOICE -> stringResource(R.string.voice_message)
                    MsgKind.FILE -> stringResource(R.string.file)
                    MsgKind.CALL -> app.zura.ui.chat.parseCall(c.preview).let { stringResource(if (it.missed) (if (it.video) R.string.missed_video_call else R.string.missed_voice_call) else if (it.video) R.string.video_call else R.string.voice_call) }
                    MsgKind.EVENT -> app.zura.ui.chat.eventText(LocalContext.current, c.preview, c.previewMine)
                    MsgKind.TEXT -> if (c.peerIds.isEmpty()) stringResource(R.string.notes_hint) else ""
                }
                if (typing) {
                    Text(stringResource(R.string.typing), style = Zura.type.label.copy(fontWeight = null), color = col.accent, maxLines = 1, modifier = Modifier.weight(1f))
                } else {
                    // My own last message: its ticks first (sent / delivered / failed), like inside the chat.
                    val peer = c.peerIds.singleOrNull()
                    val st = c.previewStatus?.takeIf { c.previewMine && peer != null && c.previewKind != MsgKind.CALL && c.previewKind != MsgKind.EVENT }
                    // Fair play, as in the chat: if I hide my read receipts from them, I don't see theirs.
                    val g = LocalContext.current.graph
                    val rr by g.s.privacy.readReceipts.collectAsStateWithLifecycle()
                    val ghost by g.s.privacy.ghost.collectAsStateWithLifecycle()
                    val seen = st == MsgStatus.READ && rr && peer !in ghost
                    if (st != null && !seen) {
                        val (icon, tint) = when (st) {
                            MsgStatus.PENDING -> app.zura.ui.ZuraIcons.Clock to col.textMuted
                            MsgStatus.SENT -> androidx.compose.material.icons.Icons.Default.Done to col.textMuted
                            MsgStatus.FAILED -> androidx.compose.material.icons.Icons.Default.Warning to col.danger
                            else -> app.zura.ui.ZuraIcons.DoneAll to col.textMuted
                        }
                        Icon(icon, null, tint = tint, modifier = Modifier.padding(end = 4.dp).size(16.dp))
                    }
                    // My own last message reads "You: …" (not for call logs, which already say who).
                    val shown = if (c.previewMine && c.previewKind != MsgKind.CALL && c.previewKind != MsgKind.EVENT && preview.isNotEmpty()) stringResource(R.string.you_prefix, preview) else preview
                    Text(shown, style = Zura.type.label.copy(fontWeight = null), color = col.textMuted, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                    // Seen: their photo, small, at the end of the row (like Messenger).
                    if (seen) Box(Modifier.padding(start = 8.dp)) { app.zura.ui.components.UserAvatar(c.title, c.avatarHue, c.avatarPath, 18.dp) }
                }
                if (c.unread > 0) {
                    Box(
                        Modifier.padding(start = 8.dp).widthIn(min = 22.dp).height(22.dp).clip(CircleShape).background(col.accent).padding(horizontal = 6.dp),
                        contentAlignment = Alignment.Center,
                    ) { Text(fmt.count(c.unread), style = Zura.type.caption, color = col.onAccent) }
                }
            }
        }
    }
}
