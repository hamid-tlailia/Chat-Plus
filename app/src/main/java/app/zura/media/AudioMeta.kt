package app.zura.media

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import java.io.File

/** Length of a music / audio file, read before it is encrypted into the vault (0 if unknown). */
object AudioMeta {
    fun durationMs(file: File): Long = read { setDataSource(file.absolutePath) }
    fun durationMs(ctx: Context, uri: Uri): Long = read { setDataSource(ctx, uri) }

    private fun read(open: MediaMetadataRetriever.() -> Unit): Long {
        val r = MediaMetadataRetriever()
        return try {
            r.open()
            r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
        } catch (_: Exception) { 0L } finally { runCatching { r.release() } }
    }
}
