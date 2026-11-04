package app.zura.ui.components

import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember

/**
 * Zura's sheets draw in an overlay layer of the app's own (edge-to-edge) screen — not in a
 * separate dialog/popup window. Separate windows got laid out under the status bar on some phones
 * and pushed the sheet's bottom off screen; here the system-bar insets are always right.
 */
object Overlays {
    class Entry(val content: MutableState<@Composable () -> Unit>)
    internal val entries = mutableStateListOf<Entry>()
}

/** Shows [content] full-screen above everything while this composable is in the tree. */
@Composable
fun SheetHost(onDismiss: () -> Unit, content: @Composable () -> Unit) {
    val entry = remember { Overlays.Entry(mutableStateOf(content)) }
    SideEffect { entry.content.value = { BackHandler(onBack = onDismiss); content() } }
    DisposableEffect(entry) {
        Overlays.entries.add(entry)
        onDispose { Overlays.entries.remove(entry) }
    }
}

/** Place once, last, in the root of the app's content. */
@Composable
fun OverlayLayer() {
    Overlays.entries.forEach { e -> key(e) { e.content.value() } }
}
