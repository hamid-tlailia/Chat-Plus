package app.zura.push

import android.Manifest
import android.annotation.SuppressLint
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LifecycleResumeEffect
import app.zura.R
import app.zura.ui.theme.Zura

/** Everything a phone needs to ring for Zura while the app is closed, and where to fix each one. */
object PushPermissions {
    fun notificationsGranted(c: Context) = Build.VERSION.SDK_INT < 33 ||
        ContextCompat.checkSelfPermission(c, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    /** Android 14+: full-screen intents (the call screen over the lock screen) need explicit consent. */
    fun fullScreenAllowed(c: Context) = Build.VERSION.SDK_INT < 34 ||
        c.getSystemService(NotificationManager::class.java).canUseFullScreenIntent()

    /** Not battery-optimised: Doze / OEM killers won't delay the call push or stop the ringing. */
    fun batteryUnrestricted(c: Context) = c.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(c.packageName)

    fun allReady(c: Context) = notificationsGranted(c) && fullScreenAllowed(c) && batteryUnrestricted(c)

    fun openFullScreenSettings(c: Context) {
        if (Build.VERSION.SDK_INT >= 34) open(c, Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT, Uri.parse("package:${c.packageName}")))
    }

    fun openNotificationSettings(c: Context) {
        open(c, Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, c.packageName))
    }

    @SuppressLint("BatteryLife") // a calling app is exactly the case this request exists for
    fun requestBatteryExemption(c: Context) {
        if (!open(c, Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${c.packageName}")))) {
            open(c, Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
        }
    }

    /** App info page: OEM extras like "Autostart" / "Show on lock screen" (Xiaomi, Oppo…) live here. */
    fun openAppSettings(c: Context) {
        open(c, Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${c.packageName}")))
    }

    private fun open(c: Context, i: Intent): Boolean =
        runCatching { c.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }.isSuccess
}

/**
 * The three switches, each with its live status and an Allow button. Re-checked every time the
 * screen resumes (the user comes back from the system settings page).
 */
@Composable
fun CallReadinessList(modifier: Modifier = Modifier) {
    val ctx = LocalContext.current
    val prefs = remember { ctx.getSharedPreferences("zura_prompts", Context.MODE_PRIVATE) }
    var tick by remember { mutableIntStateOf(0) }
    LifecycleResumeEffect(Unit) { tick++; onPauseOrDispose { } }
    val notifLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { tick++ }
    // key(tick): statuses are re-read after every resume / permission result.
    androidx.compose.runtime.key(tick) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        ReadinessRow(
            R.string.ready_notifications, R.string.ready_notifications_hint, PushPermissions.notificationsGranted(ctx),
        ) {
            // The system dialog only appears twice; after that, go to the settings page.
            if (Build.VERSION.SDK_INT >= 33 && !prefs.getBoolean("asked_notif", false)) {
                prefs.edit().putBoolean("asked_notif", true).apply()
                notifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            } else PushPermissions.openNotificationSettings(ctx)
        }
        ReadinessRow(
            R.string.ready_fullscreen, R.string.ready_fullscreen_hint, PushPermissions.fullScreenAllowed(ctx),
        ) { PushPermissions.openFullScreenSettings(ctx) }
        ReadinessRow(
            R.string.ready_battery, R.string.ready_battery_hint, PushPermissions.batteryUnrestricted(ctx),
        ) { PushPermissions.requestBatteryExemption(ctx) }
        Text(
            stringResource(R.string.ready_more) + " →", style = Zura.type.caption, color = Zura.colors.accent,
            modifier = Modifier.clip(CircleShape).clickable { PushPermissions.openAppSettings(ctx) }.padding(vertical = 4.dp),
        )
    }
    }
}

@Composable
private fun ReadinessRow(title: Int, hint: Int, ok: Boolean, onAllow: () -> Unit) {
    val c = Zura.colors
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier.size(26.dp).clip(CircleShape).background(if (ok) Color(0xFF2EBD6B) else c.surfaceHigh),
            contentAlignment = Alignment.Center,
        ) { Text(if (ok) "✓" else "!", color = if (ok) Color.White else c.danger, style = Zura.type.label) }
        Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
            Text(stringResource(title), style = Zura.type.label, color = c.text)
            Text(stringResource(hint), style = Zura.type.caption, color = c.textMuted)
        }
        if (ok) Text(stringResource(R.string.ready_on), style = Zura.type.caption, color = c.textMuted)
        else Box(
            Modifier.clip(CircleShape).background(c.accent).clickable(onClick = onAllow).padding(horizontal = 14.dp, vertical = 8.dp),
        ) { Text(stringResource(R.string.allow), color = c.onAccent, style = Zura.type.label) }
    }
}

/**
 * First launch after sign-in: explain and offer "ring when closed / full-screen calls / wake the
 * phone" in one place. Shown once; the same list lives in Settings → Calls when closed.
 */
@Composable
fun CallReadinessPrompt() {
    val ctx = LocalContext.current
    val prefs = remember { ctx.getSharedPreferences("zura_prompts", Context.MODE_PRIVATE) }
    var show by remember { mutableStateOf(!prefs.getBoolean("asked_ready", false) && !PushPermissions.allReady(ctx)) }
    if (!show) return
    val c = Zura.colors
    fun done() { prefs.edit().putBoolean("asked_ready", true).apply(); show = false }
    AlertDialog(
        onDismissRequest = {},
        containerColor = c.surfaceHigh,
        title = { Text(stringResource(R.string.notif_permission_title), color = c.text) },
        text = {
            Column {
                Text(stringResource(R.string.ready_intro), style = Zura.type.caption, color = c.textMuted)
                Spacer(Modifier.height(14.dp))
                CallReadinessList()
            }
        },
        confirmButton = { TextButton(onClick = ::done) { Text(stringResource(R.string.done), color = c.accent) } },
        dismissButton = { TextButton(onClick = ::done) { Text(stringResource(R.string.not_now), color = c.text) } },
    )
}
