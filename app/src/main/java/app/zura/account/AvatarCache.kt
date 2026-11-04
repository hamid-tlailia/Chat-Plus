package app.zura.account

import android.graphics.BitmapFactory
import android.util.LruCache
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.produceState
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import app.zura.core.crypto.KeyWrapper
import app.zura.core.crypto.SecureFile
import app.zura.net.Backend
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.security.MessageDigest

/** Contact avatars: fetched once from the private avatars bucket, cached encrypted on disk + in RAM. */
class AvatarCache(private val backend: Backend, private val dir: File, private val keys: KeyWrapper) {
    private val mem = LruCache<String, ImageBitmap>(64)

    fun cached(path: String?): ImageBitmap? = path?.let(mem::get)

    @androidx.annotation.VisibleForTesting
    internal fun putForTest(path: String, img: ImageBitmap) { mem.put(path, img) }

    suspend fun load(path: String?): ImageBitmap? = withContext(Dispatchers.IO) {
        path ?: return@withContext null
        mem.get(path)?.let { return@withContext it }
        val file = SecureFile(File(dir, sha(path)), keys, "zavc")
        val bytes = file.read() ?: runCatching { backend.download("avatars", path) { it.readBytes() } }.getOrNull()?.also(file::write)
        bytes?.let { decode(it) }?.also { mem.put(path, it) }
    }

    fun decode(bytes: ByteArray): ImageBitmap? = runCatching {
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, o)
        var s = 1
        while (maxOf(o.outWidth, o.outHeight) / (s * 2) >= 256) s *= 2
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = s })?.asImageBitmap()
    }.getOrNull()

    private fun sha(s: String) = MessageDigest.getInstance("SHA-256").digest(s.encodeToByteArray()).joinToString("") { "%02x".format(it) }.take(32)
}

@Composable
fun rememberAvatar(cache: AvatarCache, path: String?): State<ImageBitmap?> =
    produceState(cache.cached(path), path) {
        value = cache.cached(path) // new path → never keep showing the previous photo
        if (value == null && path != null) value = cache.load(path)
    }
