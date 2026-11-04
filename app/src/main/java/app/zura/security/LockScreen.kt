package app.zura.security

import android.app.Activity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.zura.R
import app.zura.ui.components.pressScale
import app.zura.ui.components.zuraBackground
import app.zura.ui.theme.Zura

/** Full-screen lock. The prompt opens by itself; the button re-opens it if dismissed. */
@Composable
fun LockScreen(lock: AppLock) {
    val activity = LocalContext.current as Activity
    val c = Zura.colors
    val legacy = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        lock.legacyResult(r.resultCode == Activity.RESULT_OK)
    }
    val ask = { runCatching { lock.authenticate(activity, { legacy.launch(it) }) {} }; Unit }
    val error by lock.error.collectAsStateWithLifecycle()
    // Ask only once the screen is really in front (RESUMED): a prompt requested earlier — during
    // cold start — is silently dropped on some devices.
    androidx.lifecycle.compose.LifecycleResumeEffect(Unit) {
        // During a call the call screen is usable without unlocking the app (like other
        // messengers): don't throw the fingerprint prompt over it.
        if ((activity.applicationContext as app.zura.ZuraApp).graph.session.value?.calls?.ui?.value?.inCall != true) ask()
        onPauseOrDispose { }
    }
    val src = remember { MutableInteractionSource() }
    Box(
        // Swallow every touch so nothing underneath can be reached.
        Modifier.fillMaxSize().zuraBackground().clickable(remember { MutableInteractionSource() }, null) {},
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
            Box(
                Modifier.size(96.dp).clip(RoundedCornerShape(32.dp)).background(Brush.linearGradient(listOf(c.accent, c.accentAlt))),
                contentAlignment = Alignment.Center,
            ) { Icon(Icons.Default.Lock, null, tint = c.onAccent, modifier = Modifier.size(44.dp)) }
            Spacer(Modifier.height(22.dp))
            Text(stringResource(R.string.zura_locked), style = Zura.type.title, color = c.text)
            Spacer(Modifier.height(8.dp))
            Text(stringResource(R.string.unlock_subtitle), style = Zura.type.label, color = c.textMuted)
            error?.let {
                Spacer(Modifier.height(12.dp))
                Text(it, style = Zura.type.label, color = c.danger)
            }
            Spacer(Modifier.height(36.dp))
            Box(
                Modifier.pressScale(src).clip(CircleShape).background(c.accent).clickable(src, null) { ask() }
                    .padding(horizontal = 36.dp, vertical = 14.dp),
            ) { Text(stringResource(R.string.unlock), style = Zura.type.headline, color = c.onAccent) }
        }
    }
}
