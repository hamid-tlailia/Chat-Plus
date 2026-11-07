package app.zura.update

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build
import app.zura.BuildConfig
import app.zura.DiagLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/**
 * Updates straight from the project's GitHub repository (public): `updates/latest.json` says which
 * version is newest and where its APK is; a newer one is offered in the app, downloaded,
 * checked against the SHA-256 in that file, and installed over this one by Android's installer
 * (the user confirms; the APK must be signed with the same key, which Android verifies too).
 * Nothing about the user is sent: it is a plain download of two public files.
 */
object Updater {
    data class Release(val versionCode: Int, val versionName: String, val apkUrl: String, val sha256: String, val sizeBytes: Long, val notes: String?)

    sealed interface State {
        data object Idle : State
        data class Available(val r: Release) : State
        data class Downloading(val r: Release, val progress: Float) : State
        data class Installing(val r: Release) : State
        data class Failed(val r: Release?, val message: String) : State
    }

    private val _state = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = _state.asStateFlow()

    private const val BASE = "https://raw.githubusercontent.com/hamid-tlailia/Chat-Plus/" + BuildConfig.UPDATE_BRANCH + "/updates/"
    private const val PREFS = "zura_update"

    /** Looks for a newer version (at most every [minIntervalMs]); true = one is available. */
    suspend fun check(c: Context, minIntervalMs: Long = 3 * 60 * 60 * 1000L): Boolean = withContext(Dispatchers.IO) {
        val prefs = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val now = System.currentTimeMillis()
        if (_state.value is State.Downloading || _state.value is State.Installing) return@withContext false
        if (now - prefs.getLong("checked", 0) < minIntervalMs) return@withContext _state.value is State.Available
        val r = runCatching { fetchLatest(c) }.onFailure { DiagLog.record("update check", it) }.getOrNull() ?: return@withContext false
        prefs.edit().putLong("checked", now).apply()
        if (r.versionCode > BuildConfig.VERSION_CODE) { _state.value = State.Available(r); true } else { _state.value = State.Idle; false }
    }

    /** "Later" on the offer: not offered again until a newer version than this one appears. */
    fun skip(c: Context, r: Release) {
        c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putInt("skipped", r.versionCode).apply()
    }
    fun isSkipped(c: Context, r: Release) = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getInt("skipped", 0) >= r.versionCode

    private fun fetchLatest(c: Context): Release? {
        val text = get(BASE + "latest.json?t=" + System.currentTimeMillis()).bufferedReader().use { it.readText() }
        val o = Json.parseToJsonElement(text).jsonObject
        val abi = Build.SUPPORTED_ABIS.firstOrNull { o["apk"]?.jsonObject?.containsKey(it) == true } ?: return null
        val apk = o["apk"]!!.jsonObject[abi]!!.jsonObject
        val lang = c.resources.configuration.locales[0].language
        val notes = o["notes"]?.jsonObject?.let { n -> (n[lang] ?: n["en"])?.jsonPrimitive?.content }
        return Release(
            versionCode = o["versionCode"]!!.jsonPrimitive.int,
            versionName = o["versionName"]!!.jsonPrimitive.content,
            apkUrl = BASE + apk["file"]!!.jsonPrimitive.content,
            sha256 = apk["sha256"]!!.jsonPrimitive.content.lowercase(),
            sizeBytes = apk["size"]?.jsonPrimitive?.content?.toLongOrNull() ?: 0L,
            notes = notes,
        )
    }

    private fun get(url: String): java.io.InputStream {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 15_000; conn.readTimeout = 30_000
        conn.useCaches = false
        conn.setRequestProperty("Cache-Control", "no-cache")
        if (conn.responseCode != 200) { conn.disconnect(); error("HTTP ${conn.responseCode}") }
        return conn.inputStream
    }

    /** Can Zura hand APKs to the installer? Off → the "Install unknown apps" page must be opened. */
    fun canInstall(c: Context) = Build.VERSION.SDK_INT < 26 || c.packageManager.canRequestPackageInstalls()

    fun openInstallPermission(c: Context) {
        if (Build.VERSION.SDK_INT < 26) return
        runCatching {
            c.startActivity(Intent(android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, android.net.Uri.parse("package:" + c.packageName)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }

    /** Download → verify → hand to Android's installer (it asks the user to confirm). */
    suspend fun downloadAndInstall(c: Context, r: Release) = withContext(Dispatchers.IO) {
        val app = c.applicationContext
        _state.value = State.Downloading(r, 0f)
        val dir = File(app.cacheDir, "updates").apply { mkdirs() }
        dir.listFiles()?.forEach { it.delete() } // older downloads
        val file = File(dir, "Zura-${r.versionName}.apk")
        try {
            val md = MessageDigest.getInstance("SHA-256")
            get(r.apkUrl + "?v=" + r.versionCode).use { input ->
                file.outputStream().use { out ->
                    val buf = ByteArray(64 * 1024)
                    var done = 0L
                    var lastShown = 0f
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n); md.update(buf, 0, n); done += n
                        if (r.sizeBytes > 0) {
                            val p = (done.toFloat() / r.sizeBytes).coerceIn(0f, 1f)
                            if (p - lastShown >= 0.01f) { lastShown = p; _state.value = State.Downloading(r, p) }
                        }
                    }
                }
            }
            val hash = md.digest().joinToString("") { "%02x".format(it) }
            if (hash != r.sha256) { file.delete(); error("checksum mismatch") }
            _state.value = State.Installing(r)
            install(app, file)
        } catch (e: Exception) {
            DiagLog.record("update", e)
            _state.value = State.Failed(r, e.message ?: e.javaClass.simpleName)
        }
    }

    private fun install(c: Context, apk: File) {
        val pi = c.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(c.packageName)
            if (Build.VERSION.SDK_INT >= 31) setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
        }
        val id = pi.createSession(params)
        pi.openSession(id).use { session ->
            apk.inputStream().use { input -> session.openWrite("zura.apk", 0, apk.length()).use { out -> input.copyTo(out); session.fsync(out) } }
            val status = PendingIntent.getBroadcast(
                c, id, Intent(c, InstallStatusReceiver::class.java).setAction("app.zura.action.INSTALL_STATUS"),
                PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0),
            )
            session.commit(status.intentSender)
        }
    }

    internal fun onInstallStatus(c: Context, intent: Intent) {
        when (val st = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
            // Android shows its own "Update this app?" confirmation.
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                @Suppress("DEPRECATION")
                val confirm = (if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java) else intent.getParcelableExtra(Intent.EXTRA_INTENT))
                confirm?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)?.let { runCatching { c.startActivity(it) } }
            }
            PackageInstaller.STATUS_SUCCESS -> Unit // the app is replaced and restarted by the system
            else -> {
                val msg = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE) ?: "status $st"
                DiagLog.record("update", detail = "install failed: $msg")
                val r = (_state.value as? State.Installing)?.r
                _state.value = if (st == PackageInstaller.STATUS_FAILURE_ABORTED && r != null) State.Available(r) else State.Failed(r, msg)
            }
        }
    }
}

class InstallStatusReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) = Updater.onInstallStatus(context, intent)
}
