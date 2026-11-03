package app.zura.core.crypto

import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.io.RandomAccessFile
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Streaming, seekable AES-256-GCM for media files (Tink "streaming AEAD" construction, minus the dep).
 *
 * Why not plain GCM? Android's GCM decrypt buffers the whole ciphertext before releasing a byte,
 * so a 200 MB video would OOM and could not be seeked. Here every 64 KiB segment is authenticated
 * independently, so we stream with O(64 KiB) memory and ExoPlayer can seek to any offset.
 *
 * File layout:
 *   MAGIC "ZRA1" (4) | noncePrefix (7) | seg0 | seg1 | … | segN
 *   segI = GCM(plain[i*64K ..], nonce = prefix(7) || i (4, BE) || isLast (1))
 * The isLast flag in the nonce makes truncation and segment reordering detectable.
 */
object SegmentedCipher {
    const val SEGMENT = 64 * 1024
    private const val CT_SEGMENT = SEGMENT + Crypto.TAG
    private val MAGIC = byteArrayOf('Z'.code.toByte(), 'R'.code.toByte(), 'A'.code.toByte(), '1'.code.toByte())
    private const val PREFIX = 7
    const val HEADER = 4 + PREFIX

    fun encrypt(key: ByteArray, input: InputStream, output: OutputStream): Long {
        val prefix = ByteArray(PREFIX).also(Crypto.rng::nextBytes)
        output.write(MAGIC); output.write(prefix)
        val cipher = Cipher.getInstance(Crypto.GCM)
        val k = SecretKeySpec(key, "AES")
        var cur = ByteArray(SEGMENT)
        var next = ByteArray(SEGMENT)
        var curLen = readFully(input, cur)
        var index = 0
        var total = 0L
        while (true) {
            val nextLen = if (curLen == SEGMENT) readFully(input, next) else 0
            val last = nextLen == 0
            cipher.init(Cipher.ENCRYPT_MODE, k, GCMParameterSpec(128, nonce(prefix, index, last)))
            output.write(cipher.doFinal(cur, 0, curLen))
            total += curLen
            if (last) break
            val t = cur; cur = next; next = t; curLen = nextLen; index++
        }
        output.flush()
        return total
    }

    fun decrypt(key: ByteArray, input: InputStream, output: OutputStream) {
        val header = ByteArray(HEADER)
        if (readFully(input, header) != HEADER || !header.copyOfRange(0, 4).contentEquals(MAGIC)) {
            throw IllegalStateException("Not a Zura vault file")
        }
        val prefix = header.copyOfRange(4, HEADER)
        val cipher = Cipher.getInstance(Crypto.GCM)
        val k = SecretKeySpec(key, "AES")
        var cur = ByteArray(CT_SEGMENT)
        var next = ByteArray(CT_SEGMENT)
        var curLen = readFully(input, cur)
        var index = 0
        while (true) {
            val nextLen = if (curLen == CT_SEGMENT) readFully(input, next) else 0
            val last = nextLen == 0
            cipher.init(Cipher.DECRYPT_MODE, k, GCMParameterSpec(128, nonce(prefix, index, last)))
            output.write(cipher.doFinal(cur, 0, curLen))
            if (last) break
            val t = cur; cur = next; next = t; curLen = nextLen; index++
        }
        output.flush()
    }

    /** Random-access reader used by the encrypted ExoPlayer DataSource and thumbnail decoder. */
    class Reader(file: File, key: ByteArray) : AutoCloseable {
        private val raf = RandomAccessFile(file, "r")
        private val k = SecretKeySpec(key, "AES")
        private val cipher = Cipher.getInstance(Crypto.GCM)
        private val prefix: ByteArray
        private val segments: Int
        val plainSize: Long
        private var cachedIndex = -1
        private var cached = ByteArray(0)
        private val ctBuf = ByteArray(CT_SEGMENT)

        init {
            val header = ByteArray(HEADER)
            raf.readFully(header)
            require(header.copyOfRange(0, 4).contentEquals(MAGIC)) { "Not a Zura vault file" }
            prefix = header.copyOfRange(4, HEADER)
            val body = raf.length() - HEADER
            segments = ((body + CT_SEGMENT - 1) / CT_SEGMENT).toInt().coerceAtLeast(1)
            plainSize = body - segments.toLong() * Crypto.TAG
        }

        /** Reads up to [len] plaintext bytes at [position]; returns -1 at EOF. */
        fun read(position: Long, dst: ByteArray, off: Int, len: Int): Int {
            if (position >= plainSize) return -1
            val seg = (position / SEGMENT).toInt()
            val plain = segment(seg)
            val inSeg = (position - seg.toLong() * SEGMENT).toInt()
            val n = minOf(len, plain.size - inSeg)
            System.arraycopy(plain, inSeg, dst, off, n)
            return n
        }

        private fun segment(i: Int): ByteArray {
            if (i == cachedIndex) return cached
            val start = HEADER + i.toLong() * CT_SEGMENT
            val len = minOf(CT_SEGMENT.toLong(), raf.length() - start).toInt()
            raf.seek(start)
            raf.readFully(ctBuf, 0, len)
            cipher.init(Cipher.DECRYPT_MODE, k, GCMParameterSpec(128, nonce(prefix, i, i == segments - 1)))
            cached = cipher.doFinal(ctBuf, 0, len)
            cachedIndex = i
            return cached
        }

        override fun close() = raf.close()
    }

    private fun nonce(prefix: ByteArray, index: Int, last: Boolean) = ByteArray(Crypto.NONCE).also {
        System.arraycopy(prefix, 0, it, 0, PREFIX)
        it[7] = (index ushr 24).toByte(); it[8] = (index ushr 16).toByte()
        it[9] = (index ushr 8).toByte(); it[10] = index.toByte()
        it[11] = if (last) 1 else 0
    }

    private fun readFully(input: InputStream, buf: ByteArray): Int {
        var total = 0
        while (total < buf.size) {
            val n = input.read(buf, total, buf.size - total)
            if (n < 0) break
            total += n
        }
        return total
    }
}
