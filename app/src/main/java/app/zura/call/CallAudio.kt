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
    private var ringtone: Ringtone? = null
    private var ringback: ToneGenerator? = null
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

    fun setSpeaker(on: Boolean) {
        if (Build.VERSION.SDK_INT >= 31) {
            val type = if (on) AudioDeviceInfo.TYPE_BUILTIN_SPEAKER else AudioDeviceInfo.TYPE_BUILTIN_EARPIECE
            // A connected Bluetooth / wired headset wins over the earpiece automatically.
            val headset = am.availableCommunicationDevices.firstOrNull {
                it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO || it.type == AudioDeviceInfo.TYPE_WIRED_HEADSET ||
                    it.type == AudioDeviceInfo.TYPE_BLE_HEADSET || it.type == AudioDeviceInfo.TYPE_USB_HEADSET
            }
            val target = if (!on && headset != null) headset else am.availableCommunicationDevices.firstOrNull { it.type == type }
            target?.let { am.setCommunicationDevice(it) }
        } else {
            @Suppress("DEPRECATION")
            am.isSpeakerphoneOn = on
        }
    }

    fun stopCallMode() {
        if (Build.VERSION.SDK_INT >= 31) am.clearCommunicationDevice() else @Suppress("DEPRECATION") run { am.isSpeakerphoneOn = false }
        am.mode = AudioManager.MODE_NORMAL
        if (Build.VERSION.SDK_INT >= 26) focus?.let { am.abandonAudioFocusRequest(it) }
        focus = null
        proximity(false)
    }

    fun startRingtone() {
        stopTones()
        if (am.ringerMode == AudioManager.RINGER_MODE_NORMAL) {
            ringtone = RingtoneManager.getRingtone(context, RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE))?.apply {
                if (Build.VERSION.SDK_INT >= 28) isLooping = true
                play()
            }
        }
        if (am.ringerMode != AudioManager.RINGER_MODE_SILENT && Build.VERSION.SDK_INT >= 26) {
            vibrator?.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 700, 900), 0))
        }
    }

    /** "Ringing…" tone the caller hears while waiting. */
    fun startRingback() {
        stopTones()
        ringback = runCatching { ToneGenerator(AudioManager.STREAM_VOICE_CALL, 70).apply { startTone(ToneGenerator.TONE_SUP_RINGTONE) } }.getOrNull()
    }

    fun beepEnd() {
        runCatching { ToneGenerator(AudioManager.STREAM_VOICE_CALL, 60).startTone(ToneGenerator.TONE_PROP_PROMPT, 250) }
    }

    fun stopTones() {
        ringtone?.stop(); ringtone = null
        ringback?.run { stopTone(); release() }; ringback = null
        vibrator?.cancel()
    }

    /** Voice calls on the earpiece: screen turns off at the ear, like a phone call. */
    fun proximity(on: Boolean) {
        if (on) {
            if (proximity?.isHeld == true) return
            val pm = context.getSystemService(PowerManager::class.java)
            if (pm.isWakeLockLevelSupported(PowerManager.PROXIMITY_SCREEN_OFF_WAKE_LOCK)) {
                proximity = pm.newWakeLock(PowerManager.PROXIMITY_SCREEN_OFF_WAKE_LOCK, "zura:call").apply { acquire(4 * 60 * 60 * 1000L) }
            }
        } else {
            proximity?.takeIf { it.isHeld }?.release(PowerManager.RELEASE_FLAG_WAIT_FOR_NO_PROXIMITY)
            proximity = null
        }
    }
}
