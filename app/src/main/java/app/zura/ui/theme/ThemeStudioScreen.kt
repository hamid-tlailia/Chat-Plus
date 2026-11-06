package app.zura.ui.theme

import android.content.Intent
import android.graphics.BitmapFactory
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.zura.R
import app.zura.graph
import app.zura.ui.components.rememberHaptics
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** Curated, harmonious choices (fine-tune any of them with the hue slider). */
private val MINE_SWATCHES = listOf(0xFF6D5DF6, 0xFF4F8BFF, 0xFF0EA5E9, 0xFF14B8A6, 0xFF10B981, 0xFFF59E0B, 0xFFF97316, 0xFFF43F5E, 0xFFDB2777, 0xFF111114)
private val THEIR_SWATCHES = listOf(0xFFF0F0F4, 0xFFE4F1FB, 0xFFF0EBFE, 0xFFE3F6EE, 0xFFFDF0DC, 0xFF1C1C26, 0xFF13283A, 0xFF241B3A, 0xFF12302A, 0xFF2A1E21)

private class Preset(val mineA: Long, val mineB: Long, val theirs: Long)
private val PRESETS = listOf(
    Preset(0xFF6D5DF6, 0xFF4F8BFF, 0xFF1C1C26), Preset(0xFF0EA5E9, 0xFF2563EB, 0xFFE4F1FB), Preset(0xFF10B981, 0xFF047857, 0xFFE3F6EE),
    Preset(0xFFF43F5E, 0xFFDB2777, 0xFF2A1E21), Preset(0xFFFBBF24, 0xFFF97316, 0xFFFDF0DC), Preset(0xFF52525B, 0xFF27272A, 0xFFF0F0F4),
)

/** Design your own theme: colours, bubble shape, font and wallpaper — then apply or share it. */
@Composable
/** [chatId]: opened from that chat's profile → "Apply" makes it that chat's style. */
fun ThemeStudioScreen(onBack: () -> Unit, chatId: String? = null) {
    val ctx = LocalContext.current
    val g = ctx.graph
    val haptics = rememberHaptics()
    val scope = rememberCoroutineScope()
    val saved by g.settings.customTheme.collectAsStateWithLifecycle()
    val files by g.settings.themeFiles.collectAsStateWithLifecycle()
    var t by remember { mutableStateOf(saved ?: CustomTheme()) }
    // A chat style: previewed on the app's current light / dark theme.
    val p = t.applyTo(Zura.colors)
    var open by remember { mutableStateOf<String?>(null) } // which colour row is expanded
    val font = remember(files) { CustomTheme.fontFamily(ctx) }
    val wall by produceState<androidx.compose.ui.graphics.ImageBitmap?>(null, files) {
        value = withContext(Dispatchers.IO) { CustomTheme.wallFile(ctx).takeIf { it.exists() }?.let { BitmapFactory.decodeFile(it.absolutePath)?.asImageBitmap() } }
    }
    val pickWall = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        scope.launch {
            withContext(Dispatchers.IO) {
                runCatching {
                    val bmp = if (android.os.Build.VERSION.SDK_INT >= 28) android.graphics.ImageDecoder.decodeBitmap(android.graphics.ImageDecoder.createSource(ctx.contentResolver, uri)) { d, _, _ -> d.allocator = android.graphics.ImageDecoder.ALLOCATOR_SOFTWARE }
                    else ctx.contentResolver.openInputStream(uri)!!.use { BitmapFactory.decodeStream(it) }
                    CustomTheme.saveWallpaper(ctx, bmp)
                }
            }
            g.settings.themeFiles.value++
        }
    }
    val pickFont = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        scope.launch {
            val ok = withContext(Dispatchers.IO) {
                runCatching {
                    val bytes = ctx.contentResolver.openInputStream(uri)!!.use { it.readBytes() }
                    require(bytes.size in 1..3_000_000)
                    val f = CustomTheme.fontFile(ctx).apply { parentFile?.mkdirs() }
                    f.writeBytes(bytes)
                    // Reject files that aren't fonts.
                    if (CustomTheme.fontFamily(ctx) == null) { f.delete(); error("not a font") }
                }.isSuccess
            }
            if (!ok) android.widget.Toast.makeText(ctx, R.string.font_invalid, android.widget.Toast.LENGTH_SHORT).show()
            g.settings.themeFiles.value++
        }
    }

    Column(Modifier.fillMaxSize().background(p.bg).statusBarsPadding()) {
        Row(Modifier.fillMaxWidth().height(60.dp).padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, null, tint = p.text) }
            Text(stringResource(R.string.theme_studio), style = Zura.type.headline, color = p.text, modifier = Modifier.weight(1f))
        }
        // Live preview — pinned above the controls, so every change is visible while scrolling.
            Box(
                Modifier.padding(horizontal = 16.dp).fillMaxWidth().height(190.dp).clip(RoundedCornerShape(24.dp))
                    .background(Brush.linearGradient(listOf(p.bg, p.bgAccent, p.bg))),
            ) {
                wall?.let {
                    Image(it, null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                    Box(Modifier.fillMaxSize().background(p.bg.copy(alpha = t.wallDim / 100f)))
                }
                Column(Modifier.fillMaxSize().padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp, Alignment.Bottom)) {
                    PreviewBubble(stringResource(R.string.preview_theirs), false, p, t.radius, font)
                    PreviewBubble(stringResource(R.string.preview_mine), true, p, t.radius, font, Modifier.align(Alignment.End))
                }
            }
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp)) {
            Spacer(Modifier.height(16.dp))

            Label(stringResource(R.string.theme_name), p)
            BasicTextField(
                t.name, { t = t.copy(name = it.take(32)) },
                textStyle = TextStyle(color = p.text, fontSize = 16.sp), cursorBrush = SolidColor(p.accent), singleLine = true,
                modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(p.surfaceHigh).padding(14.dp),
            )

            Label(stringResource(R.string.quick_styles), p)
            androidx.compose.foundation.lazy.LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                items(PRESETS) { pr ->
                    val sel = pr.mineA == t.mineA && pr.mineB == t.mineB && pr.theirs == t.theirs
                    Column(
                        Modifier.size(70.dp, 62.dp).clip(RoundedCornerShape(16.dp)).background(p.surfaceHigh)
                            .border(if (sel) 2.dp else 0.dp, p.accent, RoundedCornerShape(16.dp))
                            .clickable { t = t.copy(mineA = pr.mineA, mineB = pr.mineB, theirs = pr.theirs) }.padding(9.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Box(Modifier.size(36.dp, 14.dp).clip(CircleShape).background(Color(pr.theirs)))
                        Box(Modifier.align(Alignment.End).size(40.dp, 14.dp).clip(CircleShape).background(Brush.linearGradient(listOf(Color(pr.mineA), Color(pr.mineB)))))
                    }
                }
            }

            Label(stringResource(R.string.theme_colors), p)
            Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(p.surfaceHigh)) {
                ColorRow("mineA", stringResource(R.string.theme_mine_a), t.mineA, MINE_SWATCHES, open, p, { open = if (open == it) null else it }) { t = t.copy(mineA = it) }
                ColorRow("mineB", stringResource(R.string.theme_mine_b), t.mineB, MINE_SWATCHES, open, p, { open = if (open == it) null else it }) { t = t.copy(mineB = it) }
                ColorRow("theirs", stringResource(R.string.theme_theirs), t.theirs, THEIR_SWATCHES, open, p, { open = if (open == it) null else it }) { t = t.copy(theirs = it) }
            }

            Label(stringResource(R.string.theme_radius) + " · ${t.radius}", p)
            Slider(t.radius.toFloat(), { t = t.copy(radius = it.toInt()) }, valueRange = 4f..28f, colors = sliderColors(p))

            Label(stringResource(R.string.theme_font), p)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Chip(stringResource(R.string.font_import), false, p) { pickFont.launch(arrayOf("font/*", "application/x-font-ttf", "application/x-font-otf", "application/octet-stream")) }
                if (font != null) Chip(stringResource(R.string.font_reset), false, p) { CustomTheme.fontFile(ctx).delete(); g.settings.themeFiles.value++ }
            }

            Label(stringResource(R.string.theme_wallpaper), p)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Chip(stringResource(R.string.wallpaper_pick), false, p) { pickWall.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }
                if (wall != null) Chip(stringResource(R.string.wallpaper_remove), false, p) { CustomTheme.wallFile(ctx).delete(); g.settings.themeFiles.value++ }
            }
            if (wall != null) {
                Label(stringResource(R.string.wallpaper_dim) + " · ${t.wallDim}%", p)
                Slider(t.wallDim.toFloat(), { t = t.copy(wallDim = it.toInt()) }, valueRange = 0f..60f, colors = sliderColors(p))
            }
            Spacer(Modifier.height(24.dp))
        }
        Row(
            Modifier.fillMaxWidth().navigationBarsPadding().padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Box(
                Modifier.weight(1f).height(52.dp).clip(RoundedCornerShape(26.dp)).background(p.surfaceHigh).clickable {
                    haptics.tick()
                    scope.launch {
                        val f = withContext(Dispatchers.IO) {
                            val dir = File(ctx.cacheDir, "export").apply { mkdirs() }
                            File(dir, t.name.replace(Regex("[^\\p{L}\\p{N} _-]"), "").ifBlank { "theme" } + CustomTheme.EXT).apply { writeBytes(CustomTheme.export(ctx, t)) }
                        }
                        val uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.files", f)
                        ctx.startActivity(
                            Intent.createChooser(
                                Intent(Intent.ACTION_SEND).setType(CustomTheme.MIME).putExtra(Intent.EXTRA_STREAM, uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION),
                                null,
                            ),
                        )
                    }
                },
                contentAlignment = Alignment.Center,
            ) { Text(stringResource(R.string.theme_share), style = Zura.type.label, color = p.text) }
            Box(
                Modifier.weight(1f).height(52.dp).clip(RoundedCornerShape(26.dp)).background(p.accent).clickable {
                    haptics.confirm(); g.settings.saveCustom(t, apply = false)
                    chatId?.let { g.settings.setChatStyle(it, app.zura.data.SettingsStore.STYLE_CUSTOM) }
                    onBack()
                },
                contentAlignment = Alignment.Center,
            ) { Text(stringResource(R.string.theme_apply), style = Zura.type.label, color = p.onAccent) }
        }
    }
}

@Composable
private fun PreviewBubble(text: String, mine: Boolean, p: ZuraPalette, radius: Int, font: androidx.compose.ui.text.font.FontFamily?, modifier: Modifier = Modifier) {
    Box(
        modifier.widthIn(max = 220.dp).clip(RoundedCornerShape(radius.dp))
            .background(if (mine) Brush.linearGradient(listOf(p.bubbleMine, p.bubbleMineAlt)) else Brush.linearGradient(listOf(p.bubbleTheirs, p.bubbleTheirs)))
            .padding(horizontal = 14.dp, vertical = 9.dp),
    ) { Text(text, style = Zura.type.body.copy(fontFamily = font), color = if (mine) p.onBubbleMine else p.onBubbleTheirs) }
}

@Composable
private fun Label(text: String, p: ZuraPalette) {
    Text(text, style = Zura.type.label, color = p.textMuted, modifier = Modifier.padding(top = 18.dp, bottom = 8.dp))
}

@Composable
private fun Chip(text: String, on: Boolean, p: ZuraPalette, onClick: () -> Unit) {
    Box(
        Modifier.clip(CircleShape).background(if (on) p.accent else p.surfaceHigh).clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 9.dp),
    ) { Text(text, style = Zura.type.label, color = if (on) p.onAccent else p.text) }
}

@Composable
private fun sliderColors(p: ZuraPalette) = SliderDefaults.colors(thumbColor = p.accent, activeTrackColor = p.accent, inactiveTrackColor = p.text.copy(alpha = 0.15f))

/** One colour: a compact row; tap to open 10 curated swatches + a hue slider (one row open at a time). */
@Composable
private fun ColorRow(id: String, title: String, value: Long, swatches: List<Long>, open: String?, p: ZuraPalette, onToggle: (String) -> Unit, onChange: (Long) -> Unit) {
    val expanded = open == id
    Row(Modifier.fillMaxWidth().clickable { onToggle(id) }.padding(horizontal = 16.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(title, style = Zura.type.label, color = p.text, modifier = Modifier.weight(1f))
        Box(Modifier.size(28.dp).clip(CircleShape).background(Color(value)).border(1.dp, p.text.copy(alpha = 0.2f), CircleShape))
        Text(if (expanded) "  ▲" else "  ▼", style = Zura.type.caption, color = p.textMuted)
    }
    androidx.compose.animation.AnimatedVisibility(expanded) {
        Column(Modifier.padding(start = 16.dp, end = 16.dp, bottom = 14.dp)) {
            swatches.chunked(5).forEach { row ->
                Row(Modifier.fillMaxWidth().padding(vertical = 5.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                    row.forEach { sw ->
                        Box(
                            Modifier.size(40.dp).clip(CircleShape).background(Color(sw))
                                .border(if (sw == value) 3.dp else 1.dp, if (sw == value) p.accent else p.text.copy(alpha = 0.15f), CircleShape)
                                .clickable { onChange(sw) },
                        )
                    }
                }
            }
            val hsv = remember(value) { FloatArray(3).also { android.graphics.Color.colorToHSV(value.toInt(), it) } }
            Slider(
                hsv[0], { h -> onChange(android.graphics.Color.HSVToColor(floatArrayOf(h, hsv[1], hsv[2])).toLong() and 0xFFFFFFFFL) },
                valueRange = 0f..360f, colors = SliderDefaults.colors(thumbColor = Color(value), activeTrackColor = Color(value), inactiveTrackColor = p.text.copy(alpha = 0.12f)),
            )
        }
    }
}
