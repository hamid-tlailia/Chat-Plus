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
