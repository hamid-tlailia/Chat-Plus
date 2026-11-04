package app.zura

import android.content.Context
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Non-fatal problems (a message that couldn't be sent, a key that couldn't be read…), kept as a
 * short rolling log so a tester can copy it from Settings. Technical details only — never message
 * content, never keys.
 */
object DiagLog {
    private const val MAX_LINES = 120
    private var file: File? = null
    private val fmt = SimpleDateFormat("MM-dd HH:mm:ss", Locale.US)

    fun init(c: Context) { file = File(c.noBackupFilesDir, "diag.log") }

    @Synchronized
    fun record(where: String, t: Throwable? = null, detail: String? = null) {
        val f = file ?: return
        val line = buildString {
            append(fmt.format(Date())).append(' ').append(where)
            detail?.let { append(" · ").append(it) }
            t?.let {
                append(" · ").append(it.javaClass.simpleName).append(": ").append(it.message?.take(300))
                it.stackTrace.firstOrNull { e -> e.className.startsWith("app.zura") }?.let { e -> append(" @").append(e.fileName).append(':').append(e.lineNumber) }
            }
        }
        runCatching {
            val lines = (if (f.exists()) f.readLines() else emptyList()) + line
            f.writeText(lines.takeLast(MAX_LINES).joinToString("\n"))
        }
    }

    fun read(): String = file?.takeIf { it.exists() }?.readText().orEmpty()
    fun clear() { file?.delete() }
}
