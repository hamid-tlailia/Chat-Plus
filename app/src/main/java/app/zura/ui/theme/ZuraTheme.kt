package app.zura.ui.theme

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.sp

/** Order = how they're offered: dark first, then light. */
enum class ZuraThemeId { MIDNIGHT, SAPPHIRE, EMERALD, VELVET_GOLD, OLED, LIGHT, PEARL }

/**
 * Message colours, chosen independently of the app theme: a gradient for my bubbles and a calm
 * tint for the other person's, each with a dark-mode and a light-mode variant so every combination
 * keeps readable contrast. THEME = use the theme's own bubble colours.
 */
enum class BubbleStyle(
    val mineA: Long, val mineB: Long, val onMine: Long,
    val theirsDark: Long, val theirsLight: Long,
) {
    THEME(0, 0, 0, 0, 0),
    OCEAN(0xFF0EA5E9, 0xFF2563EB, 0xFFFFFFFF, 0xFF13283A, 0xFFE4F1FB),
    LAVENDER(0xFFA78BFA, 0xFF7C3AED, 0xFFFFFFFF, 0xFF241B3A, 0xFFF0EBFE),
    ROSE(0xFFF43F5E, 0xFFDB2777, 0xFFFFFFFF, 0xFF341822, 0xFFFCE8EE),
    EMERALD(0xFF10B981, 0xFF047857, 0xFFFFFFFF, 0xFF12302A, 0xFFE3F6EE),
    SUNSET(0xFFFBBF24, 0xFFF97316, 0xFF1A1206, 0xFF33240F, 0xFFFDF0DC),
    GRAPHITE(0xFF52525B, 0xFF27272A, 0xFFFFFFFF, 0xFF18181B, 0xFFEDEDF0);

    fun applyTo(p: ZuraPalette): ZuraPalette = if (this == THEME) p else p.copy(
        bubbleMine = Color(mineA), bubbleMineAlt = Color(mineB), onBubbleMine = Color(onMine),
        bubbleTheirs = Color(if (p.isDark) theirsDark else theirsLight),
        onBubbleTheirs = if (p.isDark) Color(0xFFF2F1F7) else Color(0xFF111114),
        read = if (onMine == 0xFF1A1206) Color(0xFF6B3E00) else Color(0xFFBDF4FF),
    )
}

/** Every color the UI uses. No component reads Material colors directly. */
@Immutable
data class ZuraPalette(
    val isDark: Boolean,
    val bg: Color,
    val bgAccent: Color,         // second stop of the subtle background gradient
    val surface: Color,
    val surfaceHigh: Color,
    val glass: Color,            // translucent fill for floating chrome (composer, top bar, sheets)
    val glassStroke: Color,      // hairline highlight on glass edges
    val accent: Color,
    val accentAlt: Color,        // gradient partner for rings / my bubbles
    val onAccent: Color,
    val text: Color,
    val textMuted: Color,
    val bubbleMine: Color,
    val bubbleMineAlt: Color,
    val onBubbleMine: Color,
    val bubbleTheirs: Color,
    val onBubbleTheirs: Color,
    val divider: Color,
    val danger: Color,
    val read: Color,
)

object ZuraPalettes {
    private fun pal(
        dark: Boolean, bg: Long, bgAccent: Long, surface: Long, surfaceHigh: Long, glass: Long, glassStroke: Long,
        accent: Long, accentAlt: Long, onAccent: Long, text: Long, textMuted: Long,
        mine: Long, mineAlt: Long, onMine: Long, theirs: Long, onTheirs: Long, divider: Long, danger: Long, read: Long,
    ) = ZuraPalette(
        dark, Color(bg), Color(bgAccent), Color(surface), Color(surfaceHigh), Color(glass), Color(glassStroke),
        Color(accent), Color(accentAlt), Color(onAccent), Color(text), Color(textMuted),
        Color(mine), Color(mineAlt), Color(onMine), Color(theirs), Color(onTheirs), Color(divider), Color(danger), Color(read),
    )

    /** Ink-violet night with a lilac → sky accent. */
    val Midnight = pal(true, 0xFF09090F, 0xFF17132B, 0xFF13131D, 0xFF1D1C2B, 0xB8171624, 0x24FFFFFF,
        0xFF8F80FF, 0xFF6ACBFF, 0xFFFFFFFF, 0xFFF4F2FA, 0xFF918EA6,
        0xFF6E5FF5, 0xFF4B86FF, 0xFFFFFFFF, 0xFF1C1B29, 0xFFF4F2FA, 0x14FFFFFF, 0xFFFF5C7A, 0xFF8BE8FF)
    /** Deep navy with brushed silver — calm and formal. */
    val Sapphire = pal(true, 0xFF060A16, 0xFF0F1A36, 0xFF0D1426, 0xFF162038, 0xB80E1730, 0x2EC9D6F2,
        0xFFC9D6F2, 0xFF5B8CFF, 0xFF0A1226, 0xFFEFF3FB, 0xFF8B97B3,
        0xFF3D6BFF, 0xFF2343B8, 0xFFFFFFFF, 0xFF141E36, 0xFFEFF3FB, 0x17C9D6F2, 0xFFFF6B7F, 0xFFAEE4FF)
    /** Forest-dark emerald with gold leaf. */
    val Emerald = pal(true, 0xFF06110D, 0xFF0D2219, 0xFF0F1A15, 0xFF17261F, 0xB8102019, 0x33D9BA75,
        0xFFD9BA75, 0xFF2FAE83, 0xFF13100A, 0xFFEEF4EF, 0xFF8EA399,
        0xFF1E8A66, 0xFF0E5C45, 0xFFFFFFFF, 0xFF15241D, 0xFFEEF4EF, 0x1AD9BA75, 0xFFFF6F61, 0xFFB9F5DD)
    /** Burgundy velvet and champagne gold. */
    val VelvetGold = pal(true, 0xFF110B0D, 0xFF26141B, 0xFF1B1316, 0xFF271B1F, 0xB3221719, 0x3DE9CB86,
        0xFFE9CB86, 0xFFB88A3E, 0xFF1A1206, 0xFFF7EEE4, 0xFFA9938A,
        0xFFD1AA62, 0xFFA0712E, 0xFF1A1206, 0xFF2B1E22, 0xFFF7EEE4, 0x1FE9CB86, 0xFFFF6F61, 0xFF6B3E00)
    /** True black: AMOLED pixels fully off → measurably lower power on dark UIs. Platinum details. */
    val Oled = pal(true, 0xFF000000, 0xFF000000, 0xFF0A0A0A, 0xFF151515, 0xCC0A0A0A, 0x1FFFFFFF,
        0xFFF2F2F2, 0xFFA8A8A8, 0xFF000000, 0xFFEDEDED, 0xFF7A7A7A,
        0xFF262626, 0xFF161616, 0xFFFFFFFF, 0xFF0F0F0F, 0xFFEDEDED, 0x14FFFFFF, 0xFFFF4D4D, 0xFFFFFFFF)
    /** Porcelain white with ink. */
    val Light = pal(false, 0xFFFBFBFD, 0xFFEFEEF6, 0xFFFFFFFF, 0xFFF2F2F7, 0xD9FFFFFF, 0x1A000000,
        0xFF1A1A22, 0xFF4A4A58, 0xFFFFFFFF, 0xFF121216, 0xFF85858F,
        0xFF1A1A22, 0xFF3A3A48, 0xFFFFFFFF, 0xFFEEEEF3, 0xFF121216, 0x0F000000, 0xFFE5484D, 0xFF9FE6FF)
    /** Ivory and champagne: a warm, luxurious day theme. */
    val Pearl = pal(false, 0xFFFBF8F2, 0xFFF1E8D8, 0xFFFFFDF8, 0xFFF4EDE1, 0xD9FFFCF5, 0x26B08D57,
        0xFF9C7A3C, 0xFFCDAA6A, 0xFFFFFFFF, 0xFF2A2118, 0xFF8C7E6C,
        0xFFB89558, 0xFF8E6A2E, 0xFFFFFFFF, 0xFFF0E7D6, 0xFF2A2118, 0x14B08D57, 0xFFD9534F, 0xFFFFF1C9)

    fun of(id: ZuraThemeId) = when (id) {
        ZuraThemeId.MIDNIGHT -> Midnight
        ZuraThemeId.SAPPHIRE -> Sapphire
        ZuraThemeId.EMERALD -> Emerald
        ZuraThemeId.VELVET_GOLD -> VelvetGold
        ZuraThemeId.OLED -> Oled
        ZuraThemeId.LIGHT -> Light
        ZuraThemeId.PEARL -> Pearl
    }
}

/**
 * "Read" ticks: a vivid colour chosen against MY bubble, so read (✓✓ coloured) never looks like
 * delivered (✓✓ dimmed). Light bubbles → deep blue; blue bubbles → mint; purple / pink → cyan;
 * everything else → sky blue.
 */
fun readTickFor(mine: Color): Color {
    if (mine.luminance() > 0.42f) return Color(0xFF1D4ED8)
    val hsv = FloatArray(3)
    android.graphics.Color.colorToHSV(mine.toArgb(), hsv)
    val (h, sat) = hsv[0] to hsv[1]
    return when {
        sat < 0.18f -> Color(0xFF38BDF8)          // grey / black bubbles
        h in 185f..262f -> Color(0xFF6EFFC4)      // blue / indigo
        h in 262f..345f -> Color(0xFF5EEBFF)      // violet / pink
        else -> Color(0xFF38BDF8)                 // red, orange, green
    }
}

@Immutable
data class ZuraType(val title: TextStyle, val headline: TextStyle, val body: TextStyle, val label: TextStyle, val caption: TextStyle)

/**
 * System fonts only (Roboto / Noto Naskh Arabic): zero font bytes in the APK, already in page
 * cache at launch. Arabic gets +25% line height — Naskh ascenders/descenders clip otherwise.
 */
private fun typeFor(rtl: Boolean, font: androidx.compose.ui.text.font.FontFamily? = null): ZuraType {
    val lh = if (rtl) 1.45f else 1.3f
    return ZuraType(
        title = TextStyle(fontFamily = font, fontSize = 28.sp, fontWeight = FontWeight.SemiBold, letterSpacing = if (rtl) 0.sp else (-0.6).sp, lineHeight = (28 * lh).sp),
        headline = TextStyle(fontFamily = font, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, letterSpacing = if (rtl) 0.sp else (-0.2).sp, lineHeight = (17 * lh).sp),
        body = TextStyle(fontFamily = font, fontSize = 16.sp, fontWeight = FontWeight.Normal, lineHeight = (16 * lh).sp),
        label = TextStyle(fontFamily = font, fontSize = 14.sp, fontWeight = FontWeight.Medium, lineHeight = (14 * lh).sp),
        caption = TextStyle(fontFamily = font, fontSize = 11.5.sp, fontWeight = FontWeight.Normal, letterSpacing = if (rtl) 0.sp else 0.2.sp, lineHeight = (11.5 * lh).sp),
    )
}

val LocalZura = staticCompositionLocalOf { ZuraPalettes.Midnight }
val LocalZuraType = staticCompositionLocalOf { typeFor(false) }
/** Font for message text only (Theme Studio) — the rest of the app keeps the system font. */
val LocalChatFont = staticCompositionLocalOf<androidx.compose.ui.text.font.FontFamily?> { null }
/** Bubble corner radius (Theme Studio). */
val LocalBubbleRadius = staticCompositionLocalOf { 20 }
/** Chat wallpaper (Theme Studio) and how much it is dimmed (0–60 %). */
val LocalWallpaper = staticCompositionLocalOf<Pair<androidx.compose.ui.graphics.ImageBitmap, Int>?> { null }

object Zura {
    val colors: ZuraPalette @Composable get() = LocalZura.current
    val type: ZuraType @Composable get() = LocalZuraType.current
}

/** Theme switch cross-fades every token over 450 ms — no activity recreate, no flash. */
@Composable
fun ZuraTheme(
    id: ZuraThemeId,
    bubbles: BubbleStyle = BubbleStyle.THEME,
    custom: CustomTheme? = null,
    font: androidx.compose.ui.text.font.FontFamily? = null,
    wallpaper: androidx.compose.ui.graphics.ImageBitmap? = null,
    content: @Composable () -> Unit,
) {
    val base = ZuraPalettes.of(id)
    val t = custom?.applyTo(base) ?: bubbles.applyTo(base)
    @Composable fun a(c: Color) = animateColorAsState(c, tween(450), label = "theme").value
    val p = ZuraPalette(
        t.isDark, a(t.bg), a(t.bgAccent), a(t.surface), a(t.surfaceHigh), a(t.glass), a(t.glassStroke),
        a(t.accent), a(t.accentAlt), a(t.onAccent), a(t.text), a(t.textMuted), a(t.bubbleMine), a(t.bubbleMineAlt),
        a(t.onBubbleMine), a(t.bubbleTheirs), a(t.onBubbleTheirs), a(t.divider), a(t.danger), a(t.read),
    )
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    val scheme = if (t.isDark) {
        darkColorScheme(primary = p.accent, onPrimary = p.onAccent, background = p.bg, surface = p.surface, onSurface = p.text, onBackground = p.text, error = p.danger)
    } else {
        lightColorScheme(primary = p.accent, onPrimary = p.onAccent, background = p.bg, surface = p.surface, onSurface = p.text, onBackground = p.text, error = p.danger)
    }
    CompositionLocalProvider(
        LocalZura provides p, LocalZuraType provides typeFor(rtl),
        LocalChatFont provides if (custom != null) font else null,
        LocalBubbleRadius provides (custom?.radius ?: 20),
        LocalWallpaper provides if (custom != null && wallpaper != null) wallpaper to custom.wallDim else null,
    ) {
        MaterialTheme(colorScheme = scheme, content = content)
    }
}
