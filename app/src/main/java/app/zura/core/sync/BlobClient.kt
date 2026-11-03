package app.zura.core.sync

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import java.io.File
import java.io.InputStream

/**
 * Uploads/downloads media ciphertext. Files are uploaded in *vault format* — already encrypted
 * with their per-file key — so there is zero re-encryption cost and the relay never sees plaintext.
 */
class BlobClient(private val baseUrl: String, private val http: OkHttpClient = OkHttpClient()) {

    fun upload(blobId: String, file: File) {
        val req = Request.Builder().url("$baseUrl/$blobId")
            .put(file.asRequestBody("application/octet-stream".toMediaType())).build()
        http.newCall(req).execute().use { check(it.isSuccessful) { "upload ${it.code}" } }
    }

    fun <T> download(blobId: String, sink: (InputStream) -> T): T {
        http.newCall(Request.Builder().url("$baseUrl/$blobId").build()).execute().use { r ->
            check(r.isSuccessful) { "download ${r.code}" }
            return sink(r.body!!.byteStream())
        }
    }
}
