package app.zura.media

import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSpec
import app.zura.core.crypto.SegmentedCipher

/**
 * ExoPlayer reads media straight out of the encrypted vault — no decrypted temp file ever
 * touches disk. Seeking is O(1): jump to the 64 KiB segment, decrypt just that segment.
 */
@OptIn(UnstableApi::class)
class EncryptedDataSource(private val openReader: () -> SegmentedCipher.Reader) : BaseDataSource(false) {
    private var reader: SegmentedCipher.Reader? = null
    private var uri: Uri? = null
    private var position = 0L
    private var remaining = 0L

    override fun open(dataSpec: DataSpec): Long {
        transferInitializing(dataSpec)
        val r = openReader()
        reader = r
        uri = dataSpec.uri
        position = dataSpec.position
        remaining = if (dataSpec.length != C.LENGTH_UNSET.toLong()) dataSpec.length else r.plainSize - position
        transferStarted(dataSpec)
        return remaining
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        if (remaining <= 0) return C.RESULT_END_OF_INPUT
        val n = reader!!.read(position, buffer, offset, minOf(length.toLong(), remaining).toInt())
        if (n < 0) return C.RESULT_END_OF_INPUT
        position += n; remaining -= n
        bytesTransferred(n)
        return n
    }

    override fun getUri(): Uri? = uri

    override fun close() {
        reader?.close(); reader = null
        if (uri != null) { uri = null; transferEnded() }
    }
}
