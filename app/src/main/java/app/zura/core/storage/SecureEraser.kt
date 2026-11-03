package app.zura.core.storage

import java.io.File
import java.io.RandomAccessFile

/**
 * Permanent erasure on flash storage.
 *
 * Multi-pass overwrites (DoD 5220-style) are theatre on eMMC/UFS: the flash translation layer
 * remaps writes to fresh pages, so old pages linger until garbage collection. What actually works
 * is crypto-shredding: every byte Zura persists is ciphertext, and destroying the *key* (inside
 * Android Keystore — see [app.zura.core.crypto.KeyWrapper.destroy]) makes every stale copy noise.
 *
 * So erasure = destroy key → zero small key-bearing files in place → unlink → FS TRIMs blocks.
 */
object SecureEraser {

    private const val ZERO_FILL_LIMIT = 4L * 1024 * 1024

    /** Unlink a file, zero-filling it first if small (logs). Returns bytes freed. */
    fun erase(file: File): Long {
        if (!file.isFile) return 0
        val size = file.length()
        if (size in 1..ZERO_FILL_LIMIT) runCatching {
            RandomAccessFile(file, "rws").use { raf ->
                raf.write(ByteArray(size.toInt()))
                raf.fd.sync()
            }
        }
        return if (file.delete()) size else 0
    }

    /** Depth-first erase of a directory tree. Returns bytes freed. */
    fun eraseTree(dir: File): Long {
        if (!dir.exists()) return 0
        var freed = 0L
        dir.walkBottomUp().forEach { f -> if (f.isFile) freed += erase(f) else f.delete() }
        return freed
    }
}
