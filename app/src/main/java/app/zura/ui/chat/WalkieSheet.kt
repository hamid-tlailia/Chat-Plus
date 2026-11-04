package app.zura.ui.chat

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.zura.R
import app.zura.graph
import app.zura.ui.ZuraIcons
import app.zura.ui.components.rememberHaptics
import app.zura.ui.theme.Zura
import kotlinx.coroutines.launch

/**
 * Push-to-talk. Peer in the app → live voice while the button is held. Peer away → the same
 * gesture records a voice note and sends it on release.
 */
@Composable
fun WalkieSheet(vm: ChatViewModel, peerName: String, onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    val g = ctx.graph
    val c = Zura.colors
    val haptics = rememberHaptics()
    val online by vm.peerOnline.collectAsStateWithLifecycle()
    val sending by g.s.walkie.sending.collectAsStateWithLifecycle()
    val draft by g.s.walkie.draft.collectAsStateWithLifecycle()
    val active = sending
    val mic = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}
    val pulse by rememberInfiniteTransition(label = "ptt").animateFloat(1f, 1.18f, infiniteRepeatable(tween(600), RepeatMode.Reverse), label = "s")
    DisposableEffect(Unit) { onDispose { g.s.walkie.stopTalking(); g.s.walkie.discardDraft() } }
    BackHandler(onBack = onDismiss)

    app.zura.ui.components.SheetHost(onDismiss = onDismiss) {
        Box(Modifier.fillMaxSize()) {
            Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.45f)).clickable(remember { MutableInteractionSource() }, null, onClick = onDismiss))
            Column(
                Modifier.align(Alignment.BottomCenter).fillMaxWidth().navigationBarsPadding().padding(12.dp)
                    .clip(RoundedCornerShape(28.dp)).background(c.surfaceHigh)
                    .clickable(remember { MutableInteractionSource() }, null) {}.padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                androidx.compose.foundation.layout.Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(ZuraIcons.Walkie, null, tint = c.accent, modifier = Modifier.size(22.dp))
                    Spacer(Modifier.size(8.dp))
                    Text(stringResource(R.string.walkie) + " · " + peerName, style = Zura.type.headline, color = c.text)
                }
                Spacer(Modifier.height(6.dp))
                Text(
                    stringResource(if (online) R.string.walkie_live else R.string.walkie_offline),
                    style = Zura.type.caption, color = if (online) c.accent else c.textMuted, textAlign = TextAlign.Center,
                )
                Spacer(Modifier.height(28.dp))
                Box(
                    Modifier.size(132.dp).graphicsLayer { val k = if (active) pulse else 1f; scaleX = k; scaleY = k }
                        .clip(CircleShape).background(if (active) c.danger else c.accent)
                        .pointerInput(online) {
                            awaitEachGesture {
                                awaitFirstDown()
                                if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                                    mic.launch(Manifest.permission.RECORD_AUDIO); return@awaitEachGesture
                                }
                                val peer = vm.peerId ?: return@awaitEachGesture
                                // Live when they're in the app; otherwise (or if live fails) it becomes a draft below.
                                if (!g.s.walkie.startTalking(peer, vm.chatId, live = online)) { haptics.reject(); return@awaitEachGesture }
                                haptics.start()
                                waitForUpOrCancellation()
                                haptics.confirm()
                                g.s.walkie.stopTalking()
                            }
                        },
                    contentAlignment = Alignment.Center,
                ) { Icon(ZuraIcons.Mic, null, tint = c.onAccent, modifier = Modifier.size(56.dp)) }
                Spacer(Modifier.height(16.dp))
                Text(stringResource(if (active) R.string.walkie_you else R.string.walkie_hold), style = Zura.type.label, color = c.text)
                // Not heard live: the user decides — send as a voice note, or delete.
                draft?.let { d ->
                    Spacer(Modifier.height(18.dp))
                    Column(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(c.text.copy(alpha = 0.06f)).padding(14.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Text(stringResource(R.string.walkie_draft) + " · " + app.zura.i18n.ZuraFormat(java.util.Locale.getDefault()).duration(d.durationMs), style = Zura.type.label, color = c.text, textAlign = TextAlign.Center)
                        Spacer(Modifier.height(12.dp))
                        androidx.compose.foundation.layout.Row(horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(10.dp)) {
                            Box(
                                Modifier.weight(1f).height(46.dp).clip(CircleShape).background(c.danger.copy(alpha = 0.14f))
                                    .clickable { haptics.reject(); g.s.walkie.discardDraft() },
                                contentAlignment = Alignment.Center,
                            ) { Text(stringResource(R.string.delete), style = Zura.type.label, color = c.danger) }
                            Box(
                                Modifier.weight(1f).height(46.dp).clip(CircleShape).background(c.accent).clickable {
                                    haptics.confirm()
                                    g.s.walkie.discardDraft()
                                    g.s.scope.launch {
                                        runCatching {
                                            val ref = g.vault.importMedia(d.chatId, java.io.ByteArrayInputStream(d.wav), "audio/wav", durationMs = d.durationMs, waveform = d.waveform)
                                            g.chats.sendMedia(d.chatId, app.zura.core.model.MsgKind.VOICE, ref)
                                        }
                                    }
                                },
                                contentAlignment = Alignment.Center,
                            ) { Text(stringResource(R.string.send), style = Zura.type.label, color = c.onAccent) }
                        }
                    }
                }
            }
        }
    }
}

/** Floating "Hiba is talking…" while live walkie audio is playing. */
@Composable
fun WalkieIndicator(modifier: Modifier = Modifier) {
    val g = LocalContext.current.graph
    val session = g.session.collectAsStateWithLifecycle().value ?: return
    val who by session.walkie.talking.collectAsStateWithLifecycle()
    val id = who ?: return
    val name = session.contacts.profile(id)?.displayName ?: ""
    Box(
        modifier.clip(CircleShape).background(Zura.colors.accent).padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        androidx.compose.foundation.layout.Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(ZuraIcons.Walkie, null, tint = Zura.colors.onAccent, modifier = Modifier.size(18.dp))
            Spacer(Modifier.size(8.dp))
            Text(stringResource(R.string.walkie_talking, name), style = Zura.type.label, color = Zura.colors.onAccent)
        }
    }
}
