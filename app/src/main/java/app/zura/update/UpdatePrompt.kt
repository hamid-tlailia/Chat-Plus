package app.zura.update

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.zura.R
import app.zura.ui.theme.Zura
import kotlinx.coroutines.launch

/**
 * "A new version is available" — checked when Zura comes to the front (at most every few hours).
 * Update → download with progress → Android's own install confirmation. Later → not offered
 * again until an even newer version is published.
 */
@Composable
fun UpdatePrompt() {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val state by Updater.state.collectAsStateWithLifecycle()
    var dismissed by remember { mutableStateOf(false) }
    LifecycleResumeEffect(Unit) {
        val job = scope.launch { if (Updater.check(ctx)) dismissed = false }
        onPauseOrDispose { job.cancel() }
    }
    val s = state
    val r = when (s) {
        is Updater.State.Available -> s.r.takeIf { !Updater.isSkipped(ctx, it) }
        is Updater.State.Downloading -> s.r
        is Updater.State.Installing -> s.r
        is Updater.State.Failed -> s.r
        Updater.State.Idle -> null
    }
    if (r == null || dismissed) return
    UpdateDialog(r, s, onLater = { Updater.skip(ctx, r); dismissed = true }, onClose = { dismissed = true })
}

@Composable
fun UpdateDialog(r: Updater.Release, s: Updater.State, onLater: () -> Unit, onClose: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val c = Zura.colors
    val busy = s is Updater.State.Downloading || s is Updater.State.Installing
    AlertDialog(
        onDismissRequest = { if (!busy) onClose() },
        containerColor = c.surfaceHigh,
        title = { Text(stringResource(R.string.update_title, r.versionName), color = c.text) },
        text = {
            Column {
                Text(r.notes?.takeIf { it.isNotBlank() } ?: stringResource(R.string.update_body), style = Zura.type.caption, color = c.textMuted)
                when (s) {
                    is Updater.State.Downloading -> {
                        Spacer(Modifier.height(14.dp))
                        LinearProgressIndicator(progress = { s.progress }, modifier = Modifier.fillMaxWidth(), color = c.accent, trackColor = c.surface)
                        Spacer(Modifier.height(6.dp))
                        Text(stringResource(R.string.update_downloading, (s.progress * 100).toInt()), style = Zura.type.caption, color = c.textMuted)
                    }
                    is Updater.State.Installing -> {
                        Spacer(Modifier.height(14.dp))
                        Text(stringResource(R.string.update_installing), style = Zura.type.caption, color = c.textMuted)
                    }
                    is Updater.State.Failed -> {
                        Spacer(Modifier.height(14.dp))
                        Text(stringResource(R.string.update_failed), style = Zura.type.caption, color = c.danger)
                    }
                    else -> if (!Updater.canInstall(ctx)) {
                        Spacer(Modifier.height(14.dp))
                        Text(stringResource(R.string.update_allow_install), style = Zura.type.caption, color = c.textMuted)
                    }
                }
            }
        },
        confirmButton = {
            if (!busy) TextButton(onClick = {
                if (!Updater.canInstall(ctx)) Updater.openInstallPermission(ctx)
                else scope.launch { Updater.downloadAndInstall(ctx, r) }
            }) { Text(stringResource(if (s is Updater.State.Failed) R.string.retry else R.string.update_now), color = c.accent) }
        },
        dismissButton = { if (!busy) TextButton(onClick = onLater) { Text(stringResource(R.string.later), color = c.text) } },
    )
}
