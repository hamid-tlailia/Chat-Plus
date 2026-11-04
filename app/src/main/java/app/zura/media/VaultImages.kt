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

    fun evict(ref: MediaRef) = cache.snapshot().keys.filter { it.startsWith(ref.fileName) }.forEach(cache::remove)

    private fun key(ref: MediaRef, maxPx: Int) = "${ref.fileName}@$maxPx"
}

@Composable
fun rememberVaultImage(images: VaultImages, chatId: String, ref: MediaRef, maxPx: Int): State<ImageBitmap?> =
    produceState(initialValue = images.cached(ref, maxPx), ref.fileName, maxPx) {
        // produceState's state survives key changes: always replace the previous image, or a new
        // moment/photo kept showing the old one.
        value = images.cached(ref, maxPx)
        if (value == null) value = images.load(chatId, ref, maxPx)
    }
