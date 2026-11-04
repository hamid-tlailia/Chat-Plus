package app.zura.core.storage

import java.io.File
import java.io.FileOutputStream

/** Crash-safe whole-file replace: write sibling temp → fsync → atomic rename. */
inline fun File.writeAtomically(block: (FileOutputStream) -> Unit) {
    val tmp = File(parentFile, "$name.new")
    try {
        FileOutputStream(tmp).use { out ->
            block(out)
            out.flush()
            out.fd.sync()
        }
    } catch (e: Throwable) {
        // The original stays intact; don't leave a partial (ciphertext) copy lying around.
        runCatching { SecureEraser.erase(tmp) }
        throw e
    }
    if (!tmp.renameTo(this)) {
        delete()
        check(tmp.renameTo(this)) { "atomic rename failed for $this" }
    }
}
