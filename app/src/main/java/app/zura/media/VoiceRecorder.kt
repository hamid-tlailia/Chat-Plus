package app.zura.media

import android.content.Context
import android.media.MediaRecorder
import android.os.Build
import app.zura.core.storage.SecureEraser
import app.zura.core.storage.VaultStore
import app.zura.core.model.MediaRef
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
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
 * Voice notes: Opus-in-Ogg @ 24 kbps on API 29+ (≈180 KB/min — 4× smaller than typical AAC),
 * AAC @ 32 kbps fallback. Amplitude is sampled every 40 ms (25 fps) into a ring the UI draws as a
 * live waveform; on stop it is bucketed into 48 bars and stored inline with the message.
 */
class VoiceRecorder(private val context: Context, private val vault: VaultStore, private val scope: CoroutineScope) {

    data class Live(val recording: Boolean = false, val elapsedMs: Long = 0, val levels: List<Float> = emptyList())

    private val _live = MutableStateFlow(Live())
    val live: StateFlow<Live> = _live.asStateFlow()

    private var recorder: MediaRecorder? = null
    private var file: File? = null
    private var sampler: Job? = null
    private var startedAt = 0L
    private val samples = ArrayList<Float>(1500)

    private val opus get() = Build.VERSION.SDK_INT >= 29

    fun start() {
        if (recorder != null) return
        val f = vault.paths.newTemp(if (opus) "ogg" else "m4a")
        val r = if (Build.VERSION.SDK_INT >= 31) MediaRecorder(context) else @Suppress("DEPRECATION") MediaRecorder()
        r.setAudioSource(MediaRecorder.AudioSource.VOICE_COMMUNICATION) // AEC + noise suppression
        if (opus) {
            r.setOutputFormat(MediaRecorder.OutputFormat.OGG)
            r.setAudioEncoder(MediaRecorder.AudioEncoder.OPUS)
            r.setAudioSamplingRate(48_000)
            r.setAudioEncodingBitRate(24_000)
        } else {
            r.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            r.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            r.setAudioSamplingRate(22_050)
            r.setAudioEncodingBitRate(32_000)
        }
        r.setAudioChannels(1)
        r.setOutputFile(f.absolutePath)
        r.prepare()
        r.start()
        recorder = r; file = f
        samples.clear()
        startedAt = System.currentTimeMillis()
        sampler = scope.launch(Dispatchers.Default) {
            while (isActive) {
                // sqrt ≈ perceptual loudness; raw amplitude looks flat for speech.
                val level = sqrt((recorder?.maxAmplitude ?: 0) / 32767f).coerceIn(0.04f, 1f)
                samples += level
                _live.value = Live(true, System.currentTimeMillis() - startedAt, samples.takeLast(64))
                delay(40)
            }
        }
    }

    /** Stops, encrypts into the vault and erases the plaintext. Null if too short (<600 ms). */
    suspend fun stop(chatId: String): MediaRef? {
        val r = recorder ?: return null
        val f = file!!
        sampler?.cancel()
        val duration = System.currentTimeMillis() - startedAt
        runCatching { r.stop() }
        r.release()
        recorder = null; file = null
        _live.value = Live()
        return withContext(Dispatchers.IO) {
            try {
                if (duration < 600 || f.length() == 0L) null
                else f.inputStream().buffered().use {
                    vault.importMedia(
                        chatId, it, if (opus) "audio/ogg" else "audio/mp4",
                        durationMs = duration, waveform = Previews.encodeWaveform(samples),
                    )
                }
            } finally {
                SecureEraser.erase(f)
            }
        }
    }

    fun cancel() {
        sampler?.cancel()
        recorder?.let { runCatching { it.stop() }; it.release() }
        recorder = null
        file?.let(SecureEraser::erase); file = null
        _live.value = Live()
    }
}
