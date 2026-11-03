package app.zura

import android.content.Context
import android.os.Build
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter

/**
 * Keeps the last crash's stack trace (no message content — just code locations) so a tester can
 * copy it from Settings and send it. Stored in no_backup, overwritten by the next crash.
 */
object CrashLog {
    private fun file(c: Context) = File(c.noBackupFilesDir, "last_crash.txt")

    fun install(c: Context) {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            runCatching {
                val sw = StringWriter()
                e.printStackTrace(PrintWriter(sw))
                file(c).writeText(
                    "Zura ${BuildConfig.VERSION_NAME} · Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT}) · " +
                        "${Build.MANUFACTURER} ${Build.MODEL}\nthread: ${t.name}\n\n$sw",
                )
            }
            previous?.uncaughtException(t, e)
        }
    }

    fun read(c: Context): String? = file(c).takeIf { it.exists() }?.readText()
    fun clear(c: Context) { file(c).delete() }
}
