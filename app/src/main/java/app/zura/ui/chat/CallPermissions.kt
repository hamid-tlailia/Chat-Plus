package app.zura.ui.chat

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.core.content.ContextCompat

/** Returns a launcher that ensures mic (+camera for video) permission, then reports the result. */
@Composable
fun rememberCallPermissions(video: Boolean, onResult: (Boolean) -> Unit): (Context) -> Unit {
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { r ->
        onResult(r.values.all { it })
    }
    return { ctx ->
        val needed = buildList {
            add(Manifest.permission.RECORD_AUDIO)
            if (video) add(Manifest.permission.CAMERA)
        }.filter { ContextCompat.checkSelfPermission(ctx, it) != PackageManager.PERMISSION_GRANTED }
        if (needed.isEmpty()) onResult(true) else launcher.launch(needed.toTypedArray())
    }
}
