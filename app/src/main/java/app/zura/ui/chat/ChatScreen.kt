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
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.systemBars
import androidx.compose.ui.zIndex
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
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Close
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
import dev.chrisbanes.haze.hazeSource
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
fun ChatScreen(chatId: String, onBack: () -> Unit, onOpenMedia: (String) -> Unit, onOpenProfile: (String) -> Unit = {}) {
    // This chat's own bubble colours / Studio style (chosen in its profile) over the app theme.
    app.zura.ui.theme.ChatStyle(chatId) { ChatScreenContent(chatId, onBack, onOpenMedia, onOpenProfile) }
}

@Composable
private fun ChatScreenContent(chatId: String, onBack: () -> Unit, onOpenMedia: (String) -> Unit, onOpenProfile: (String) -> Unit) {
    val ctx = LocalContext.current
    val g = ctx.graph
    // Keyed by the account session too: the view model outlives screens (activity scope), and one
    // made for a session that was since re-opened kept showing that closed session's storage —
    // new reactions / messages appeared only after restarting the app.
    // (+ the chat's incarnation: after it was deleted, a new view model — never the one holding the old chat.)
    val vm = viewModel(key = "${System.identityHashCode(g.s)}:$chatId:${g.vault.incarnation(chatId)}") { ChatViewModel(g, chatId) }
    // A contact's chat that was deleted: opened again from People / their profile → give it back
    // its name and photo (it is recreated empty).
    LaunchedEffect(chatId) {
        if (g.vault.metaIndex.value[chatId] == null) g.s.contacts.contacts.value
            .firstOrNull { app.zura.data.ContactsRepository.dmChatId(g.s.uid, it.profile.id) == chatId }
            ?.let { g.s.contacts.ensureChatWith(it.profile.id) }
    }
    val items by vm.items.collectAsStateWithLifecycle()
    val meta by vm.meta.collectAsStateWithLifecycle()
    val typing by vm.peerTyping.collectAsStateWithLifecycle()
    val locale = LocalConfiguration.current.locales[0]
    val fmt = remember(locale) { ZuraFormat(locale) }
    val haptics = rememberHaptics()
    val list = rememberLazyListState()
    val scope = rememberCoroutineScope()
    var confirmDel by remember { mutableStateOf<Message?>(null) }
    val top = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()

    // This chat is on screen: no notifications for it, and clear any already shown.
    androidx.compose.runtime.DisposableEffect(chatId) {
        app.zura.push.Push.openChatId = chatId
        g.s.notifier.clear(chatId)
        onDispose { if (app.zura.push.Push.openChatId == chatId) app.zura.push.Push.openChatId = null }
    }

    // Locked chat: nothing of it is composed until the user proves it's them.
    val revealed by g.chats.lockedRevealed.collectAsStateWithLifecycle()
    if (meta?.locked == true && !revealed) {
        LockedChatGate(onBack) { g.chats.lockedRevealed.value = true }
        return
    }

    // Disappearing messages: no screenshots in this chat; read "after reading" messages vanish on leave.
    val timerOn = meta?.disappearMs != null
    androidx.compose.runtime.DisposableEffect(timerOn) {
        g.settings.chatSecure.value = timerOn
        onDispose { g.settings.chatSecure.value = false }
    }
    androidx.compose.runtime.DisposableEffect(chatId) {
        g.s.scope.launch { g.chats.expire(chatId) }
        onDispose { g.s.scope.launch { g.chats.expire(chatId, leaving = true) } }
    }

    // Android 14+: a screenshot of this chat is announced to the other side (a line in the chat
    // and a notification), like other messengers. With "Block screenshots" on, none can be taken.
    if (android.os.Build.VERSION.SDK_INT >= 34) {
        androidx.compose.runtime.DisposableEffect(chatId) {
            val unregister = ctx.findActivity()?.let { a ->
                watchScreenshots(a) { if (g.chats.peersOf(chatId).isNotEmpty()) g.s.scope.launch { g.chats.logEvent(chatId, app.zura.data.ChatRepository.EVENT_SCREENSHOT) } }
            }
            onDispose { unregister?.invoke() }
        }
    }

    // Read receipts fire while the chat is on screen and resumed.
    LifecycleResumeEffect(items.size) {
        vm.markRead()
        onPauseOrDispose { }
    }
    // A message was sent or received → always bring the newest into view, even after scrolling up
    // (far away: jump close first, then glide the last bit).
    LaunchedEffect(items.firstOrNull()?.key) {
        if (list.firstVisibleItemIndex > 12) list.scrollToItem(6)
        list.animateScrollToItem(0)
    }

    val byId = remember(items) { items.filterIsInstance<ChatItem.Bubble>().associateBy({ it.m.id }, { it.m }) }

    // Real frosted glass: the message list is blurred live behind the header and the composer.
    val haze = remember { dev.chrisbanes.haze.HazeState() }
    val showRead by vm.showReadTicks.collectAsStateWithLifecycle()
    val peerFace = remember(meta?.title, meta?.avatarHue, meta?.avatarPath, meta?.peerIds?.size) {
        meta?.takeIf { it.peerIds.size == 1 }?.let { PeerFace(it.title, it.avatarHue, it.avatarPath) }
    }
    // Tap on a reply's quote → scroll to the original and flash it.
    var flashId by remember { androidx.compose.runtime.mutableStateOf<String?>(null) }
    val itemsNow by androidx.compose.runtime.rememberUpdatedState(items)
    val jumpTo: (String) -> Unit = remember {
        { id ->
            val index = itemsNow.indexOfFirst { it.key == id }
            if (index >= 0) scope.launch {
                flashId = null
                // (A negative offset made the list overshoot and bounce back — the "shake".)
                if (list.layoutInfo.visibleItemsInfo.none { it.index == index }) {
                    // Far away: jump near it first (a long fling stutters), then glide the rest.
                    if (kotlin.math.abs(index - list.firstVisibleItemIndex) > 12) list.scrollToItem((index - 3).coerceAtLeast(0))
                    list.animateScrollToItem(index)
                }
                // Then centre it with one smooth motion.
                list.layoutInfo.visibleItemsInfo.firstOrNull { it.index == index }?.let { info ->
                    val li = list.layoutInfo
                    val delta = (info.offset + info.size / 2) - (li.viewportStartOffset + li.viewportEndOffset) / 2
                    if (kotlin.math.abs(delta) > 8) list.animateScrollBy(delta.toFloat(), androidx.compose.animation.core.tween(320, easing = androidx.compose.animation.core.FastOutSlowInEasing))
                }
                flashId = id
            }
        }
    }
    androidx.compose.runtime.CompositionLocalProvider(app.zura.ui.components.LocalHaze provides haze, LocalShowReadTicks provides showRead, LocalTranslations provides vm.translations, LocalRetranslate provides vm::translate,
        LocalSelection provides Selection(vm.selected.toSet(), vm::toggleSelect), LocalJumpTo provides jumpTo, LocalFlashId provides flashId,
        LocalPeerFace provides peerFace) {
    Box(Modifier.fillMaxSize()) {
        // Theme Studio wallpaper, dimmed toward the theme background so messages stay readable.
        app.zura.ui.theme.LocalWallpaper.current?.let { (img, dim) ->
            androidx.compose.foundation.Image(img, null, contentScale = androidx.compose.ui.layout.ContentScale.Crop, modifier = Modifier.fillMaxSize())
            Box(Modifier.fillMaxSize().background(Zura.colors.bg.copy(alpha = dim / 100f)))
        }
        // First visit / empty chat: a calm welcome with what privacy this chat has.
        val historyLoaded by vm.loaded.collectAsStateWithLifecycle()
        if (!historyLoaded) app.zura.ui.components.SkeletonBubbles(
            app.zura.ui.components.shimmerBrush(), Modifier.fillMaxSize().statusBarsPadding().padding(top = 76.dp, bottom = 110.dp),
        )
        else if (items.isEmpty()) {
            val notes = meta?.peerIds.isNullOrEmpty()
            Column(
                Modifier.align(Alignment.Center).padding(horizontal = 32.dp).padding(bottom = 80.dp)
                    .glass(RoundedCornerShape(24.dp)).padding(22.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    if (notes) stringResource(R.string.welcome_notes_title) else stringResource(R.string.welcome_dm_title, meta?.title.orEmpty()),
                    style = Zura.type.headline, color = Zura.colors.text, textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    stringResource(if (notes) R.string.welcome_notes_body else R.string.welcome_dm_body),
                    style = Zura.type.label, color = Zura.colors.textMuted, textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                )
            }
        }
        Column(Modifier.fillMaxSize()) {
            LazyColumn(
                state = list,
                reverseLayout = true, // newest at the bottom, anchored; no scroll jumps on insert
                contentPadding = PaddingValues(top = top + 72.dp, bottom = 8.dp),
                modifier = Modifier.weight(1f).fillMaxWidth().then(Modifier.hazeSourceCompat(haze)),
            ) {
                items(items, key = { it.key }, contentType = { if (it is ChatItem.Day) 0 else (it as ChatItem.Bubble).m.kind.ordinal + 1 }) { item ->
                    when (item) {
                        is ChatItem.Day -> DayChip(fmt.daySeparator(ctx, item.epochMs))
                        is ChatItem.Bubble -> BubbleRow(
                            item, fmt, haptics, resolve = byId::get,
                            onReply = { if (vm.pending.isEmpty()) { vm.replyTo = it; vm.editing = null } }, // no replies while attachments wait
                            onLongPress = { vm.focused = it },
                            onDoubleTap = { vm.react(it, "❤️") },
                            onOpenMedia = { msg ->
                                if (msg.kind == app.zura.core.model.MsgKind.FILE) scope.launch {
                                    val ref = msg.media ?: return@launch
                                    runCatching { ctx.startActivity(g.exporter.openIntent(chatId, ref)) }
                                        .onFailure { Toast.makeText(ctx, R.string.no_app_to_open, Toast.LENGTH_SHORT).show() }
                                } else onOpenMedia(msg.id)
                            },
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
            // Blocked either way → no composer: say so (and offer Unblock to the one who blocked).
            val peer = meta?.peerIds?.singleOrNull()
            val contactsList by g.s.contacts.contacts.collectAsStateWithLifecycle()
            val blockedMe by g.s.contacts.blockedMe.collectAsStateWithLifecycle()
            val iBlocked = peer != null && contactsList.any { it.profile.id == peer && it.blocked }
            when {
                peer != null && iBlocked -> BlockedBar(stringResource(R.string.you_blocked, meta?.title.orEmpty()), stringResource(R.string.unblock)) {
                    scope.launch { g.s.setBlocked(peer, false) }
                }
                peer != null && peer in blockedMe -> BlockedBar(stringResource(R.string.cant_message), null) {}
                else -> Composer(vm, fmt, haptics)
            }
        }

        // Glass header
        val peerOnline by vm.peerOnline.collectAsStateWithLifecycle()
        val peerSeen by vm.peerLastSeen.collectAsStateWithLifecycle()
        val callVoice = rememberCallPermissions(false) { ok -> if (ok) vm.call(false) }
        val callVideo = rememberCallPermissions(true) { ok -> if (ok) vm.call(true) }
        var showSafety by remember { mutableStateOf(false) }
        Column(Modifier.fillMaxWidth()) {
            Row(
                Modifier.fillMaxWidth().glass(RoundedCornerShape(bottomStart = 22.dp, bottomEnd = 22.dp)).padding(top = top).height(64.dp).padding(end = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, null, tint = Zura.colors.text) }
                Box(Modifier.clickable(enabled = vm.peerId != null) { vm.peerId?.let(onOpenProfile) }) {
                    app.zura.ui.components.UserAvatar(meta?.title ?: "", meta?.avatarHue ?: 0f, meta?.avatarPath, 40.dp)
                    if (peerOnline) Box(
                        Modifier.align(Alignment.BottomEnd).size(12.dp).clip(CircleShape).background(Zura.colors.bg).padding(2.dp)
                            .clip(CircleShape).background(androidx.compose.ui.graphics.Color(0xFF2EBD6B)),
                    )
                }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f).clickable(enabled = vm.peerId != null) { vm.peerId?.let(onOpenProfile) }) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(meta?.title ?: "", style = Zura.type.headline, color = Zura.colors.text, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                        if (meta?.disappearMs != null) Text(" ⏱", style = Zura.type.label, color = Zura.colors.accent)
                    }
                    AnimatedContent(
                        typing,
                        transitionSpec = { (slideInVertically { it } + fadeIn()) togetherWith (slideOutVertically { -it } + fadeOut()) },
                        label = "typing",
                    ) { t ->
                        if (t) TypingLabel() else Text(
                            when {
                                meta?.peerIds.isNullOrEmpty() -> stringResource(R.string.about_privacy).substringBefore('.')
                                peerOnline -> stringResource(R.string.online_now)
                                peerSeen != null -> fmt.lastSeen(ctx, peerSeen!!)
                                else -> "🔒 " + stringResource(R.string.e2ee_short)
                            },
                            style = Zura.type.caption, color = if (peerOnline) Zura.colors.accent else Zura.colors.textMuted,
                            maxLines = 1, overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                val hdrContacts by g.s.contacts.contacts.collectAsStateWithLifecycle()
                val hdrBlockedMe by g.s.contacts.blockedMe.collectAsStateWithLifecycle()
                val blockedEither = vm.peerId?.let { id -> id in hdrBlockedMe || hdrContacts.any { it.profile.id == id && it.blocked } } == true
                var walkieOpen by remember { mutableStateOf(false) }
                if (walkieOpen) WalkieSheet(vm, meta?.title.orEmpty()) { walkieOpen = false }
                if (vm.peerId != null && !blockedEither) {
                    val keyboard = androidx.compose.ui.platform.LocalSoftwareKeyboardController.current
                    val focusManager = androidx.compose.ui.platform.LocalFocusManager.current
                    // Walkie-talkie while typing: the keyboard goes away so the talk button is in reach.
                    IconButton(onClick = { haptics.tick(); keyboard?.hide(); focusManager.clearFocus(); walkieOpen = true }) { Icon(app.zura.ui.ZuraIcons.Walkie, stringResource(R.string.walkie), tint = Zura.colors.text) }
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
            val number by androidx.compose.runtime.produceState<String?>(null) { value = vm.safetyNumber() }
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

        // Multi-select bar (replaces the header while messages are selected).
        if (vm.selected.isNotEmpty()) {
            var confirmMany by remember { mutableStateOf(false) }
            androidx.activity.compose.BackHandler { vm.selected.clear() }
            Row(
                Modifier.fillMaxWidth().background(Zura.colors.surfaceHigh).padding(top = top).height(64.dp).padding(horizontal = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = { vm.selected.clear() }) { Icon(androidx.compose.material.icons.Icons.Default.Close, null, tint = Zura.colors.text) }
                Text("${vm.selected.size}", style = Zura.type.headline, color = Zura.colors.text, modifier = Modifier.weight(1f))
                val texts = byId.values.filter { it.id in vm.selected && !it.text.isNullOrBlank() }.sortedBy { it.sentAt }
                if (texts.isNotEmpty()) IconButton(onClick = {
                    ctx.getSystemService(android.content.ClipboardManager::class.java)
                        .setPrimaryClip(android.content.ClipData.newPlainText("zura", texts.joinToString("\n") { it.text.orEmpty() }))
                    Toast.makeText(ctx, R.string.copied, Toast.LENGTH_SHORT).show(); vm.selected.clear()
                }) { Icon(app.zura.ui.ZuraIcons.Copy, stringResource(R.string.copy_n), tint = Zura.colors.text) }
                IconButton(onClick = { haptics.tick(); confirmMany = true }) { Icon(androidx.compose.material.icons.Icons.Default.Delete, null, tint = Zura.colors.danger) }
            }
            if (confirmMany) {
                val allMine = vm.selected.all { id -> byId[id]?.senderId == vm.me }
                app.zura.ui.components.ConfirmSheet(
                    title = stringResource(R.string.delete_n_q, vm.selected.size),
                    message = if (allMine && vm.peerId != null) stringResource(R.string.delete_n_hint_mine) else stringResource(R.string.delete_message_hint),
                    actions = buildList {
                        if (allMine && vm.peerId != null) add(app.zura.ui.components.ConfirmAction(stringResource(R.string.action_delete_all), danger = true) { confirmMany = false; vm.deleteSelected(true) })
                        add(app.zura.ui.components.ConfirmAction(stringResource(R.string.action_delete_me), danger = true) { confirmMany = false; vm.deleteSelected(false) })
                    },
                    cancelLabel = stringResource(R.string.cancel),
                    onDismiss = { confirmMany = false },
                )
            }
        }

        vm.focused?.let { m ->
            val mine = m.senderId == vm.me
            ActionOverlay(
                m, mine, vm.me, haptics,
                onDismiss = { vm.focused = null },
                onReact = { vm.react(m, it); vm.focused = null },
                canReply = vm.pending.isEmpty(),
                onTranslate = { vm.translate(m); vm.focused = null },
                onSelect = { vm.selected.add(m.id); vm.focused = null },
                onReply = { if (vm.pending.isEmpty()) vm.replyTo = m; vm.focused = null },
                onEdit = { vm.startEdit(m); vm.focused = null },
                onCopy = { copy(ctx, m); vm.focused = null },
                onDownload = {
                    vm.focused = null
                    Toast.makeText(ctx, R.string.saving, Toast.LENGTH_SHORT).show()
                    scope.launch {
                        val ok = m.media?.let { g.exporter.saveToDevice(chatId, it) } == true
                        Toast.makeText(ctx, if (ok) R.string.save_done else R.string.save_failed, Toast.LENGTH_SHORT).show()
                    }
                },
                onDeleteMe = { confirmDel = m; vm.focused = null },
                onDeleteAll = { confirmDel = m; vm.focused = null },
            )
        }
        // Deleting always asks first (one sheet: "for everyone" when it's mine, and "for me").
        confirmDel?.let { m ->
            val mine = m.senderId == vm.me
            app.zura.ui.components.ConfirmSheet(
                title = stringResource(R.string.delete_message_q),
                message = stringResource(if (mine) R.string.delete_message_hint_mine else R.string.delete_message_hint),
                actions = buildList {
                    if (mine && vm.peerId != null) add(app.zura.ui.components.ConfirmAction(stringResource(R.string.action_delete_all), danger = true) { confirmDel = null; vm.deleteForAll(m) })
                    add(app.zura.ui.components.ConfirmAction(stringResource(R.string.action_delete_me), danger = true) { confirmDel = null; vm.deleteForMe(m) })
                },
                cancelLabel = stringResource(R.string.cancel),
                onDismiss = { confirmDel = null },
            )
        }
        // Photo editor (draw / text / rotate) for a staged photo, full screen above the chat — in
        // the activity's own (edge-to-edge) window, not a Dialog: some phones place a full-screen
        // dialog window below the status bar at the screen's full height, so its bottom (the
        // colours) ended up off-screen / under the navigation bar.
        val bars = androidx.compose.foundation.layout.WindowInsets.systemBars.asPaddingValues()
        vm.editingPhoto?.let { photo ->
            Box(Modifier.fillMaxSize().zIndex(10f)) {
                ImageEditor(photo, onDone = { edited -> vm.replaceAttachment(photo, edited); vm.editingPhoto = null }, onCancel = { vm.editingPhoto = null }, insets = bars)
            }
        }
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

private fun Modifier.hazeSourceCompat(state: dev.chrisbanes.haze.HazeState): Modifier = this.then(Modifier.hazeSource(state))


private tailrec fun android.content.Context.findActivity(): android.app.Activity? = when (this) {
    is android.app.Activity -> this
    is android.content.ContextWrapper -> baseContext.findActivity()
    else -> null
}

@androidx.annotation.RequiresApi(34)
private fun watchScreenshots(a: android.app.Activity, onShot: () -> Unit): () -> Unit {
    val cb = android.app.Activity.ScreenCaptureCallback { onShot() }
    runCatching { a.registerScreenCaptureCallback(a.mainExecutor, cb) }
    return { runCatching { a.unregisterScreenCaptureCallback(cb) } }
}

@Composable
private fun BlockedBar(text: String, action: String?, onAction: () -> Unit) {
    val c = Zura.colors
    var busy by remember { mutableStateOf(false) }
    Column(
        Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 16.dp, vertical = 14.dp)
            .glass(RoundedCornerShape(22.dp)).padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(text, style = Zura.type.label, color = c.textMuted, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
        if (action != null) {
            Spacer(Modifier.height(10.dp))
            Box(
                Modifier.clip(CircleShape).background(c.accent).clickable(enabled = !busy) { busy = true; onAction() }
                    .padding(horizontal = 18.dp, vertical = 9.dp),
                contentAlignment = Alignment.Center,
            ) {
                if (busy) androidx.compose.material3.CircularProgressIndicator(Modifier.size(18.dp), color = c.onAccent, strokeWidth = 2.dp)
                else Text(action, style = Zura.type.label, color = c.onAccent)
            }
        }
    }
}

@Composable
private fun LockedChatGate(onBack: () -> Unit, onUnlocked: () -> Unit) {
    val c = Zura.colors
    val auth = app.zura.security.rememberDeviceAuth()
    val ask = { auth { ok -> if (ok) onUnlocked() } }
    LaunchedEffect(Unit) { ask() }
    androidx.activity.compose.BackHandler(onBack = onBack)
    Column(
        Modifier.fillMaxSize().background(Zura.colors.bg).statusBarsPadding().navigationBarsPadding().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center,
    ) {
        Text("🔒", style = Zura.type.title)
        Spacer(Modifier.height(12.dp))
        Text(stringResource(R.string.locked_chat_title), style = Zura.type.headline, color = c.text)
        Spacer(Modifier.height(20.dp))
        Box(
            Modifier.clip(CircleShape).background(c.accent).clickable { ask() }.padding(horizontal = 26.dp, vertical = 12.dp),
        ) { Text(stringResource(R.string.unlock_chat), style = Zura.type.label, color = c.onAccent) }
    }
}

