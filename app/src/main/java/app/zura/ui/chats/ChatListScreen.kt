package app.zura.ui.chats

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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
fun ChatListScreen(onOpen: (String) -> Unit, onSettings: () -> Unit, onMoments: (String) -> Unit) {
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
    var creating by remember { mutableStateOf(false) }

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

    Box(Modifier.fillMaxSize()) {
        LazyColumn(
            state = list,
            contentPadding = PaddingValues(top = top + 64.dp, bottom = 120.dp),
            modifier = Modifier.fillMaxSize(),
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
            items(chats, key = { it.id }, contentType = { "chat" }) { c ->
                ChatRow(
                    c, fmt,
                    onClick = { onOpen(c.id) },
                    onLongClick = { haptics.heavy(); confirmDelete = c },
                    modifier = Modifier.animateItem(),
                )
            }
        }

        // Floating glass top bar
        Row(
            Modifier.fillMaxWidth().glass(RoundedCornerShape(bottomStart = 22.dp, bottomEnd = 22.dp))
                .padding(top = top).height(56.dp).padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                stringResource(R.string.app_name), style = Zura.type.headline, color = Zura.colors.text,
                modifier = Modifier.padding(start = 12.dp).weight(1f),
            )
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
                    .combinedClickable(fab, null, onClick = { haptics.tick(); creating = true }),
                contentAlignment = Alignment.Center,
            ) { Icon(Icons.Default.Add, null, tint = Zura.colors.onAccent) }
        }
    }

    confirmDelete?.let { c ->
        AlertDialog(
            onDismissRequest = { confirmDelete = null },
            containerColor = Zura.colors.surfaceHigh,
            title = { Text(stringResource(R.string.delete_chat), color = Zura.colors.text) },
            text = { Text(stringResource(R.string.delete_chat_confirm), color = Zura.colors.textMuted) },
            confirmButton = {
                TextButton(onClick = {
                    haptics.reject(); confirmDelete = null
                    scope.launch { g.chats.deleteChat(c.id) }
                }) { Text(stringResource(R.string.delete), color = Zura.colors.danger) }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = null }) { Text(stringResource(R.string.cancel), color = Zura.colors.text) } },
        )
    }

    if (creating) NewChatDialog(onDismiss = { creating = false }) { name, peer ->
        creating = false
        scope.launch { onOpen(g.chats.createChat(name, listOf(peer))) }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ChatRow(c: ChatMeta, fmt: ZuraFormat, onClick: () -> Unit, onLongClick: () -> Unit, modifier: Modifier) {
    val src = remember { MutableInteractionSource() }
    val col = Zura.colors
    Row(
        modifier.fillMaxWidth().pressScale(src, 0.98f)
            .combinedClickable(src, null, onClick = onClick, onLongClick = onLongClick)
            .padding(horizontal = 18.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Avatar(c.title, c.avatarHue)
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(c.title, style = Zura.type.headline, color = col.text, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                Text(fmt.listStamp(c.updatedAt), style = Zura.type.caption, color = if (c.unread > 0) col.accent else col.textMuted)
            }
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 2.dp)) {
                val preview = c.preview?.takeIf { it.isNotBlank() } ?: when (c.previewKind) {
                    MsgKind.IMAGE -> stringResource(R.string.photo)
                    MsgKind.VIDEO -> stringResource(R.string.video)
                    MsgKind.VOICE -> stringResource(R.string.voice_message)
                    MsgKind.FILE -> stringResource(R.string.file)
                    MsgKind.TEXT -> ""
                }
                Text(preview, style = Zura.type.label.copy(fontWeight = null), color = col.textMuted, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
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
