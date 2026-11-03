package app.zura.ui.chats

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import app.zura.R
import app.zura.ui.theme.Zura

/** Minimal contact entry: display name + Zura ID (QR / phone discovery is on the roadmap). */
@Composable
fun NewChatDialog(onDismiss: () -> Unit, onCreate: (name: String, peerId: String) -> Unit) {
    var name by remember { mutableStateOf("") }
    var peer by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Zura.colors.surfaceHigh,
        title = { Text(stringResource(R.string.new_chat), color = Zura.colors.text) },
        text = {
            Column {
                OutlinedTextField(name, { name = it }, label = { Text(stringResource(R.string.name)) }, singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next))
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(peer, { peer = it.filter { c -> c.isLetterOrDigit() } }, label = { Text(stringResource(R.string.zura_id)) }, singleLine = true)
            }
        },
        confirmButton = {
            TextButton(enabled = name.isNotBlank() && peer.length >= 6, onClick = { onCreate(name.trim(), peer) }) {
                Text(stringResource(R.string.create), color = Zura.colors.accent)
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel), color = Zura.colors.text) } },
    )
}
