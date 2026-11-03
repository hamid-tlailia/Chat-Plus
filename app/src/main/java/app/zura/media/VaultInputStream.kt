package app.zura.media

import app.zura.core.crypto.SegmentedCipher
import java.io.InputStream

/** Decrypting InputStream over a vault file — O(64 KiB) memory regardless of file size. */
class VaultInputStream(private val reader: SegmentedCipher.Reader) : InputStream() {
    private var pos = 0L
    private val one = ByteArray(1)

    override fun read(): Int = if (read(one, 0, 1) < 0) -1 else one[0].toInt() and 0xFF

    override fun read(b: ByteArray, off: Int, len: Int): Int {
        if (len == 0) return 0
        val n = reader.read(pos, b, off, len)
        if (n > 0) pos += n
        return n
    }

    override fun skip(n: Long): Long {
        val s = minOf(n, reader.plainSize - pos).coerceAtLeast(0)
        pos += s
        return s
    }

    override fun available(): Int = minOf(Int.MAX_VALUE.toLong(), reader.plainSize - pos).toInt()
    override fun close() = reader.close()
}
