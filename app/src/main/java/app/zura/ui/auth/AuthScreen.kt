package app.zura.ui.auth

import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.os.Build
import android.provider.MediaStore
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
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
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.zura.R
import app.zura.account.AuthResult
import app.zura.account.SavedAccount
import app.zura.graph
import app.zura.ui.components.Avatar
import app.zura.ui.components.glass
import app.zura.ui.components.pressScale
import app.zura.ui.components.rememberHaptics
import app.zura.ui.theme.Zura
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import kotlin.math.min

private sealed interface Mode {
    data object Picker : Mode
    data class Login(val locked: SavedAccount? = null) : Mode
    data object Register : Mode
    data class Confirm(val email: String) : Mode
}

/**
 * Entry flow. If accounts were used on this phone before, they're shown first as cards
 * (photo + name): one tap enters directly when "remember me" was on, otherwise asks only for the
 * password. Otherwise: log in or create an account (with profile photo).
 */
@Composable
fun AuthScreen(previewLockedUid: String? = null, previewRegister: Boolean = false) {
    val g = LocalContext.current.graph
    val saved by g.accountStore.accounts.collectAsStateWithLifecycle()
    var mode by remember {
        mutableStateOf<Mode>(
            when {
                previewRegister -> Mode.Register
                previewLockedUid != null -> Mode.Login(g.accountStore.get(previewLockedUid))
                saved.isNotEmpty() -> Mode.Picker
                else -> Mode.Login()
            },
        )
    }

    Box(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().imePadding()) {
        AnimatedContent(mode, transitionSpec = { (fadeIn() + slideInHorizontally { it / 6 }) togetherWith fadeOut() }, label = "auth") { m ->
            when (m) {
                Mode.Picker -> Picker(saved, onLogin = { mode = Mode.Login(it) }, onOther = { mode = Mode.Login() }, onRegister = { mode = Mode.Register })
                is Mode.Login -> Login(m.locked, onBack = if (saved.isNotEmpty()) ({ mode = Mode.Picker }) else null, onRegister = { mode = Mode.Register })
                Mode.Register -> Register(onBack = { mode = if (saved.isNotEmpty()) Mode.Picker else Mode.Login() }, onConfirm = { mode = Mode.Confirm(it) })
                is Mode.Confirm -> Confirm(m.email) { mode = Mode.Login() }
            }
        }
    }
}

@Composable
private fun Brand(sub: Boolean = true) {
    val c = Zura.colors
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth().padding(top = 36.dp, bottom = 28.dp)) {
        Box(
            Modifier.size(78.dp).clip(RoundedCornerShape(26.dp)).background(Brush.linearGradient(listOf(c.accent, c.accentAlt))),
            contentAlignment = Alignment.Center,
        ) { Text("Z", color = c.onAccent, fontSize = 40.sp, fontWeight = FontWeight.Bold) }
        Spacer(Modifier.height(18.dp))
        Text(stringResource(R.string.welcome_title), style = Zura.type.title, color = c.text)
        if (sub) {
            Spacer(Modifier.height(6.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Lock, null, tint = c.textMuted, modifier = Modifier.size(14.dp))
                Spacer(Modifier.width(6.dp))
                Text(stringResource(R.string.welcome_sub), style = Zura.type.label, color = c.textMuted)
            }
        }
    }
}

// ───────────────────────────────────────────────────────────── saved accounts

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun Picker(saved: List<SavedAccount>, onLogin: (SavedAccount) -> Unit, onOther: () -> Unit, onRegister: () -> Unit) {
    val g = LocalContext.current.graph
    val c = Zura.colors
    val scope = rememberCoroutineScope()
    val haptics = rememberHaptics()
    var busy by remember { mutableStateOf<String?>(null) }
    var removing by remember { mutableStateOf<SavedAccount?>(null) }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp)) {
        Brand()
        Text(stringResource(R.string.accounts_on_device), style = Zura.type.caption, color = c.textMuted, modifier = Modifier.padding(start = 6.dp, bottom = 8.dp))
        Column(Modifier.fillMaxWidth().glass(RoundedCornerShape(24.dp), solid = 0.5f)) {
            saved.forEachIndexed { i, a ->
                val src = remember { MutableInteractionSource() }
                val avatar by produceState<ImageBitmap?>(null, a.uid) {
                    value = withContext(Dispatchers.IO) { g.accountStore.avatar(a.uid).read()?.let(g.avatars::decode) }
                }
                Row(
                    Modifier.fillMaxWidth().pressScale(src, 0.98f)
                        .combinedClickable(src, null,
                            onClick = {
                                if (busy != null) return@combinedClickable
                                haptics.tick(); busy = a.uid
                                scope.launch {
                                    val r = g.accounts.quickLogin(a.uid)
                                    busy = null
                                    if (r !is AuthResult.Ok) onLogin(a)
                                }
                            },
                            onLongClick = { haptics.heavy(); removing = a })
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Avatar(a.displayName, (a.uid.hashCode() and 0xFFFF) % 360f, 52.dp, image = avatar)
                    Spacer(Modifier.width(14.dp))
                    Column(Modifier.weight(1f)) {
                        Text(a.displayName, style = Zura.type.headline, color = c.text)
                        Text(app.zura.i18n.handle(a.username), style = Zura.type.label, color = c.textMuted)
                    }
                    if (busy == a.uid) CircularProgressIndicator(Modifier.size(22.dp), color = c.accent, strokeWidth = 2.dp)
                    else if (!a.remember) Icon(Icons.Default.Lock, null, tint = c.textMuted, modifier = Modifier.size(18.dp))
                    else Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = c.textMuted)
                }
                if (i < saved.lastIndex) Box(Modifier.fillMaxWidth().padding(start = 82.dp).height(1.dp).background(c.divider))
            }
        }
        Spacer(Modifier.height(14.dp))
        SecondaryButton(Icons.Default.Person, stringResource(R.string.use_another), onOther)
        Spacer(Modifier.height(10.dp))
        SecondaryButton(Icons.Default.Add, stringResource(R.string.create_account), onRegister)
        Spacer(Modifier.height(24.dp))
    }

    removing?.let { a ->
        AlertDialog(
            onDismissRequest = { removing = null },
            containerColor = c.surfaceHigh,
            title = { Text(stringResource(R.string.remove_account), color = c.text) },
            text = { Text(stringResource(R.string.remove_account_confirm), color = c.textMuted) },
            confirmButton = {
                TextButton(onClick = { removing = null; scope.launch { g.accounts.forget(a.uid) } }) {
                    Text(stringResource(R.string.remove), color = c.danger)
                }
            },
            dismissButton = { TextButton(onClick = { removing = null }) { Text(stringResource(R.string.cancel), color = c.text) } },
        )
    }
}

// ───────────────────────────────────────────────────────────── log in

@Composable
private fun Login(locked: SavedAccount?, onBack: (() -> Unit)?, onRegister: () -> Unit) {
    val g = LocalContext.current.graph
    val c = Zura.colors
    val scope = rememberCoroutineScope()
    var email by remember { mutableStateOf(locked?.email.orEmpty()) }
    var password by remember { mutableStateOf("") }
    var remember by remember { mutableStateOf(locked?.remember ?: true) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val avatar by produceState<ImageBitmap?>(null, locked?.uid) {
        value = locked?.let { a -> withContext(Dispatchers.IO) { g.accountStore.avatar(a.uid).read()?.let(g.avatars::decode) } }
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp)) {
        onBack?.let { IconButton(onClick = it) { Icon(Icons.AutoMirrored.Filled.ArrowBack, null, tint = c.text) } }
        if (locked != null) {
            Column(Modifier.fillMaxWidth().padding(top = 24.dp, bottom = 24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Avatar(locked.displayName, (locked.uid.hashCode() and 0xFFFF) % 360f, 96.dp, image = avatar)
                Spacer(Modifier.height(14.dp))
                Text(locked.displayName, style = Zura.type.title, color = c.text)
                Text(app.zura.i18n.handle(locked.username), style = Zura.type.label, color = c.textMuted)
                Spacer(Modifier.height(8.dp))
                Text(stringResource(R.string.enter_password_for, locked.email), style = Zura.type.label, color = c.textMuted)
            }
        } else Brand()

        if (locked == null) {
            Field(email, { email = it; error = null }, R.string.email, KeyboardType.Email)
            Spacer(Modifier.height(10.dp))
        }
        Field(password, { password = it; error = null }, R.string.password, KeyboardType.Password, secret = true, last = true)
        RememberRow(remember) { remember = it }
        error?.let { Text(it, color = c.danger, style = Zura.type.label, modifier = Modifier.padding(vertical = 6.dp)) }
        Spacer(Modifier.height(8.dp))
        PrimaryButton(stringResource(R.string.login), busy, enabled = email.contains('@') && password.isNotEmpty()) {
            busy = true
            scope.launch {
                when (val r = g.accounts.login(email, password, remember)) {
                    is AuthResult.Error -> error = r.message
                    else -> Unit
                }
                busy = false
            }
        }
        if (locked == null) {
            TextButton(onClick = onRegister, modifier = Modifier.align(Alignment.CenterHorizontally).padding(top = 8.dp)) {
                Text(stringResource(R.string.no_account), color = c.accent)
            }
        }
    }
}

// ───────────────────────────────────────────────────────────── register

@Composable
private fun Register(onBack: () -> Unit, onConfirm: (String) -> Unit) {
    val ctx = LocalContext.current
    val g = ctx.graph
    val c = Zura.colors
    val scope = rememberCoroutineScope()
    var name by remember { mutableStateOf("") }
    var username by remember { mutableStateOf("") }
    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var remember by remember { mutableStateOf(true) }
    var photo by remember { mutableStateOf<Pair<ImageBitmap, ByteArray>?>(null) }
    var available by remember { mutableStateOf<Boolean?>(null) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    val validUser = Regex("^[A-Za-z0-9_]{3,24}$").matches(username)
    LaunchedEffect(username) {
        available = null
        if (validUser) { delay(400); available = g.accounts.usernameAvailable(username) }
    }
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        scope.launch {
            photo = withContext(Dispatchers.Default) {
                runCatching {
                    val src = if (Build.VERSION.SDK_INT >= 28) ImageDecoder.decodeBitmap(ImageDecoder.createSource(ctx.contentResolver, uri)) { d, _, _ ->
                        d.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                    } else @Suppress("DEPRECATION") MediaStore.Images.Media.getBitmap(ctx.contentResolver, uri)
                    // Center-crop to a 512 px square: small upload, sharp on every screen.
                    val side = min(src.width, src.height)
                    val sq = Bitmap.createBitmap(src, (src.width - side) / 2, (src.height - side) / 2, side, side)
                    val out = Bitmap.createScaledBitmap(sq, 512, 512, true)
                    val bytes = ByteArrayOutputStream().also { out.compress(Bitmap.CompressFormat.JPEG, 86, it) }.toByteArray()
                    out.asImageBitmap() to bytes
                }.getOrNull()
            }
        }
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp)) {
        IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, null, tint = c.text) }
        Text(stringResource(R.string.create_account), style = Zura.type.title, color = c.text, modifier = Modifier.padding(start = 4.dp, bottom = 20.dp))

        // Profile photo
        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
            val src = remember { MutableInteractionSource() }
            Box(
                Modifier.size(112.dp).pressScale(src).clip(CircleShape)
                    .background(Brush.linearGradient(listOf(c.surfaceHigh, c.surface)))
                    .border(2.dp, Brush.sweepGradient(listOf(c.accent, c.accentAlt, c.accent)), CircleShape)
                    .clickable(src, null) { pick.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
                contentAlignment = Alignment.Center,
            ) {
                photo?.let { Image(it.first, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop) }
                    ?: Icon(Icons.Default.Add, null, tint = c.accent, modifier = Modifier.size(36.dp))
            }
            Text(stringResource(R.string.choose_photo), style = Zura.type.label, color = c.textMuted, modifier = Modifier.padding(top = 8.dp, bottom = 18.dp))
        }

        Field(name, { name = it.take(48) }, R.string.name, KeyboardType.Text)
        Spacer(Modifier.height(10.dp))
        Field(username, { username = it.filter { ch -> ch.isLetterOrDigit() && ch.code < 128 || ch == '_' }.take(24); error = null }, R.string.username, KeyboardType.Ascii, prefix = "@")
        Text(
            when {
                username.isEmpty() || !validUser -> stringResource(R.string.username_rules)
                available == false -> stringResource(R.string.username_taken)
                else -> "✓"
            },
            style = Zura.type.caption, color = if (available == false) c.danger else if (available == true) c.accent else c.textMuted,
            modifier = Modifier.padding(start = 6.dp, top = 4.dp),
        )
        Spacer(Modifier.height(10.dp))
        Field(email, { email = it.trim(); error = null }, R.string.email, KeyboardType.Email)
        Spacer(Modifier.height(10.dp))
        Field(password, { password = it; error = null }, R.string.password, KeyboardType.Password, secret = true, last = true)
        Text(stringResource(R.string.password_rules), style = Zura.type.caption, color = if (password.isNotEmpty() && password.length < 8) c.danger else c.textMuted, modifier = Modifier.padding(start = 6.dp, top = 4.dp))
        RememberRow(remember) { remember = it }
        error?.let { Text(it, color = c.danger, style = Zura.type.label, modifier = Modifier.padding(vertical = 6.dp)) }
        Spacer(Modifier.height(8.dp))
        val ok = name.isNotBlank() && validUser && available != false && email.contains('@') && password.length >= 8
        PrimaryButton(stringResource(R.string.create_account), busy, enabled = ok) {
            busy = true
            scope.launch {
                when (val r = g.accounts.register(email, password, username, name, photo?.second, remember)) {
                    AuthResult.ConfirmEmail -> onConfirm(email)
                    is AuthResult.Error -> error = r.message
                    else -> Unit
                }
                busy = false
            }
        }
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun Confirm(email: String, onLogin: () -> Unit) {
    val c = Zura.colors
    Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.size(84.dp).clip(CircleShape).background(c.accent.copy(alpha = 0.15f)), contentAlignment = Alignment.Center) {
            Text("✉", fontSize = 38.sp, color = c.accent)
        }
        Spacer(Modifier.height(20.dp))
        Text(stringResource(R.string.confirm_email_title), style = Zura.type.title, color = c.text)
        Spacer(Modifier.height(10.dp))
        Text(stringResource(R.string.confirm_email_body, email), style = Zura.type.body, color = c.textMuted)
        Spacer(Modifier.height(28.dp))
        PrimaryButton(stringResource(R.string.login), busy = false, enabled = true, onClick = onLogin)
    }
}

// ───────────────────────────────────────────────────────────── building blocks

@Composable
private fun Field(value: String, onChange: (String) -> Unit, label: Int, type: KeyboardType, secret: Boolean = false, last: Boolean = false, prefix: String? = null) {
    val c = Zura.colors
    var show by remember { mutableStateOf(false) }
    OutlinedTextField(
        value = value, onValueChange = onChange,
        label = { Text(stringResource(label)) },
        singleLine = true,
        prefix = prefix?.let { { Text(it, color = c.textMuted) } },
        visualTransformation = if (secret && !show) PasswordVisualTransformation() else VisualTransformation.None,
        trailingIcon = if (secret) ({ TextButton(onClick = { show = !show }) { Text(if (show) "🙈" else "👁") } }) else null,
        keyboardOptions = KeyboardOptions(keyboardType = type, imeAction = if (last) ImeAction.Done else ImeAction.Next),
        shape = RoundedCornerShape(16.dp),
        colors = OutlinedTextFieldDefaults.colors(
            focusedBorderColor = c.accent, unfocusedBorderColor = c.divider.copy(alpha = 0.6f),
            focusedLabelColor = c.accent, unfocusedLabelColor = c.textMuted, cursorColor = c.accent,
            focusedTextColor = c.text, unfocusedTextColor = c.text,
            focusedContainerColor = c.surface.copy(alpha = 0.5f), unfocusedContainerColor = c.surface.copy(alpha = 0.35f),
        ),
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun RememberRow(checked: Boolean, onChange: (Boolean) -> Unit) {
    val c = Zura.colors
    Row(Modifier.fillMaxWidth().padding(top = 6.dp).clip(RoundedCornerShape(12.dp)).clickable { onChange(!checked) }, verticalAlignment = Alignment.CenterVertically) {
        Checkbox(checked, onChange, colors = CheckboxDefaults.colors(checkedColor = c.accent, checkmarkColor = c.onAccent, uncheckedColor = c.textMuted))
        Text(stringResource(R.string.remember_me), style = Zura.type.label, color = c.text)
    }
}

@Composable
fun PrimaryButton(text: String, busy: Boolean, enabled: Boolean, onClick: () -> Unit) {
    val c = Zura.colors
    val src = remember { MutableInteractionSource() }
    Box(
        Modifier.fillMaxWidth().height(54.dp).pressScale(src).clip(RoundedCornerShape(18.dp))
            .background(if (enabled) Brush.linearGradient(listOf(c.accent, c.accentAlt)) else Brush.linearGradient(listOf(c.surfaceHigh, c.surfaceHigh)))
            .clickable(src, null, enabled = enabled && !busy, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        if (busy) CircularProgressIndicator(Modifier.size(22.dp), color = c.onAccent, strokeWidth = 2.dp)
        else Text(text, style = Zura.type.headline, color = if (enabled) c.onAccent else c.textMuted)
    }
}

@Composable
private fun SecondaryButton(icon: androidx.compose.ui.graphics.vector.ImageVector, text: String, onClick: () -> Unit) {
    val c = Zura.colors
    val src = remember { MutableInteractionSource() }
    Row(
        Modifier.fillMaxWidth().pressScale(src, 0.98f).glass(RoundedCornerShape(18.dp), solid = 0.4f).clickable(src, null, onClick = onClick).padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, tint = c.accent)
        Spacer(Modifier.width(12.dp))
        Text(text, style = Zura.type.label, color = c.text)
    }
}
