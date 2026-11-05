package app.zura.call

import android.annotation.SuppressLint
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.util.Base64
import app.zura.core.sync.Frame
import app.zura.net.SupabaseTransport
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Walkie-talkie: hold to talk, the other person hears you live (≈ ½ s behind) while both have
 * Zura open. Audio is 8 kHz G.711 μ-law in 100 ms chunks, each one sealed end-to-end like every
 * other frame and sent over the realtime channel — no server keeps it, nothing is stored.
 */
class Walkie(private val transport: SupabaseTransport, private val me: String, private val scope: CoroutineScope) {
    private val _talking = MutableStateFlow<String?>(null)
    /** Who is talking to me right now (user id), for the on-screen indicator. */
    val talking: StateFlow<String?> = _talking.asStateFlow()

    private val _sending = MutableStateFlow(false)
    val sending: StateFlow<Boolean> = _sending.asStateFlow()

    private var talkJob: Job? = null
    @Volatile private var stopRequested = false

    /** A recording that did NOT reach the other person live: the user sends it as a voice note or deletes it. */
    class Draft(val chatId: String, val wav: ByteArray, val durationMs: Long, val waveform: String)
    private val _draft = MutableStateFlow<Draft?>(null)
    val draft: StateFlow<Draft?> = _draft.asStateFlow()
    fun discardDraft() { _draft.value = null }

    /**
     * Hold-to-talk. [live] = the other person is in the app: stream to them. Either way a local
     * copy is kept; if they weren't reachable (or any chunk failed) it becomes a [draft].
     */
    @SuppressLint("MissingPermission") // the UI asks for RECORD_AUDIO before calling this
    fun startTalking(peer: String, chatId: String, live: Boolean = true): Boolean {
        if (talkJob?.isActive == true) return false // previous burst still finishing
        val min = AudioRecord.getMinBufferSize(RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val rec = runCatching {
            AudioRecord(MediaRecorder.AudioSource.VOICE_COMMUNICATION, RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(min, CHUNK * 4))
                .takeIf { it.state == AudioRecord.STATE_INITIALIZED }
        }.getOrNull() ?: return false
        _draft.value = null
        stopRequested = false
        _sending.value = true
        talkJob = scope.launch(Dispatchers.IO) {
            var seq = 0
            var failed = 0
            // Recording never waits for the network: chunks queue up and a sender ships everything
            // queued so far in ONE request — a slow request makes the next one bigger instead of
            // making the voice fall further and further behind.
            val queue = kotlinx.coroutines.channels.Channel<Frame.Walkie>(kotlinx.coroutines.channels.Channel.UNLIMITED)
            val sender = if (live) launch {
                for (first in queue) {
                    val batch = mutableListOf(first)
                    while (true) batch += queue.tryReceive().getOrNull() ?: break
                    if (!transport.signalAll(peer, batch)) failed++
                }
            } else null
            val local = java.io.ByteArrayOutputStream()
            val buf = ShortArray(CHUNK / 2)
            try {
                rec.startRecording()
                while (!stopRequested && local.size() < MAX_BYTES) {
                    var n = 0
                    while (n < buf.size && !stopRequested) { val r = rec.read(buf, n, buf.size - n); if (r <= 0) break; n += r }
                    if (n <= 0) break
                    for (k in 0 until n) { local.write(buf[k].toInt() and 0xFF); local.write((buf[k].toInt() shr 8) and 0xFF) }
                    if (live) {
                        val bytes = ByteArray(n) { k -> MuLaw.encode(buf[k]) }
                        queue.trySend(Frame.Walkie(chatId, me, seq++, Base64.encodeToString(bytes, Base64.NO_WRAP)))
                    }
                }
            } finally {
                runCatching { rec.stop() }; rec.release()
                kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) {
                    if (live) { queue.trySend(Frame.Walkie(chatId, me, seq, "", end = true)); queue.close(); sender?.join() }
                    val pcm = local.toByteArray()
                    // Not heard live → keep it for "Send as voice note / Delete" (ignore accidental taps < 0.5 s).
                    if ((!live || failed > 0) && pcm.size >= RATE) _draft.value = makeDraft(chatId, pcm)
                    _sending.value = false
                }
            }
        }
        return true
    }

    /** Release: finish the current chunk, send "end", then stop. */
    fun stopTalking() { stopRequested = true }

    private fun makeDraft(chatId: String, pcm: ByteArray): Draft {
        val samples = pcm.size / 2
        // 48-bar waveform for the voice-note bubble (RMS per bar, 0–255).
        val bars = ByteArray(48) { b ->
            val from = b * samples / 48; val to = maxOf(from + 1, (b + 1) * samples / 48)
            var sum = 0.0
            for (k in from until to) { val v = ((pcm[2 * k].toInt() and 0xFF) or (pcm[2 * k + 1].toInt() shl 8)).toShort().toDouble(); sum += v * v }
            (kotlin.math.sqrt(sum / (to - from)) / 8000.0 * 255).coerceIn(10.0, 255.0).toInt().toByte()
        }
        return Draft(chatId, wav(pcm), samples * 1000L / RATE, java.util.Base64.getEncoder().encodeToString(bars))
    }

    /** PCM16 mono 8 kHz → a WAV file (playable by the in-app player). */
    private fun wav(pcm: ByteArray): ByteArray {
        val out = java.io.ByteArrayOutputStream(pcm.size + 44)
        fun i32(v: Int) { out.write(v and 0xFF); out.write((v shr 8) and 0xFF); out.write((v shr 16) and 0xFF); out.write((v shr 24) and 0xFF) }
        fun i16(v: Int) { out.write(v and 0xFF); out.write((v shr 8) and 0xFF) }
        out.write("RIFF".toByteArray()); i32(36 + pcm.size); out.write("WAVE".toByteArray())
        out.write("fmt ".toByteArray()); i32(16); i16(1); i16(1); i32(RATE); i32(RATE * 2); i16(2); i16(16)
        out.write("data".toByteArray()); i32(pcm.size); out.write(pcm)
        return out.toByteArray()
    }

    // ------------------------------------------------------------------ receiving

    private var track: AudioTrack? = null
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    private val player = Dispatchers.IO.limitedParallelism(1)
    private var idle: Job? = null

    fun onFrame(from: String, f: Frame.Walkie) {
        val fresh = _talking.value != from
        _talking.value = from
        idle?.cancel()
        if (f.end) { _talking.value = null; return }
        val pcm = runCatching { Base64.decode(f.audio, Base64.NO_WRAP) }.getOrNull() ?: return
        val out = ShortArray(pcm.size) { i -> MuLaw.decode(pcm[i]) }
        val t = track ?: AudioTrack.Builder()
            .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
            .setAudioFormat(AudioFormat.Builder().setSampleRate(RATE).setEncoding(AudioFormat.ENCODING_PCM_16BIT).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
            .setBufferSizeInBytes(CHUNK * 8)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build().also { it.play(); track = it }
        // Start of a burst: a short cushion (120 ms) so small network jitter between the 100 ms
        // chunks doesn't make the voice stutter.
        val cushion = if (fresh) ShortArray(RATE * 120 / 1000) else null
        scope.launch(player) { runCatching { cushion?.let { t.write(it, 0, it.size) }; t.write(out, 0, out.size) } } // one thread → chunks stay in order
        // No "end" (connection dropped)? Clear the indicator after a short silence.
        idle = scope.launch { delay(1_500); _talking.value = null }
    }

    fun release() {
        stopTalking()
        _draft.value = null
        runCatching { track?.stop() }; track?.release(); track = null
        _talking.value = null
    }

    private companion object {
        const val RATE = 8_000
        /** 100 ms of 16-bit mono at 8 kHz (smaller chunks = less delay before the first sound). */
        const val CHUNK = RATE / 10 * 2
        /** At most 5 minutes kept locally. */
        const val MAX_BYTES = RATE * 2 * 300
    }
}

/** G.711 μ-law — tiny, robust, walkie-talkie quality. */
internal object MuLaw {
    fun encode(sample: Short): Byte {
        var s = sample.toInt()
        val sign = if (s < 0) { s = -s; 0x80 } else 0
        if (s > 32635) s = 32635
        s += 0x84
        var exp = 7
        var mask = 0x4000
        while (exp > 0 && (s and mask) == 0) { exp--; mask = mask shr 1 }
        val mant = (s shr (exp + 3)) and 0x0F
        return (sign or (exp shl 4) or mant).inv().toByte()
    }

    fun decode(b: Byte): Short {
        val u = b.toInt().inv() and 0xFF
        val sign = u and 0x80
        val exp = (u shr 4) and 0x07
        val mant = u and 0x0F
        var s = ((mant shl 3) + 0x84) shl exp
        s -= 0x84
        return (if (sign != 0) -s else s).toShort()
    }
}
