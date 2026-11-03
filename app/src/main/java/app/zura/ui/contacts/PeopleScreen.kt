package app.zura.ui.contacts

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.zura.R
import app.zura.account.Contact
import app.zura.account.Invite
import app.zura.account.SearchHit
import app.zura.data.ContactsRepository
import app.zura.graph
import app.zura.ui.components.UserAvatar
import app.zura.ui.components.glass
import app.zura.ui.components.rememberHaptics
import app.zura.ui.theme.Zura
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * People hub: find users by @username and invite them; accept / decline received invitations;
 * cancel sent ones; manage contacts. Chatting is only possible with accepted contacts.
 */
@Composable
fun PeopleScreen(onBack: () -> Unit, onOpenChat: (String) -> Unit, startOnInvites: Boolean = false) {
    val g = LocalContext.current.graph
    val repo = g.s.contacts
    val c = Zura.colors
    val contacts by repo.contacts.collectAsStateWithLifecycle()
    val invites by repo.invites.collectAsStateWithLifecycle()
    val incoming = invites.count { it.incoming }
    var tab by remember { mutableIntStateOf(if (startOnInvites || incoming > 0) 1 else 0) }
    var query by remember { mutableStateOf("") }
    var results by remember { mutableStateOf<List<SearchHit>?>(null) }
    var searching by remember { mutableStateOf(false) }
    var searchError by remember { mutableStateOf<String?>(null) }
    val me by repo.myProfile.collectAsStateWithLifecycle()
    val ctx = LocalContext.current
    var inviteTarget by remember { mutableStateOf<SearchHit?>(null) }
    val scope = rememberCoroutineScope()
    val haptics = rememberHaptics()

    LaunchedEffect(query, contacts.size, invites.size) {
        val q = query.trim().removePrefix("@")
        if (q.length < 2) { results = null; searchError = null; return@LaunchedEffect }
        delay(300)
        searching = true
        runCatching { repo.search(q) }
            .onSuccess { results = it; searchError = null }
            .onFailure { results = emptyList(); searchError = it.message }
        searching = false
    }

    Column(Modifier.fillMaxSize().statusBarsPadding()) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(8.dp)) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, null, tint = c.text) }
            Text(stringResource(R.string.contacts), style = Zura.type.title, color = c.text)
        }

        // My handle — what friends type to find me — with a one-tap share.
        me?.let { p ->
            Row(
                Modifier.padding(horizontal = 16.dp).padding(bottom = 10.dp).fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp)).background(c.accent.copy(alpha = 0.10f))
                    .clickable {
                        val text = ctx.getString(R.string.share_my_username, p.username)
                        ctx.startActivity(android.content.Intent.createChooser(
                            android.content.Intent(android.content.Intent.ACTION_SEND).setType("text/plain").putExtra(android.content.Intent.EXTRA_TEXT, text), null))
                    }
                    .padding(horizontal = 14.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.my_username), style = Zura.type.caption, color = c.textMuted)
                    Text(app.zura.i18n.handle(p.username), style = Zura.type.headline, color = c.text)
                }
                Icon(Icons.Default.Share, stringResource(R.string.share), tint = c.accent)
            }
        }

        // Search
        Row(
            Modifier.padding(horizontal = 16.dp).fillMaxWidth().glass(RoundedCornerShape(18.dp), solid = 0.5f).padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Default.Search, null, tint = c.textMuted)
            Spacer(Modifier.width(10.dp))
            Box(Modifier.weight(1f)) {
                if (query.isEmpty()) Text(stringResource(R.string.search_people), style = Zura.type.body, color = c.textMuted)
                BasicTextField(query, { query = it.take(25) }, singleLine = true, textStyle = Zura.type.body.copy(color = c.text), cursorBrush = SolidColor(c.accent), modifier = Modifier.fillMaxWidth())
            }
            if (searching) CircularProgressIndicator(Modifier.size(18.dp), color = c.accent, strokeWidth = 2.dp)
        }

        val r = results
        if (r != null) {
            LazyColumn(contentPadding = PaddingValues(vertical = 8.dp)) {
                if (r.isEmpty()) item { Empty(searchError?.let { stringResource(R.string.search_failed) } ?: stringResource(R.string.no_results_hint)) }
                items(r, key = { it.id }) { hit ->
                    PersonRow(hit.displayName, app.zura.i18n.handle(hit.username), hit.avatarPath, hit.id) {
                        when (hit.relation) {
                            "contact" -> Pill(stringResource(R.string.open_chat), filled = true) { onOpenChat(ContactsRepository.dmChatId(repo.me, hit.id)) }
                            "pending_out" -> Pill(stringResource(R.string.invited), filled = false) {}
                            "pending_in" -> Pill(stringResource(R.string.accept), filled = true) {
                                invites.firstOrNull { it.incoming && it.profile.id == hit.id }?.let { inv -> scope.launch { haptics.confirm(); repo.respond(inv.id, true) } }
                            }
                            else -> Pill(stringResource(R.string.invite), filled = true) { inviteTarget = hit }
                        }
                    }
                }
            }
            return@Column
        }

        // Tabs
        Row(Modifier.padding(16.dp).fillMaxWidth().clip(CircleShape).background(c.surfaceHigh).padding(4.dp)) {
            listOf(stringResource(R.string.contacts) to 0, stringResource(R.string.invitations) to incoming).forEachIndexed { i, (label, badge) ->
                Row(
                    Modifier.weight(1f).clip(CircleShape).background(if (tab == i) c.accent else Color.Transparent).clickable { haptics.tick(); tab = i }.padding(vertical = 10.dp),
                    horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(label, style = Zura.type.label, color = if (tab == i) c.onAccent else c.text)
                    if (badge > 0) {
                        Spacer(Modifier.width(6.dp))
                        Box(Modifier.size(20.dp).clip(CircleShape).background(if (tab == i) c.onAccent else c.danger), contentAlignment = Alignment.Center) {
                            Text("$badge", style = Zura.type.caption, color = if (tab == i) c.accent else Color.White)
                        }
                    }
                }
            }
        }

        if (tab == 0) ContactsList(contacts, onOpenChat)
        else InvitesList(invites)
    }

    inviteTarget?.let { hit ->
        var note by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { inviteTarget = null },
            containerColor = c.surfaceHigh,
            icon = { UserAvatar(hit.displayName, (hit.id.hashCode() and 0xFFFF) % 360f, hit.avatarPath, 64.dp) },
            title = { Text(hit.displayName, color = c.text) },
            text = {
                OutlinedTextField(
                    note, { note = it.take(280) }, label = { Text(stringResource(R.string.invite_note_hint)) }, maxLines = 3,
                    colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = c.accent, focusedTextColor = c.text, unfocusedTextColor = c.text, focusedLabelColor = c.accent),
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    inviteTarget = null; haptics.confirm()
                    scope.launch { repo.sendInvite(hit.id, note.ifBlank { null }); results = runCatching { repo.search(query.trim()) }.getOrNull() }
                }) { Text(stringResource(R.string.send_invite), color = c.accent) }
            },
            dismissButton = { TextButton(onClick = { inviteTarget = null }) { Text(stringResource(R.string.cancel), color = c.text) } },
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ContactsList(contacts: List<Contact>, onOpenChat: (String) -> Unit) {
    val g = LocalContext.current.graph
    val repo = g.s.contacts
    val c = Zura.colors
    val scope = rememberCoroutineScope()
    var menu by remember { mutableStateOf<Contact?>(null) }
    LazyColumn(contentPadding = PaddingValues(bottom = 24.dp)) {
        if (contacts.isEmpty()) item { Empty(stringResource(R.string.start_chat_hint)) }
        items(contacts, key = { it.profile.id }) { ct ->
            Box {
                PersonRow(
                    ct.profile.displayName, app.zura.i18n.handle(ct.profile.username), ct.profile.avatarPath, ct.profile.id,
                    modifier = Modifier.combinedClickable(onClick = { onOpenChat(ContactsRepository.dmChatId(repo.me, ct.profile.id)) }, onLongClick = { menu = ct }),
                ) { if (ct.blocked) Text(stringResource(R.string.blocked), style = Zura.type.caption, color = c.danger) }
                DropdownMenu(menu == ct, { menu = null }, containerColor = c.surfaceHigh) {
                    DropdownMenuItem(
                        text = { Text(stringResource(if (ct.blocked) R.string.unblock else R.string.block), color = c.text) },
                        onClick = { menu = null; scope.launch { repo.setBlocked(ct.profile.id, !ct.blocked) } },
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.remove_contact), color = c.danger) },
                        onClick = { menu = null; scope.launch { repo.remove(ct.profile.id) } },
                    )
                }
            }
        }
    }
}

@Composable
private fun InvitesList(invites: List<Invite>) {
    val g = LocalContext.current.graph
    val repo = g.s.contacts
    val c = Zura.colors
    val scope = rememberCoroutineScope()
    val haptics = rememberHaptics()
    val received = invites.filter { it.incoming }
    val sent = invites.filterNot { it.incoming }
    LazyColumn(contentPadding = PaddingValues(bottom = 24.dp)) {
        if (invites.isEmpty()) item { Empty(stringResource(R.string.no_invites)) }
        if (received.isNotEmpty()) item { Header(stringResource(R.string.received)) }
        items(received, key = { it.id }) { inv ->
            Column(Modifier.animateItem()) {
                PersonRow(inv.profile.displayName, app.zura.i18n.handle(inv.profile.username), inv.profile.avatarPath, inv.profile.id) {}
                inv.message?.let {
                    Text("“$it”", style = Zura.type.label, color = c.textMuted, modifier = Modifier.padding(start = 86.dp, end = 20.dp, bottom = 6.dp))
                }
                Row(Modifier.padding(start = 86.dp, end = 20.dp, bottom = 12.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Pill(stringResource(R.string.accept), filled = true, modifier = Modifier.weight(1f)) { haptics.confirm(); scope.launch { repo.respond(inv.id, true) } }
                    Pill(stringResource(R.string.reject), filled = false, modifier = Modifier.weight(1f)) { haptics.reject(); scope.launch { repo.respond(inv.id, false) } }
                }
            }
        }
        if (sent.isNotEmpty()) item { Header(stringResource(R.string.sent)) }
        items(sent, key = { it.id }) { inv ->
            PersonRow(inv.profile.displayName, app.zura.i18n.handle(inv.profile.username), inv.profile.avatarPath, inv.profile.id, modifier = Modifier.animateItem()) {
                Pill(stringResource(R.string.cancel_invite), filled = false) { scope.launch { repo.cancel(inv.id) } }
            }
        }
    }
}

@Composable
private fun PersonRow(name: String, sub: String, avatarPath: String?, id: String, modifier: Modifier = Modifier, trailing: @Composable () -> Unit) {
    val c = Zura.colors
    Row(modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        UserAvatar(name, (id.hashCode() and 0xFFFF) % 360f, avatarPath, 52.dp)
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(name, style = Zura.type.headline, color = c.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(sub, style = Zura.type.label, color = c.textMuted, maxLines = 1)
        }
        trailing()
    }
}

@Composable
private fun Pill(text: String, filled: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val c = Zura.colors
    Box(
        modifier.clip(CircleShape).background(if (filled) c.accent else c.surfaceHigh).clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 9.dp),
        contentAlignment = Alignment.Center,
    ) { Text(text, style = Zura.type.label, color = if (filled) c.onAccent else c.text) }
}

@Composable
private fun Header(text: String) =
    Text(text, style = Zura.type.caption, color = Zura.colors.textMuted, modifier = Modifier.padding(start = 20.dp, top = 12.dp, bottom = 4.dp))

@Composable
private fun Empty(text: String) {
    Box(Modifier.fillMaxWidth().height(160.dp).padding(32.dp), contentAlignment = Alignment.Center) {
        Text(text, style = Zura.type.label, color = Zura.colors.textMuted)
    }
}

