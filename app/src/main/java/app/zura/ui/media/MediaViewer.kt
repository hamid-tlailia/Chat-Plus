package app.zura.ui.media

import androidx.compose.material.icons.filled.Done
import android.view.SurfaceView
import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Text
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.aspectRatio
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.zura.core.model.MediaRef
import app.zura.core.model.MsgKind
import app.zura.graph
import app.zura.media.Previews
import app.zura.media.rememberVaultImage
import app.zura.ui.ZuraIcons
import app.zura.ui.components.ZuraMotion
import kotlinx.coroutines.launch

/**
 * Full-screen gallery of this chat's photos & videos: swipe left / right for the previous / next
 * one (an album's photos are neighbours), pinch / double-tap to zoom, swipe down to dismiss.
 */
@Composable
fun MediaViewer(chatId: String, messageId: String, onClose: () -> Unit) {
    val ctx = LocalContext.current
    val g = ctx.graph
    // First frame straight from the open chat (it is, we came from it): no empty black frame
    // while the message flow spins up on a background thread.
    val initial = remember(chatId) { runCatching { g.vault.chat(chatId).messages.value }.getOrDefault(emptyList()) }
    val msgs by remember(chatId) { g.chats.messages(chatId) }.collectAsStateWithLifecycle(initial)
    val gallery = remember(msgs) { msgs.filter { (it.kind == MsgKind.IMAGE || it.kind == MsgKind.VIDEO) && it.media != null && it.media?.isPlaceholder != true } }
    val start = remember { gallery.indexOfFirst { it.id == messageId }.coerceAtLeast(0) }
    val pager = androidx.compose.foundation.pager.rememberPagerState(initialPage = start) { gallery.size }
    val m = gallery.getOrNull(pager.currentPage) ?: msgs.firstOrNull { it.id == messageId }
    val ref = m?.media
    val scope = rememberCoroutineScope()
    val dismiss = remember { Animatable(0f) }
    var zoomed by remember { mutableStateOf(false) }

    Box(
        Modifier.fillMaxSize().background(Color.Black.copy(alpha = (1f - dismiss.value / 900f).coerceIn(0.3f, 1f)))
            .pointerInput(Unit) {
                detectVerticalDragGestures(
                    onDragEnd = { scope.launch { if (dismiss.value > 220f) onClose() else dismiss.animateTo(0f, ZuraMotion.Snappy) } },
                ) { _, dy -> if (!zoomed) scope.launch { dismiss.snapTo((dismiss.value + dy).coerceAtLeast(0f)) } }
            },
    ) {
        androidx.compose.foundation.pager.HorizontalPager(
            pager, Modifier.fillMaxSize().graphicsLayer { translationY = dismiss.value },
            userScrollEnabled = !zoomed, beyondViewportPageCount = 1, key = { gallery.getOrNull(it)?.id ?: it },
        ) { page ->
            val pm = gallery.getOrNull(page) ?: return@HorizontalPager
            MediaPage(chatId, pm, current = page == pager.currentPage) { z -> if (page == pager.currentPage) zoomed = z }
        }
        // "3 / 7" when there is more than one.
        if (gallery.size > 1) Text(
            "${pager.currentPage + 1} / ${gallery.size}", color = Color.White, style = app.zura.ui.theme.Zura.type.label,
            modifier = Modifier.align(Alignment.TopCenter).statusBarsPadding().padding(top = 14.dp)
                .clip(androidx.compose.foundation.shape.CircleShape).background(Color.Black.copy(alpha = 0.4f)).padding(horizontal = 12.dp, vertical = 4.dp),
        )
        Row(Modifier.fillMaxWidth().statusBarsPadding()) {
            IconButton(onClick = onClose) { Icon(Icons.Default.Close, null, tint = Color.White) }
            Spacer(Modifier.weight(1f))
            if (ref != null) {
                IconButton(onClick = { scope.launch { ctx.startActivity(g.exporter.shareOut(chatId, ref)) } }) { Icon(Icons.Default.Share, null, tint = Color.White) }
                // Save: a spinner while it copies, then ✓ — and it says where it went.
                var saving by remember { mutableStateOf(false) }
                var saved by remember(ref.fileName) { mutableStateOf(false) }
                IconButton(enabled = !saving, onClick = {
                    saving = true
                    scope.launch {
                        val ok = g.exporter.saveToDevice(chatId, ref)
                        saving = false; saved = ok
                        android.widget.Toast.makeText(ctx, if (ok) app.zura.R.string.save_done else app.zura.R.string.save_failed, android.widget.Toast.LENGTH_SHORT).show()
                    }
                }) {
                    androidx.compose.animation.Crossfade(when { saving -> 1; saved -> 2; else -> 0 }, label = "save") { st ->
                        when (st) {
                            1 -> androidx.compose.material3.CircularProgressIndicator(Modifier.size(22.dp), color = Color.White, strokeWidth = 2.dp)
                            2 -> Icon(Icons.Default.Done, null, tint = Color(0xFF6EFFC4))
                            else -> Icon(ZuraIcons.Download, null, tint = Color.White)
                        }
                    }
                }
            }
        }
    }
}

/** One photo / video of the gallery. Zoom only with two fingers or once zoomed in — a one-finger swipe goes to the pager. */
@Composable
private fun MediaPage(chatId: String, m: app.zura.core.model.Message, current: Boolean, onZoom: (Boolean) -> Unit) {
    val g = LocalContext.current.graph
    val ref = m.media ?: return
    var scale by remember { mutableFloatStateOf(1f) }
    var pan by remember { mutableStateOf(Offset.Zero) }
    LaunchedEffect(current) { if (!current) { scale = 1f; pan = Offset.Zero } }
    LaunchedEffect(scale, current) { if (current) onZoom(scale > 1.01f) }
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        if (m.kind == MsgKind.VIDEO) {
            VaultVideo(chatId, m.id, ref, playing = current, Modifier.fillMaxWidth().aspectRatio(if (ref.height > 0) ref.width.toFloat() / ref.height else 16f / 9))
            if (current) VideoControls(m.id, ref.durationMs)
        } else {
            val preview = remember(ref.preview) { ref.preview?.let(Previews::decode)?.asImageBitmap() }
            // Paint instantly with the copy already decoded for the chat bubble, then sharpen.
            val mid = remember(ref.fileName) { g.images.cached(ref, 720) ?: g.images.cached(ref, 480) }
            val full by rememberVaultImage(g.images, chatId, ref, 1920)
            (full ?: mid ?: preview)?.let {
                Image(
                    it, null, contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxSize()
                        .pointerInput(Unit) {
                            awaitEachGesture {
                                awaitFirstDown(requireUnconsumed = false)
                                do {
                                    val ev = awaitPointerEvent()
                                    if (ev.changes.count { it.pressed } > 1 || scale > 1.01f) {
                                        val z = ev.calculateZoom()
                                        val p = ev.calculatePan()
                                        scale = (scale * z).coerceIn(1f, 5f)
                                        pan = if (scale > 1.01f) pan + p else Offset.Zero
                                        ev.changes.forEach { c -> if (c.position != c.previousPosition) c.consume() }
                                    }
                                } while (ev.changes.any { it.pressed })
                            }
                        }
                        .pointerInput(Unit) { detectTapGestures(onDoubleTap = { scale = if (scale > 1f) 1f else 2.5f; pan = Offset.Zero }) }
                        .graphicsLayer { scaleX = scale; scaleY = scale; translationX = pan.x; translationY = pan.y },
                )
            }
        }
    }
}

/** Video straight from the encrypted vault via the shared ExoPlayer — rendered to a SurfaceView (overlay plane, lowest power). */
@Composable
fun VaultVideo(chatId: String, key: String, ref: MediaRef, playing: Boolean, modifier: Modifier = Modifier) {
    val g = LocalContext.current.graph
    LaunchedEffect(key) { if (g.playback.state.value.key != key) g.playback.toggle(chatId, key, ref) }
    LaunchedEffect(playing) { if (playing) g.playback.play() else g.playback.pause() }
    DisposableEffect(key) { onDispose { g.playback.stop() } }
    AndroidView(
        factory = { SurfaceView(it).also { sv -> g.playback.player().setVideoSurfaceView(sv) } },
        onRelease = { sv -> g.playback.player().clearVideoSurfaceView(sv) },
        modifier = modifier,
    )
}


/**
 * Player controls over a full-screen video: tap to show / hide, big play-pause in the middle,
 * seek bar with position / length and speed at the bottom. Auto-hides after 3 s while playing.
 */
@Composable
private fun VideoControls(key: String, fallbackDuration: Long) {
    val g = LocalContext.current.graph
    val st by g.playback.state.collectAsStateWithLifecycle()
    val fmt = remember { app.zura.i18n.ZuraFormat(java.util.Locale.getDefault()) }
    var shown by remember { mutableStateOf(true) }
    var touch by remember { mutableStateOf(0) }
    val active = st.key == key
    val total = if (active && st.durationMs > 0) st.durationMs else fallbackDuration
    val playing = active && st.playing
    LaunchedEffect(shown, playing, touch) { if (shown && playing) { kotlinx.coroutines.delay(3_000); shown = false } }
    val alpha by androidx.compose.animation.core.animateFloatAsState(if (shown) 1f else 0f, label = "ctl")

    Box(
        Modifier.fillMaxSize().pointerInput(Unit) { detectTapGestures(onTap = { shown = !shown; touch++ }) },
    ) {
        if (alpha > 0.01f) {
            Box(
                Modifier.align(Alignment.Center).size(72.dp).graphicsLayer { this.alpha = alpha }
                    .clip(androidx.compose.foundation.shape.CircleShape).background(Color.Black.copy(alpha = 0.45f))
                    .pointerInput(playing) { detectTapGestures { if (playing) g.playback.pause() else g.playback.play(); touch++ } },
                contentAlignment = Alignment.Center,
            ) {
                Icon(if (playing) ZuraIcons.Pause else Icons.Default.PlayArrow, null, tint = Color.White, modifier = Modifier.size(40.dp))
            }
            Column(
                Modifier.align(Alignment.BottomCenter).fillMaxWidth().graphicsLayer { this.alpha = alpha }
                    .background(androidx.compose.ui.graphics.Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.6f))))
                    .navigationBarsPadding().padding(horizontal = 16.dp, vertical = 12.dp),
            ) {
                androidx.compose.material3.Slider(
                    value = if (total > 0 && active) (st.positionMs.toFloat() / total).coerceIn(0f, 1f) else 0f,
                    onValueChange = { g.playback.seek(it); touch++ },
                    colors = androidx.compose.material3.SliderDefaults.colors(
                        thumbColor = Color.White, activeTrackColor = Color.White, inactiveTrackColor = Color.White.copy(alpha = 0.3f),
                    ),
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "${fmt.duration(if (active) st.positionMs else 0)} / ${fmt.duration(total)}",
                        color = Color.White, style = app.zura.ui.theme.Zura.type.caption, modifier = Modifier.weight(1f),
                    )
                    Text(
                        "${st.speed}×", color = Color.White, style = app.zura.ui.theme.Zura.type.caption,
                        modifier = Modifier.clip(androidx.compose.foundation.shape.CircleShape).background(Color.White.copy(alpha = 0.18f))
                            .pointerInput(Unit) { detectTapGestures { g.playback.cycleSpeed(); touch++ } }.padding(horizontal = 10.dp, vertical = 4.dp),
                    )
                }
            }
        }
    }
}
