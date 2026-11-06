package app.zura.ui.moments

import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.getValue
import app.zura.ui.components.appear
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.Image
import app.zura.R
import app.zura.data.MomentGroup
import app.zura.data.MomentsRepository
import app.zura.graph
import app.zura.media.rememberVaultImage
import androidx.compose.ui.platform.LocalContext
import app.zura.media.Previews
import app.zura.ui.components.Avatar
import app.zura.ui.components.pressScale
import app.zura.ui.theme.Zura

/** Horizontal story rings. Unseen = animated-gradient ring, seen = muted hairline. */
@Composable
fun MomentsRow(groups: List<MomentGroup>, myName: String, onAdd: () -> Unit, onOpen: (String) -> Unit) {
    val mine = groups.firstOrNull { it.mine }
    val graph = LocalContext.current.graph
    val me by graph.s.contacts.myProfile.collectAsStateWithLifecycle()
    LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        item(key = "me") {
            RingItem(
                label = stringResource(R.string.your_moment), group = mine, name = me?.displayName ?: myName, hue = (graph.s.uid.hashCode() and 0xFFFF) % 360f,
                badge = true, onClick = { if (mine != null) onOpen(mine.ownerId) else onAdd() }, onBadge = onAdd,
                avatarPath = me?.avatarPath,
            )
        }
        items(groups.filterNot { it.mine }, key = { it.ownerId }) { g ->
            // Stories slide in one after another when the list first shows.
            androidx.compose.foundation.layout.Box(Modifier.appear(groups.indexOf(g))) { RingItem(g.name, g, g.name, g.hue, badge = false, onClick = { onOpen(g.ownerId) }, onBadge = {}, avatarPath = graph.s.contacts.profile(g.ownerId)?.avatarPath) }
        }
    }
}

@Composable
private fun RingItem(label: String, group: MomentGroup?, name: String, hue: Float, badge: Boolean, onClick: () -> Unit, onBadge: () -> Unit, avatarPath: String? = null) {
    val src = remember { MutableInteractionSource() }
    val c = Zura.colors
    Column(
        Modifier.width(78.dp).pressScale(src).clickable(src, null, onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box {
            // The person's profile photo inside the ring (monogram if they have none).
            val g = LocalContext.current.graph
            val photo = app.zura.account.rememberAvatar(g.avatars, avatarPath).value
            Avatar(name, hue, 66.dp, ring = group != null, ringSeen = group?.allSeen == true, image = photo)
            if (badge) {
                Box(
                    Modifier.align(Alignment.BottomEnd).size(22.dp).clip(CircleShape).background(c.accent)
                        .border(2.dp, c.bg, CircleShape).clickable(onClick = onBadge),
                    contentAlignment = Alignment.Center,
                ) { Icon(Icons.Default.Add, null, tint = c.onAccent, modifier = Modifier.size(14.dp)) }
            }
        }
        Text(
            label, style = Zura.type.caption, color = if (group?.allSeen == false) c.text else c.textMuted,
            maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 6.dp),
        )
    }
}
