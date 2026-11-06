package app.zura.ui.profile

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.zura.R
import app.zura.account.Profile
import app.zura.data.ContactsRepository
import app.zura.graph
import app.zura.i18n.handle
import app.zura.ui.ZuraIcons
import app.zura.ui.chat.rememberCallPermissions
import app.zura.ui.components.UserAvatar
import app.zura.ui.components.glass
import app.zura.ui.components.pressScale
import app.zura.ui.components.rememberHaptics
import app.zura.ui.theme.Zura
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream

/**
 * A person's profile — or mine, editable. Mine: photo, display name, @username.
 * Someone else's: message, voice/video call, security code, block, remove.
 */
@Composable
fun ProfileScreen(userId: String, onBack: () -> Unit, onOpenChat: (String) -> Unit) {
    val ctx = LocalContext.current
    val g = ctx.graph
    val repo = g.s.contacts
    val c = Zura.colors
    val isMe = userId == g.s.uid
    val mine by repo.myProfile.collectAsStateWithLifecycle()
    val contacts by repo.contacts.collectAsStateWithLifecycle()
    var other by remember { mutableStateOf(repo.profile(userId)) }
    LaunchedEffect(userId) { if (!isMe && other == null) other = repo.loadProfile(userId) }
    val p: Profile? = if (isMe) mine else other
    val contact = contacts.firstOrNull { it.profile.id == userId }
    val hue = (userId.hashCode() and 0xFFFF) % 360f

    Column(Modifier.fillMaxSize().statusBarsPadding().verticalScroll(rememberScrollState()).navigationBarsPadding()) {
        Row(Modifier.padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, null, tint = c.text) }
        }
        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
            if (isMe) MyPhoto(p, hue) else UserAvatar(p?.displayName ?: "", hue, p?.avatarPath, 128.dp)
            Spacer(Modifier.height(16.dp))
            Text(p?.displayName ?: "", style = Zura.type.title, color = c.text, textAlign = TextAlign.Center)
            Text(p?.username?.let(::handle) ?: "", style = Zura.type.label, color = c.textMuted)
            if (isMe) Text(g.s.email, style = Zura.type.caption, color = c.textMuted, modifier = Modifier.padding(top = 2.dp))
        }
        Spacer(Modifier.height(24.dp))
        if (isMe) MyDetails(p) else if (p != null) TheirActions(p, contact != null, contact?.blocked == true, onOpenChat, onBack)
        Spacer(Modifier.height(32.dp))
    }
}

// ─────────────────────────────────────────────────────── me

@Composable
private fun MyPhoto(p: Profile?, hue: Float) {
    val ctx = LocalContext.current
    val g = ctx.graph
    val c = Zura.colors
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        busy = true
        scope.launch {
            val bytes = withContext(Dispatchers.Default) {
                runCatching { app.zura.media.AvatarEncoder.encode(ctx, uri) }.getOrNull()
            }
            if (bytes != null) runCatching { g.s.contacts.setAvatar(bytes); g.accountStore.avatar(g.s.uid).write(bytes) }
                .onFailure { android.widget.Toast.makeText(ctx, it.message ?: ctx.getString(R.string.err_generic), android.widget.Toast.LENGTH_SHORT).show() }
            busy = false
        }
    }
    val src = remember { MutableInteractionSource() }
    Box(Modifier.pressScale(src).clickable(src, null, enabled = !busy) { pick.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }) {
        UserAvatar(p?.displayName ?: "", hue, p?.avatarPath, 128.dp)
        Box(
            Modifier.align(Alignment.BottomEnd).size(38.dp).clip(CircleShape).background(c.accent),
            contentAlignment = Alignment.Center,
        ) {
            if (busy) CircularProgressIndicator(Modifier.size(18.dp), color = c.onAccent, strokeWidth = 2.dp)
            else Icon(Icons.Default.Edit, stringResource(R.string.change_photo), tint = c.onAccent, modifier = Modifier.size(18.dp))
        }
    }
}

@Composable
private fun MyDetails(p: Profile?) {
    val ctx = LocalContext.current
    val g = ctx.graph
    val c = Zura.colors
    val scope = rememberCoroutineScope()
    val haptics = rememberHaptics()
    var name by remember(p?.displayName) { mutableStateOf(p?.displayName.orEmpty()) }
    var username by remember(p?.username) { mutableStateOf(p?.username.orEmpty()) }
    var available by remember { mutableStateOf<Boolean?>(null) }
    var saving by remember { mutableStateOf(false) }
    val validUser = Regex("^[A-Za-z0-9_]{3,24}$").matches(username)
    val usernameChanged = username != p?.username
    val nameChanged = name.trim() != p?.displayName && name.isNotBlank()
    LaunchedEffect(username) {
        available = null
        if (usernameChanged && validUser) { delay(400); available = g.accounts.usernameAvailable(username) }
    }
    Column(Modifier.padding(horizontal = 16.dp).fillMaxWidth().glass(RoundedCornerShape(22.dp), solid = 0.5f).padding(16.dp)) {
        Field(name, { name = it.take(48) }, R.string.name)
        Spacer(Modifier.height(10.dp))
        Field(username, { username = it.filter { ch -> (ch.isLetterOrDigit() && ch.code < 128) || ch == '_' }.take(24) }, R.string.username, prefix = "@")
        Text(
            when {
                !usernameChanged -> stringResource(R.string.username_rules)
                !validUser -> stringResource(R.string.username_rules)
                available == false -> stringResource(R.string.username_taken)
                available == true -> "✓"
                else -> "…"
            },
            style = Zura.type.caption, color = if (available == false) c.danger else c.textMuted, modifier = Modifier.padding(start = 6.dp, top = 4.dp),
        )
        Spacer(Modifier.height(14.dp))
        val canSave = !saving && (nameChanged || (usernameChanged && validUser && available == true))
        Box(
            Modifier.fillMaxWidth().height(48.dp).clip(RoundedCornerShape(16.dp)).background(if (canSave) c.accent else c.surfaceHigh)
                .clickable(enabled = canSave) {
                    saving = true; haptics.confirm()
                    scope.launch {
                        val r1 = if (nameChanged) runCatching { g.s.contacts.setDisplayName(name) } else Result.success(Unit)
                        val r2 = if (usernameChanged) g.s.contacts.setUsername(username) else Result.success(Unit)
                        val err = r1.exceptionOrNull() ?: r2.exceptionOrNull()
                        android.widget.Toast.makeText(ctx, err?.message ?: ctx.getString(R.string.saved), android.widget.Toast.LENGTH_SHORT).show()
                        saving = false
                    }
                },
            contentAlignment = Alignment.Center,
        ) {
            if (saving) CircularProgressIndicator(Modifier.size(20.dp), color = c.onAccent, strokeWidth = 2.dp)
            else Text(stringResource(R.string.save), style = Zura.type.label, color = if (canSave) c.onAccent else c.textMuted)
        }
    }
}

@Composable
private fun Field(value: String, onChange: (String) -> Unit, label: Int, prefix: String? = null) {
    val c = Zura.colors
    OutlinedTextField(
        value, onChange, label = { Text(stringResource(label)) }, singleLine = true,
        prefix = prefix?.let { { Text(it, color = c.textMuted) } },
        shape = RoundedCornerShape(16.dp),
        colors = OutlinedTextFieldDefaults.colors(
            focusedBorderColor = c.accent, unfocusedBorderColor = c.divider.copy(alpha = 0.6f), focusedLabelColor = c.accent,
            unfocusedLabelColor = c.textMuted, cursorColor = c.accent, focusedTextColor = c.text, unfocusedTextColor = c.text,
        ),
        modifier = Modifier.fillMaxWidth(),
    )
}

// ─────────────────────────────────────────────────────── someone else

@Composable
private fun TheirActions(p: Profile, isContact: Boolean, blocked: Boolean, onOpenChat: (String) -> Unit, onBack: () -> Unit) {
    val ctx = LocalContext.current
    val g = ctx.graph
    val c = Zura.colors
    val scope = rememberCoroutineScope()
    val haptics = rememberHaptics()
    val chatId = ContactsRepository.dmChatId(g.s.uid, p.id)
    val voice = rememberCallPermissions(false) { ok -> if (ok) g.s.calls.startCall(p.id, false) }
    val video = rememberCallPermissions(true) { ok -> if (ok) g.s.calls.startCall(p.id, true) }
    var showSafety by remember { mutableStateOf(false) }
    var confirmRemove by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf<String?>(null) }

    if (isContact) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            ActionTile(ZuraIcons.Reply, stringResource(R.string.open_chat), Modifier.weight(1f)) { onOpenChat(chatId) }
            // Blocked: no calls offered (same as inside the chat).
            if (!blocked) {
                ActionTile(Icons.Default.Call, stringResource(R.string.voice_call), Modifier.weight(1f)) { haptics.tick(); voice(ctx) }
                ActionTile(ZuraIcons.VideoCam, stringResource(R.string.video_call), Modifier.weight(1f)) { haptics.tick(); video(ctx) }
            }
        }
        Spacer(Modifier.height(16.dp))
    }
    Column(Modifier.padding(horizontal = 16.dp).fillMaxWidth().glass(RoundedCornerShape(22.dp), solid = 0.5f)) {
        Row(Modifier.fillMaxWidth().clickable { showSafety = true }.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(ZuraIcons.Shield, null, tint = c.accent)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.safety_number), style = Zura.type.label, color = c.text)
                Text(stringResource(R.string.e2ee_short), style = Zura.type.caption, color = c.textMuted)
            }
        }
        if (isContact) {
            // Disappearing messages for this chat.
            val chatMeta by remember(chatId) { g.chats.meta(chatId) }.collectAsStateWithLifecycle(null)
            var pickTimer by remember { mutableStateOf(false) }
            Box(Modifier.fillMaxWidth().height(1.dp).background(c.divider))
            Row(Modifier.fillMaxWidth().clickable { pickTimer = true }.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("⏱", style = Zura.type.headline, color = c.text) // drawn as a glyph: must follow the theme (was black on dark)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.disappearing), style = Zura.type.label, color = c.text)
                    Text(app.zura.ui.chat.ttlLabel(ctx, chatMeta?.disappearMs), style = Zura.type.caption, color = if (chatMeta?.disappearMs != null) c.accent else c.textMuted)
                }
            }
            if (pickTimer) app.zura.ui.components.PickerSheet(
                title = stringResource(R.string.disappearing), hint = stringResource(R.string.disappearing_hint),
                options = app.zura.ui.chat.DISAPPEAR_OPTIONS.map { it to app.zura.ui.chat.ttlLabel(ctx, it) },
                selected = chatMeta?.disappearMs,
                onPick = { v -> pickTimer = false; if (v != chatMeta?.disappearMs) g.s.scope.launch { g.chats.setDisappearing(chatId, v) } },
                onDismiss = { pickTimer = false },
            )
            // Chat lock (needs the phone's own screen lock; changing it asks for it too).
            val lockAuth = app.zura.security.rememberDeviceAuth()
            Box(Modifier.fillMaxWidth().height(1.dp).background(c.divider))
            app.zura.ui.components.SettingSwitch(
                stringResource(R.string.lock_chat), stringResource(R.string.lock_chat_hint), chatMeta?.locked == true,
                Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
            ) { on ->
                if (!g.appLock.deviceSecure()) android.widget.Toast.makeText(ctx, R.string.needs_screen_lock, android.widget.Toast.LENGTH_LONG).show()
                else lockAuth { ok -> if (ok) g.s.scope.launch { g.chats.setLocked(chatId, on); if (on) g.chats.lockedRevealed.value = false } }
            }
            // Custom tone / ringtone / vibration for this chat and person.
            var showSounds by remember { mutableStateOf(false) }
            Box(Modifier.fillMaxWidth().height(1.dp).background(c.divider))
            Row(Modifier.fillMaxWidth().clickable { showSounds = !showSounds }.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("🔔", style = Zura.type.headline)
                Spacer(Modifier.width(12.dp))
                Text(stringResource(R.string.custom_sounds), style = Zura.type.label, color = c.text, modifier = Modifier.weight(1f))
                Text(if (showSounds) "▲" else "▼", style = Zura.type.caption, color = c.textMuted)
            }
            androidx.compose.animation.AnimatedVisibility(showSounds) {
                Column(Modifier.padding(horizontal = 16.dp)) { app.zura.ui.settings.SoundRows(chatId = chatId, peerId = p.id) }
            }
            // Lost this chat's history on this phone (or part of it): ask the contact for their copy.
            if (!blocked) {
                Box(Modifier.fillMaxWidth().height(1.dp).background(c.divider))
                Row(
                    Modifier.fillMaxWidth().clickable {
                        g.s.scope.launch {
                            val ok = g.s.history.request(chatId)
                            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                                android.widget.Toast.makeText(ctx, if (ok) ctx.getString(R.string.history_requested_toast, p.displayName) else ctx.getString(R.string.history_restore_failed), android.widget.Toast.LENGTH_SHORT).show()
                            }
                        }
                    }.padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("🔄", style = Zura.type.headline)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(stringResource(R.string.profile_restore_history), style = Zura.type.label, color = c.text)
                        Text(stringResource(R.string.profile_restore_hint, p.displayName), style = Zura.type.caption, color = c.textMuted)
                    }
                }
            }
            // Per-contact stealth: invisible to this one person.
            val ghost by g.s.privacy.ghost.collectAsStateWithLifecycle()
            Box(Modifier.fillMaxWidth().height(1.dp).background(c.divider))
            app.zura.ui.components.SettingSwitch(
                stringResource(R.string.ghost_with), stringResource(R.string.ghost_with_hint), p.id in ghost,
                Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
            ) { g.s.privacy.setGhost(p.id, it) }
            Box(Modifier.fillMaxWidth().height(1.dp).background(c.divider))
            Row(
                Modifier.fillMaxWidth().clickable(enabled = busy == null) {
                    busy = "block"
                    scope.launch { g.s.setBlocked(p.id, !blocked); busy = null }
                }.padding(16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(stringResource(if (blocked) R.string.unblock else R.string.block), style = Zura.type.label, color = c.danger, modifier = Modifier.weight(1f))
                if (busy == "block") CircularProgressIndicator(Modifier.size(18.dp), color = c.danger, strokeWidth = 2.dp)
            }
            Box(Modifier.fillMaxWidth().height(1.dp).background(c.divider))
            Row(Modifier.fillMaxWidth().clickable { confirmRemove = true }.padding(16.dp)) {
                Text(stringResource(R.string.remove_contact), style = Zura.type.label, color = c.danger)
            }
        }
    }

    if (showSafety) {
        // The key may not be in memory yet (it was shown as "—" until a message had been exchanged).
        val number by androidx.compose.runtime.produceState<String?>(null, p.id) { value = g.s.contacts.safetyNumber(p.id, g.s.identity.keyPair.public) }
        AlertDialog(
            onDismissRequest = { showSafety = false },
            containerColor = c.surfaceHigh,
            icon = { Icon(ZuraIcons.Shield, null, tint = c.accent) },
            title = { Text(stringResource(R.string.safety_number), color = c.text) },
            text = {
                Column {
                    Text(
                        number?.chunked(18)?.joinToString("\n") ?: "…",
                        style = Zura.type.headline.copy(fontFamily = FontFamily.Monospace, textDirection = TextDirection.Ltr), color = c.text,
                    )
                    Spacer(Modifier.height(12.dp))
                    Text(stringResource(R.string.verify_hint, p.displayName), style = Zura.type.label, color = c.textMuted)
                }
            },
            confirmButton = { TextButton(onClick = { showSafety = false }) { Text("OK", color = c.accent) } },
        )
    }
    if (confirmRemove) {
        app.zura.ui.components.ConfirmSheet(
            title = stringResource(R.string.remove_contact),
            message = p.displayName,
            actions = listOf(app.zura.ui.components.ConfirmAction(stringResource(R.string.remove), danger = true) {
                confirmRemove = false
                scope.launch { g.s.contacts.remove(p.id); onBack() }
            }),
            cancelLabel = stringResource(R.string.cancel),
            onDismiss = { confirmRemove = false },
            icon = Icons.Default.Delete,
        )
    }
}

@Composable
private fun ActionTile(icon: ImageVector, label: String, modifier: Modifier, onClick: () -> Unit) {
    val c = Zura.colors
    val src = remember { MutableInteractionSource() }
    Column(
        modifier.pressScale(src).glass(RoundedCornerShape(18.dp), solid = 0.5f).clickable(src, null, onClick = onClick).padding(vertical = 14.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(icon, label, tint = c.accent)
        Spacer(Modifier.height(6.dp))
        Text(label, style = Zura.type.caption, color = c.text)
    }
}
