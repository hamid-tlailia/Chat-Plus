package app.zura.push

import android.Manifest
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.core.content.ContextCompat
import app.zura.R
import app.zura.ui.theme.Zura

object PushPermissions {
    fun notificationsGranted(c: Context) = Build.VERSION.SDK_INT < 33 ||
        ContextCompat.checkSelfPermission(c, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    /** Android 14+: full-screen intents need explicit user consent for non-dialer apps. */
    fun fullScreenAllowed(c: Context) = Build.VERSION.SDK_INT < 34 ||
        c.getSystemService(NotificationManager::class.java).canUseFullScreenIntent()

    fun openFullScreenSettings(c: Context) {
        if (Build.VERSION.SDK_INT >= 34) runCatching {
            c.startActivity(Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT, Uri.parse("package:${c.packageName}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }
}

/** One-time, explained prompts after sign-in: notifications, then full-screen calls (Android 14+). */
@Composable
fun PushPermissionPrompts() {
    val ctx = LocalContext.current
    val prefs = remember { ctx.getSharedPreferences("zura_prompts", Context.MODE_PRIVATE) }
    var step by remember {
        mutableStateOf(
            when {
                !PushPermissions.notificationsGranted(ctx) && !prefs.getBoolean("asked_notif", false) -> 1
                !PushPermissions.fullScreenAllowed(ctx) && !prefs.getBoolean("asked_fsi", false) -> 2
                else -> 0
            },
        )
    }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        step = if (!PushPermissions.fullScreenAllowed(ctx) && !prefs.getBoolean("asked_fsi", false)) 2 else 0
    }
    val c = Zura.colors
    if (step == 0) return
    AlertDialog(
        onDismissRequest = {},
        containerColor = c.surfaceHigh,
        title = { Text(stringResource(R.string.notif_permission_title), color = c.text) },
        text = { Text(stringResource(if (step == 1) R.string.notif_permission_body else R.string.fullscreen_permission_body), color = c.textMuted) },
        confirmButton = {
            TextButton(onClick = {
                if (step == 1) {
                    prefs.edit().putBoolean("asked_notif", true).apply()
                    if (Build.VERSION.SDK_INT >= 33) launcher.launch(Manifest.permission.POST_NOTIFICATIONS) else step = 0
                } else {
                    prefs.edit().putBoolean("asked_fsi", true).apply()
                    PushPermissions.openFullScreenSettings(ctx); step = 0
                }
            }) { Text(stringResource(R.string.allow), color = c.accent) }
        },
        dismissButton = {
            TextButton(onClick = {
                prefs.edit().putBoolean(if (step == 1) "asked_notif" else "asked_fsi", true).apply()
                step = if (step == 1 && !PushPermissions.fullScreenAllowed(ctx) && !prefs.getBoolean("asked_fsi", false)) 2 else 0
            }) { Text(stringResource(R.string.not_now), color = c.text) }
        },
    )
}
