package app.zura.ui.components

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import app.zura.ui.theme.Zura

/** One button of a [ConfirmSheet]. */
data class ConfirmAction(val label: String, val danger: Boolean = false, val onClick: () -> Unit)

/**
 * Zura's own confirmation sheet: dimmed backdrop, a glass card that springs up from the bottom,
 * an icon, a title and message, the actions as full-width pills and Cancel. Not a system dialog.
 */
@Composable
fun ConfirmSheet(
    title: String,
    message: String?,
    actions: List<ConfirmAction>,
    cancelLabel: String,
    onDismiss: () -> Unit,
    icon: ImageVector = Icons.Default.Delete,
) {
    val c = Zura.colors
    val haptics = rememberHaptics()
    val shown = remember { MutableTransitionState(false) }
    LaunchedEffect(Unit) { shown.targetState = true }
    // Animate out, then tell the caller.
    val pending = remember { androidx.compose.runtime.mutableStateOf<(() -> Unit)?>(null) }
    fun close(then: () -> Unit) { if (pending.value == null) { pending.value = then; shown.targetState = false } }
    LaunchedEffect(shown.currentState, shown.targetState) {
        if (!shown.targetState && !shown.currentState) pending.value?.invoke()
    }
    BackHandler { close(onDismiss) }

    Popup(onDismissRequest = { close(onDismiss) }, properties = PopupProperties(focusable = true)) {
        Box(Modifier.fillMaxSize()) {
            AnimatedVisibility(shown, enter = fadeIn(), exit = fadeOut()) {
                Box(
                    Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.45f))
                        .clickable(remember { MutableInteractionSource() }, null) { close(onDismiss) },
                )
            }
            AnimatedVisibility(
                shown, modifier = Modifier.align(Alignment.BottomCenter),
                enter = slideInVertically(androidx.compose.animation.core.spring(0.82f, 500f, androidx.compose.ui.unit.IntOffset(1, 1))) { it } + fadeIn(),
                exit = slideOutVertically { it } + fadeOut(),
            ) {
                Column(
                    Modifier.fillMaxWidth().navigationBarsPadding().padding(12.dp)
                        .clip(RoundedCornerShape(28.dp)).background(c.surfaceHigh)
                        .clickable(remember { MutableInteractionSource() }, null) {}
                        .padding(20.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Box(
                        Modifier.size(52.dp).clip(CircleShape).background(c.danger.copy(alpha = 0.14f)),
                        contentAlignment = Alignment.Center,
                    ) { Icon(icon, null, tint = c.danger) }
                    Spacer(Modifier.height(12.dp))
                    Text(title, style = Zura.type.headline, color = c.text, textAlign = TextAlign.Center)
                    message?.let {
                        Spacer(Modifier.height(6.dp))
                        Text(it, style = Zura.type.label, color = c.textMuted, textAlign = TextAlign.Center)
                    }
                    Spacer(Modifier.height(18.dp))
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        actions.forEach { a ->
                            Pill(a.label, if (a.danger) c.danger else c.accent, Color.White) { haptics.reject(); close(a.onClick) }
                        }
                        Pill(cancelLabel, c.text.copy(alpha = 0.08f), c.text) { haptics.tick(); close(onDismiss) }
                    }
                }
            }
        }
    }
}

@Composable
private fun Pill(label: String, bg: Color, fg: Color, onClick: () -> Unit) {
    val src = remember { MutableInteractionSource() }
    Row(
        Modifier.fillMaxWidth().height(50.dp).pressScale(src, 0.97f).clip(RoundedCornerShape(25.dp)).background(bg)
            .clickable(src, null, onClick = onClick),
        horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically,
    ) { Text(label, style = Zura.type.label, color = fg) }
}
