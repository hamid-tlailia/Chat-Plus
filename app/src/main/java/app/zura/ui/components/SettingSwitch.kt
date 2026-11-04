package app.zura.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import app.zura.ui.theme.Zura

/** Title + hint on the start side, a themed switch on the end; the whole row toggles. */
@Composable
fun SettingSwitch(title: String, hint: String?, checked: Boolean, modifier: Modifier = Modifier, onChange: (Boolean) -> Unit) {
    val c = Zura.colors
    val haptics = rememberHaptics()
    Row(
        modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable { haptics.tick(); onChange(!checked) }.padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f).padding(end = 12.dp)) {
            Text(title, style = Zura.type.label, color = c.text)
            hint?.let { Text(it, style = Zura.type.caption, color = c.textMuted) }
        }
        Switch(checked, { haptics.tick(); onChange(it) }, colors = SwitchDefaults.colors(checkedTrackColor = c.accent, checkedThumbColor = c.onAccent))
    }
}
