package app.zura.media

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.produceState
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import app.zura.core.model.MediaRef
import app.zura.core.storage.VaultStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.max

/**
 * Tiny purpose-built image loader (replaces Coil/Glide: −600 KB, no reflection, no disk cache —
 * the vault *is* the disk cache). Decrypts with streaming, subsamples at decode time, and keeps
 * decoded bitmaps in a RAM-bounded LRU (1/8 of the heap).
 */
class VaultImages(private val vault: VaultStore) {

    private val cache = object : LruCache<String, ImageBitmap>((Runtime.getRuntime().maxMemory() / 8 / 1024).toInt()) {
        override fun sizeOf(key: String, value: ImageBitmap) = value.width * value.height * 4 / 1024
    }

    fun cached(ref: MediaRef, maxPx: Int): ImageBitmap? = cache.get(key(ref, maxPx))
    fun cachedFrame(ref: MediaRef, maxPx: Int): ImageBitmap? = cache.get("${ref.fileName}@v$maxPx")

    suspend fun load(chatId: String, ref: MediaRef, maxPx: Int): ImageBitmap? = withContext(Dispatchers.IO) {
        cache.get(key(ref, maxPx))?.let { return@withContext it }
        runCatching {
            val srcW = if (ref.width > 0) ref.width else maxPx
            val srcH = if (ref.height > 0) ref.height else maxPx
            var sample = 1
            while (max(srcW, srcH) / (sample * 2) >= maxPx) sample *= 2
            val opts = BitmapFactory.Options().apply { inSampleSize = sample; inPreferredConfig = Bitmap.Config.ARGB_8888 }
            VaultInputStream(vault.openReader(chatId, ref)).buffered(64 * 1024).use { BitmapFactory.decodeStream(it, null, opts) }
                ?.asImageBitmap()
                ?.also { cache.put(key(ref, maxPx), it) }
        }.getOrNull()
    }

    /**
     * A real frame of a video (~1 s in), decrypted straight from the vault — the bubble used to
     * show only the tiny blurred preview until the video was opened.
     */
    suspend fun loadVideoFrame(chatId: String, ref: MediaRef, maxPx: Int): ImageBitmap? = withContext(Dispatchers.IO) {
        val key = "${ref.fileName}@v$maxPx"
        cache.get(key)?.let { return@withContext it }
        runCatching {
            vault.openReader(chatId, ref).use { r ->
                val mmr = android.media.MediaMetadataRetriever()
                try {
                    mmr.setDataSource(object : android.media.MediaDataSource() {
                        override fun readAt(position: Long, buffer: ByteArray, offset: Int, size: Int): Int =
                            if (size == 0) 0 else synchronized(r) { r.read(position, buffer, offset, size) }
                        override fun getSize() = r.plainSize
                        override fun close() = Unit
                    })
                    val atUs = ref.durationMs.coerceIn(0, 2_000) / 2 * 1000
                    val opt = android.media.MediaMetadataRetriever.OPTION_CLOSEST_SYNC
                    val bmp = if (android.os.Build.VERSION.SDK_INT >= 27) mmr.getScaledFrameAtTime(atUs, opt, maxPx, maxPx)
                    else mmr.getFrameAtTime(atUs, opt)?.let { b ->
                        val s = maxPx.toFloat() / max(b.width, b.height)
                        if (s < 1f) Bitmap.createScaledBitmap(b, (b.width * s).toInt(), (b.height * s).toInt(), true) else b
                    }
                    bmp?.asImageBitmap()?.also { cache.put(key, it) }
                } finally { runCatching { mmr.release() } }
            }
        }.getOrNull()
    }

    fun evict(ref: MediaRef) = cache.snapshot().keys.filter { it.startsWith(ref.fileName) }.forEach(cache::remove)

    private fun key(ref: MediaRef, maxPx: Int) = "${ref.fileName}@$maxPx"
}

/** A video's first-second frame for its bubble / thumbnail (see [VaultImages.loadVideoFrame]). */
@Composable
fun rememberVideoFrame(images: VaultImages, chatId: String, ref: MediaRef, maxPx: Int): State<ImageBitmap?> =
    produceState(initialValue = images.cachedFrame(ref, maxPx), ref.fileName, maxPx) {
        value = images.cachedFrame(ref, maxPx)
        if (value == null) value = images.loadVideoFrame(chatId, ref, maxPx)
    }

@Composable
fun rememberVaultImage(images: VaultImages, chatId: String, ref: MediaRef, maxPx: Int): State<ImageBitmap?> =
    produceState(initialValue = images.cached(ref, maxPx), ref.fileName, maxPx) {
        // produceState's state survives key changes: always replace the previous image, or a new
        // moment/photo kept showing the old one.
        value = images.cached(ref, maxPx)
        if (value == null) value = images.load(chatId, ref, maxPx)
    }
