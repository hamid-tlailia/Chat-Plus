package app.zura.media

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.core.content.FileProvider
import app.zura.core.model.MediaRef
import app.zura.core.storage.VaultStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * The ONLY two ways plaintext media ever leaves the vault — both explicit user actions:
 *  - [saveToDevice]: "Download" → public Pictures/Movies/Music/Download "Zura" folder.
 *  - [shareOut]: decrypt to cache/export, grant a one-shot FileProvider URI; wiped on next start.
 */
class MediaExporter(private val context: Context, private val vault: VaultStore) {

    suspend fun saveToDevice(chatId: String, ref: MediaRef): Boolean = withContext(Dispatchers.IO) {
        val name = ref.originalName ?: "Zura_${System.currentTimeMillis()}.${ext(ref.mime)}"
        runCatching {
            if (Build.VERSION.SDK_INT >= 29) {
                val (collection, dir) = when {
                    ref.mime.startsWith("image/") -> MediaStore.Images.Media.EXTERNAL_CONTENT_URI to Environment.DIRECTORY_PICTURES
                    ref.mime.startsWith("video/") -> MediaStore.Video.Media.EXTERNAL_CONTENT_URI to Environment.DIRECTORY_MOVIES
                    ref.mime.startsWith("audio/") -> MediaStore.Audio.Media.EXTERNAL_CONTENT_URI to Environment.DIRECTORY_MUSIC
                    else -> MediaStore.Downloads.EXTERNAL_CONTENT_URI to Environment.DIRECTORY_DOWNLOADS
                }
                val values = ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, name)
                    put(MediaStore.MediaColumns.MIME_TYPE, ref.mime)
                    put(MediaStore.MediaColumns.RELATIVE_PATH, "$dir/Zura")
                    put(MediaStore.MediaColumns.IS_PENDING, 1)
                }
                val cr = context.contentResolver
                val uri = cr.insert(collection, values)!!
                cr.openOutputStream(uri)!!.use { vault.decryptTo(chatId, ref, it) }
                cr.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
            } else {
                @Suppress("DEPRECATION")
                val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "Zura").apply { mkdirs() }
                File(dir, name).outputStream().use { vault.decryptTo(chatId, ref, it) }
            }
        }.isSuccess
    }

    suspend fun shareOut(chatId: String, ref: MediaRef): Intent = withContext(Dispatchers.IO) {
        // Must live under <cache>/export — the only folder the FileProvider is configured to expose
        // (per-account cache folders crashed sharing with "Failed to find configured root").
        val dir = File(context.cacheDir, "export").apply { mkdirs() }
        val f = File(dir, (ref.originalName ?: "zura.${ext(ref.mime)}").replace('/', '_'))
        f.outputStream().use { vault.decryptTo(chatId, ref, it) }
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", f)
        Intent.createChooser(
            Intent(Intent.ACTION_SEND).setType(ref.mime).putExtra(Intent.EXTRA_STREAM, uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION),
            null,
        )
    }

    /**
     * Open a received document / APK / any file in the right app (PDF viewer, installer…).
     * Decrypted to a short-lived copy under <cache>/export, granted read-only to that app.
     */
    suspend fun openIntent(chatId: String, ref: MediaRef): Intent = withContext(Dispatchers.IO) {
        val dir = File(context.cacheDir, "export").apply { mkdirs() }
        val f = File(dir, (ref.originalName ?: "zura.${ext(ref.mime)}").replace('/', '_'))
        f.outputStream().use { vault.decryptTo(chatId, ref, it) }
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", f)
        val mime = when {
            f.name.endsWith(".apk", true) -> "application/vnd.android.package-archive"
            ref.mime == "application/octet-stream" -> android.webkit.MimeTypeMap.getSingleton().getMimeTypeFromExtension(f.extension.lowercase()) ?: ref.mime
            else -> ref.mime
        }
        Intent(Intent.ACTION_VIEW).setDataAndType(uri, mime).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
    }

    private fun ext(mime: String) = when (mime) {
        "image/webp" -> "webp"; "image/jpeg" -> "jpg"; "image/png" -> "png"
        "video/mp4" -> "mp4"; "audio/ogg" -> "ogg"; "audio/mp4" -> "m4a"
        else -> mime.substringAfter('/', "bin")
    }
}
