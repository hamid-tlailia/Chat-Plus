package app.zura.ui.chat

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.zura.R
import app.zura.i18n.ZuraFormat
import app.zura.ui.ZuraIcons
import app.zura.ui.components.Haptics
import app.zura.ui.components.glass
import app.zura.ui.theme.Zura
import kotlin.math.roundToInt

@Composable
fun Composer(vm: ChatViewModel, fmt: ZuraFormat, haptics: Haptics, modifier: Modifier = Modifier) {
    val ctx = LocalContext.current
    val c = Zura.colors
    val live by vm.recorder.live.collectAsStateWithLifecycle()
    val hasText = vm.draft.text.isNotBlank()
    var attachMenu by remember { mutableStateOf(false) }
    var cancelDrag by remember { mutableFloatStateOf(0f) }

    val pickMedia = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(10)) { uris ->
        uris.forEach { vm.sendMedia(it, ctx.contentResolver.getType(it).orEmpty()) }
    }
    val pickFile = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        val name = ctx.contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)?.use { if (it.moveToFirst()) it.getString(0) else null }
        vm.sendFile(uri, ctx.contentResolver.getType(uri) ?: "application/octet-stream", name)
    }
    val micPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}

    // Gesture-nav phones report a tiny bottom inset; keep a comfortable thumb margin above the edge.
    Column(modifier.fillMaxWidth().navigationBarsPadding().imePadding().padding(start = 10.dp, end = 10.dp, top = 8.dp, bottom = 14.dp)) {
        // Reply / edit context banner
        AnimatedVisibility(vm.replyTo != null || vm.editing != null, enter = expandVertically() + fadeIn(), exit = shrinkVertically() + fadeOut()) {
            val target = vm.editing ?: vm.replyTo
            Row(
                Modifier.fillMaxWidth().padding(bottom = 6.dp).glass(RoundedCornerShape(18.dp)).padding(start = 14.dp, top = 8.dp, bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(if (vm.editing != null) Icons.Default.Edit else ZuraIcons.Reply, null, tint = c.accent, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(10.dp))
                // Replying to a photo/video: show it, like the quote inside the bubble will.
                val thumb = androidx.compose.runtime.remember(target?.media?.preview) {
                    target?.media?.preview?.let(app.zura.media.Previews::decode)?.asImageBitmap()
                }
                if (thumb != null) {
                    androidx.compose.foundation.Image(
                        thumb, null,
                        Modifier.size(40.dp).clip(RoundedCornerShape(8.dp)),
                        contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                    )
                    Spacer(Modifier.width(10.dp))
                }
                Column(Modifier.weight(1f)) {
                    Text(stringResource(if (vm.editing != null) R.string.action_edit else R.string.action_reply), style = Zura.type.caption, color = c.accent)
                    Text(target?.text ?: target?.let { stringResource(kindLabel(it.kind)) } ?: "", style = Zura.type.label, color = c.textMuted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                IconButton(onClick = {
                    if (vm.editing != null) vm.draft = androidx.compose.ui.text.input.TextFieldValue("")
                    vm.editing = null; vm.replyTo = null
                }) { Icon(Icons.Default.Close, null, tint = c.textMuted) }
            }
        }

        Row(verticalAlignment = Alignment.Bottom) {
            Box(Modifier.weight(1f).glass(RoundedCornerShape(26.dp), solid = 0.6f).heightIn(min = 52.dp), contentAlignment = Alignment.CenterStart) {
                AnimatedContent(live.recording, transitionSpec = { fadeIn() togetherWith fadeOut() }, label = "rec") { recording ->
                    if (recording) RecordingStrip(live.elapsedMs, live.levels, cancelDrag, fmt)
                    else Row(verticalAlignment = Alignment.CenterVertically) {
                        Box {
                            IconButton(onClick = { haptics.tick(); attachMenu = true }) { Icon(ZuraIcons.Attach, null, tint = c.textMuted) }
                            DropdownMenu(attachMenu, { attachMenu = false }, containerColor = c.surfaceHigh) {
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.photo) + " / " + stringResource(R.string.video), color = c.text) },
                                    leadingIcon = { Icon(ZuraIcons.Photo, null, tint = c.text) },
                                    onClick = { attachMenu = false; pickMedia.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageAndVideo)) },
                                )
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.file), color = c.text) },
                                    leadingIcon = { Icon(ZuraIcons.File, null, tint = c.text) },
                                    onClick = { attachMenu = false; pickFile.launch(arrayOf("*/*")) },
                                )
                            }
                        }
                        BasicTextField(
                            value = vm.draft,
                            onValueChange = vm::onDraft,
                            textStyle = Zura.type.body.copy(color = c.text, textDirection = TextDirection.Content),
                            cursorBrush = SolidColor(c.accent),
                            maxLines = 6,
                            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                            modifier = Modifier.weight(1f).padding(vertical = 14.dp).padding(end = 14.dp),
                            decorationBox = { inner ->
                                if (vm.draft.text.isEmpty()) Text(stringResource(R.string.message_hint), style = Zura.type.body, color = c.textMuted)
                                inner()
                            },
                        )
                    }
                }
            }
            Spacer(Modifier.width(8.dp))

            // Send ⇄ Mic morph. Mic: press-and-hold records, slide toward start cancels.
            val density = LocalDensity.current
            val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
            val cancelAt = with(density) { 110.dp.toPx() }
            val recScale by animateFloatAsState(if (live.recording) 1.35f else 1f, label = "mic")
            Box(
                Modifier.size(52.dp).graphicsLayer { scaleX = recScale; scaleY = recScale }.clip(CircleShape)
                    .background(if (live.recording) c.danger else c.accent)
                    .then(
                        if (hasText || vm.editing != null) Modifier.clickable { haptics.confirm(); vm.send() }
                        else Modifier.pointerInput(rtl) {
                            awaitEachGesture {
                                awaitFirstDown()
                                if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                                    micPermission.launch(Manifest.permission.RECORD_AUDIO); return@awaitEachGesture
                                }
                                if (!vm.startRecording()) {
                                    haptics.reject()
                                    android.widget.Toast.makeText(ctx, R.string.mic_unavailable, android.widget.Toast.LENGTH_SHORT).show()
                                    return@awaitEachGesture
                                }
                                haptics.start(); cancelDrag = 0f
                                var cancelled = false
                                try {
                                    while (true) {
                                        val ev = awaitPointerEvent()
                                        val ch = ev.changes.firstOrNull() ?: break
                                        val dx = ch.positionChange().x
                                        cancelDrag = (cancelDrag + if (rtl) dx else -dx).coerceAtLeast(0f)
                                        ch.consume()
                                        if (cancelDrag > cancelAt) { cancelled = true; haptics.reject(); vm.cancelRecording(); break }
                                        if (!ch.pressed) break
                                    }
                                } catch (e: kotlinx.coroutines.CancellationException) {
                                    // Gesture interrupted (screen left, system dialog…): discard, don't crash.
                                    vm.cancelRecording(); throw e
                                }
                                if (!cancelled) { haptics.confirm(); vm.finishRecording() }
                                cancelDrag = 0f
                            }
                        },
                    ),
                contentAlignment = Alignment.Center,
            ) {
                AnimatedContent(hasText || vm.editing != null, transitionSpec = { scaleIn() + fadeIn() togetherWith scaleOut() + fadeOut() }, label = "send") { send ->
                    Icon(if (send) Icons.AutoMirrored.Filled.Send else ZuraIcons.Mic, null, tint = c.onAccent)
                }
            }
        }
    }
}

@Composable
private fun RecordingStrip(elapsed: Long, levels: List<Float>, cancelDrag: Float, fmt: ZuraFormat) {
    val c = Zura.colors
    val pulse by rememberInfiniteTransition(label = "pulse").animateFloat(0.35f, 1f, infiniteRepeatable(tween(650), RepeatMode.Reverse), label = "dot")
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Box(Modifier.size(10.dp).graphicsLayer { alpha = pulse }.clip(CircleShape).background(c.danger))
        Text(fmt.duration(elapsed), style = Zura.type.label, color = c.text)
        LiveWaveform(levels, c.accent, Modifier.weight(1f).height(28.dp))
        Text(
            "‹ " + stringResource(R.string.cancel_recording), style = Zura.type.caption, color = c.textMuted,
            modifier = Modifier.offset { IntOffset(((if (rtl) 1 else -1) * cancelDrag * 0.5f).roundToInt(), 0) }
                .graphicsLayer { alpha = (1f - cancelDrag / 300f).coerceIn(0.2f, 1f) },
        )
    }
}
