package app.zura.media

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.Presentation
import androidx.media3.transformer.Composition
import androidx.media3.transformer.DefaultEncoderFactory
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.Effects
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.Transformer
import androidx.media3.transformer.VideoEncoderSettings
import app.zura.core.model.MediaRef
import app.zura.core.storage.SecureEraser
import app.zura.core.storage.VaultStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.math.roundToInt

/**
 * Video pipeline on the hardware encoder (Media3 Transformer → MediaCodec): short side 720 px,
 * H.264 @ 2 Mbps, AAC. A 60 s 4K clip (~400 MB) → ~15 MB in roughly real-time / 3 on modern SoCs,
 * with near-zero CPU. Plaintext output lives in no_backup/tmp only until encrypted, then erased.
 */
@OptIn(UnstableApi::class)
class VideoCompressor(private val context: Context, private val vault: VaultStore) {

    suspend fun import(chatId: String, uri: Uri, shortSide: Int = 720): MediaRef {
        val meta = probe(uri)
        val out = vault.paths.newTemp("mp4")
        try {
            val (w, h) = target(meta.width, meta.height, shortSide)
            transcode(uri, out.absolutePath, w, h)
            return withContext(Dispatchers.IO) {
                out.inputStream().buffered().use {
                    vault.importMedia(chatId, it, "video/mp4", w, h, meta.durationMs, preview = meta.preview)
                }
            }
        } finally {
            SecureEraser.erase(out)
        }
    }

    private suspend fun transcode(uri: Uri, path: String, w: Int, h: Int) = withContext(Dispatchers.Main) {
        suspendCancellableCoroutine { cont ->
            val transformer = Transformer.Builder(context)
                .setVideoMimeType(MimeTypes.VIDEO_H264)
                .setAudioMimeType(MimeTypes.AUDIO_AAC)
                .setEncoderFactory(
                    DefaultEncoderFactory.Builder(context)
                        .setRequestedVideoEncoderSettings(VideoEncoderSettings.Builder().setBitrate(2_000_000).build())
                        .build(),
                )
                .addListener(object : Transformer.Listener {
                    override fun onCompleted(composition: Composition, exportResult: ExportResult) = cont.resume(Unit)
                    override fun onError(composition: Composition, exportResult: ExportResult, exportException: ExportException) =
                        cont.resumeWithException(exportException)
                })
                .build()
            val item = EditedMediaItem.Builder(MediaItem.fromUri(uri))
                .setEffects(Effects(emptyList(), listOf(Presentation.createForWidthAndHeight(w, h, Presentation.LAYOUT_SCALE_TO_FIT))))
                .build()
            transformer.start(item, path)
            cont.invokeOnCancellation { transformer.cancel() }
        }
    }

    private data class Probe(val width: Int, val height: Int, val durationMs: Long, val preview: String?)

    private suspend fun probe(uri: Uri): Probe = withContext(Dispatchers.IO) {
        MediaMetadataRetriever().run {
            try {
                setDataSource(context, uri)
                val rot = extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
                var w = extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 1280
                var h = extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 720
                if (rot == 90 || rot == 270) { val t = w; w = h; h = t }
                val dur = extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0
                val preview = getFrameAtTime(0)?.let { Previews.encode(it, longEdge = 96, quality = 60).also { _ -> it.recycle() } }
                Probe(w, h, dur, preview)
            } finally {
                release()
            }
        }
    }

    private fun target(w: Int, h: Int, shortSide: Int): Pair<Int, Int> {
        val short = minOf(w, h)
        if (short <= shortSide) return even(w) to even(h)
        val s = shortSide.toFloat() / short
        return even((w * s).roundToInt()) to even((h * s).roundToInt())
    }

    private fun even(v: Int) = v and 1.inv()
}
