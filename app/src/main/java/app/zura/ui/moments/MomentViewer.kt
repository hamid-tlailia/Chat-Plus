package app.zura.ui.moments

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.draw.blur
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.zura.core.model.MsgKind
import app.zura.data.MomentGroup
import app.zura.data.MomentsRepository
import app.zura.graph
import app.zura.i18n.ZuraFormat
import app.zura.media.Previews
import app.zura.media.rememberVaultImage
import app.zura.ui.components.Avatar
import app.zura.ui.components.ZuraMotion
import app.zura.ui.components.rememberHaptics
import app.zura.ui.media.VaultVideo
import kotlinx.coroutines.launch
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.clickable
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material.icons.filled.Delete
import app.zura.ui.components.appear

/**
 * Immersive Moments viewer: pager across people (cube-ish depth via graphicsLayer), segmented
 * progress per person, tap start/end edge = previous/next (mirrored for RTL), press-and-hold =
 * pause, swipe down = dismiss with spring.
 */
@Composable
fun MomentViewer(startOwner: String, onClose: () -> Unit) {
    // Light status-bar icons over the photo + shade, whatever the app theme (restored on close).
    val view = androidx.compose.ui.platform.LocalView.current
    androidx.compose.runtime.DisposableEffect(view) {
        val window = generateSequence(view.context) { (it as? android.content.ContextWrapper)?.baseContext }
            .filterIsInstance<android.app.Activity>().firstOrNull()?.window
        val bars = window?.let { androidx.core.view.WindowCompat.getInsetsController(it, view) }
        val before = bars?.isAppearanceLightStatusBars
        bars?.isAppearanceLightStatusBars = false
        onDispose { if (bars != null && before != null) bars.isAppearanceLightStatusBars = before }
    }
    val g = LocalContext.current.graph
    val groups by g.moments.groups.collectAsStateWithLifecycle()
    if (groups.isEmpty()) { LaunchedEffect(Unit) { onClose() }; return }
    val start = groups.indexOfFirst { it.ownerId == startOwner }.coerceAtLeast(0)
    val pager = rememberPagerState(start) { groups.size }
    val scope = rememberCoroutineScope()
    val dismiss = remember { Animatable(0f) }

    Box(
        Modifier.fillMaxSize().background(Color.Black)
            .graphicsLayer { translationY = dismiss.value; val s = 1f - (dismiss.value / 4000f).coerceIn(0f, 0.15f); scaleX = s; scaleY = s }
            .pointerInput(Unit) {
                detectVerticalDragGestures(
                    onDragEnd = { scope.launch { if (dismiss.value > 260f) onClose() else dismiss.animateTo(0f, ZuraMotion.Snappy) } },
                ) { _, dy -> scope.launch { dismiss.snapTo((dismiss.value + dy).coerceAtLeast(0f)) } }
            },
    ) {
        HorizontalPager(pager, beyondViewportPageCount = 0) { page ->
            val offset = (pager.currentPage - page) + pager.currentPageOffsetFraction
            Box(Modifier.fillMaxSize().graphicsLayer {
                // Depth parallax between people
                val f = offset.coerceIn(-1f, 1f)
                rotationY = f * -18f
                cameraDistance = 12f * density
                alpha = 1f - kotlin.math.abs(f) * 0.4f
            }) {
                GroupPlayer(
                    groups[page], active = pager.currentPage == page,
                    onNextGroup = { if (page < groups.lastIndex) scope.launch { pager.animateScrollToPage(page + 1) } else onClose() },
                    onPrevGroup = { if (page > 0) scope.launch { pager.animateScrollToPage(page - 1) } },
                    onClose = onClose,
                )
            }
        }
    }
}

@Composable
private fun GroupPlayer(group: MomentGroup, active: Boolean, onNextGroup: () -> Unit, onPrevGroup: () -> Unit, onClose: () -> Unit) {
    val g = LocalContext.current.graph
    val haptics = rememberHaptics()
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    val locale = LocalConfiguration.current.locales[0]
    var index by remember(group.ownerId) { mutableIntStateOf(group.items.indexOfFirst { it.status != app.zura.core.model.MsgStatus.READ }.coerceAtLeast(0)) }
    var paused by remember { mutableStateOf(false) }
    var replying by remember { mutableStateOf(false) } // typing a reply pauses the moment
    var showViewers by remember { mutableStateOf(false) } // my moment: viewer list open (pauses)
    var confirmDelete by remember { mutableStateOf(false) }
    val progress = remember(group.ownerId, index) { Animatable(0f) }
    val item = group.items.getOrNull(index) ?: return

    LaunchedEffect(active, index, paused, replying, showViewers, confirmDelete) {
        if (!active || paused || replying || showViewers || confirmDelete) return@LaunchedEffect
        g.moments.markSeen(item.id)
        val total = if (item.kind == MsgKind.VIDEO) item.media?.durationMs?.coerceIn(1000, 30_000) ?: 5000L else 5000L
        val remaining = ((1f - progress.value) * total).toInt()
        progress.animateTo(1f, tween(remaining, easing = LinearEasing))
        if (index < group.items.lastIndex) index++ else onNextGroup()
    }

    Box(
        Modifier.fillMaxSize().pointerInput(group.ownerId, rtl) {
            detectTapGestures(
                onPress = { paused = true; tryAwaitRelease(); paused = false },
                onTap = { o ->
                    val startSide = if (rtl) o.x > size.width * 0.7f else o.x < size.width * 0.3f
                    haptics.tick()
                    if (startSide) { if (index > 0) index-- else onPrevGroup() }
                    else { if (index < group.items.lastIndex) index++ else onNextGroup() }
                },
            )
        },
    ) {
        val ref = item.media
        if (ref != null) {
            if (item.kind == MsgKind.VIDEO && active) VaultVideo(MomentsRepository.STORE, item.id, ref, playing = !paused, Modifier.fillMaxSize())
            else {
                val preview = remember(ref.preview) { ref.preview?.let(Previews::decode)?.asImageBitmap() }
                val full by rememberVaultImage(g.images, MomentsRepository.STORE, ref, 1920)
                (full ?: preview)?.let { img ->
                    // The WHOLE photo (a landscape shot used to be cropped to a slice), over a dark,
                    // blurred copy of itself filling the screen — like other story apps.
                    val tall = img.height.toFloat() / img.width >= 1.6f
                    if (!tall) Image(
                        img, null, Modifier.fillMaxSize().then(if (android.os.Build.VERSION.SDK_INT >= 31) Modifier.blur(40.dp) else Modifier).graphicsLayer { alpha = 0.55f },
                        contentScale = ContentScale.Crop,
                    )
                    Image(img, null, Modifier.fillMaxSize(), contentScale = if (tall) ContentScale.Crop else ContentScale.Fit)
                }
            }
        }
        // A soft shade under the status bar and the header: readable even over a white photo.
        Box(
            Modifier.fillMaxWidth().height(160.dp)
                .background(androidx.compose.ui.graphics.Brush.verticalGradient(listOf(Color.Black.copy(alpha = 0.55f), Color.Transparent))),
        )

        Column(Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 10.dp, vertical = 8.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                group.items.forEachIndexed { i, _ ->
                    val f = when { i < index -> 1f; i == index -> progress.value; else -> 0f }
                    Box(Modifier.weight(1f).height(2.5.dp).clip(CircleShape).background(Color.White.copy(alpha = 0.3f))) {
                        Box(Modifier.fillMaxWidth(f).height(2.5.dp).background(Color.White))
                    }
                }
            }
            Row(Modifier.padding(top = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                app.zura.ui.components.UserAvatar(
                    group.name, group.hue,
                    if (group.mine) g.s.contacts.myProfile.value?.avatarPath else g.s.contacts.profile(group.ownerId)?.avatarPath, 36.dp,
                )
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(group.name, color = Color.White, style = app.zura.ui.theme.Zura.type.label)
                    Text(ZuraFormat(locale).time(item.sentAt), color = Color.White.copy(alpha = 0.7f), style = app.zura.ui.theme.Zura.type.caption)
                }
                IconButton(onClick = onClose) { Icon(Icons.Default.Close, null, tint = Color.White) }
            }
        }
        // Someone else's moment: react or reply — it reaches them as a message (with a notification).
        if (!group.mine) MomentReplyBar(group.ownerId, item, Modifier.align(Alignment.BottomCenter)) { replying = it }
        // My moment: who saw it, and delete.
        else MyMomentBar(item, Modifier.align(Alignment.BottomCenter), onViewers = { showViewers = true }, onDelete = { haptics.heavy(); confirmDelete = true })
        androidx.compose.animation.AnimatedVisibility(
            showViewers, Modifier.align(Alignment.BottomCenter),
            enter = androidx.compose.animation.slideInVertically { it } + androidx.compose.animation.fadeIn(),
            exit = androidx.compose.animation.slideOutVertically { it } + androidx.compose.animation.fadeOut(),
        ) { ViewersPanel(item) { showViewers = false } }
        if (confirmDelete) app.zura.ui.components.ConfirmSheet(
            title = androidx.compose.ui.res.stringResource(app.zura.R.string.moment_delete),
            message = androidx.compose.ui.res.stringResource(app.zura.R.string.moment_delete_hint),
            actions = listOf(app.zura.ui.components.ConfirmAction(androidx.compose.ui.res.stringResource(app.zura.R.string.delete), danger = true) {
                val id = item.id
                g.s.scope.launch { g.moments.delete(id) }
                if (group.items.size <= 1) onNextGroup() else if (index >= group.items.lastIndex) index = (index - 1).coerceAtLeast(0)
            }),
            cancelLabel = androidx.compose.ui.res.stringResource(app.zura.R.string.cancel),
            onDismiss = { confirmDelete = false },
        )
        item.text?.takeIf { it.isNotBlank() }?.let {
            Text(
                it, color = Color.White, style = app.zura.ui.theme.Zura.type.body.copy(textDirection = TextDirection.Content),
                modifier = Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(24.dp).padding(bottom = if (group.mine) 0.dp else 110.dp)
                    .clip(CircleShape).background(Color.Black.copy(alpha = 0.4f)).padding(horizontal = 14.dp, vertical = 8.dp),
            )
        }
    }
}

private val MOMENT_REACTIONS = listOf("❤️", "😂", "😮", "😢", "🔥", "👏")

/** Bottom of MY moment: "👁 12" (opens the list) and a delete button. */
@Composable
private fun MyMomentBar(item: app.zura.core.model.Message, modifier: Modifier, onViewers: () -> Unit, onDelete: () -> Unit) {
    val count = (item.seenBy.keys + item.reactions.keys).size
    val top = item.reactions.values.groupingBy { it }.eachCount().entries.sortedByDescending { it.value }.take(3).joinToString("") { it.key }
    val shown by androidx.compose.animation.core.animateIntAsState(count, androidx.compose.animation.core.tween(500), label = "views")
    Row(
        modifier.fillMaxWidth()
            .background(androidx.compose.ui.graphics.Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.55f))))
            .navigationBarsPadding().padding(horizontal = 18.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            Modifier.clip(CircleShape).background(Color.White.copy(alpha = 0.16f)).clickable(onClick = onViewers).padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("👁", fontSize = 16.sp)
            Spacer(Modifier.width(8.dp))
            Text(shown.toString(), color = Color.White, style = app.zura.ui.theme.Zura.type.headline)
            if (top.isNotEmpty()) { Spacer(Modifier.width(10.dp)); Text(top, fontSize = 16.sp) }
        }
        Spacer(Modifier.weight(1f))
        Box(
            Modifier.size(46.dp).clip(CircleShape).background(Color.White.copy(alpha = 0.16f)).clickable(onClick = onDelete),
            contentAlignment = Alignment.Center,
        ) { Icon(androidx.compose.material.icons.Icons.Default.Delete, androidx.compose.ui.res.stringResource(app.zura.R.string.moment_delete), tint = Color.White) }
    }
}

/** Who saw my moment, newest first (names from my contacts; nothing leaves the phone). */
@Composable
private fun ViewersPanel(item: app.zura.core.model.Message, onClose: () -> Unit) {
    val g = LocalContext.current.graph
    val fmt = ZuraFormat(LocalConfiguration.current.locales[0])
    // Everyone who saw it (or reacted — that's seeing too), newest first; their emoji by their name.
    val viewers = (item.seenBy.keys + item.reactions.keys).map { it to (item.seenBy[it] ?: 0L) }.sortedByDescending { it.second }
    Column(
        Modifier.fillMaxWidth().clip(androidx.compose.foundation.shape.RoundedCornerShape(topStart = 26.dp, topEnd = 26.dp))
            .background(Color(0xF0141418)).clickable(enabled = false) {}.navigationBarsPadding().padding(vertical = 14.dp),
    ) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("👁  " + androidx.compose.ui.res.stringResource(app.zura.R.string.moment_viewers) + " · " + fmt.count(viewers.size), color = Color.White, style = app.zura.ui.theme.Zura.type.headline, modifier = Modifier.weight(1f))
            IconButton(onClick = onClose) { Icon(Icons.Default.Close, null, tint = Color.White) }
        }
        if (viewers.isEmpty()) Text(androidx.compose.ui.res.stringResource(app.zura.R.string.moment_no_views), color = Color.White.copy(alpha = 0.6f), style = app.zura.ui.theme.Zura.type.label, modifier = Modifier.padding(20.dp))
        androidx.compose.foundation.lazy.LazyColumn(Modifier.heightIn(max = 340.dp)) {
            items(viewers.size, key = { viewers[it].first }) { i ->
                val (uid, at) = viewers[i]
                val p = g.s.contacts.profile(uid)
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp).appear(i),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    app.zura.ui.components.UserAvatar(p?.displayName ?: "", (uid.hashCode() and 0xFFFF) % 360f, p?.avatarPath, 40.dp)
                    Spacer(Modifier.width(12.dp))
                    Text(p?.displayName ?: g.moments.nameFor(uid) ?: "…", color = Color.White, style = app.zura.ui.theme.Zura.type.label, modifier = Modifier.weight(1f))
                    item.reactions[uid]?.let { Text(it, fontSize = 22.sp, modifier = Modifier.padding(end = 10.dp)) }
                    if (at > 0) Text(fmt.time(at), color = Color.White.copy(alpha = 0.6f), style = app.zura.ui.theme.Zura.type.caption)
                }
            }
        }
        Text(androidx.compose.ui.res.stringResource(app.zura.R.string.moment_viewers_privacy), color = Color.White.copy(alpha = 0.45f), style = app.zura.ui.theme.Zura.type.caption, modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp))
    }
}

@Composable
private fun MomentReplyBar(ownerId: String, item: app.zura.core.model.Message, modifier: Modifier, onTyping: (Boolean) -> Unit) {
    val ctx = LocalContext.current
    val g = ctx.graph
    val haptics = rememberHaptics()
    var text by remember(item.id) { mutableStateOf("") }
    var burst by remember { mutableStateOf<String?>(null) }
    val pop = remember { Animatable(0f) }
    LaunchedEffect(burst) { if (burst != null) { pop.snapTo(0f); pop.animateTo(1f, tween(700)); burst = null } }
    fun send(t: String) {
        if (t.isBlank()) return
        g.s.scope.launch {
            // A crisp 240 px thumbnail of the photo (≈10 KB) instead of the blurred placeholder.
            val thumb = if (item.kind == MsgKind.IMAGE) item.media?.let { ref ->
                runCatching {
                    g.images.load(MomentsRepository.STORE, ref, 240)?.asAndroidBitmap()?.let { bmp ->
                        val out = java.io.ByteArrayOutputStream()
                        bmp.compress(android.graphics.Bitmap.CompressFormat.JPEG, 72, out)
                        android.util.Base64.encodeToString(out.toByteArray(), android.util.Base64.NO_WRAP)
                    }
                }.getOrNull()
            } else null
            g.chats.replyToMoment(ownerId, item, t, thumb)
        }
        android.widget.Toast.makeText(ctx, app.zura.R.string.moment_sent, android.widget.Toast.LENGTH_SHORT).show()
    }
    Box(modifier.fillMaxWidth()) {
        burst?.let { e ->
            Text(e, fontSize = 64.sp, modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 140.dp)
                .graphicsLayer { translationY = -pop.value * 220f; alpha = 1f - pop.value; scaleX = 1f + pop.value; scaleY = scaleX })
        }
        // Pinned to the bottom: the flying emoji above makes this box taller for a moment, and the
        // bar used to ride up with it (and drop back) on every reaction.
        Column(
            Modifier.align(Alignment.BottomCenter).fillMaxWidth()
                .background(androidx.compose.ui.graphics.Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.55f))))
                .navigationBarsPadding().imePadding().padding(horizontal = 14.dp, vertical = 10.dp),
        ) {
            // An emoji reaches only them, next to my name in their viewers (no chat message).
            // Typing a reply (below) sends it to our chat.
            val mine = item.reactions[g.s.uid]
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                MOMENT_REACTIONS.forEach { e ->
                    val on = e == mine
                    val grow by androidx.compose.animation.core.animateFloatAsState(if (on) 1.18f else 1f, app.zura.ui.components.ZuraMotion.Bouncy, label = "react")
                    Text(
                        e, fontSize = 30.sp,
                        modifier = Modifier.graphicsLayer { scaleX = grow; scaleY = grow }.clip(CircleShape)
                            .background(if (on) Color.White.copy(alpha = 0.22f) else Color.Transparent)
                            .clickable { haptics.confirm(); burst = e; g.s.scope.launch { g.moments.react(ownerId, item.id, e) } }.padding(6.dp),
                    )
                }
            }
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                androidx.compose.foundation.text.BasicTextField(
                    text, { text = it; onTyping(it.isNotEmpty()) },
                    textStyle = app.zura.ui.theme.Zura.type.body.copy(color = Color.White, textDirection = TextDirection.Content),
                    cursorBrush = androidx.compose.ui.graphics.SolidColor(Color.White), maxLines = 3,
                    modifier = Modifier.weight(1f).clip(CircleShape).background(Color.White.copy(alpha = 0.16f))
                        .onFocusChanged { onTyping(it.isFocused) }.padding(horizontal = 16.dp, vertical = 12.dp),
                    decorationBox = { inner ->
                        if (text.isEmpty()) Text(androidx.compose.ui.res.stringResource(app.zura.R.string.moment_reply_hint), color = Color.White.copy(alpha = 0.7f), style = app.zura.ui.theme.Zura.type.body)
                        inner()
                    },
                )
                if (text.isNotBlank()) {
                    Spacer(Modifier.width(8.dp))
                    Box(
                        Modifier.size(46.dp).clip(CircleShape).background(Color.White).clickable { haptics.confirm(); send(text); text = ""; onTyping(false) },
                        contentAlignment = Alignment.Center,
                    ) { Icon(Icons.AutoMirrored.Filled.Send, null, tint = Color.Black) }
                }
            }
        }
    }
}
