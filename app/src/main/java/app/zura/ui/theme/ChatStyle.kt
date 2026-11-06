package app.zura.ui.theme

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.zura.data.SettingsStore
import app.zura.graph
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** The look chosen for one chat (its profile → "Chat colours"). */
fun styleOf(settings: SettingsStore, chatId: String): String = settings.chatStyles.value[chatId] ?: settings.defaultChatStyle

/**
 * One chat's own look on top of the app theme: its bubble colours — or the Theme Studio design
 * (colours, roundness, font, wallpaper). The app theme keeps the rest (background, text, cards).
 * Bubble colours glide to the new ones when the style changes.
 */
@Composable
fun ChatStyle(chatId: String, content: @Composable () -> Unit) {
    val ctx = LocalContext.current
    val g = ctx.graph
    val styles by g.settings.chatStyles.collectAsStateWithLifecycle()
    val custom by g.settings.customTheme.collectAsStateWithLifecycle()
    val files by g.settings.themeFiles.collectAsStateWithLifecycle()
    val name = styles[chatId] ?: g.settings.defaultChatStyle
    val active = custom.takeIf { name == SettingsStore.STYLE_CUSTOM }
    val bubbles = runCatching { BubbleStyle.valueOf(name) }.getOrDefault(BubbleStyle.THEME)
    val font = remember(active != null, files) { if (active != null) CustomTheme.fontFamily(ctx) else null }
    val wall by produceState(Walls.cached(files).takeIf { active != null }, active != null, files) {
        value = if (active == null) null else Walls.load(ctx, files)
    }
    val base = LocalZura.current
    val t = active?.applyTo(base) ?: bubbles.applyTo(base)
    @Composable fun a(c: Color) = animateColorAsState(c, tween(350), label = "bubbles").value
    val p = t.copy(
        bubbleMine = a(t.bubbleMine), bubbleMineAlt = a(t.bubbleMineAlt), onBubbleMine = a(t.onBubbleMine),
        bubbleTheirs = a(t.bubbleTheirs), onBubbleTheirs = a(t.onBubbleTheirs), read = a(t.read),
    )
    CompositionLocalProvider(
        LocalZura provides p,
        LocalChatFont provides font,
        LocalBubbleRadius provides (active?.radius ?: 20),
        LocalWallpaper provides (if (active != null && wall != null) wall!! to active.wallDim else null),
        content = content,
    )
}

/** The Studio wallpaper, decoded once per change (not on every chat open). */
private object Walls {
    @Volatile private var cache: Pair<Int, ImageBitmap?>? = null
    fun cached(version: Int) = cache?.takeIf { it.first == version }?.second
    suspend fun load(ctx: android.content.Context, version: Int): ImageBitmap? {
        cached(version)?.let { return it }
        val bmp = withContext(Dispatchers.IO) {
            CustomTheme.wallFile(ctx).takeIf { it.exists() }?.let { android.graphics.BitmapFactory.decodeFile(it.absolutePath)?.asImageBitmap() }
        }
        cache = version to bmp
        return bmp
    }
}
