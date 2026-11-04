package app.zura.ui.theme

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Base64
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.ByteArrayOutputStream
import java.io.File

/**
 * A chat style designed in the Theme Studio: my bubble gradient, the other person's bubble,
 * bubble roundness, an optional imported font (messages only) and chat wallpaper.
 * (`dark` / `accent` are kept only so older shared files still parse.)
 * Shared as a small `.zuratheme` file (JSON; font and wallpaper embedded when present).
 */
@Serializable
data class CustomTheme(
    val name: String = "My theme",
    val dark: Boolean = true,
    val accent: Long = 0xFF8B7CFF,
    val mineA: Long = 0xFF6D5DF6,
    val mineB: Long = 0xFF4F8BFF,
    val theirs: Long = 0xFF1C1C26,
    /** Bubble corner radius in dp (4 = square-ish … 28 = pill). */
    val radius: Int = 20,
    /** 0–60: how strongly the wallpaper is dimmed behind messages, for readability. */
    val wallDim: Int = 35,
    /** Embedded only in exported files (base64). Locally the files live in the app's private storage. */
    val fontB64: String? = null,
    val wallB64: String? = null,
) {
    /**
     * A CHAT style: only the bubbles change (colours with readable text on them); the app's own
     * light / dark theme, accent and screens stay as they are.
     */
    fun applyTo(base: ZuraPalette): ZuraPalette {
        val a = Color(mineA)
        val t = Color(theirs)
        fun on(c: Color) = if (c.luminance() > 0.5f) Color(0xFF111114) else Color.White
        return base.copy(
            bubbleMine = a, bubbleMineAlt = Color(mineB), onBubbleMine = on(a),
            bubbleTheirs = t, onBubbleTheirs = on(t),
            read = if (on(a) == Color.White) Color(0xFFBDF4FF) else Color(0xFF0B4F6C),
        )
    }

    companion object {
        val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
        const val MIME = "application/vnd.zura.theme"
        const val EXT = ".zuratheme"

        fun fontFile(c: Context) = File(c.filesDir, "theme/font.ttf")
        fun wallFile(c: Context) = File(c.filesDir, "theme/wallpaper.jpg")

        fun fontFamily(c: Context): FontFamily? = fontFile(c).takeIf { it.exists() && it.length() > 0 }
            ?.let { f -> runCatching { FontFamily(Font(f)) }.getOrNull() }

        /** Shrinks a picked wallpaper to ≤ 1440 px and stores it as JPEG. */
        fun saveWallpaper(c: Context, bmp: Bitmap) {
            val scale = 1440f / maxOf(bmp.width, bmp.height)
            val b = if (scale < 1f) Bitmap.createScaledBitmap(bmp, (bmp.width * scale).toInt(), (bmp.height * scale).toInt(), true) else bmp
            wallFile(c).apply { parentFile?.mkdirs() }.outputStream().use { b.compress(Bitmap.CompressFormat.JPEG, 82, it) }
        }

        /** Theme file to send: font + wallpaper embedded (wallpaper re-compressed small). */
        fun export(c: Context, t: CustomTheme): ByteArray {
            val font = fontFile(c).takeIf { it.exists() && it.length() in 1..1_500_000 }?.readBytes()?.let { Base64.encodeToString(it, Base64.NO_WRAP) }
            val wall = wallFile(c).takeIf { it.exists() }?.let { f ->
                BitmapFactory.decodeFile(f.absolutePath)?.let { bmp ->
                    val s = 1080f / maxOf(bmp.width, bmp.height)
                    val small = if (s < 1f) Bitmap.createScaledBitmap(bmp, (bmp.width * s).toInt(), (bmp.height * s).toInt(), true) else bmp
                    Base64.encodeToString(ByteArrayOutputStream().also { small.compress(Bitmap.CompressFormat.JPEG, 70, it) }.toByteArray(), Base64.NO_WRAP)
                }
            }
            return json.encodeToString(serializer(), t.copy(fontB64 = font, wallB64 = wall)).toByteArray()
        }

        /** Applies a received theme file: stores its font / wallpaper locally, returns the theme. */
        fun import(c: Context, bytes: ByteArray): CustomTheme {
            val t = json.decodeFromString(serializer(), String(bytes))
            fontFile(c).apply { parentFile?.mkdirs() }.let { f -> t.fontB64?.let { f.writeBytes(Base64.decode(it, Base64.NO_WRAP)) } ?: f.delete() }
            wallFile(c).apply { parentFile?.mkdirs() }.let { f -> t.wallB64?.let { f.writeBytes(Base64.decode(it, Base64.NO_WRAP)) } ?: f.delete() }
            return t.copy(fontB64 = null, wallB64 = null)
        }

        fun argb(c: Color): Long = c.toArgb().toLong() and 0xFFFFFFFFL
    }
}
