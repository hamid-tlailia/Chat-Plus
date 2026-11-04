package app.zura.ui.media

import android.view.SurfaceView
import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
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

/** Full-screen photo (pinch/double-tap zoom) or video, swipe down to dismiss. */
@Composable
fun MediaViewer(chatId: String, messageId: String, onClose: () -> Unit) {
    val ctx = LocalContext.current
    val g = ctx.graph
    val msgs by remember(chatId) { g.chats.messages(chatId) }.collectAsStateWithLifecycle(emptyList())
    val m = msgs.firstOrNull { it.id == messageId }
    val ref = m?.media
    val scope = rememberCoroutineScope()
    val dismiss = remember { Animatable(0f) }
    var scale by remember { mutableFloatStateOf(1f) }
    var pan by remember { mutableStateOf(Offset.Zero) }

    Box(
        Modifier.fillMaxSize().background(Color.Black.copy(alpha = (1f - dismiss.value / 900f).coerceIn(0.3f, 1f)))
            .pointerInput(Unit) {
                detectVerticalDragGestures(
                    onDragEnd = { scope.launch { if (dismiss.value > 220f) onClose() else dismiss.animateTo(0f, ZuraMotion.Snappy) } },
                ) { _, dy -> if (scale <= 1.01f) scope.launch { dismiss.snapTo((dismiss.value + dy).coerceAtLeast(0f)) } }
            },
    ) {
        Box(Modifier.fillMaxSize().graphicsLayer { translationY = dismiss.value }, contentAlignment = Alignment.Center) {
            if (ref != null && m.kind == MsgKind.VIDEO) {
                VaultVideo(chatId, m.id, ref, playing = true, Modifier.fillMaxWidth().aspectRatio(if (ref.height > 0) ref.width.toFloat() / ref.height else 16f / 9))
                VideoControls(m.id, ref.durationMs)
            } else if (ref != null) {
                val preview = remember(ref.preview) { ref.preview?.let(Previews::decode)?.asImageBitmap() }
                // Paint instantly with the copy already decoded for the chat bubble, then sharpen.
                val mid = remember(ref.fileName) { g.images.cached(ref, 720) }
                val full by rememberVaultImage(g.images, chatId, ref, 1920)
                (full ?: mid ?: preview)?.let {
                    Image(
                        it, null, contentScale = ContentScale.Fit,
                        modifier = Modifier.fillMaxSize()
                            .pointerInput(Unit) {
                                detectTransformGestures { _, p, z, _ ->
                                    scale = (scale * z).coerceIn(1f, 5f)
                                    pan = if (scale > 1f) pan + p else Offset.Zero
                                }
                            }
                            .pointerInput(Unit) { detectTapGestures(onDoubleTap = { scale = if (scale > 1f) 1f else 2.5f; pan = Offset.Zero }) }
                            .graphicsLayer { scaleX = scale; scaleY = scale; translationX = pan.x; translationY = pan.y },
                    )
                }
            }
        }
        Row(Modifier.fillMaxWidth().statusBarsPadding()) {
            IconButton(onClick = onClose) { Icon(Icons.Default.Close, null, tint = Color.White) }
            Spacer(Modifier.weight(1f))
            if (ref != null) {
                IconButton(onClick = { scope.launch { ctx.startActivity(g.exporter.shareOut(chatId, ref)) } }) { Icon(Icons.Default.Share, null, tint = Color.White) }
                IconButton(onClick = { scope.launch { g.exporter.saveToDevice(chatId, ref) } }) { Icon(ZuraIcons.Download, null, tint = Color.White) }
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
