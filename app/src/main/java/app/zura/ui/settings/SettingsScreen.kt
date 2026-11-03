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
import app.zura.ui.components.UserAvatar
import app.zura.i18n.LocaleEngine
import app.zura.i18n.ZuraFormat
import app.zura.ui.components.glass
import app.zura.ui.components.rememberHaptics
import app.zura.ui.theme.Zura
import app.zura.ui.theme.ZuraPalettes
import app.zura.ui.theme.ZuraThemeId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.launch

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

        Section(R.string.chat_colors) { BubblePicker() }

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

        Section(R.string.privacy) {
            val secure by g.settings.secureScreen.collectAsStateWithLifecycle()
            Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable { haptics.tick(); g.settings.setSecureScreen(!secure) }, verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.secure_screen), style = Zura.type.label, color = c.text)
                    Text(stringResource(R.string.secure_screen_hint), style = Zura.type.caption, color = c.textMuted)
                }
                androidx.compose.material3.Switch(
                    secure, { g.settings.setSecureScreen(it) },
                    colors = androidx.compose.material3.SwitchDefaults.colors(checkedTrackColor = c.accent, checkedThumbColor = c.onAccent),
                )
            }
            Spacer(Modifier.height(12.dp))
            Text(stringResource(R.string.push_status), style = Zura.type.label, color = c.text)
            Text(
                stringResource(if (app.zura.push.Push.enabled) R.string.push_on else R.string.push_off),
                style = Zura.type.caption, color = if (app.zura.push.Push.enabled) c.textMuted else c.danger,
            )
            if (!app.zura.push.PushPermissions.fullScreenAllowed(ctx)) {
                Text(
                    stringResource(R.string.fullscreen_calls) + " →", style = Zura.type.label, color = c.accent,
                    modifier = Modifier.padding(top = 6.dp).clickable { app.zura.push.PushPermissions.openFullScreenSettings(ctx) },
                )
            }
            Spacer(Modifier.height(12.dp))
            Text(stringResource(R.string.encrypted_storage), style = Zura.type.label, color = c.text)
            Text(stringResource(R.string.encrypted_storage_hint), style = Zura.type.caption, color = c.textMuted)
            Spacer(Modifier.height(8.dp))
            Text(stringResource(R.string.e2ee_note), style = Zura.type.caption, color = c.textMuted)
        }

        Section(R.string.app_lock) { AppLockSection() }

        Section(R.string.security_title) {
            Text(stringResource(R.string.security_details), style = Zura.type.label, color = c.textMuted)
        }

        Section(R.string.account) { AccountCard() }

        app.zura.CrashLog.read(ctx)?.let { crash ->
            Section(R.string.crash_report) {
                Text(stringResource(R.string.crash_report_hint), style = Zura.type.caption, color = c.textMuted)
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Box(Modifier.clip(CircleShape).background(c.accent).clickable {
                        ctx.getSystemService(android.content.ClipboardManager::class.java)
                            .setPrimaryClip(android.content.ClipData.newPlainText("zura-crash", crash))
                        android.widget.Toast.makeText(ctx, R.string.copied, android.widget.Toast.LENGTH_SHORT).show()
                    }.padding(horizontal = 16.dp, vertical = 9.dp)) { Text(stringResource(R.string.action_copy), color = c.onAccent, style = Zura.type.label) }
                    Box(Modifier.clip(CircleShape).background(c.surfaceHigh).clickable { app.zura.CrashLog.clear(ctx) }
                        .padding(horizontal = 16.dp, vertical = 9.dp)) { Text(stringResource(R.string.delete), color = c.text, style = Zura.type.label) }
                }
            }
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

@Composable
private fun AccountCard() {
    val ctx = LocalContext.current
    val g = ctx.graph
    val c = Zura.colors
    val me by g.s.contacts.myProfile.collectAsStateWithLifecycle()
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    var confirm by remember { androidx.compose.runtime.mutableStateOf(false) }
    var busy by remember { androidx.compose.runtime.mutableStateOf(false) }
    val pick = androidx.activity.compose.rememberLauncherForActivityResult(androidx.activity.result.contract.ActivityResultContracts.PickVisualMedia()) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        busy = true
        scope.launch {
            withContext(Dispatchers.Default) {
                runCatching {
                    val src = ctx.contentResolver.openInputStream(uri)!!.use { android.graphics.BitmapFactory.decodeStream(it) }
                    val side = minOf(src.width, src.height)
                    val sq = android.graphics.Bitmap.createBitmap(src, (src.width - side) / 2, (src.height - side) / 2, side, side)
                    val out = android.graphics.Bitmap.createScaledBitmap(sq, 512, 512, true)
                    java.io.ByteArrayOutputStream().also { out.compress(android.graphics.Bitmap.CompressFormat.JPEG, 86, it) }.toByteArray()
                }.getOrNull()
            }?.let { bytes -> runCatching { g.s.contacts.setAvatar(bytes); g.accountStore.avatar(g.s.uid).write(bytes) } }
            busy = false
        }
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.clickable { pick.launch(androidx.activity.result.PickVisualMediaRequest(androidx.activity.result.contract.ActivityResultContracts.PickVisualMedia.ImageOnly)) }) {
            UserAvatar(me?.displayName ?: "", (g.s.uid.hashCode() and 0xFFFF) % 360f, me?.avatarPath, 64.dp)
            if (busy) androidx.compose.material3.CircularProgressIndicator(Modifier.size(64.dp), color = c.accent, strokeWidth = 2.dp)
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(me?.displayName ?: "", style = Zura.type.headline, color = c.text)
            Text(app.zura.i18n.handle(me?.username ?: ""), style = Zura.type.label, color = c.textMuted)
            Text(g.s.email, style = Zura.type.caption, color = c.textMuted)
        }
    }
    Spacer(Modifier.height(14.dp))
    Box(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(c.danger.copy(alpha = 0.12f)).clickable { confirm = true }.padding(14.dp),
        contentAlignment = Alignment.Center,
    ) { Text(stringResource(R.string.logout), style = Zura.type.label, color = c.danger) }

    if (confirm) {
        var keep by remember { androidx.compose.runtime.mutableStateOf(g.accountStore.get(g.s.uid)?.remember ?: true) }
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { confirm = false },
            containerColor = c.surfaceHigh,
            title = { Text(stringResource(R.string.logout_title), color = c.text) },
            text = {
                Row(Modifier.clickable { keep = !keep }, verticalAlignment = Alignment.CenterVertically) {
                    androidx.compose.material3.Checkbox(keep, { keep = it }, colors = androidx.compose.material3.CheckboxDefaults.colors(checkedColor = c.accent))
                    Text(stringResource(R.string.logout_remember), style = Zura.type.label, color = c.text)
                }
            },
            confirmButton = {
                androidx.compose.material3.TextButton(onClick = { confirm = false; scope.launch { g.accounts.logout(remember = keep) } }) {
                    Text(stringResource(R.string.logout), color = c.danger)
                }
            },
            dismissButton = { androidx.compose.material3.TextButton(onClick = { confirm = false }) { Text(stringResource(R.string.cancel), color = c.text) } },
        )
    }
}

/** Message colour presets: each swatch shows "my" bubble over "their" bubble, in the current theme. */
@Composable
private fun BubblePicker() {
    val g = LocalContext.current.graph
    val c = Zura.colors
    val haptics = rememberHaptics()
    val current by g.settings.bubbles.collectAsStateWithLifecycle()
    val theme by g.settings.theme.collectAsStateWithLifecycle()
    val base = ZuraPalettes.of(theme)
    androidx.compose.foundation.lazy.LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        items(app.zura.ui.theme.BubbleStyle.entries.size) { i ->
            val style = app.zura.ui.theme.BubbleStyle.entries[i]
            val p = style.applyTo(base)
            val ring by animateDpAsState(if (style == current) 2.dp else 0.dp, label = "bubble")
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Column(
                    Modifier.size(64.dp, 76.dp).clip(RoundedCornerShape(16.dp)).background(base.bg)
                        .border(ring, c.accent, RoundedCornerShape(16.dp))
                        .clickable { haptics.tick(); g.settings.setBubbles(style) }
                        .padding(8.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Box(Modifier.size(34.dp, 14.dp).clip(CircleShape).background(p.bubbleTheirs))
                    Box(Modifier.align(Alignment.End).size(38.dp, 14.dp).clip(CircleShape).background(Brush.linearGradient(listOf(p.bubbleMine, p.bubbleMineAlt))))
                    Box(Modifier.size(26.dp, 14.dp).clip(CircleShape).background(p.bubbleTheirs))
                }
                Text(
                    androidx.compose.ui.res.stringResource(bubbleName(style)),
                    style = Zura.type.caption, color = if (style == current) c.text else c.textMuted, modifier = Modifier.padding(top = 6.dp),
                )
            }
        }
    }
}

private fun bubbleName(s: app.zura.ui.theme.BubbleStyle) = when (s) {
    app.zura.ui.theme.BubbleStyle.THEME -> R.string.bubbles_theme
    app.zura.ui.theme.BubbleStyle.OCEAN -> R.string.bubbles_ocean
    app.zura.ui.theme.BubbleStyle.LAVENDER -> R.string.bubbles_lavender
    app.zura.ui.theme.BubbleStyle.ROSE -> R.string.bubbles_rose
    app.zura.ui.theme.BubbleStyle.EMERALD -> R.string.bubbles_emerald
    app.zura.ui.theme.BubbleStyle.SUNSET -> R.string.bubbles_sunset
    app.zura.ui.theme.BubbleStyle.GRAPHITE -> R.string.bubbles_graphite
}

@Composable
private fun AppLockSection() {
    val ctx = LocalContext.current
    val g = ctx.graph
    val c = Zura.colors
    val haptics = rememberHaptics()
    val enabled by g.appLock.enabled.collectAsStateWithLifecycle()
    val timeout by g.appLock.timeoutSec.collectAsStateWithLifecycle()
    val secure = remember { g.appLock.deviceSecure() }
    val activity = ctx as Activity
    val legacy = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult(),
    ) { r -> if (r.resultCode == Activity.RESULT_OK) { g.appLock.legacyResult(true); g.appLock.setEnabled(true) } }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(stringResource(R.string.app_lock), style = Zura.type.label, color = c.text)
            Text(
                stringResource(if (secure) R.string.app_lock_hint else R.string.app_lock_needs_screen_lock),
                style = Zura.type.caption, color = if (secure) c.textMuted else c.danger,
            )
        }
        androidx.compose.material3.Switch(
            enabled,
            { on ->
                haptics.tick()
                // Turning it ON first proves the fingerprint/PIN works, so the user can't lock themselves out.
                if (on) g.appLock.authenticate(activity, { legacy.launch(it) }) { ok -> if (ok) g.appLock.setEnabled(true) }
                else g.appLock.setEnabled(false)
            },
            enabled = secure,
            colors = androidx.compose.material3.SwitchDefaults.colors(checkedTrackColor = c.accent, checkedThumbColor = c.onAccent),
        )
    }
    if (enabled) {
        Spacer(Modifier.height(12.dp))
        Text(stringResource(R.string.lock_after), style = Zura.type.caption, color = c.textMuted)
        Spacer(Modifier.height(6.dp))
        Row(Modifier.fillMaxWidth().clip(CircleShape).background(c.surfaceHigh).padding(4.dp)) {
            listOf(0 to R.string.lock_immediately, 60 to R.string.lock_1m, 300 to R.string.lock_5m, 1800 to R.string.lock_30m).forEach { (sec, label) ->
                val sel = sec == timeout
                Box(
                    Modifier.weight(1f).clip(CircleShape).background(if (sel) c.accent else c.surfaceHigh)
                        .clickable { haptics.tick(); g.appLock.setTimeout(sec) }.padding(vertical = 8.dp),
                    contentAlignment = Alignment.Center,
                ) { Text(stringResource(label), style = Zura.type.caption, color = if (sel) c.onAccent else c.text, maxLines = 1) }
            }
        }
    }
}
