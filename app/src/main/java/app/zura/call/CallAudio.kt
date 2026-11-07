package app.zura.call

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.Ringtone
import android.media.RingtoneManager
import android.media.ToneGenerator
import android.os.Build
import android.os.PowerManager
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager

/** Audio routing, ringtone/ringback, vibration and proximity screen-off for calls. */
class CallAudio(private val context: Context) {
    private val am = context.getSystemService(AudioManager::class.java)
    private var focus: AudioFocusRequest? = null
    private var ringback: android.media.AudioTrack? = null
    private var proximity: PowerManager.WakeLock? = null
    private val vibrator: Vibrator? = if (Build.VERSION.SDK_INT >= 31)
        context.getSystemService(VibratorManager::class.java)?.defaultVibrator
    else @Suppress("DEPRECATION") context.getSystemService(Vibrator::class.java)

    fun startCallMode(speaker: Boolean) {
        val attrs = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build()
        if (Build.VERSION.SDK_INT >= 26) {
            focus = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT).setAudioAttributes(attrs).build().also { am.requestAudioFocus(it) }
        }
        am.mode = AudioManager.MODE_IN_COMMUNICATION
        setSpeaker(speaker)
    }

    /**
     * Loudspeaker on / off. Several layers, because phones differ:
     *  1. a Telecom call (opt-in) is routed by the system → ask it ([Telecom.route]);
     *  2. Android 12+: setCommunicationDevice — then CHECK where the audio really goes: some
     *     phones accept the call and keep the earpiece (the speaker button "did nothing");
     *  3. the old speakerphone switch as the fallback, which those phones still honour.
     * Each switch is written to the diagnostics log with the route actually in use.
     */
    fun setSpeaker(on: Boolean) {
        runCatching { if (am.mode != AudioManager.MODE_IN_COMMUNICATION) am.mode = AudioManager.MODE_IN_COMMUNICATION }
        val telecom = Telecom.route(on)
        if (Build.VERSION.SDK_INT >= 31) {
            val devices = am.availableCommunicationDevices
            // A connected Bluetooth / wired headset wins over the earpiece.
            val headset = devices.firstOrNull {
                it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO || it.type == AudioDeviceInfo.TYPE_WIRED_HEADSET ||
                    it.type == AudioDeviceInfo.TYPE_BLE_HEADSET || it.type == AudioDeviceInfo.TYPE_USB_HEADSET
            }
            val target = if (on) devices.firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER }
            else headset ?: devices.firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_EARPIECE }
            val ok = target?.let { runCatching { am.setCommunicationDevice(it) }.getOrDefault(false) } ?: false
            if (!on && target == null) runCatching { am.clearCommunicationDevice() }
            val now = am.communicationDevice?.type
            val applied = ok && (if (on) now == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER else now != AudioDeviceInfo.TYPE_BUILTIN_SPEAKER)
            if (!applied) @Suppress("DEPRECATION") runCatching { am.isSpeakerphoneOn = on }
            @Suppress("DEPRECATION")
            app.zura.DiagLog.record("call", detail = "speaker ${if (on) "ON" else "off"}: device=${target?.type} accepted=$ok now=${am.communicationDevice?.type}" +
                (if (!applied) ", fallback speakerphone=${am.isSpeakerphoneOn}" else "") + (if (telecom) ", via Telecom" else "") + ", mode=${am.mode}")
        } else {
            @Suppress("DEPRECATION")
            am.isSpeakerphoneOn = on
            @Suppress("DEPRECATION")
            app.zura.DiagLog.record("call", detail = "speaker ${if (on) "ON" else "off"}: speakerphone=${am.isSpeakerphoneOn}" + (if (telecom) ", via Telecom" else "") + ", mode=${am.mode}")
        }
    }

    fun stopCallMode() {
        if (Build.VERSION.SDK_INT >= 31) am.clearCommunicationDevice() else @Suppress("DEPRECATION") run { am.isSpeakerphoneOn = false }
        am.mode = AudioManager.MODE_NORMAL
        if (Build.VERSION.SDK_INT >= 26) focus?.let { am.abandonAudioFocusRequest(it) }
        focus = null
        proximity(false)
    }

    /** Incoming-call ring: shared with the provisional ring of a push (see [Ringer]). */
    fun startRingtone(peerId: String? = null) {
        if (Ringer.isRinging) return
        stopTones()
        Ringer.start(context, peerId)
    }

    /**
     * "Ringing…" heard by the caller: ONE soft 425 Hz tone (1 s, faded in/out) then 3 s of silence,
     * looping — synthesised once into a static AudioTrack, so it can never play twice or overlap.
     */
    fun startRingback() {
        if (ringback != null) return
        stopTones()
        ringback = runCatching {
            val rate = 16_000
            val total = rate * 4 // 1 s tone + 3 s silence
            val pcm = ShortArray(total)
            val fade = rate / 20 // 50 ms
            for (i in 0 until rate) {
                val env = minOf(1f, i / fade.toFloat(), (rate - i) / fade.toFloat())
                pcm[i] = (Math.sin(2.0 * Math.PI * 425.0 * i / rate) * 0.28 * env * Short.MAX_VALUE).toInt().toShort()
            }
            val track = android.media.AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION_SIGNALLING)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build(),
                )
                .setAudioFormat(
                    android.media.AudioFormat.Builder().setEncoding(android.media.AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(rate).setChannelMask(android.media.AudioFormat.CHANNEL_OUT_MONO).build(),
                )
                .setBufferSizeInBytes(total * 2)
                .setTransferMode(android.media.AudioTrack.MODE_STATIC)
                .build()
            track.write(pcm, 0, total)
            track.setLoopPoints(0, total, -1)
            track.play()
            track
        }.getOrNull()
    }

    fun beepEnd() {
        runCatching { ToneGenerator(AudioManager.STREAM_VOICE_CALL, 60).startTone(ToneGenerator.TONE_PROP_PROMPT, 250) }
    }

    fun stopTones() {
        Ringer.stop()
        ringback?.run { runCatching { stop() }; release() }; ringback = null
        vibrator?.cancel()
    }

    /** Voice calls on the earpiece: screen turns off at the ear, like a phone call. */
    fun proximity(on: Boolean) {
        if (on) {
            if (proximity?.isHeld == true) return
            val pm = context.getSystemService(PowerManager::class.java)
            val supported = pm.isWakeLockLevelSupported(PowerManager.PROXIMITY_SCREEN_OFF_WAKE_LOCK)
            if (supported) {
                proximity = runCatching { pm.newWakeLock(PowerManager.PROXIMITY_SCREEN_OFF_WAKE_LOCK, "zura:call").apply { acquire(4 * 60 * 60 * 1000L) } }
                    .onFailure { app.zura.DiagLog.record("proximity", it) }.getOrNull()
            }
            listenNear(true)
            app.zura.DiagLog.record("call", detail = "earpiece: proximity screen-off ${if (proximity?.isHeld == true) "on" else "NOT available (supported=$supported)"} + sensor guard")
        } else {
            proximity?.takeIf { it.isHeld }?.release(PowerManager.RELEASE_FLAG_WAIT_FOR_NO_PROXIMITY)
            proximity = null
            listenNear(false)
        }
    }

    /**
     * Phone at the ear (sensor). Some phones don't switch the screen off for the wake lock above
     * (or the system declines it): the call screen then goes fully black and ignores touches while
     * this is true, so a cheek can't hang up or mute.
     */
    val near = kotlinx.coroutines.flow.MutableStateFlow(false)
    private var nearListener: android.hardware.SensorEventListener? = null
    private fun listenNear(on: Boolean) {
        val sm = context.getSystemService(android.hardware.SensorManager::class.java) ?: return
        if (!on) { nearListener?.let(sm::unregisterListener); nearListener = null; near.value = false; return }
        if (nearListener != null) return
        val sensor = sm.getDefaultSensor(android.hardware.Sensor.TYPE_PROXIMITY) ?: return
        val l = object : android.hardware.SensorEventListener {
            override fun onSensorChanged(e: android.hardware.SensorEvent) {
                near.value = e.values[0] < minOf(5f, sensor.maximumRange)
            }
            override fun onAccuracyChanged(s: android.hardware.Sensor?, a: Int) = Unit
        }
        if (sm.registerListener(l, sensor, android.hardware.SensorManager.SENSOR_DELAY_NORMAL)) nearListener = l
    }
}
