package app.zura.ui.chat

import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.zura.R
import app.zura.core.model.Message
import app.zura.core.model.MsgKind
import app.zura.ui.ZuraIcons
import app.zura.ui.components.Haptics
import app.zura.ui.components.ZuraMotion
import app.zura.ui.components.glass
import app.zura.ui.theme.Zura
import kotlinx.coroutines.delay

val QuickReactions = listOf("❤️", "😂", "😮", "😢", "🙏", "👍")

/**
 * Long-press overlay: dimmed backdrop, staggered spring-in reaction bar, glass action card.
 * Only actions valid for the message are shown (edit = own text; download = media; delete for
 * everyone = own messages).
 */
@Composable
fun ActionOverlay(
    m: Message,
    mine: Boolean,
    me: String,
    haptics: Haptics,
    canReply: Boolean = true,
    onTranslate: (() -> Unit)? = null,
    onDismiss: () -> Unit,
    onReact: (String) -> Unit,
    onReply: () -> Unit,
    onEdit: () -> Unit,
    onCopy: () -> Unit,
    onDownload: () -> Unit,
    onDeleteMe: () -> Unit,
    onDeleteAll: () -> Unit,
) {
    val c = Zura.colors
    val enter = remember { Animatable(0f) }
    LaunchedEffect(Unit) { enter.animateTo(1f, ZuraMotion.Snappy) }
    Box(
        Modifier.fillMaxSize().graphicsLayer { alpha = enter.value }.background(Color.Black.copy(alpha = 0.72f)).clickable(onClick = onDismiss),
        contentAlignment = Alignment.BottomCenter,
    ) {
        Column(
            Modifier.navigationBarsPadding().padding(16.dp).graphicsLayer { translationY = (1f - enter.value) * 80f },
            horizontalAlignment = if (mine) Alignment.End else Alignment.Start,
        ) {
            // The focused message, lifted out of the list
            FocusedPreview(m, mine)
            Spacer(Modifier.height(12.dp))
            // Reaction bar
            Row(
                Modifier.glass(CircleShape, solid = 0.94f).padding(horizontal = 10.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                QuickReactions.forEachIndexed { i, e ->
                    val s = remember { Animatable(0f) }
                    LaunchedEffect(Unit) { delay(i * 28L); s.animateTo(1f, ZuraMotion.Bouncy) }
                    val selected = m.reactions[me] == e
                    Box(
                        Modifier.size(44.dp).graphicsLayer { scaleX = s.value; scaleY = s.value }.clip(CircleShape)
                            .background(if (selected) c.accent.copy(alpha = 0.25f) else Color.Transparent)
                            .clickable { haptics.tick(); onReact(e) },
                        contentAlignment = Alignment.Center,
                    ) { Text(e, fontSize = 26.sp) }
                }
            }
            Spacer(Modifier.height(10.dp))
            Column(Modifier.width(250.dp).glass(RoundedCornerShape(22.dp), solid = 0.94f).padding(vertical = 6.dp)) {
                if (canReply) Action(ZuraIcons.Reply, R.string.action_reply, c.text, onReply)
                if (mine && m.kind == MsgKind.TEXT) Action(Icons.Default.Edit, R.string.action_edit, c.text, onEdit)
                if (!m.text.isNullOrEmpty()) Action(ZuraIcons.Copy, R.string.action_copy, c.text, onCopy)
                if (!m.text.isNullOrEmpty() && m.kind != MsgKind.EVENT && m.kind != MsgKind.CALL && onTranslate != null) {
                    Action(ZuraIcons.Translate, R.string.action_translate, c.text, onTranslate)
                }
                if (m.media != null) Action(ZuraIcons.Download, R.string.action_download, c.text, onDownload)
                Box(Modifier.fillMaxWidth().padding(vertical = 4.dp).height(1.dp).background(c.divider))
                // One entry; the confirmation sheet offers "for everyone" / "for me".
                Action(Icons.Default.Delete, R.string.delete, c.danger) { haptics.tick(); onDeleteMe() }
            }
        }
    }
}

@Composable
private fun FocusedPreview(m: Message, mine: Boolean) {
    val c = Zura.colors
    val label = m.text ?: androidx.compose.ui.res.stringResource(kindLabel(m.kind))
    Text(
        label,
        style = Zura.type.body.copy(textDirection = androidx.compose.ui.text.style.TextDirection.Content),
        color = if (mine) c.onBubbleMine else c.onBubbleTheirs,
        maxLines = 4,
        modifier = Modifier.widthIn(max = 300.dp).clip(RoundedCornerShape(20.dp))
            .background(if (mine) androidx.compose.ui.graphics.Brush.linearGradient(listOf(c.bubbleMine, c.bubbleMineAlt)) else androidx.compose.ui.graphics.Brush.linearGradient(listOf(c.bubbleTheirs, c.bubbleTheirs)))
            .padding(horizontal = 16.dp, vertical = 12.dp),
    )
}

@Composable
private fun Action(icon: ImageVector, label: Int, tint: Color, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 18.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(stringResource(label), style = Zura.type.label, color = tint, modifier = Modifier.weight(1f))
        Icon(icon, null, tint = tint, modifier = Modifier.size(20.dp))
    }
}
