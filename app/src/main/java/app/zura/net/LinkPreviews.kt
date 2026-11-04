package app.zura.net

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Base64
import app.zura.core.model.LinkPreview
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.ByteArrayOutputStream
import java.util.concurrent.TimeUnit

/**
 * Builds a link card (Open Graph title / description / image) on the SENDER's phone. The card
 * then travels inside the end-to-end-encrypted message, so the recipient's phone never contacts
 * the linked site (no IP leak on their side, nothing for the server to see).
 */
object LinkPreviews {
    private val URL = Regex("""(?i)\b(https?://[^\s<>"']+|www\.[^\s<>"']+\.[a-z]{2,}[^\s<>"']*)""")

    private val http = OkHttpClient.Builder()
        .connectTimeout(4, TimeUnit.SECONDS).readTimeout(4, TimeUnit.SECONDS)
        .followRedirects(true).followSslRedirects(true)
        .build()

    /** All links in [text] as (range, url) — also used to make them tappable. */
    fun find(text: String): List<Pair<IntRange, String>> = URL.findAll(text).map { m ->
        val raw = m.value.trimEnd('.', ',', ')', ']', '!', '?', ';', ':', '»', '"')
        val range = m.range.first until m.range.first + raw.length
        range to (if (raw.startsWith("http", ignoreCase = true)) raw else "https://$raw")
    }.toList()

    suspend fun fetch(text: String): LinkPreview? {
        val url = find(text).firstOrNull()?.second ?: return null
        return withTimeoutOrNull(6_000) { withContext(Dispatchers.IO) { runCatching { load(url) }.getOrNull() } }
    }

    private fun load(url: String): LinkPreview? {
        val req = Request.Builder().url(url)
            .header("User-Agent", "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Mobile Safari/537.36 ZuraLinkPreview/1.0")
            .header("Accept", "text/html,application/xhtml+xml")
            .header("Accept-Language", java.util.Locale.getDefault().toLanguageTag() + ",en;q=0.8")
            .build()
        val (finalUrl, html) = http.newCall(req).execute().use { r ->
            if (!r.isSuccessful) return null
            val type = r.header("Content-Type").orEmpty()
            if (!type.contains("html", ignoreCase = true)) return null
            // Only the <head> matters: read at most 512 KB.
            val src = r.body ?: return null
            val bytes = src.byteStream().use { it.readNBytesCompat(512 * 1024) }
            r.request.url.toString() to String(bytes, Charsets.UTF_8)
        }
        fun meta(vararg keys: String): String? = keys.firstNotNullOfOrNull { k ->
            Regex("""<meta[^>]+(?:property|name)\s*=\s*["']${Regex.escape(k)}["'][^>]*>""", RegexOption.IGNORE_CASE).find(html)?.value
                ?.let { tag -> Regex("""content\s*=\s*["']([^"']*)["']""", RegexOption.IGNORE_CASE).find(tag)?.groupValues?.get(1) }
                ?.let(::unescape)?.trim()?.takeIf { it.isNotEmpty() }
        }
        val title = meta("og:title", "twitter:title")
            ?: Regex("""<title[^>]*>([^<]{1,300})</title>""", RegexOption.IGNORE_CASE).find(html)?.groupValues?.get(1)?.let(::unescape)?.trim()
        val desc = meta("og:description", "twitter:description", "description")
        val host = finalUrl.toHttpUrlOrNull()?.host?.removePrefix("www.") ?: url.toHttpUrlOrNull()?.host?.removePrefix("www.")
        val site = meta("og:site_name") ?: host
        val imageUrl = meta("og:image", "og:image:url", "twitter:image")?.let { img -> finalUrl.toHttpUrlOrNull()?.resolve(img)?.toString() }
        if (title == null && imageUrl == null) return null
        return LinkPreview(
            url = url, title = title?.take(200), description = desc?.take(300), site = site,
            image = imageUrl?.let { runCatching { thumbnail(it) }.getOrNull() },
        )
    }

    /** Downloads the og:image and shrinks it to a small JPEG (≈10–25 KB) for the card. */
    private fun thumbnail(url: String): String? {
        val bytes = http.newCall(Request.Builder().url(url).build()).execute().use { r ->
            if (!r.isSuccessful) return null
            r.body?.byteStream()?.use { it.readNBytesCompat(4 * 1024 * 1024) } ?: return null
        }
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, o)
        if (o.outWidth <= 0) return null
        var s = 1
        while (maxOf(o.outWidth, o.outHeight) / (s * 2) >= 480) s *= 2
        val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = s }) ?: return null
        val scale = 480f / maxOf(bmp.width, bmp.height)
        val small = if (scale < 1f) Bitmap.createScaledBitmap(bmp, (bmp.width * scale).toInt(), (bmp.height * scale).toInt(), true) else bmp
        var q = 70
        var out: ByteArray
        do {
            out = ByteArrayOutputStream().also { small.compress(Bitmap.CompressFormat.JPEG, q, it) }.toByteArray()
            q -= 15
        } while (out.size > 30_000 && q > 25)
        return Base64.encodeToString(out, Base64.NO_WRAP)
    }

    private fun java.io.InputStream.readNBytesCompat(max: Int): ByteArray {
        val out = ByteArrayOutputStream()
        val buf = ByteArray(16 * 1024)
        while (out.size() < max) {
            val n = read(buf, 0, minOf(buf.size, max - out.size()))
            if (n < 0) break
            out.write(buf, 0, n)
        }
        return out.toByteArray()
    }

    private fun unescape(s: String) = s.replace("&amp;", "&").replace("&quot;", "\"").replace("&#39;", "'").replace("&#039;", "'")
        .replace("&lt;", "<").replace("&gt;", ">").replace("&nbsp;", " ")
        .replace(Regex("&#(\\d+);")) { m -> m.groupValues[1].toIntOrNull()?.let { String(Character.toChars(it)) } ?: m.value }
        .replace(Regex("&#x([0-9a-fA-F]+);")) { m -> m.groupValues[1].toIntOrNull(16)?.let { String(Character.toChars(it)) } ?: m.value }
}
