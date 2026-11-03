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
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.sp

enum class ZuraThemeId { MIDNIGHT, OLED, VELVET_GOLD, LIGHT }

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
    val Midnight = ZuraPalette(
        isDark = true,
        bg = Color(0xFF0B0B10), bgAccent = Color(0xFF14121F),
        surface = Color(0xFF15151D), surfaceHigh = Color(0xFF1E1E29),
        glass = Color(0xB31A1A24), glassStroke = Color(0x22FFFFFF),
        accent = Color(0xFF8B7CFF), accentAlt = Color(0xFF5CC8FF), onAccent = Color.White,
        text = Color(0xFFF2F1F7), textMuted = Color(0xFF8E8C9E),
        bubbleMine = Color(0xFF6D5DF6), bubbleMineAlt = Color(0xFF4F8BFF), onBubbleMine = Color.White,
        bubbleTheirs = Color(0xFF1C1C26), onBubbleTheirs = Color(0xFFF2F1F7),
        divider = Color(0x14FFFFFF), danger = Color(0xFFFF5C7A), read = Color(0xFF7FE3FF),
    )
    /** True black: AMOLED pixels fully off → measurably lower power on dark UIs. */
    val Oled = ZuraPalette(
        isDark = true,
        bg = Color.Black, bgAccent = Color.Black,
        surface = Color(0xFF0A0A0A), surfaceHigh = Color(0xFF141414),
        glass = Color(0xCC0A0A0A), glassStroke = Color(0x1AFFFFFF),
        accent = Color(0xFFFFFFFF), accentAlt = Color(0xFFB0B0B0), onAccent = Color.Black,
        text = Color(0xFFEDEDED), textMuted = Color(0xFF777777),
        bubbleMine = Color(0xFF1F1F1F), bubbleMineAlt = Color(0xFF2A2A2A), onBubbleMine = Color.White,
        bubbleTheirs = Color(0xFF0E0E0E), onBubbleTheirs = Color(0xFFEDEDED),
        divider = Color(0x12FFFFFF), danger = Color(0xFFFF4D4D), read = Color(0xFFFFFFFF),
    )
    val VelvetGold = ZuraPalette(
        isDark = true,
        bg = Color(0xFF120D0F), bgAccent = Color(0xFF231419),
        surface = Color(0xFF1C1416), surfaceHigh = Color(0xFF281C1F),
        glass = Color(0xB3241A1D), glassStroke = Color(0x33E8C77E),
        accent = Color(0xFFE8C77E), accentAlt = Color(0xFFB8873B), onAccent = Color(0xFF1A1206),
        text = Color(0xFFF6EDE3), textMuted = Color(0xFFA48F86),
        bubbleMine = Color(0xFFC9A15A), bubbleMineAlt = Color(0xFF9C6F2C), onBubbleMine = Color(0xFF1A1206),
        bubbleTheirs = Color(0xFF2A1E21), onBubbleTheirs = Color(0xFFF6EDE3),
        divider = Color(0x1AE8C77E), danger = Color(0xFFFF6F61), read = Color(0xFF6B3E00),
    )
    val Light = ZuraPalette(
        isDark = false,
        bg = Color(0xFFFAFAFC), bgAccent = Color(0xFFF1F0F7),
        surface = Color(0xFFFFFFFF), surfaceHigh = Color(0xFFF3F3F7),
        glass = Color(0xCCFFFFFF), glassStroke = Color(0x14000000),
        accent = Color(0xFF111111), accentAlt = Color(0xFF555555), onAccent = Color.White,
        text = Color(0xFF111114), textMuted = Color(0xFF8A8A93),
        bubbleMine = Color(0xFF111114), bubbleMineAlt = Color(0xFF2B2B33), onBubbleMine = Color.White,
        bubbleTheirs = Color(0xFFF0F0F4), onBubbleTheirs = Color(0xFF111114),
        divider = Color(0x0F000000), danger = Color(0xFFE5484D), read = Color(0xFF7FE3FF),
    )

    fun of(id: ZuraThemeId) = when (id) {
        ZuraThemeId.MIDNIGHT -> Midnight
        ZuraThemeId.OLED -> Oled
        ZuraThemeId.VELVET_GOLD -> VelvetGold
        ZuraThemeId.LIGHT -> Light
    }
}

@Immutable
data class ZuraType(val title: TextStyle, val headline: TextStyle, val body: TextStyle, val label: TextStyle, val caption: TextStyle)

/**
 * System fonts only (Roboto / Noto Naskh Arabic): zero font bytes in the APK, already in page
 * cache at launch. Arabic gets +25% line height — Naskh ascenders/descenders clip otherwise.
 */
private fun typeFor(rtl: Boolean): ZuraType {
    val lh = if (rtl) 1.45f else 1.3f
    return ZuraType(
        title = TextStyle(fontSize = 28.sp, fontWeight = FontWeight.SemiBold, letterSpacing = if (rtl) 0.sp else (-0.6).sp, lineHeight = (28 * lh).sp),
        headline = TextStyle(fontSize = 17.sp, fontWeight = FontWeight.SemiBold, letterSpacing = if (rtl) 0.sp else (-0.2).sp, lineHeight = (17 * lh).sp),
        body = TextStyle(fontSize = 16.sp, fontWeight = FontWeight.Normal, lineHeight = (16 * lh).sp),
        label = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.Medium, lineHeight = (14 * lh).sp),
        caption = TextStyle(fontSize = 11.5.sp, fontWeight = FontWeight.Normal, letterSpacing = if (rtl) 0.sp else 0.2.sp, lineHeight = (11.5 * lh).sp),
    )
}

val LocalZura = staticCompositionLocalOf { ZuraPalettes.Midnight }
val LocalZuraType = staticCompositionLocalOf { typeFor(false) }

object Zura {
    val colors: ZuraPalette @Composable get() = LocalZura.current
    val type: ZuraType @Composable get() = LocalZuraType.current
}

/** Theme switch cross-fades every token over 450 ms — no activity recreate, no flash. */
@Composable
fun ZuraTheme(id: ZuraThemeId, content: @Composable () -> Unit) {
    val t = ZuraPalettes.of(id)
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
    CompositionLocalProvider(LocalZura provides p, LocalZuraType provides typeFor(rtl)) {
        MaterialTheme(colorScheme = scheme, content = content)
    }
}
