package app.zura.ui.settings

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContract
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.zura.R
import app.zura.data.Vibe
import app.zura.graph
import app.zura.ui.components.PickerSheet
import app.zura.ui.theme.Zura

/** System tone picker. Output: null = cancelled; "" = silent; "default" = default; else the URI. */
private class PickTone(private val toneType: Int) : ActivityResultContract<String?, String?>() {
    override fun createIntent(context: Context, input: String?) = Intent(RingtoneManager.ACTION_RINGTONE_PICKER).apply {
        putExtra(RingtoneManager.EXTRA_RINGTONE_TYPE, toneType)
        putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_SILENT, true)
        putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_DEFAULT, true)
        val def: android.os.Parcelable = RingtoneManager.getDefaultUri(toneType)
        putExtra(RingtoneManager.EXTRA_RINGTONE_DEFAULT_URI, def)
        // Current choice: "" = silent (no existing uri), null = default.
        val existing: android.os.Parcelable? = when {
            input == null -> def
            input.isEmpty() -> null
            else -> Uri.parse(input)
        }
        putExtra(RingtoneManager.EXTRA_RINGTONE_EXISTING_URI, existing)
    }
    override fun parseResult(resultCode: Int, intent: Intent?): String? {
        if (resultCode != Activity.RESULT_OK) return null
        val uri: Uri? = if (Build.VERSION.SDK_INT >= 33) intent?.getParcelableExtra(RingtoneManager.EXTRA_RINGTONE_PICKED_URI, Uri::class.java)
        else @Suppress("DEPRECATION") intent?.getParcelableExtra(RingtoneManager.EXTRA_RINGTONE_PICKED_URI)
        return when {
            uri == null -> ""
            RingtoneManager.isDefault(uri) -> "default"
            else -> uri.toString()
        }
    }
}

/**
 * Tone + vibration rows for messages and calls. [chatId]/[peerId] null = the app-wide defaults;
 * otherwise overrides for that one chat / person (with "Use default" to go back).
 */
@Composable
fun SoundRows(chatId: String?, peerId: String?) {
    val ctx = LocalContext.current
    val s = ctx.graph.sounds
    val c = Zura.colors
    @Suppress("UNUSED_VARIABLE") val v by s.version.collectAsStateWithLifecycle() // refresh on change
    var pickVibe by remember { mutableStateOf<String?>(null) } // "msg" | "ring"
    val toneLauncher = rememberLauncherForActivityResult(PickTone(RingtoneManager.TYPE_NOTIFICATION)) { r ->
        r?.let { s.setMessageTone(chatId, if (it == "default") null else it) }
    }
    val ringLauncher = rememberLauncherForActivityResult(PickTone(RingtoneManager.TYPE_RINGTONE)) { r ->
        r?.let { s.setRingtone(peerId, if (it == "default") null else it) }
    }
    val scoped = chatId != null || peerId != null

    Row2(stringResource(R.string.message_tone), toneName(s.messageTone(chatId), RingtoneManager.TYPE_NOTIFICATION)) { toneLauncher.launch(s.messageTone(chatId)) }
    Row2(stringResource(R.string.message_vibe), vibeName(s.vibe(chatId))) { pickVibe = "msg" }
    if (!scoped || peerId != null) {
        Row2(stringResource(R.string.ringtone), toneName(s.ringtone(peerId), RingtoneManager.TYPE_RINGTONE)) { ringLauncher.launch(s.ringtone(peerId)) }
        Row2(stringResource(R.string.ring_vibe), vibeName(s.ringVibe(peerId))) { pickVibe = "ring" }
    }
    if (scoped && (s.messageTone(chatId) != null || s.vibe(chatId) != Vibe.DEFAULT || s.ringtone(peerId) != null || s.ringVibe(peerId) != Vibe.DEFAULT)) {
        Text(
            stringResource(R.string.use_default), style = Zura.type.label, color = c.accent,
            modifier = Modifier.padding(vertical = 8.dp).clickable {
                s.setMessageTone(chatId, null); s.setVibe(chatId, Vibe.DEFAULT); s.setRingtone(peerId, null); s.setRingVibe(peerId, Vibe.DEFAULT)
            },
        )
    }
    pickVibe?.let { which ->
        PickerSheet(
            title = stringResource(if (which == "msg") R.string.message_vibe else R.string.ring_vibe), hint = null,
            options = Vibe.entries.map { it to vibeName(it) },
            selected = if (which == "msg") s.vibe(chatId) else s.ringVibe(peerId),
            onPick = { p ->
                pickVibe = null
                if (which == "msg") s.setVibe(chatId, p) else s.setRingVibe(peerId, p)
                previewVibe(ctx, p)
            },
            onDismiss = { pickVibe = null },
        )
    }
}

@Composable
private fun Row2(title: String, value: String, onClick: () -> Unit) {
    val c = Zura.colors
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(title, style = Zura.type.label, color = c.text, modifier = Modifier.weight(1f))
        Text(value, style = Zura.type.caption, color = c.accent)
    }
}

@Composable
private fun vibeName(v: Vibe): String = stringResource(
    when (v) {
        Vibe.DEFAULT -> R.string.vibe_DEFAULT
        Vibe.NONE -> R.string.vibe_NONE
        Vibe.SHORT -> R.string.vibe_SHORT
        Vibe.DOUBLE -> R.string.vibe_DOUBLE
        Vibe.LONG -> R.string.vibe_LONG
        Vibe.HEARTBEAT -> R.string.vibe_HEARTBEAT
    },
)

private fun previewVibe(ctx: Context, v: Vibe) {
    val p = v.pattern?.takeIf { it.size > 1 } ?: return
    runCatching {
        val vib = if (Build.VERSION.SDK_INT >= 31) ctx.getSystemService(android.os.VibratorManager::class.java).defaultVibrator
        else @Suppress("DEPRECATION") ctx.getSystemService(android.os.Vibrator::class.java)
        if (Build.VERSION.SDK_INT >= 26) vib.vibrate(android.os.VibrationEffect.createWaveform(p, -1))
    }
}

/**
 * A tone's title, looked up OFF the main thread: the system answers it with a media-database
 * query (tens to hundreds of ms) — done inline it made opening Settings stutter.
 */
@Composable
private fun toneName(tone: String?, type: Int): String {
    val ctx = LocalContext.current
    val quick = when {
        tone == null -> stringResource(R.string.tone_default)
        tone.isEmpty() -> stringResource(R.string.tone_silent)
        else -> null
    }
    if (quick != null) return quick
    val name by androidx.compose.runtime.produceState(toneTitles[tone] ?: "…", tone) {
        value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            ctx.graph.sounds.toneName(ctx, tone, type).also { toneTitles[tone!!] = it }
        }
    }
    return name
}

private val toneTitles = java.util.concurrent.ConcurrentHashMap<String, String>()
