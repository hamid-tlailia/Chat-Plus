package app.zura.ui.call

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.zura.R
import app.zura.call.CallManager
import app.zura.call.CallPhase
import app.zura.call.CallUi
import app.zura.call.NetQuality
import app.zura.call.NetType
import app.zura.graph
import app.zura.i18n.ZuraFormat
import app.zura.ui.ZuraIcons
import app.zura.ui.components.UserAvatar
import app.zura.ui.components.ZuraMotion
import app.zura.ui.components.glass
import app.zura.ui.components.pressScale
import app.zura.ui.components.rememberHaptics
import app.zura.ui.theme.Zura
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.webrtc.RendererCommon
import org.webrtc.SurfaceViewRenderer
import org.webrtc.VideoTrack
import kotlin.math.roundToInt

/** Full-screen call UI, drawn above everything while a call is in progress. */
@Composable
fun CallScreen(calls: CallManager) {
    val s by calls.ui.collectAsStateWithLifecycle()
    if (s.phase == CallPhase.IDLE) return
    val remote by calls.remoteVideo.collectAsStateWithLifecycle()
    val local by calls.localVideo.collectAsStateWithLifecycle()
    val haptics = rememberHaptics()
    val showRemoteVideo = s.video && remote != null && s.remoteVideoOn && s.phase in setOf(CallPhase.ACTIVE, CallPhase.RECONNECTING)

    LaunchedEffect(s.phase) { if (s.phase == CallPhase.ENDED) { haptics.reject(); delay(1_600); calls.dismiss() } }

    Box(Modifier.fillMaxSize().background(Color(0xFF07070B))) {
        if (showRemoteVideo) Video(calls, remote!!, mirror = false, Modifier.fillMaxSize())
        else Backdrop(s)

        // Top: network + status
        Column(Modifier.fillMaxWidth().statusBarsPadding().padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            NetworkChip(s)
            if (showRemoteVideo) {
                Spacer(Modifier.height(10.dp))
                Text(s.peerName, style = Zura.type.headline, color = Color.White)
                StatusLine(s)
            }
        }

        if (!showRemoteVideo) {
            Column(Modifier.align(Alignment.Center).padding(bottom = 120.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Pulse(active = s.phase in setOf(CallPhase.OUTGOING, CallPhase.RINGING, CallPhase.INCOMING, CallPhase.RECONNECTING)) {
                    UserAvatar(s.peerName, s.peerHue, s.peerAvatar, 132.dp)
                }
                Spacer(Modifier.height(22.dp))
                Text(s.peerName, style = Zura.type.title, color = Color.White)
                Spacer(Modifier.height(6.dp))
                StatusLine(s)
            }
        }

        // Self-view (draggable, snaps to the nearest corner with a spring)
        if (s.video && s.cameraOn && local != null && s.phase != CallPhase.ENDED) SelfView(calls, local!!, s.frontCamera)

        // Controls
        Box(Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(16.dp)) {
            if (s.phase == CallPhase.INCOMING) IncomingControls(calls, s) else ActiveControls(calls, s)
        }
    }
}

@Composable
private fun Backdrop(s: CallUi) {
    val c = Zura.colors
    Box(
        Modifier.fillMaxSize().background(
            Brush.verticalGradient(listOf(Color.hsv(s.peerHue % 360f, 0.45f, 0.22f), Color(0xFF07070B), Color(0xFF07070B))),
        ),
    ) { Box(Modifier.fillMaxSize().background(c.bg.copy(alpha = 0.25f))) }
}

@Composable
private fun StatusLine(s: CallUi) {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(s.connectedAt) { while (s.connectedAt != null) { now = System.currentTimeMillis(); delay(500) } }
    val fmt = remember { ZuraFormat(java.util.Locale.getDefault()) }
    val text = when (s.phase) {
        CallPhase.OUTGOING -> stringResource(R.string.calling)
        CallPhase.RINGING -> stringResource(R.string.ringing)
        CallPhase.INCOMING -> stringResource(if (s.video) R.string.incoming_video else R.string.incoming_voice)
        CallPhase.CONNECTING -> stringResource(R.string.connecting)
        CallPhase.RECONNECTING -> stringResource(R.string.reconnecting)
        CallPhase.ACTIVE -> fmt.duration(now - (s.connectedAt ?: now))
        CallPhase.ENDED -> when (s.endReason) {
            "busy" -> stringResource(R.string.busy)
            "declined" -> stringResource(R.string.declined)
            "timeout" -> stringResource(R.string.no_answer)
            "failed" -> stringResource(R.string.connection_lost)
            else -> stringResource(R.string.call_ended)
        }
        CallPhase.IDLE -> ""
    }
    Text(text, style = Zura.type.label, color = if (s.phase == CallPhase.RECONNECTING) Color(0xFFFFC857) else Color.White.copy(alpha = 0.75f))
}

/** Network type + live link quality ("Wi-Fi · ▂▄▆", "Weak connection", "No internet"). */
@Composable
private fun NetworkChip(s: CallUi) {
    val (label, color) = when {
        s.net == NetType.OFFLINE -> stringResource(R.string.net_offline) to Color(0xFFFF5C7A)
        s.quality == NetQuality.POOR && s.phase == CallPhase.ACTIVE -> stringResource(R.string.weak_connection) to Color(0xFFFFC857)
        s.net == NetType.WIFI -> stringResource(R.string.net_wifi) to Color.White
        s.net == NetType.CELLULAR -> stringResource(R.string.net_mobile) to Color.White
        else -> "" to Color.White
    }
    val bars = when (s.quality) { NetQuality.GOOD -> 3; NetQuality.FAIR -> 2; NetQuality.POOR -> 1; NetQuality.UNKNOWN -> 0 }
    Row(
        Modifier.glass(CircleShape).padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text("🔒", style = Zura.type.caption)
        if (label.isNotEmpty()) Text(label, style = Zura.type.caption, color = color)
        if (s.phase == CallPhase.ACTIVE || s.phase == CallPhase.RECONNECTING) {
            Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                repeat(3) { i ->
                    Box(Modifier.width(3.dp).height((5 + i * 4).dp).clip(CircleShape).background(if (i < bars) color else Color.White.copy(alpha = 0.25f)))
                }
            }
            s.rttMs?.let { Text("${it}ms", style = Zura.type.caption, color = Color.White.copy(alpha = 0.6f)) }
        }
    }
}

@Composable
private fun ActiveControls(calls: CallManager, s: CallUi) {
    val haptics = rememberHaptics()
    Row(
        Modifier.glass(RoundedCornerShape(32.dp)).padding(horizontal = 14.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically,
    ) {
        if (s.video) {
            RoundButton(ZuraIcons.VideoCam, stringResource(R.string.camera), active = !s.cameraOn) { haptics.tick(); calls.toggleCamera() }
            RoundButton(Icons.Default.Refresh, stringResource(R.string.flip), active = false) { haptics.tick(); calls.switchCamera() }
        } else {
            RoundButton(ZuraIcons.Speaker, stringResource(R.string.speaker), active = s.speaker) { haptics.tick(); calls.toggleSpeaker() }
        }
        RoundButton(ZuraIcons.MicOff, stringResource(R.string.mute), active = s.muted) { haptics.tick(); calls.toggleMute() }
        RoundButton(ZuraIcons.CallEnd, stringResource(R.string.end_call), active = false, danger = true) { haptics.reject(); calls.hangup() }
    }
}

@Composable
private fun IncomingControls(calls: CallManager, s: CallUi) {
    val haptics = rememberHaptics()
    val ctx = LocalContext.current
    val perms = app.zura.ui.chat.rememberCallPermissions(s.video) { granted -> if (granted) calls.accept() else calls.decline() }
    Row(Modifier.fillMaxWidth().padding(horizontal = 40.dp, vertical = 24.dp), horizontalArrangement = Arrangement.SpaceBetween) {
        BigButton(ZuraIcons.CallEnd, stringResource(R.string.decline), Color(0xFFE5484D)) { haptics.reject(); calls.decline() }
        BigButton(if (s.video) ZuraIcons.VideoCam else Icons.Default.Call, stringResource(R.string.answer), Color(0xFF2EBD6B), bounce = true) {
            haptics.confirm(); perms(ctx)
        }
    }
}

@Composable
private fun RoundButton(icon: ImageVector, label: String, active: Boolean, danger: Boolean = false, onClick: () -> Unit) {
    val src = remember { MutableInteractionSource() }
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            Modifier.size(58.dp).pressScale(src, 0.9f).clip(CircleShape)
                .background(when { danger -> Color(0xFFE5484D); active -> Color.White; else -> Color.White.copy(alpha = 0.14f) })
                .clickable(src, null, onClick = onClick),
            contentAlignment = Alignment.Center,
        ) { Icon(icon, label, tint = if (active && !danger) Color.Black else Color.White, modifier = Modifier.size(26.dp)) }
        Spacer(Modifier.height(4.dp))
        Text(label, style = Zura.type.caption, color = Color.White.copy(alpha = 0.8f))
    }
}

@Composable
private fun BigButton(icon: ImageVector, label: String, color: Color, bounce: Boolean = false, onClick: () -> Unit) {
    val src = remember { MutableInteractionSource() }
    val t = rememberInfiniteTransition(label = "answer")
    val y by t.animateFloat(0f, if (bounce) -10f else 0f, infiniteRepeatable(tween(650), RepeatMode.Reverse), label = "y")
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.graphicsLayer { translationY = y }) {
        Box(
            Modifier.size(76.dp).pressScale(src, 0.9f).clip(CircleShape).background(color).clickable(src, null, onClick = onClick),
            contentAlignment = Alignment.Center,
        ) { Icon(icon, label, tint = Color.White, modifier = Modifier.size(34.dp)) }
        Spacer(Modifier.height(8.dp))
        Text(label, style = Zura.type.label, color = Color.White)
    }
}

/** Soft concentric rings breathing behind the avatar while ringing / reconnecting. */
@Composable
private fun Pulse(active: Boolean, content: @Composable () -> Unit) {
    val t = rememberInfiniteTransition(label = "pulse")
    val p by t.animateFloat(0f, 1f, infiniteRepeatable(tween(1800), RepeatMode.Restart), label = "p")
    Box(contentAlignment = Alignment.Center) {
        if (active) repeat(2) { i ->
            val f = (p + i * 0.5f) % 1f
            Box(Modifier.size(132.dp).graphicsLayer { scaleX = 1f + f * 0.7f; scaleY = scaleX; alpha = (1f - f) * 0.35f }.clip(CircleShape).background(Color.White))
        }
        content()
    }
}

@Composable
private fun Video(calls: CallManager, track: VideoTrack, mirror: Boolean, modifier: Modifier, overlay: Boolean = false) {
    val ctx = LocalContext.current
    val view = remember {
        SurfaceViewRenderer(ctx).apply {
            init(calls.egl.eglBaseContext, null)
            setScalingType(RendererCommon.ScalingType.SCALE_ASPECT_FILL)
            setEnableHardwareScaler(true)
            setZOrderMediaOverlay(overlay)
        }
    }
    DisposableEffect(track) {
        track.addSink(view)
        onDispose { runCatching { track.removeSink(view) } }
    }
    DisposableEffect(Unit) { onDispose { view.release() } }
    view.setMirror(mirror)
    AndroidView({ view }, modifier)
}

@Composable
private fun SelfView(calls: CallManager, track: VideoTrack, front: Boolean) {
    val density = LocalDensity.current
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    val scope = rememberCoroutineScope()
    BoxWithConstraints(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().padding(16.dp)) {
        val w = with(density) { 112.dp.toPx() }
        val h = with(density) { 168.dp.toPx() }
        val maxX = constraints.maxWidth - w
        val maxY = constraints.maxHeight - h - with(density) { 120.dp.toPx() }
        val x = remember { Animatable(if (rtl) 0f else maxX) }
        val y = remember { Animatable(with(density) { 56.dp.toPx() }) }
        Box(
            Modifier.offset { IntOffset(x.value.roundToInt(), y.value.roundToInt()) }.size(112.dp, 168.dp).clip(RoundedCornerShape(20.dp))
                .pointerInput(Unit) {
                    detectDragGestures(
                        onDragEnd = {
                            scope.launch { x.animateTo(if (x.value + w / 2 < constraints.maxWidth / 2) 0f else maxX, ZuraMotion.Bouncy) }
                            scope.launch { y.animateTo(y.value.coerceIn(0f, maxY.coerceAtLeast(0f)), ZuraMotion.Bouncy) }
                        },
                    ) { ch, d ->
                        ch.consume()
                        scope.launch { x.snapTo(x.value + d.x); y.snapTo(y.value + d.y) }
                    }
                },
        ) { Video(calls, track, mirror = front, Modifier.fillMaxSize(), overlay = true) }
    }
}
