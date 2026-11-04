package app.zura.ui.components

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.material3.Text
import app.zura.ui.theme.Zura

/** Zura's bottom sheet with a single choice (radio list). Not a system dialog. */
@Composable
fun <T> PickerSheet(title: String, hint: String?, options: List<Pair<T, String>>, selected: T, onPick: (T) -> Unit, onDismiss: () -> Unit) {
    val c = Zura.colors
    val haptics = rememberHaptics()
    val shown = remember { MutableTransitionState(false) }
    LaunchedEffect(Unit) { shown.targetState = true }
    val pending = remember { mutableStateOf<(() -> Unit)?>(null) }
    fun close(then: () -> Unit) { if (pending.value == null) { pending.value = then; shown.targetState = false } }
    LaunchedEffect(shown.currentState, shown.targetState) { if (!shown.targetState && !shown.currentState) pending.value?.invoke() }
    BackHandler { close(onDismiss) }

    app.zura.ui.components.SheetHost(onDismiss = { close(onDismiss) }) {
        Box(Modifier.fillMaxSize()) {
            AnimatedVisibility(shown, enter = fadeIn(), exit = fadeOut()) {
                Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.45f)).clickable(remember { MutableInteractionSource() }, null) { close(onDismiss) })
            }
            AnimatedVisibility(
                shown, modifier = Modifier.align(Alignment.BottomCenter),
                enter = slideInVertically(spring(0.82f, 500f, IntOffset(1, 1))) { it } + fadeIn(),
                exit = slideOutVertically { it } + fadeOut(),
            ) {
                Column(
                    Modifier.fillMaxWidth().navigationBarsPadding().padding(12.dp).clip(RoundedCornerShape(28.dp)).background(c.surfaceHigh)
                        .clickable(remember { MutableInteractionSource() }, null) {}.padding(vertical = 18.dp)
                        .verticalScroll(rememberScrollState()),
                ) {
                    Text(title, style = Zura.type.headline, color = c.text, modifier = Modifier.padding(horizontal = 22.dp))
                    hint?.let { Text(it, style = Zura.type.caption, color = c.textMuted, modifier = Modifier.padding(horizontal = 22.dp, vertical = 4.dp)) }
                    Spacer(Modifier.height(8.dp))
                    options.forEach { (value, label) ->
                        val on = value == selected
                        Row(
                            Modifier.fillMaxWidth().clickable { haptics.tick(); close { onPick(value) } }.padding(horizontal = 22.dp, vertical = 13.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(label, style = Zura.type.label, color = if (on) c.accent else c.text, modifier = Modifier.weight(1f))
                            Box(Modifier.size(22.dp).clip(CircleShape).background(if (on) c.accent else c.text.copy(alpha = 0.1f)), contentAlignment = Alignment.Center) {
                                if (on) Box(Modifier.size(8.dp).clip(CircleShape).background(c.onAccent))
                            }
                        }
                    }
                }
            }
        }
    }
}
