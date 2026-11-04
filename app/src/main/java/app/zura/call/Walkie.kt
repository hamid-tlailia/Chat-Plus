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
 * Zura open. Audio is 8 kHz G.711 μ-law in 250 ms chunks, each one sealed end-to-end like every
 * other frame and sent over the realtime channel — no server keeps it, nothing is stored.
 */
class Walkie(private val transport: SupabaseTransport, private val me: String, private val scope: CoroutineScope) {
    private val _talking = MutableStateFlow<String?>(null)
    /** Who is talking to me right now (user id), for the on-screen indicator. */
    val talking: StateFlow<String?> = _talking.asStateFlow()

    private val _sending = MutableStateFlow(false)
    val sending: StateFlow<Boolean> = _sending.asStateFlow()

    private var talkJob: Job? = null

    @SuppressLint("MissingPermission") // the UI asks for RECORD_AUDIO before calling this
    fun startTalking(peer: String, chatId: String): Boolean {
        if (talkJob?.isActive == true) return true
        val min = AudioRecord.getMinBufferSize(RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val rec = runCatching {
            AudioRecord(MediaRecorder.AudioSource.VOICE_COMMUNICATION, RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(min, CHUNK * 4))
                .takeIf { it.state == AudioRecord.STATE_INITIALIZED }
        }.getOrNull() ?: return false
        _sending.value = true
        talkJob = scope.launch(Dispatchers.IO) {
            var seq = 0
            val buf = ShortArray(CHUNK / 2)
            try {
                rec.startRecording()
                while (isActive) {
                    var n = 0
                    while (n < buf.size && isActive) { val r = rec.read(buf, n, buf.size - n); if (r <= 0) break; n += r }
                    if (n <= 0) break
                    val bytes = ByteArray(n) { i -> MuLaw.encode(buf[i]) }
                    transport.signal(peer, Frame.Walkie(chatId, me, seq++, Base64.encodeToString(bytes, Base64.NO_WRAP)))
                }
            } finally {
                runCatching { rec.stop() }; rec.release()
                transport.signal(peer, Frame.Walkie(chatId, me, seq, "", end = true))
                _sending.value = false
            }
        }
        return true
    }

    fun stopTalking() { talkJob?.cancel(); talkJob = null }

    // ------------------------------------------------------------------ receiving

    private var track: AudioTrack? = null
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    private val player = Dispatchers.IO.limitedParallelism(1)
    private var idle: Job? = null

    fun onFrame(from: String, f: Frame.Walkie) {
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
        scope.launch(player) { runCatching { t.write(out, 0, out.size) } } // one thread → chunks stay in order
        // No "end" (connection dropped)? Clear the indicator after a short silence.
        idle = scope.launch { delay(1_500); _talking.value = null }
    }

    fun release() {
        stopTalking()
        runCatching { track?.stop() }; track?.release(); track = null
        _talking.value = null
    }

    private companion object {
        const val RATE = 8_000
        /** 250 ms of 16-bit mono at 8 kHz. */
        const val CHUNK = RATE / 4 * 2
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
