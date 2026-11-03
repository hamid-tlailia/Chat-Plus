package app.zura.media

import android.content.Context
import android.media.MediaRecorder
import android.os.Build
import app.zura.core.model.MediaRef
import app.zura.core.storage.SecureEraser
import app.zura.core.storage.VaultStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.sqrt

/**
 * Voice notes. Prefers Opus-in-Ogg @ 24 kbps (≈180 KB/min); some OEM builds (notably several
 * MIUI/HyperOS and older Samsung firmwares) reject Opus or the VOICE_COMMUNICATION source, so
 * start() walks a list of configurations until one actually starts. Every touch of the native
 * MediaRecorder is serialized under one lock — an amplitude read racing release() used to crash
 * the app on some devices.
 */
class VoiceRecorder(private val context: Context, private val vault: VaultStore, private val scope: CoroutineScope) {

    data class Live(val recording: Boolean = false, val elapsedMs: Long = 0, val levels: List<Float> = emptyList())

    private val _live = MutableStateFlow(Live())
    val live: StateFlow<Live> = _live.asStateFlow()

    private val lock = Any()
    private var recorder: MediaRecorder? = null
    private var file: File? = null
    private var mime = "audio/mp4"
    private var sampler: Job? = null
    private var startedAt = 0L
    private val samples = ArrayList<Float>(1500)

    private data class Config(val source: Int, val opus: Boolean)

    private val configs = buildList {
        if (Build.VERSION.SDK_INT >= 29) {
            add(Config(MediaRecorder.AudioSource.VOICE_COMMUNICATION, opus = true))
            add(Config(MediaRecorder.AudioSource.MIC, opus = true))
        }
        add(Config(MediaRecorder.AudioSource.VOICE_COMMUNICATION, opus = false))
        add(Config(MediaRecorder.AudioSource.MIC, opus = false))
    }

    /** @return false if no configuration could start (mic busy, permission revoked…). Never throws. */
    fun start(): Boolean = synchronized(lock) {
        if (recorder != null) return true
        for (cfg in configs) {
            val f = vault.paths.newTemp(if (cfg.opus) "ogg" else "m4a")
            val r = if (Build.VERSION.SDK_INT >= 31) MediaRecorder(context) else @Suppress("DEPRECATION") MediaRecorder()
            val ok = runCatching {
                r.setAudioSource(cfg.source)
                if (cfg.opus && Build.VERSION.SDK_INT >= 29) {
                    r.setOutputFormat(MediaRecorder.OutputFormat.OGG)
                    r.setAudioEncoder(MediaRecorder.AudioEncoder.OPUS)
                    r.setAudioSamplingRate(48_000)
                    r.setAudioEncodingBitRate(24_000)
                } else {
                    r.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                    r.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                    r.setAudioSamplingRate(44_100)
                    r.setAudioEncodingBitRate(48_000)
                }
                r.setAudioChannels(1)
                r.setOutputFile(f.absolutePath)
                r.prepare()
                r.start()
            }.isSuccess
            if (ok) {
                recorder = r; file = f
                mime = if (cfg.opus) "audio/ogg" else "audio/mp4"
                break
            }
            runCatching { r.reset() }; runCatching { r.release() }
            SecureEraser.erase(f)
        }
        val r = recorder ?: return false
        samples.clear()
        startedAt = System.currentTimeMillis()
        sampler = scope.launch(Dispatchers.Default) {
            while (isActive) {
                val amp = synchronized(lock) { if (recorder === r) runCatching { r.maxAmplitude }.getOrDefault(0) else return@launch }
                // sqrt ≈ perceptual loudness; raw amplitude looks flat for speech.
                samples += sqrt(amp / 32767f).coerceIn(0.04f, 1f)
                _live.value = Live(true, System.currentTimeMillis() - startedAt, samples.takeLast(64))
                delay(40)
            }
        }
        true
    }

    /** Stops, encrypts into the vault and erases the plaintext. Null if too short (<600 ms) or failed. */
    suspend fun stop(chatId: String): MediaRef? {
        sampler?.cancelAndJoin()
        val (r, f) = synchronized(lock) {
            val r = recorder ?: return null
            recorder = null
            r to file!!
        }
        val duration = System.currentTimeMillis() - startedAt
        // stop() throws if nothing was captured (very short press) — that's a "too short", not a crash.
        val stopped = runCatching { r.stop() }.isSuccess
        runCatching { r.release() }
        file = null
        _live.value = Live()
        return withContext(Dispatchers.IO) {
            try {
                if (!stopped || duration < 600 || f.length() == 0L) null
                else f.inputStream().buffered().use {
                    vault.importMedia(chatId, it, mime, durationMs = duration, waveform = Previews.encodeWaveform(samples))
                }
            } catch (_: Exception) {
                null
            } finally {
                SecureEraser.erase(f)
            }
        }
    }

    fun cancel() {
        sampler?.cancel()
        synchronized(lock) {
            recorder?.let { runCatching { it.stop() }; runCatching { it.release() } }
            recorder = null
            file?.let(SecureEraser::erase); file = null
        }
        _live.value = Live()
    }
}
