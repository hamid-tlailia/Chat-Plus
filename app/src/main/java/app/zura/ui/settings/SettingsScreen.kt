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
import androidx.compose.foundation.layout.heightIn
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
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.animation.togetherWith
import androidx.compose.ui.graphics.graphicsLayer
import app.zura.ui.components.appear
import app.zura.ui.components.pressScale
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
import androidx.compose.ui.draw.drawBehind
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

/**
 * Settings, WhatsApp-style: a calm menu of groups (icon · title · current value · arrow), each
 * opening its own page. The pages hold the actual switches.
 */
@Composable
fun SettingsScreen(onBack: () -> Unit, onProfile: () -> Unit = {}, onPage: (String) -> Unit = {}) {
    val ctx = LocalContext.current
    val g = ctx.graph
    val c = Zura.colors
    val fmt = ZuraFormat(LocalConfiguration.current.locales[0])
    val me by g.s.contacts.myProfile.collectAsStateWithLifecycle()
    val auto by g.settings.autoTheme.collectAsStateWithLifecycle()
    val theme by g.settings.theme.collectAsStateWithLifecycle()
    val day by g.settings.dayTheme.collectAsStateWithLifecycle()
    val night by g.settings.nightTheme.collectAsStateWithLifecycle()
    var used by remember { mutableLongStateOf(-1L) }
    LaunchedEffect(Unit) { used = withContext(Dispatchers.IO) { g.vault.totalBytes() } }
    val ready = remember { app.zura.push.PushPermissions.allReady(ctx) }

    androidx.compose.foundation.lazy.LazyColumn(Modifier.fillMaxSize().statusBarsPadding()) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(8.dp)) {
                IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, null, tint = c.text) }
                Text(stringResource(R.string.settings), style = Zura.type.title, color = c.text)
            }
        }
        // Me, at the top (opens my profile).
        item {
            val src = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp).appear(0)
                    .pressScale(src, 0.98f).glass(RoundedCornerShape(24.dp))
                    .clickable(src, null, onClick = onProfile).padding(16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                UserAvatar(me?.displayName ?: "", (g.s.uid.hashCode() and 0xFFFF) % 360f, me?.avatarPath, 60.dp)
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text(me?.displayName ?: "", style = Zura.type.headline, color = c.text)
                    Text(app.zura.i18n.handle(me?.username ?: ""), style = Zura.type.label, color = c.textMuted)
                }
                Chevron()
            }
        }
        item {
            MenuGroup(1) {
                MenuRow("🎨", 0xFF8B7CFF, stringResource(R.string.settings_appearance),
                    if (auto) "☀️ " + stringResource(themeName(day)) + " · 🌙 " + stringResource(themeName(night)) else stringResource(themeName(theme))) { onPage(PAGE_APPEARANCE) }
                MenuDivider()
                MenuRow("🔔", 0xFFF59E0B, stringResource(R.string.settings_notifications), stringResource(R.string.settings_notifications_sub)) { onPage(PAGE_NOTIFICATIONS) }
            }
        }
        item {
            MenuGroup(2) {
                MenuRow("🔒", 0xFF10B981, stringResource(R.string.settings_privacy), stringResource(R.string.settings_privacy_sub)) { onPage(PAGE_PRIVACY) }
                MenuDivider()
                MenuRow("📞", 0xFF0EA5E9, stringResource(R.string.settings_calls),
                    stringResource(if (ready) R.string.settings_calls_ready else R.string.settings_calls_attention), warn = !ready) { onPage(PAGE_CALLS) }
            }
        }
        item {
            MenuGroup(3) {
                MenuRow("🗂️", 0xFFEC4899, stringResource(R.string.settings_storage),
                    if (used >= 0) stringResource(R.string.settings_storage_sub, fmt.size(used)) else "…") { onPage(PAGE_STORAGE) }
            }
        }
        item {
            MenuGroup(4) {
                MenuRow("👤", 0xFF6366F1, stringResource(R.string.settings_account), stringResource(R.string.settings_account_sub)) { onPage(PAGE_ACCOUNT) }
                MenuDivider()
                MenuRow("🩺", 0xFF64748B, stringResource(R.string.settings_help), stringResource(R.string.settings_help_sub)) { onPage(PAGE_HELP) }
            }
        }
        item {
            Text(
                stringResource(R.string.settings_footer, app.zura.BuildConfig.VERSION_NAME), style = Zura.type.caption, color = c.textMuted,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                modifier = Modifier.fillMaxWidth().padding(top = 18.dp, bottom = 40.dp).appear(5),
            )
        }
    }
}

const val PAGE_APPEARANCE = "appearance"
const val PAGE_NOTIFICATIONS = "notifications"
const val PAGE_PRIVACY = "privacy"
const val PAGE_CALLS = "calls"
const val PAGE_STORAGE = "storage"
const val PAGE_ACCOUNT = "account"
const val PAGE_HELP = "help"

/** One settings page (opened from the menu). */
@Composable
fun SettingsPageScreen(page: String, onBack: () -> Unit, onProfile: () -> Unit, onVault: () -> Unit) {
    val ctx = LocalContext.current
    val g = ctx.graph
    val c = Zura.colors
    val haptics = rememberHaptics()
    val fmt = ZuraFormat(LocalConfiguration.current.locales[0])
    val title = when (page) {
        PAGE_APPEARANCE -> R.string.settings_appearance
        PAGE_NOTIFICATIONS -> R.string.settings_notifications
        PAGE_PRIVACY -> R.string.settings_privacy
        PAGE_CALLS -> R.string.settings_calls
        PAGE_STORAGE -> R.string.settings_storage
        PAGE_ACCOUNT -> R.string.settings_account
        else -> R.string.settings_help
    }
    var n = 0
    androidx.compose.foundation.lazy.LazyColumn(Modifier.fillMaxSize().statusBarsPadding()) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(8.dp)) {
                IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, null, tint = c.text) }
                Text(stringResource(title), style = Zura.type.title, color = c.text)
            }
        }
        when (page) {
            PAGE_APPEARANCE -> {
                item { Section(R.string.theme, n++) { ThemeSection() } }
                item {
                    Section(R.string.language, n++) {
                        val current = remember { LocaleEngine.current(ctx) }
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
                }
            }
            PAGE_NOTIFICATIONS -> item {
                Section(R.string.sounds, n++) {
                    SoundRows(chatId = null, peerId = null)
                    val hap by g.sounds.haptics.collectAsStateWithLifecycle()
                    app.zura.ui.components.SettingSwitch(stringResource(R.string.inapp_haptics), stringResource(R.string.inapp_haptics_hint), hap) { g.sounds.setHaptics(it) }
                }
            }
            PAGE_PRIVACY -> {
                item {
                    Section(R.string.stealth_mode, n++) {
                        val pv = g.s.privacy
                        val rr by pv.readReceipts.collectAsStateWithLifecycle()
                        val ty by pv.typing.collectAsStateWithLifecycle()
                        val ls by pv.lastSeen.collectAsStateWithLifecycle()
                        app.zura.ui.components.SettingSwitch(stringResource(R.string.stealth_read), stringResource(R.string.stealth_read_hint), rr) { pv.setReadReceipts(it) }
                        app.zura.ui.components.SettingSwitch(stringResource(R.string.stealth_typing), null, ty) { pv.setTyping(it) }
                        app.zura.ui.components.SettingSwitch(stringResource(R.string.stealth_seen), stringResource(R.string.stealth_seen_hint), ls) { pv.setLastSeen(it) }
                        Text(stringResource(R.string.stealth_per_person), style = Zura.type.caption, color = c.textMuted, modifier = Modifier.padding(top = 6.dp))
                    }
                }
                item { Section(R.string.app_lock, n++) { AppLockSection() } }
                item {
                    Section(R.string.privacy, n++) {
                        val secure by g.settings.secureScreen.collectAsStateWithLifecycle()
                        app.zura.ui.components.SettingSwitch(stringResource(R.string.secure_screen), stringResource(R.string.secure_screen_hint), secure) { g.settings.setSecureScreen(it) }
                        Spacer(Modifier.height(12.dp))
                        Text(stringResource(R.string.encrypted_storage), style = Zura.type.label, color = c.text)
                        Text(stringResource(R.string.encrypted_storage_hint), style = Zura.type.caption, color = c.textMuted)
                        Spacer(Modifier.height(8.dp))
                        Text(stringResource(R.string.e2ee_note), style = Zura.type.caption, color = c.textMuted)
                    }
                }
                item { Section(R.string.security_title, n++) { Text(stringResource(R.string.security_details), style = Zura.type.label, color = c.textMuted) } }
            }
            PAGE_CALLS -> item {
                // Calls & messages when the app is closed: push, full-screen calls, wake-up (battery).
                Section(R.string.push_status, n++) {
                    Text(
                        stringResource(if (app.zura.push.Push.enabled) R.string.push_on else R.string.push_off),
                        style = Zura.type.caption, color = if (app.zura.push.Push.enabled) c.textMuted else c.danger,
                    )
                    Spacer(Modifier.height(12.dp))
                    app.zura.push.CallReadinessList()
                    var telecom by remember { androidx.compose.runtime.mutableStateOf(app.zura.call.Telecom.enabled(ctx)) }
                    app.zura.ui.components.SettingSwitch(stringResource(R.string.use_telecom), stringResource(R.string.use_telecom_hint), telecom, Modifier.padding(top = 8.dp)) {
                        telecom = it; app.zura.call.Telecom.setEnabled(ctx, it)
                    }
                }
            }
            PAGE_STORAGE -> item {
                var used by remember { mutableLongStateOf(-1L) }
                LaunchedEffect(Unit) { used = withContext(Dispatchers.IO) { g.vault.totalBytes() } }
                Section(R.string.storage, n++) {
                    Text(stringResource(R.string.storage_used, if (used >= 0) fmt.size(used) else "…"), style = Zura.type.label, color = c.text)
                    Spacer(Modifier.height(10.dp))
                    MenuRow("🖼️", 0xFFEC4899, stringResource(R.string.manage_media), stringResource(R.string.manage_media_sub), padded = false, onClick = onVault)
                    Spacer(Modifier.height(6.dp))
                    Text(stringResource(R.string.about_privacy), style = Zura.type.caption, color = c.textMuted)
                }
            }
            PAGE_ACCOUNT -> item {
                Section(R.string.account, n++) {
                    MenuRow("👤", 0xFF6366F1, stringResource(R.string.my_profile), null, padded = false, onClick = onProfile)
                    Spacer(Modifier.height(12.dp))
                    AccountCard()
                }
            }
            else -> {
                item { Section(R.string.diagnostics, n++) { DiagnosticsSection() } }
                item {
                    val crashLog by androidx.compose.runtime.produceState<String?>(null) { value = withContext(Dispatchers.IO) { app.zura.CrashLog.read(ctx) } }
                    crashLog?.let { crash ->
                        Section(R.string.crash_report, n++) {
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
                }
            }
        }
        item { Spacer(Modifier.size(40.dp)) }
    }
}

/** Theme picker: the app's look, and the automatic day / night switch (sunrise / sunset). */
@Composable
private fun ThemeSection() {
    val ctx = LocalContext.current
    val g = ctx.graph
    val c = Zura.colors
    val haptics = rememberHaptics()
    val auto by g.settings.autoTheme.collectAsStateWithLifecycle()
    val theme by g.settings.theme.collectAsStateWithLifecycle()
    val day by g.settings.dayTheme.collectAsStateWithLifecycle()
    val night by g.settings.nightTheme.collectAsStateWithLifecycle()
    val today by g.themeClock.today.collectAsStateWithLifecycle()
    val askLocation = androidx.activity.compose.rememberLauncherForActivityResult(androidx.activity.result.contract.ActivityResultContracts.RequestPermission()) {
        g.themeClock.refresh()
    }
    Text(stringResource(R.string.app_look_hint), style = Zura.type.caption, color = c.textMuted, modifier = Modifier.padding(bottom = 12.dp))
    app.zura.ui.components.SettingSwitch(stringResource(R.string.theme_auto), stringResource(R.string.theme_auto_hint), auto) { on ->
        haptics.tick(); g.settings.setAutoTheme(on)
    }
    androidx.compose.animation.AnimatedContent(auto, label = "auto", transitionSpec = {
        (androidx.compose.animation.fadeIn(androidx.compose.animation.core.tween(260)) + androidx.compose.animation.expandVertically()) togetherWith
            (androidx.compose.animation.fadeOut(androidx.compose.animation.core.tween(160)) + androidx.compose.animation.shrinkVertically())
    }) { isAuto ->
        Column(Modifier.padding(top = 10.dp)) {
            if (!isAuto) ThemeGrid(ZuraThemeId.entries, theme) { haptics.tick(); g.settings.setTheme(it) }
            else {
                Text("☀️  " + stringResource(R.string.theme_day), style = Zura.type.label, color = c.text, modifier = Modifier.padding(bottom = 8.dp))
                ThemeGrid(ZuraThemeId.entries.filter { !ZuraPalettes.of(it).isDark }, day) { haptics.tick(); g.settings.setDayTheme(it) }
                Text("🌙  " + stringResource(R.string.theme_night), style = Zura.type.label, color = c.text, modifier = Modifier.padding(top = 14.dp, bottom = 8.dp))
                ThemeGrid(ZuraThemeId.entries.filter { ZuraPalettes.of(it).isDark }, night) { haptics.tick(); g.settings.setNightTheme(it) }
                today?.let { d ->
                    val tf = java.time.format.DateTimeFormatter.ofPattern("HH:mm").withZone(java.time.ZoneId.systemDefault())
                    if (d.sunrise != null && d.sunset != null) Text(
                        stringResource(R.string.theme_sun_times, tf.format(d.sunrise), tf.format(d.sunset)),
                        style = Zura.type.caption, color = c.textMuted, modifier = Modifier.padding(top = 12.dp),
                    )
                    if (d.place.estimated) {
                        Text(stringResource(R.string.theme_sun_estimated), style = Zura.type.caption, color = c.textMuted, modifier = Modifier.padding(top = 6.dp))
                        Text(
                            stringResource(R.string.theme_allow_location), style = Zura.type.label, color = c.accent,
                            modifier = Modifier.padding(top = 6.dp).clip(CircleShape).clickable { askLocation.launch(android.Manifest.permission.ACCESS_COARSE_LOCATION) }.padding(vertical = 4.dp),
                        )
                    }
                }
            }
        }
    }
}

/** Theme cards, 3 per row: a miniature of the app (background glow, a card, two bubbles) + name. */
@Composable
private fun ThemeGrid(ids: List<ZuraThemeId>, selected: ZuraThemeId, onPick: (ZuraThemeId) -> Unit) {
    val c = Zura.colors
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        ids.chunked(3).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                row.forEach { id ->
                    val p = ZuraPalettes.of(id)
                    val sel = id == selected
                    val ring by animateDpAsState(if (sel) 2.dp else 0.dp, label = "ring")
                    val lift by androidx.compose.animation.core.animateFloatAsState(if (sel) 1.04f else 1f, app.zura.ui.components.ZuraMotion.Bouncy, label = "lift")
                    Column(Modifier.weight(1f).graphicsLayer { scaleX = lift; scaleY = lift }, horizontalAlignment = Alignment.CenterHorizontally) {
                        Box(
                            Modifier.fillMaxWidth().aspectRatio(0.66f).clip(RoundedCornerShape(18.dp))
                                .background(Brush.verticalGradient(listOf(p.bgAccent, p.bg)))
                                .drawBehind {
                                    // The theme's glow, from the top corner (as on the real background).
                                    drawRect(Brush.radialGradient(listOf(p.accent.copy(alpha = if (p.isDark) 0.20f else 0.12f), androidx.compose.ui.graphics.Color.Transparent),
                                        center = androidx.compose.ui.geometry.Offset(size.width, 0f), radius = size.maxDimension * 0.8f))
                                }
                                .border(ring, c.accent, RoundedCornerShape(18.dp))
                                .border(1.dp, p.glassStroke, RoundedCornerShape(18.dp))
                                .clickable { onPick(id) }
                                .padding(8.dp),
                        ) {
                            Box(Modifier.fillMaxWidth().height(12.dp).clip(RoundedCornerShape(6.dp)).background(p.surfaceHigh))
                            Column(verticalArrangement = Arrangement.spacedBy(5.dp), modifier = Modifier.align(Alignment.BottomCenter)) {
                                Box(Modifier.width(34.dp).height(10.dp).clip(CircleShape).background(p.bubbleTheirs))
                                Box(Modifier.align(Alignment.End).width(40.dp).height(10.dp).clip(CircleShape).background(Brush.linearGradient(listOf(p.bubbleMine, p.bubbleMineAlt))))
                                Box(Modifier.size(14.dp).clip(CircleShape).background(p.accent).align(Alignment.End))
                            }
                            if (sel) Box(
                                Modifier.align(Alignment.TopEnd).padding(top = 18.dp).size(18.dp).clip(CircleShape).background(c.accent),
                                contentAlignment = Alignment.Center,
                            ) { Text("✓", style = Zura.type.caption, color = c.onAccent) }
                        }
                        Text(stringResource(themeName(id)), style = Zura.type.caption, color = if (sel) c.text else c.textMuted, modifier = Modifier.padding(top = 6.dp), maxLines = 1)
                    }
                }
                repeat(3 - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

@Composable
private fun Chevron() = Icon(
    Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = Zura.colors.textMuted.copy(alpha = 0.7f), modifier = Modifier.size(22.dp),
)

/** A card holding a few menu rows; cascades in after the ones above it. */
@Composable
private fun MenuGroup(index: Int, content: @Composable () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 7.dp).appear(index).glass(RoundedCornerShape(22.dp)).padding(vertical = 4.dp)) { content() }
}

@Composable
private fun MenuDivider() = Box(Modifier.fillMaxWidth().padding(start = 66.dp).height(1.dp).background(Zura.colors.divider))

/** Icon tile · title · current value · arrow. */
@Composable
private fun MenuRow(icon: String, tint: Long, title: String, value: String?, warn: Boolean = false, padded: Boolean = true, onClick: () -> Unit) {
    val c = Zura.colors
    val src = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).pressScale(src, 0.98f)
            .clickable(src, androidx.compose.material3.ripple(), onClick = onClick)
            .padding(horizontal = if (padded) 14.dp else 0.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(38.dp).clip(RoundedCornerShape(12.dp)).background(androidx.compose.ui.graphics.Color(tint).copy(alpha = 0.18f)),
            contentAlignment = Alignment.Center,
        ) { Text(icon, style = Zura.type.headline) }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = Zura.type.label, color = c.text)
            value?.let { Text(it, style = Zura.type.caption, color = if (warn) c.danger else c.textMuted, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis) }
        }
        Chevron()
    }
}

@Composable
private fun Section(title: Int, index: Int = 0, content: @Composable () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp).appear(index).glass(RoundedCornerShape(22.dp)).padding(16.dp)) {
        Text(stringResource(title), style = Zura.type.headline, color = Zura.colors.text, modifier = Modifier.padding(bottom = 12.dp))
        content()
    }
}

fun themeName(id: ZuraThemeId) = when (id) {
    ZuraThemeId.MIDNIGHT -> R.string.theme_midnight
    ZuraThemeId.SAPPHIRE -> R.string.theme_sapphire
    ZuraThemeId.EMERALD -> R.string.theme_emerald
    ZuraThemeId.VELVET_GOLD -> R.string.theme_velvet
    ZuraThemeId.OLED -> R.string.theme_oled
    ZuraThemeId.LIGHT -> R.string.theme_light
    ZuraThemeId.PEARL -> R.string.theme_pearl
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
                runCatching { app.zura.media.AvatarEncoder.encode(ctx, uri) }.getOrNull()
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
                if (on) g.appLock.authenticate(activity, { legacy.launch(it) }) { ok ->
                    if (ok) g.appLock.setEnabled(true)
                    else g.appLock.error.value?.let { android.widget.Toast.makeText(ctx, it, android.widget.Toast.LENGTH_LONG).show() }
                }
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

/** "Connection check": runs the on-device self-test and shows a copyable report. */
@Composable
private fun DiagnosticsSection() {
    val ctx = LocalContext.current
    val g = ctx.graph
    val c = Zura.colors
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    var running by remember { androidx.compose.runtime.mutableStateOf(false) }
    var report by remember { androidx.compose.runtime.mutableStateOf<String?>(null) }
    Text(stringResource(R.string.diagnostics_hint), style = Zura.type.caption, color = c.textMuted)
    Spacer(Modifier.height(10.dp))
    Box(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(c.accent.copy(alpha = 0.14f))
            .clickable(enabled = !running) {
                running = true
                scope.launch { report = runCatching { g.s.selfTest() }.getOrElse { "✗ ${it.message}" }; running = false }
            }.padding(14.dp),
        contentAlignment = Alignment.Center,
    ) {
        if (running) androidx.compose.material3.CircularProgressIndicator(Modifier.size(20.dp), color = c.accent, strokeWidth = 2.dp)
        else Text(stringResource(R.string.run_check), style = Zura.type.label, color = c.accent)
    }
    report?.let { r ->
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { report = null },
            containerColor = c.surfaceHigh,
            title = { Text(stringResource(R.string.diagnostics), color = c.text) },
            text = {
                Text(
                    r, color = c.text,
                    style = Zura.type.caption.copy(fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace, textDirection = androidx.compose.ui.text.style.TextDirection.Ltr),
                    modifier = Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState()),
                )
            },
            confirmButton = {
                androidx.compose.material3.TextButton(onClick = {
                    ctx.getSystemService(android.content.ClipboardManager::class.java).setPrimaryClip(android.content.ClipData.newPlainText("zura-diagnostics", r))
                    android.widget.Toast.makeText(ctx, R.string.copied, android.widget.Toast.LENGTH_SHORT).show()
                }) { Text(stringResource(R.string.action_copy), color = c.accent) }
            },
            dismissButton = { androidx.compose.material3.TextButton(onClick = { report = null }) { Text(stringResource(R.string.cancel), color = c.text) } },
        )
    }
}
