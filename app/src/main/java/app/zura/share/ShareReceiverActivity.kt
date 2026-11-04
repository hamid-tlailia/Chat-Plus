package app.zura.share

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.OpenableColumns
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import app.zura.MainActivity
import app.zura.R
import app.zura.core.model.MsgKind
import app.zura.core.storage.SecureEraser
import app.zura.graph
import app.zura.i18n.LocaleEngine
import app.zura.ui.components.Avatar
import app.zura.ui.components.glass
import app.zura.ui.theme.Zura
import app.zura.ui.theme.ZuraTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * System-wide share target. Accepts text, images, videos, audio and arbitrary files (single or
 * multiple) from ANY app. Two entry points:
 *  - Direct Share shortcut (chat pre-selected in the sharesheet) → import & send immediately.
 *  - Generic "Zura" target → translucent glass sheet with a chat picker.
 * Imported content is compressed/encrypted into the vault; the sender's URI grant is used once.
 */
class ShareReceiverActivity : ComponentActivity() {

    override fun attachBaseContext(newBase: android.content.Context) = super.attachBaseContext(LocaleEngine.wrap(newBase))

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val payload = parse(intent) ?: return finish()

        intent.getStringExtra(ShortcutManagerCompat.EXTRA_SHORTCUT_ID)?.let { chatId ->
            deliver(chatId, payload); return
        }

        setContent {
            val g = graph
            val theme by g.settings.theme.collectAsStateWithLifecycle()
            val chats by g.chats.chats.collectAsStateWithLifecycle()
            val locked by g.appLock.locked.collectAsStateWithLifecycle()
            ZuraTheme(theme) {
                if (locked) { app.zura.security.LockScreen(g.appLock); return@ZuraTheme }
                Box(Modifier.fillMaxSize().clickable(onClick = ::finish), contentAlignment = Alignment.BottomCenter) {
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .padding(10.dp)
                            .glass(RoundedCornerShape(28.dp))
                            .clickable(enabled = false) {}
                            .navigationBarsPadding()
                            .padding(vertical = 18.dp),
                    ) {
                        Text(stringResource(R.string.share_to), style = Zura.type.headline, color = Zura.colors.text, modifier = Modifier.padding(horizontal = 22.dp))
                        Spacer(Modifier.width(8.dp))
                        LazyColumn(Modifier.heightIn(max = 420.dp).padding(top = 10.dp)) {
                            items(chats, key = { it.id }) { c ->
                                Row(
                                    Modifier.fillMaxWidth().clickable { deliver(c.id, payload) }.padding(horizontal = 22.dp, vertical = 10.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(14.dp),
                                ) {
                                    Avatar(c.title, c.avatarHue, 44.dp)
                                    Text(c.title, style = Zura.type.label, color = Zura.colors.text)
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    private sealed interface Payload {
        data class Text(val text: String) : Payload
        data class Files(val uris: List<Uri>, val caption: String?) : Payload
    }

    private fun parse(i: Intent): Payload? {
        val text = i.getStringExtra(Intent.EXTRA_TEXT)
        val uris: List<Uri> = when (i.action) {
            Intent.ACTION_SEND -> listOfNotNull(stream(i))
            Intent.ACTION_SEND_MULTIPLE -> streams(i)
            else -> emptyList()
        }
        return when {
            uris.isNotEmpty() -> Payload.Files(uris, text)
            !text.isNullOrBlank() -> Payload.Text(text)
            else -> null
        }
    }

    @Suppress("DEPRECATION")
    private fun stream(i: Intent): Uri? =
        if (Build.VERSION.SDK_INT >= 33) i.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java) else i.getParcelableExtra(Intent.EXTRA_STREAM)

    @Suppress("DEPRECATION")
    private fun streams(i: Intent): List<Uri> =
        (if (Build.VERSION.SDK_INT >= 33) i.getParcelableArrayListExtra(Intent.EXTRA_STREAM, Uri::class.java) else i.getParcelableArrayListExtra(Intent.EXTRA_STREAM))
            .orEmpty()

    private fun deliver(chatId: String, payload: Payload) {
        val g = graph
        val cr = contentResolver
        lifecycleScope.launch {
            // The sender's URI grant dies with this activity, so first copy the raw bytes into
            // our private no_backup/tmp (fast disk→disk), then compress/encrypt off the UI path.
            val staged = withContext(Dispatchers.IO) {
                (payload as? Payload.Files)?.uris?.mapNotNull { uri ->
                    runCatching {
                        val mime = cr.getType(uri) ?: "application/octet-stream"
                        val tmp = g.vault.paths.newTemp("in")
                        cr.openInputStream(uri)!!.use { input -> tmp.outputStream().use { input.copyTo(it) } }
                        Staged(tmp, mime, displayName(uri))
                    }.getOrNull()
                }.orEmpty()
            }
            // Not sent yet: it waits in that chat's composer for an optional caption (or edit).
            when (payload) {
                is Payload.Text -> ShareInbox.put(chatId, emptyList(), payload.text)
                is Payload.Files -> ShareInbox.put(chatId, staged.map { Attachment(Uri.fromFile(it.file), it.mime, it.name, it.file) }, payload.caption)
            }
            startActivity(
                Intent(this@ShareReceiverActivity, MainActivity::class.java)
                    .setAction(Intent.ACTION_VIEW).putExtra(MainActivity.EXTRA_CHAT, chatId).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
            finish()
        }
    }

    private class Staged(val file: File, val mime: String, val name: String?)

    private fun displayName(uri: Uri): String? = runCatching {
        contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }
    }.getOrNull()
}
