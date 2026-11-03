package app.zura.media

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.ByteArrayOutputStream
import java.util.Base64
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Inline micro-previews (~0.5–3 KB) stored inside the message record itself. The chat list and
 * bubbles paint them on the very first frame — no disk I/O, no decryption, no layout jump —
 * while the full-resolution image decodes in the background.
 */
object Previews {
    fun encode(src: Bitmap, longEdge: Int = 32, quality: Int = 55): String {
        val scale = longEdge.toFloat() / max(src.width, src.height)
        val small = Bitmap.createScaledBitmap(src, (src.width * scale).roundToInt().coerceAtLeast(1), (src.height * scale).roundToInt().coerceAtLeast(1), true)
        val out = ByteArrayOutputStream()
        small.compress(Bitmap.CompressFormat.JPEG, quality, out)
        if (small !== src) small.recycle()
        return Base64.getEncoder().encodeToString(out.toByteArray())
    }

    fun decode(b64: String): Bitmap? = runCatching {
        val bytes = Base64.getDecoder().decode(b64)
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
    }.getOrNull()

    /** 48 amplitude buckets (0–255) → Base64. */
    fun encodeWaveform(samples: List<Float>, buckets: Int = 48): String {
        if (samples.isEmpty()) return Base64.getEncoder().encodeToString(ByteArray(buckets))
        val out = ByteArray(buckets) { i ->
            val from = i * samples.size / buckets
            val to = maxOf(from + 1, (i + 1) * samples.size / buckets)
            val peak = (from until minOf(to, samples.size)).maxOf { samples[it] }
            (peak.coerceIn(0f, 1f) * 255).roundToInt().toByte()
        }
        return Base64.getEncoder().encodeToString(out)
    }

    fun decodeWaveform(b64: String?): FloatArray =
        b64?.let { runCatching { Base64.getDecoder().decode(it) }.getOrNull() }
            ?.let { bytes -> FloatArray(bytes.size) { (bytes[it].toInt() and 0xFF) / 255f } }
            ?: FloatArray(48) { 0.15f }
}
