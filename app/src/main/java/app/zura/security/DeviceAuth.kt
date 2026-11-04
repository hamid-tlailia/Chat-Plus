package app.zura.security

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import app.zura.graph

/**
 * Ask for fingerprint / face / phone PIN right now, for one action (opening a locked chat,
 * changing a lock). Returns a function: call it with what to do once the user is verified.
 */
@Composable
fun rememberDeviceAuth(): (onResult: (Boolean) -> Unit) -> Unit {
    val ctx = LocalContext.current
    val lock = ctx.graph.appLock
    val pending = remember { mutableStateOf<((Boolean) -> Unit)?>(null) }
    val legacy = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        val ok = r.resultCode == Activity.RESULT_OK
        lock.legacyResult(ok)
        pending.value?.invoke(ok); pending.value = null
    }
    return remember(ctx) {
        { onResult ->
            val activity = ctx.findActivity()
            if (activity == null) onResult(false)
            else {
                pending.value = onResult
                runCatching {
                    lock.authenticate(activity, { legacy.launch(it) }) { ok -> pending.value?.invoke(ok); pending.value = null }
                }.onFailure { onResult(false) }
            }
        }
    }
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
