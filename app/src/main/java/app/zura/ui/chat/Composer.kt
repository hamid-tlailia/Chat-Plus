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
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Lock
import androidx.compose.runtime.LaunchedEffect
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
    var lockDrag by remember { mutableFloatStateOf(0f) }
    var locked by remember { mutableStateOf(false) }
    // Recording ended elsewhere (sent, cancelled, screen left): drop the lock state.
    LaunchedEffect(live.recording) { if (!live.recording) locked = false }

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
            Box(Modifier.weight(1f).glass(RoundedCornerShape(26.dp)).heightIn(min = 52.dp), contentAlignment = Alignment.CenterStart) {
                AnimatedContent(live.recording, transitionSpec = { fadeIn() togetherWith fadeOut() }, label = "rec") { recording ->
                    if (recording) RecordingStrip(live.elapsedMs, live.levels, cancelDrag, locked, onDelete = { haptics.reject(); locked = false; vm.cancelRecording() }, fmt)
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

            // Send ⇄ Mic. Voice notes, WhatsApp-style:
            //   hold → record · release → send · slide toward start → delete · slide UP → lock (hands-free)
            // The button never grows beyond its slot (a scaled button used to overflow the screen edge).
            val density = LocalDensity.current
            val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
            val cancelAt = with(density) { 110.dp.toPx() }
            val lockAt = with(density) { 80.dp.toPx() }
            val showSend = hasText || vm.editing != null || locked
            val pulse by rememberInfiniteTransition(label = "rec").animateFloat(0.55f, 1f, infiniteRepeatable(tween(700), RepeatMode.Reverse), label = "recPulse")
            Box(contentAlignment = Alignment.Center) {
                // Lock hint: rises with the finger; reaching the top locks the recording.
                if (live.recording && !locked) {
                    val progress = (lockDrag / lockAt).coerceIn(0f, 1f)
                    Column(
                        Modifier.align(Alignment.TopCenter)
                            .offset { IntOffset(0, (-with(density) { 104.dp.toPx() } - lockDrag * 0.6f).roundToInt()) }
                            .size(width = 44.dp, height = 92.dp)
                            .glass(CircleShape, solid = 0.85f)
                            .padding(vertical = 10.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Icon(Icons.Default.Lock, null, tint = if (progress > 0.95f) c.accent else c.text, modifier = Modifier.size(20.dp))
                        Icon(Icons.Default.KeyboardArrowUp, null, tint = c.textMuted, modifier = Modifier.size(22.dp).graphicsLayer { translationY = -progress * 10f })
                    }
                }
                Box(
                    Modifier.size(52.dp).clip(CircleShape)
                        .background(if (live.recording && !locked) c.danger.copy(alpha = pulse) else c.accent)
                        .then(
                            if (showSend) Modifier.clickable {
                                haptics.confirm()
                                if (locked) { locked = false; vm.finishRecording() } else vm.send()
                            }
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
                                    haptics.start(); cancelDrag = 0f; lockDrag = 0f
                                    val startedAt = System.currentTimeMillis()
                                    var outcome = 0 // 0 = send on release, 1 = cancelled, 2 = locked
                                    try {
                                        while (true) {
                                            val ev = awaitPointerEvent()
                                            val ch = ev.changes.firstOrNull() ?: break
                                            val d = ch.positionChange()
                                            cancelDrag = (cancelDrag + if (rtl) d.x else -d.x).coerceAtLeast(0f)
                                            lockDrag = (lockDrag - d.y).coerceAtLeast(0f)
                                            ch.consume()
                                            if (lockDrag > lockAt && lockDrag > cancelDrag) { outcome = 2; break }
                                            if (cancelDrag > cancelAt) { outcome = 1; break }
                                            if (!ch.pressed) break
                                        }
                                    } catch (e: kotlinx.coroutines.CancellationException) {
                                        vm.cancelRecording(); cancelDrag = 0f; lockDrag = 0f; throw e
                                    }
                                    when {
                                        outcome == 2 -> { haptics.confirm(); locked = true }
                                        outcome == 1 -> { haptics.reject(); vm.cancelRecording() }
                                        System.currentTimeMillis() - startedAt < 400 -> {
                                            // A quick tap: explain instead of sending an empty note.
                                            vm.cancelRecording()
                                            android.widget.Toast.makeText(ctx, R.string.record_hint, android.widget.Toast.LENGTH_SHORT).show()
                                        }
                                        else -> { haptics.confirm(); vm.finishRecording() }
                                    }
                                    cancelDrag = 0f; lockDrag = 0f
                                }
                            },
                        ),
                    contentAlignment = Alignment.Center,
                ) {
                    AnimatedContent(showSend, transitionSpec = { scaleIn() + fadeIn() togetherWith scaleOut() + fadeOut() }, label = "send") { send ->
                        Icon(if (send) Icons.AutoMirrored.Filled.Send else ZuraIcons.Mic, null, tint = c.onAccent)
                    }
                }
            }
        }
    }
}

@Composable
private fun RecordingStrip(elapsed: Long, levels: List<Float>, cancelDrag: Float, locked: Boolean, onDelete: () -> Unit, fmt: ZuraFormat) {
    val c = Zura.colors
    val pulse by rememberInfiniteTransition(label = "pulse").animateFloat(0.35f, 1f, infiniteRepeatable(tween(650), RepeatMode.Reverse), label = "dot")
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    Row(Modifier.fillMaxWidth().heightIn(min = 52.dp).padding(horizontal = if (locked) 4.dp else 16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        if (locked) {
            IconButton(onClick = onDelete) { Icon(Icons.Default.Delete, stringResource(R.string.delete), tint = c.danger) }
        }
        Box(Modifier.size(10.dp).graphicsLayer { alpha = pulse }.clip(CircleShape).background(c.danger))
        Text(fmt.duration(elapsed), style = Zura.type.label, color = c.text)
        LiveWaveform(levels, c.accent, Modifier.weight(1f).height(28.dp))
        if (!locked) Text(
            "‹ " + stringResource(R.string.cancel_recording), style = Zura.type.caption, color = c.textMuted, maxLines = 1,
            modifier = Modifier.offset { IntOffset(((if (rtl) 1 else -1) * cancelDrag * 0.5f).roundToInt(), 0) }
                .graphicsLayer { alpha = (1f - cancelDrag / 300f).coerceIn(0.2f, 1f) },
        ) else Spacer(Modifier.width(8.dp))
    }
}
