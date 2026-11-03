package app.zura.core.storage

import java.io.File
import java.io.FileOutputStream

/** Crash-safe whole-file replace: write sibling temp → fsync → atomic rename. */
internal inline fun File.writeAtomically(block: (FileOutputStream) -> Unit) {
    val tmp = File(parentFile, "$name.new")
    FileOutputStream(tmp).use { out ->
        block(out)
        out.flush()
        out.fd.sync()
    }
    if (!tmp.renameTo(this)) {
        delete()
        check(tmp.renameTo(this)) { "atomic rename failed for $this" }
    }
}
