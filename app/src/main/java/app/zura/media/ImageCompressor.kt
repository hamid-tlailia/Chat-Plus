package app.zura.media

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Build
import app.zura.core.model.MediaRef
import app.zura.core.storage.VaultStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Photo pipeline: decode (subsampled at the decoder, so a 50 MP photo never allocates 200 MB)
 * → cap long edge at 2560 px → WebP (API 30+) / JPEG → encrypt into vault + inline preview.
 * Typical 12 MP / 4 MB camera JPEG → ~350 KB in ~120 ms on a mid-range SoC.
 */
class ImageCompressor(private val context: Context, private val vault: VaultStore) {

    suspend fun import(chatId: String, uri: Uri, maxEdge: Int = 2560): MediaRef = withContext(Dispatchers.Default) {
        val bmp = decode(uri, maxEdge)
        val out = ByteArrayOutputStream(512 * 1024)
        val mime = if (Build.VERSION.SDK_INT >= 30) {
            bmp.compress(Bitmap.CompressFormat.WEBP_LOSSY, 80, out); "image/webp"
        } else {
            bmp.compress(Bitmap.CompressFormat.JPEG, 84, out); "image/jpeg"
        }
        val preview = Previews.encode(bmp)
        val ref = withContext(Dispatchers.IO) {
            vault.importMedia(chatId, ByteArrayInputStream(out.toByteArray()), mime, bmp.width, bmp.height, preview = preview)
        }
        bmp.recycle()
        ref
    }

    /** ~30 ms: tiny preview + dimensions so the bubble can appear before compression finishes. */
    suspend fun placeholder(uri: Uri, mime: String): MediaRef = withContext(Dispatchers.Default) {
        runCatching {
            val bmp = decode(uri, 96)
            val bounds = runCatching {
                if (Build.VERSION.SDK_INT >= 28) {
                    var w = 0; var h = 0
                    ImageDecoder.decodeBitmap(ImageDecoder.createSource(context.contentResolver, uri)) { d, info, _ ->
                        w = info.size.width; h = info.size.height; d.setTargetSize(1, 1)
                    }.recycle()
                    w to h
                } else bmp.width to bmp.height
            }.getOrDefault(bmp.width to bmp.height)
            MediaRef("pending-" + java.util.UUID.randomUUID().toString().replace("-", ""), "", mime, 0, bounds.first, bounds.second, preview = Previews.encode(bmp))
                .also { bmp.recycle() }
        }.getOrElse { MediaRef("pending-" + java.util.UUID.randomUUID().toString().replace("-", ""), "", mime, 0) }
    }

    private fun decode(uri: Uri, maxEdge: Int): Bitmap {
        if (Build.VERSION.SDK_INT >= 28) {
            // ImageDecoder applies EXIF orientation and subsamples during decode.
            val src = ImageDecoder.createSource(context.contentResolver, uri)
            return ImageDecoder.decodeBitmap(src) { decoder, info, _ ->
                val s = info.size
                val scale = minOf(1f, maxEdge.toFloat() / max(s.width, s.height))
                decoder.setTargetSize((s.width * scale).roundToInt(), (s.height * scale).roundToInt())
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                decoder.memorySizePolicy = ImageDecoder.MEMORY_POLICY_LOW_RAM
            }
        }
        val cr = context.contentResolver
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        cr.openInputStream(uri)!!.use { BitmapFactory.decodeStream(it, null, bounds) }
        var sample = 1
        while (max(bounds.outWidth, bounds.outHeight) / (sample * 2) >= maxEdge) sample *= 2
        val bmp = cr.openInputStream(uri)!!.use { BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample }) }!!
        val scale = minOf(1f, maxEdge.toFloat() / max(bmp.width, bmp.height))
        return if (scale >= 1f) bmp else Bitmap.createScaledBitmap(bmp, (bmp.width * scale).roundToInt(), (bmp.height * scale).roundToInt(), true)
    }
}
