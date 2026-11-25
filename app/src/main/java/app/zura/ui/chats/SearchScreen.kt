package app.zura.ui.chats

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.zura.R
import app.zura.core.model.ChatMeta
import app.zura.core.model.Message
import app.zura.graph
import app.zura.i18n.ZuraFormat
import app.zura.ui.components.UserAvatar
import app.zura.ui.components.appear
import app.zura.ui.theme.Zura
import kotlinx.coroutines.delay

/**
 * Search across Zura: chats by name, and messages (text and captions) in every chat — all on this
 * phone (the messages are decrypted locally; nothing is sent anywhere). Tap a message → that chat,
 * scrolled to it.
 */
@Composable
fun SearchScreen(onBack: () -> Unit, onOpen: (chatId: String, messageId: String?) -> Unit) {
    val ctx = LocalContext.current
    val g = ctx.graph
    val c = Zura.colors
    val fmt = remember { ZuraFormat(java.util.Locale.getDefault()) }
    val chats by g.chats.chats.collectAsStateWithLifecycle()
    var query by remember { mutableStateOf("") }
    var hits by remember { mutableStateOf<List<Pair<ChatMeta, Message>>>(emptyList()) }
    var searching by remember { mutableStateOf(false) }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    LaunchedEffect(query) {
        if (query.trim().length < 2) { hits = emptyList(); return@LaunchedEffect }
        delay(250) // typing: search once it pauses
        searching = true
        hits = runCatching { g.chats.searchMessages(query) }.getOrDefault(emptyList())
        searching = false
    }
    val q = query.trim()
    val chatHits = if (q.isEmpty()) emptyList() else chats.filter { it.title.contains(q, ignoreCase = true) }

    Column(Modifier.fillMaxSize().statusBarsPadding().imePadding()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, null, tint = c.text) }
            Row(
                Modifier.weight(1f).padding(end = 12.dp).background(c.surfaceHigh, RoundedCornerShape(22.dp)).padding(horizontal = 14.dp, vertical = 11.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Default.Search, null, tint = c.textMuted)
                Spacer(Modifier.width(10.dp))
                Box(Modifier.weight(1f)) {
                    if (query.isEmpty()) Text(stringResource(R.string.search_hint), style = Zura.type.body, color = c.textMuted)
                    BasicTextField(
                        query, { query = it }, singleLine = true,
                        textStyle = Zura.type.body.copy(color = c.text), cursorBrush = SolidColor(c.accent),
                        modifier = Modifier.fillMaxWidth().focusRequester(focus),
                    )
                }
            }
        }
        LazyColumn(Modifier.fillMaxSize()) {
            if (chatHits.isNotEmpty()) {
                item { Section(stringResource(R.string.search_chats)) }
                items(chatHits, key = { "c" + it.id }) { m ->
                    Row(
                        Modifier.fillMaxWidth().clickable { onOpen(m.id, null) }.padding(horizontal = 18.dp, vertical = 9.dp),
                        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp),
                    ) {
                        UserAvatar(m.title, m.avatarHue, m.avatarPath, 46.dp)
                        Text(highlight(m.title, q, c.accent), style = Zura.type.label, color = c.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
            if (hits.isNotEmpty()) item { Section(stringResource(R.string.search_messages)) }
            items(hits, key = { "m" + it.second.id }) { (meta, m) ->
                Row(
                    Modifier.appear(0).fillMaxWidth().clickable { onOpen(meta.id, m.id) }.padding(horizontal = 18.dp, vertical = 9.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    UserAvatar(meta.title, meta.avatarHue, meta.avatarPath, 46.dp)
                    Column(Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(meta.title, style = Zura.type.label, color = c.text, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                            Text(fmt.time(m.sentAt) + " · " + fmt.daySeparator(ctx, m.sentAt), style = Zura.type.caption, color = c.textMuted)
                        }
                        Text(highlight(snippet(m.text.orEmpty(), q), q, c.accent), style = Zura.type.caption, color = c.textMuted, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
            if (q.length >= 2 && !searching && hits.isEmpty() && chatHits.isEmpty()) item {
                Text(stringResource(R.string.search_none), style = Zura.type.body, color = c.textMuted, modifier = Modifier.fillMaxWidth().padding(32.dp))
            }
        }
    }
}

@Composable
private fun Section(title: String) =
    Text(title, style = Zura.type.caption, color = Zura.colors.textMuted, modifier = Modifier.padding(start = 20.dp, top = 14.dp, bottom = 4.dp))

/** The part of a long message around the match. */
private fun snippet(text: String, q: String): String {
    val i = text.indexOf(q, ignoreCase = true)
    if (i < 40) return text
    return "…" + text.substring((i - 30).coerceAtLeast(0))
}

private fun highlight(text: String, q: String, color: androidx.compose.ui.graphics.Color) = buildAnnotatedString {
    var start = 0
    if (q.isEmpty()) { append(text); return@buildAnnotatedString }
    while (true) {
        val i = text.indexOf(q, start, ignoreCase = true)
        if (i < 0) { append(text.substring(start)); break }
        append(text.substring(start, i))
        withStyle(SpanStyle(color = color, fontWeight = FontWeight.Bold)) { append(text.substring(i, i + q.length)) }
        start = i + q.length
    }
}
