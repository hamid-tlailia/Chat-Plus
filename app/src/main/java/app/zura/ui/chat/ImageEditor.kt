package app.zura.ui.chat

import android.graphics.Bitmap
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Done
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import app.zura.R
import app.zura.graph
import app.zura.share.Attachment
import app.zura.ui.components.ZuraMotion
import app.zura.ui.components.rememberHaptics
import app.zura.ui.theme.Zura
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Edit a photo before sending it (from the gallery or shared from another app): draw on it, add
 * text you can drag anywhere, rotate, undo. "Done" bakes everything into a new JPEG that replaces
 * the staged photo — kept in the app's private no-backup storage and wiped once sent.
 * All positions are kept as fractions of the image, so the result matches what's on screen.
 */
@Composable
fun ImageEditor(
    source: Attachment, onDone: (Attachment) -> Unit, onCancel: () -> Unit,
    /** The phone's status / navigation bar sizes (the chat screen passes WindowInsets.systemBars). */
    insets: androidx.compose.foundation.layout.PaddingValues = androidx.compose.foundation.layout.PaddingValues(),
) {
    val top = insets.calculateTopPadding()
    val bottom = insets.calculateBottomPadding()
    val ctx = LocalContext.current
    val g = ctx.graph
    val haptics = rememberHaptics()
    val scope = rememberCoroutineScope()
    val loaded by produceState<Bitmap?>(null, source.uri) { value = withContext(Dispatchers.IO) { decodeForEdit(ctx, source.uri) } }
    var base by remember { mutableStateOf<Bitmap?>(null) }
    LaunchedEffect(loaded) { if (base == null) base = loaded }

    val strokes = remember { mutableStateListOf<Stroke2>() }
    val texts = remember { mutableStateListOf<TextItem>() }
    val history = remember { mutableStateListOf<Any>() } // added strokes / texts, newest last (undo)
    var mode by remember { mutableStateOf(Mode.DRAW) }
    var color by remember { mutableStateOf(PALETTE[1]) }
    var brush by remember { mutableStateOf(BRUSHES[1]) }
    var typing by remember { mutableStateOf<TextItem?>(null) } // the text being written / edited
    var saving by remember { mutableStateOf(false) }

    BackHandler { if (typing != null) typing = null else onCancel() }

    // Drawn over the chat in the same window: put the chat's keyboard away, and light bar icons
    // on the black editor (restored when it closes — the app theme may be a light one).
    val focusManager = androidx.compose.ui.platform.LocalFocusManager.current
    val keyboard = androidx.compose.ui.platform.LocalSoftwareKeyboardController.current
    LaunchedEffect(Unit) { focusManager.clearFocus(); keyboard?.hide() }
    val view = androidx.compose.ui.platform.LocalView.current
    androidx.compose.runtime.DisposableEffect(view) {
        val window = generateSequence(view.context) { (it as? android.content.ContextWrapper)?.baseContext }
            .filterIsInstance<android.app.Activity>().firstOrNull()?.window
        val bars = window?.let { androidx.core.view.WindowCompat.getInsetsController(it, view) }
        val lightStatus = bars?.isAppearanceLightStatusBars
        val lightNav = bars?.isAppearanceLightNavigationBars
        bars?.isAppearanceLightStatusBars = false
        bars?.isAppearanceLightNavigationBars = false
        onDispose {
            if (bars != null && lightStatus != null && lightNav != null) {
                bars.isAppearanceLightStatusBars = lightStatus
                bars.isAppearanceLightNavigationBars = lightNav
            }
        }
    }

    // Hit-testable over its whole area, so no touch reaches the chat underneath.
    Box(Modifier.fillMaxSize().background(Color.Black).pointerInput(Unit) { awaitPointerEventScope { while (true) awaitPointerEvent() } }) {
        val bmp = base
        if (bmp == null) CircularProgressIndicator(Modifier.align(Alignment.Center), color = Color.White)
        else BoxWithConstraints(Modifier.fillMaxSize().padding(top = top + 64.dp, bottom = bottom + 150.dp), contentAlignment = Alignment.Center) {
            // The photo fitted on screen; everything is drawn in its coordinates (fractions).
            val ratio = bmp.width.toFloat() / bmp.height
            val boxRatio = constraints.maxWidth.toFloat() / constraints.maxHeight
            val w = if (ratio > boxRatio) constraints.maxWidth.toFloat() else constraints.maxHeight * ratio
            val h = w / ratio
            val density = LocalDensity.current
            val image = remember(bmp) { bmp.asImageBitmap() }
            Box(Modifier.size(with(density) { w.toDp() }, with(density) { h.toDp() })) {
                Image(image, null, Modifier.fillMaxSize(), contentScale = ContentScale.FillBounds)
                Canvas(
                    Modifier.fillMaxSize().pointerInput(mode, color, brush) {
                        if (mode != Mode.DRAW) return@pointerInput
                        // From the first touch (no drag threshold): a single tap leaves a dot.
                        awaitEachGesture {
                            val down = awaitFirstDown()
                            down.consume()
                            val pw = size.width.toFloat(); val ph = size.height.toFloat()
                            val s = Stroke2(color, brush, mutableStateListOf(Offset(down.position.x / pw, down.position.y / ph)))
                            strokes += s; history += s
                            while (true) {
                                val ch = awaitPointerEvent().changes.firstOrNull { it.id == down.id } ?: break
                                if (!ch.pressed) break
                                if (ch.positionChanged()) { ch.consume(); s.points.add(Offset(ch.position.x / pw, ch.position.y / ph)) }
                            }
                        }
                    },
                ) {
                    strokes.forEach { s ->
                        if (s.points.size == 1) { // a dot
                            drawCircle(s.color, s.width * size.width / 2, Offset(s.points[0].x * size.width, s.points[0].y * size.height))
                            return@forEach
                        }
                        val p = Path()
                        s.points.forEachIndexed { i, pt -> if (i == 0) p.moveTo(pt.x * size.width, pt.y * size.height) else p.lineTo(pt.x * size.width, pt.y * size.height) }
                        drawPath(p, s.color, style = Stroke(width = s.width * size.width, cap = StrokeCap.Round, join = StrokeJoin.Round))
                    }
                }
                // Text stickers: drag to move, tap to edit.
                texts.forEach { t ->
                    var sz by remember(t) { mutableStateOf(IntSize.Zero) }
                    Text(
                        t.text, color = t.color, textAlign = TextAlign.Center,
                        style = TextStyle(fontSize = with(density) { (t.size * w).toSp() }, fontWeight = FontWeight.Bold, shadow = Shadow(Color.Black.copy(alpha = 0.6f), Offset(0f, 2f), 6f)),
                        modifier = Modifier.onSizeChanged { sz = it }
                            .offset { IntOffset((t.x * w - sz.width / 2f).roundToInt(), (t.y * h - sz.height / 2f).roundToInt()) }
                            .pointerInput(t) {
                                detectDragGestures { change, drag -> change.consume(); t.x = (t.x + drag.x / w).coerceIn(0f, 1f); t.y = (t.y + drag.y / h).coerceIn(0f, 1f) }
                            }
                            .pointerInput(t) { detectTapGestures { typing = t } },
                    )
                }
            }
        }

        // Top bar: cancel · undo · rotate · done — hidden while writing a text (that has its own).
        if (typing == null) Row(Modifier.fillMaxWidth().padding(top = top).padding(horizontal = 6.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onCancel) { Icon(Icons.Default.Close, stringResource(R.string.cancel), tint = Color.White) }
            Spacer(Modifier.weight(1f))
            ToolChip(app.zura.ui.ZuraIcons.Undo, enabled = history.isNotEmpty()) {
                haptics.tick()
                when (val last = history.removeLastOrNull()) { is Stroke2 -> strokes.remove(last); is TextItem -> texts.remove(last) }
            }
            Spacer(Modifier.width(10.dp))
            ToolChip(app.zura.ui.ZuraIcons.RotateRight, enabled = base != null) {
                haptics.tick()
                base = base?.let { rotate90(it) }
                // Everything turns with the photo: (x, y) → (1 − y, x).
                strokes.forEach { s -> s.points.replaceAll { Offset(1f - it.y, it.x) } }
                texts.forEach { t -> val x = t.x; t.x = 1f - t.y; t.y = x }
            }
            Spacer(Modifier.width(10.dp))
            Box(
                Modifier.clip(CircleShape).background(Zura.colors.accent).clickable(enabled = base != null && !saving) {
                    val b = base ?: return@clickable
                    saving = true
                    scope.launch {
                        val out = withContext(Dispatchers.IO) { runCatching { render(ctx, g, b, strokes.toList(), texts.toList(), source) }.getOrNull() }
                        saving = false
                        if (out != null) { haptics.confirm(); onDone(out) }
                    }
                }.padding(horizontal = 18.dp, vertical = 10.dp),
                contentAlignment = Alignment.Center,
            ) {
                if (saving) CircularProgressIndicator(Modifier.size(18.dp), color = Zura.colors.onAccent, strokeWidth = 2.dp)
                else Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Done, null, tint = Zura.colors.onAccent, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(stringResource(R.string.edit_done), style = Zura.type.label, color = Zura.colors.onAccent)
                }
            }
        }

        // Bottom: tools, colours, brush size
        // Above the phone's gesture / navigation bar (its height comes in [insets]).
        if (typing == null) Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(bottom = bottom + 12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                ModeChip(app.zura.ui.ZuraIcons.Brush, stringResource(R.string.edit_draw), mode == Mode.DRAW) { haptics.tick(); mode = Mode.DRAW }
                ModeChip(app.zura.ui.ZuraIcons.TextFields, stringResource(R.string.edit_text), false) {
                    haptics.tick(); mode = Mode.MOVE
                    typing = TextItem("", color, 0.5f, 0.5f, 0.07f).also { it.isNew = true }
                }
                ModeChip(app.zura.ui.ZuraIcons.Move, stringResource(R.string.edit_move), mode == Mode.MOVE) { haptics.tick(); mode = Mode.MOVE }
            }
            // Brush sizes (drawing only), then the colours (scroll if the screen is narrow).
            AnimatedVisibility(mode == Mode.DRAW) {
                Row(Modifier.padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically) {
                    BRUSHES.forEach { b ->
                        Box(Modifier.size(34.dp).clip(CircleShape).background(Color.White.copy(alpha = if (b == brush) 0.22f else 0f)).clickable { haptics.tick(); brush = b }, contentAlignment = Alignment.Center) {
                            Box(Modifier.size((b * 900).dp.coerceAtMost(20.dp)).clip(CircleShape).background(color))
                        }
                    }
                }
            }
            ColorRow(color, Modifier.padding(top = 10.dp)) { haptics.tick(); color = it }
        }

        // Writing / editing a text sticker.
        AnimatedVisibility(typing != null, enter = fadeIn() + slideInVertically { it / 3 }, exit = fadeOut() + slideOutVertically { it / 3 }) {
            val t = typing ?: return@AnimatedVisibility
            var value by remember(t) { mutableStateOf(t.text) }
            val focus = remember { FocusRequester() }
            LaunchedEffect(t) { runCatching { focus.requestFocus() } }
            val imeUp = WindowInsets.ime.getBottom(LocalDensity.current) > 0
            Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.6f)).pointerInput(Unit) { awaitPointerEventScope { while (true) awaitPointerEvent() } }.imePadding()) {
                BasicTextField(
                    value, { value = it },
                    textStyle = TextStyle(color = t.color, fontSize = Zura.type.title.fontSize, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center),
                    cursorBrush = SolidColor(Color.White),
                    modifier = Modifier.align(Alignment.Center).fillMaxWidth().padding(24.dp).focusRequester(focus),
                )
                Row(Modifier.align(Alignment.TopEnd).padding(top = top).padding(12.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    if (!t.isNew) ToolChip(androidx.compose.material.icons.Icons.Default.Delete, true) { texts.remove(t); history.remove(t); typing = null }
                    ToolChip(Icons.Default.Done, true, accent = true) {
                        val txt = value.trim()
                        if (txt.isEmpty()) { if (!t.isNew) { texts.remove(t); history.remove(t) } }
                        else { t.text = txt; if (t.isNew) { t.isNew = false; texts += t; history += t } }
                        typing = null
                    }
                }
                // The text's colour, right above the keyboard (or the navigation bar).
                ColorRow(t.color, Modifier.align(Alignment.BottomCenter).padding(bottom = if (imeUp) 12.dp else bottom + 12.dp)) { haptics.tick(); color = it; t.color = it }
            }
        }
    }
}

private enum class Mode { DRAW, MOVE }

private class Stroke2(val color: Color, val width: Float, val points: androidx.compose.runtime.snapshots.SnapshotStateList<Offset>)

/** A text sticker; position = its centre, size = font size — all as fractions of the image width/height. */
private class TextItem(text: String, color: Color, x: Float, y: Float, val size: Float) {
    var text by mutableStateOf(text)
    var color by mutableStateOf(color)
    var x by mutableStateOf(x)
    var y by mutableStateOf(y)
    var isNew = false
}

private val PALETTE = listOf(
    Color.White, Color(0xFFFF3B30), Color(0xFFFF9500), Color(0xFFFFCC00), Color(0xFF34C759),
    Color(0xFF0A84FF), Color(0xFFAF52DE), Color(0xFFFF2D92), Color.Black,
)
/** Brush widths as a fraction of the image width. */
private val BRUSHES = listOf(0.006f, 0.012f, 0.024f)

/** Round tool button: a 24 dp icon, exactly centred in a 48 dp circle. */
@Composable
private fun ToolChip(icon: androidx.compose.ui.graphics.vector.ImageVector, enabled: Boolean, accent: Boolean = false, onClick: () -> Unit) {
    val bg = if (accent) Zura.colors.accent else Color(0xFF2C2C2E).copy(alpha = if (enabled) 1f else 0.6f)
    val fg = if (accent) Zura.colors.onAccent else Color.White.copy(alpha = if (enabled) 1f else 0.35f)
    Box(
        Modifier.size(48.dp).clip(CircleShape).background(bg).clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { Icon(icon, null, tint = fg, modifier = Modifier.size(24.dp)) }
}

/** The colours (scroll if the screen is narrow); the chosen one grows. */
@Composable
private fun ColorRow(selected: Color, modifier: Modifier = Modifier, onPick: (Color) -> Unit) {
    androidx.compose.foundation.lazy.LazyRow(
        modifier.fillMaxWidth(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        items(PALETTE.size) { i ->
            val col = PALETTE[i]
            val sel = col == selected
            val grow by animateFloatAsState(if (sel) 1.25f else 1f, ZuraMotion.Bouncy, label = "col")
            Box(
                Modifier.padding(4.dp).size(26.dp).graphicsLayer { scaleX = grow; scaleY = grow }.clip(CircleShape).background(col)
                    .border(2.dp, if (sel) Color.White else Color.White.copy(alpha = 0.3f), CircleShape)
                    .clickable { onPick(col) },
            )
        }
    }
}

/** Tool mode: icon + label, centred in a pill. */
@Composable
private fun ModeChip(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, selected: Boolean, onClick: () -> Unit) {
    val fg = if (selected) Color.Black else Color.White
    Row(
        Modifier.clip(RoundedCornerShape(22.dp)).background(if (selected) Color.White else Color.White.copy(alpha = 0.14f))
            .clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, tint = fg, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(8.dp))
        Text(label, color = fg, style = Zura.type.label)
    }
}

/** The photo, upright (EXIF applied), at most 2560 px on its long edge, editable (software bitmap). */
private fun decodeForEdit(ctx: android.content.Context, uri: Uri): Bitmap? = runCatching {
    val max = 2560
    if (android.os.Build.VERSION.SDK_INT >= 28) {
        android.graphics.ImageDecoder.decodeBitmap(android.graphics.ImageDecoder.createSource(ctx.contentResolver, uri)) { d, info, _ ->
            d.allocator = android.graphics.ImageDecoder.ALLOCATOR_SOFTWARE
            val edge = max(info.size.width, info.size.height)
            if (edge > max) { val s = max.toFloat() / edge; d.setTargetSize((info.size.width * s).toInt(), (info.size.height * s).toInt()) }
        }
    } else {
        val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
        ctx.contentResolver.openInputStream(uri)?.use { android.graphics.BitmapFactory.decodeStream(it, null, bounds) }
        var sample = 1
        while (max(bounds.outWidth, bounds.outHeight) / (sample * 2) >= max) sample *= 2
        val raw = ctx.contentResolver.openInputStream(uri)?.use { android.graphics.BitmapFactory.decodeStream(it, null, android.graphics.BitmapFactory.Options().apply { inSampleSize = sample }) }
        val deg = ctx.contentResolver.openInputStream(uri)?.use {
            when (android.media.ExifInterface(it).getAttributeInt(android.media.ExifInterface.TAG_ORIENTATION, android.media.ExifInterface.ORIENTATION_NORMAL)) {
                android.media.ExifInterface.ORIENTATION_ROTATE_90 -> 90
                android.media.ExifInterface.ORIENTATION_ROTATE_180 -> 180
                android.media.ExifInterface.ORIENTATION_ROTATE_270 -> 270
                else -> 0
            }
        } ?: 0
        if (raw != null && deg != 0) Bitmap.createBitmap(raw, 0, 0, raw.width, raw.height, android.graphics.Matrix().apply { postRotate(deg.toFloat()) }, true) else raw
    }
}.getOrNull()

private fun rotate90(b: Bitmap): Bitmap = Bitmap.createBitmap(b, 0, 0, b.width, b.height, android.graphics.Matrix().apply { postRotate(90f) }, true)

/** Bakes drawing + text into the photo → a new staged JPEG (private storage; wiped after sending). */
private fun render(ctx: android.content.Context, g: app.zura.AppGraph, base: Bitmap, strokes: List<Stroke2>, texts: List<TextItem>, source: Attachment): Attachment {
    val out = base.copy(Bitmap.Config.ARGB_8888, true)
    val canvas = android.graphics.Canvas(out)
    val w = out.width.toFloat(); val h = out.height.toFloat()
    val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
        style = android.graphics.Paint.Style.STROKE; strokeCap = android.graphics.Paint.Cap.ROUND; strokeJoin = android.graphics.Paint.Join.ROUND
    }
    strokes.forEach { s ->
        paint.color = s.color.toArgb(); paint.strokeWidth = s.width * w
        if (s.points.size == 1) { // a dot
            paint.style = android.graphics.Paint.Style.FILL
            canvas.drawCircle(s.points[0].x * w, s.points[0].y * h, s.width * w / 2, paint)
            paint.style = android.graphics.Paint.Style.STROKE
            return@forEach
        }
        val p = android.graphics.Path()
        s.points.forEachIndexed { i, pt -> if (i == 0) p.moveTo(pt.x * w, pt.y * h) else p.lineTo(pt.x * w, pt.y * h) }
        canvas.drawPath(p, paint)
    }
    val tp = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
        typeface = android.graphics.Typeface.DEFAULT_BOLD; textAlign = android.graphics.Paint.Align.CENTER
    }
    texts.forEach { t ->
        tp.color = t.color.toArgb(); tp.textSize = t.size * w
        tp.setShadowLayer(t.size * w * 0.12f, 0f, t.size * w * 0.05f, 0x99000000.toInt())
        val lines = t.text.split('\n')
        val lh = tp.fontSpacing
        val top = t.y * h - lh * lines.size / 2f
        lines.forEachIndexed { i, line -> canvas.drawText(line, t.x * w, top + lh * i - tp.ascent(), tp) }
    }
    val file = g.vault.paths.newTemp("jpg")
    file.outputStream().use { out.compress(Bitmap.CompressFormat.JPEG, 92, it) }
    source.temp?.let { app.zura.core.storage.SecureEraser.erase(it) } // the previous staged copy
    return Attachment(Uri.fromFile(file), "image/jpeg", source.name, file)
}
