package app.zura.ui.settings

import android.app.Activity
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.zura.R
import app.zura.graph
import app.zura.i18n.LocaleEngine
import app.zura.i18n.ZuraFormat
import app.zura.ui.components.glass
import app.zura.ui.components.rememberHaptics
import app.zura.ui.theme.Zura
import app.zura.ui.theme.ZuraPalettes
import app.zura.ui.theme.ZuraThemeId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
fun SettingsScreen(onBack: () -> Unit) {
    val ctx = LocalContext.current
    val g = ctx.graph
    val c = Zura.colors
    val haptics = rememberHaptics()
    val theme by g.settings.theme.collectAsStateWithLifecycle()
    val fmt = ZuraFormat(LocalConfiguration.current.locales[0])
    var used by remember { mutableLongStateOf(0L) }
    LaunchedEffect(Unit) { used = withContext(Dispatchers.IO) { g.vault.totalBytes() } }

    Column(Modifier.fillMaxSize().statusBarsPadding().verticalScroll(rememberScrollState())) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(8.dp)) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, null, tint = c.text) }
            Text(stringResource(R.string.settings), style = Zura.type.title, color = c.text)
        }

        Section(R.string.theme) {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                ZuraThemeId.entries.forEach { id ->
                    val p = ZuraPalettes.of(id)
                    val ring by animateDpAsState(if (id == theme) 2.dp else 0.dp, label = "ring")
                    Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                        Box(
                            Modifier.fillMaxWidth().aspectRatio(0.62f).clip(RoundedCornerShape(16.dp))
                                .background(Brush.verticalGradient(listOf(p.bgAccent, p.bg)))
                                .border(ring, c.accent, RoundedCornerShape(16.dp))
                                .clickable { haptics.tick(); g.settings.setTheme(id) }
                                .padding(8.dp),
                        ) {
                            // Mini chat preview inside each swatch
                            Column(verticalArrangement = Arrangement.spacedBy(5.dp), modifier = Modifier.align(Alignment.BottomCenter)) {
                                Box(Modifier.width(34.dp).height(10.dp).clip(CircleShape).background(p.bubbleTheirs))
                                Box(Modifier.align(Alignment.End).width(40.dp).height(10.dp).clip(CircleShape).background(Brush.linearGradient(listOf(p.bubbleMine, p.bubbleMineAlt))))
                            }
                        }
                        Text(stringResource(themeName(id)), style = Zura.type.caption, color = c.textMuted, modifier = Modifier.padding(top = 6.dp))
                    }
                }
            }
        }

        Section(R.string.language) {
            val current = LocaleEngine.current(ctx)
            Row(Modifier.fillMaxWidth().clip(CircleShape).background(c.surfaceHigh).padding(4.dp)) {
                LocaleEngine.Lang.entries.forEach { l ->
                    val sel = l == current
                    Box(
                        Modifier.weight(1f).clip(CircleShape).background(if (sel) c.accent else c.surfaceHigh)
                            .clickable(enabled = !sel) { haptics.tick(); LocaleEngine.set(ctx as Activity, l) }
                            .padding(vertical = 10.dp),
                        contentAlignment = Alignment.Center,
                    ) { Text(stringResource(if (l == LocaleEngine.Lang.EN) R.string.lang_en else R.string.lang_ar), color = if (sel) c.onAccent else c.text, style = Zura.type.label) }
                }
            }
        }

        Section(R.string.storage) {
            Text(stringResource(R.string.storage_used, fmt.size(used)), style = Zura.type.label, color = c.text)
            Spacer(Modifier.height(6.dp))
            Text(stringResource(R.string.about_privacy), style = Zura.type.caption, color = c.textMuted)
        }

        Section(R.string.my_id) {
            Text(g.settings.userId, style = Zura.type.label, color = c.text)
        }
        Spacer(Modifier.size(40.dp))
    }
}

@Composable
private fun Section(title: Int, content: @Composable () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp).glass(RoundedCornerShape(22.dp)).padding(16.dp)) {
        Text(stringResource(title), style = Zura.type.headline, color = Zura.colors.text, modifier = Modifier.padding(bottom = 12.dp))
        content()
    }
}

private fun themeName(id: ZuraThemeId) = when (id) {
    ZuraThemeId.MIDNIGHT -> R.string.theme_midnight
    ZuraThemeId.OLED -> R.string.theme_oled
    ZuraThemeId.VELVET_GOLD -> R.string.theme_velvet
    ZuraThemeId.LIGHT -> R.string.theme_light
}
