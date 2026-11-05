package app.zura.call

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.Ringtone
import android.media.RingtoneManager
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager

/**
 * The ONE thing that rings for an incoming call, whatever state the app is in.
 *
 * It used to be split: with the app in the background the notification channel rang, in the
 * foreground the app played the ringtone. A call that arrived while the app was closed and the
 * call screen then came up (or the app came to the front) switched the notification to the
 * silent channel — which silences the channel's sound — while the app's own ringtone had been
 * skipped because the app was in the background when the call arrived: no ring at all.
 * Now the call notification is always silent and this rings from the first moment (the
 * provisional ring of a push) until answered / declined / ended.
 */
object Ringer {
    private var ringtone: Ringtone? = null
    private var vibrating = false
    private var vibrator: Vibrator? = null

    val isRinging: Boolean @Synchronized get() = ringtone?.isPlaying == true || vibrating

    @Synchronized fun start(context: Context, peerId: String? = null) {
        if (isRinging) return // never stack a second ringtone
        val c = context.applicationContext
        val am = c.getSystemService(AudioManager::class.java) ?: return
        val sounds = runCatching { (c as app.zura.ZuraApp).graph.sounds }.getOrNull()
        val uri = sounds?.soundUri(sounds.effectiveRingtone(peerId), RingtoneManager.TYPE_RINGTONE)
            ?: if (sounds == null) RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE) else null
        val vibe = sounds?.effectiveRingVibe(peerId)
        if (am.ringerMode == AudioManager.RINGER_MODE_NORMAL && uri != null) {
            ringtone = runCatching {
                RingtoneManager.getRingtone(c, uri)?.apply {
                    audioAttributes = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_NOTIFICATION_RINGTONE)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build()
                    if (Build.VERSION.SDK_INT >= 28) isLooping = true
                    play()
                }
            }.onFailure { app.zura.DiagLog.record("ringtone", it) }.getOrNull()
        }
        if (am.ringerMode != AudioManager.RINGER_MODE_SILENT && Build.VERSION.SDK_INT >= 26 && vibe != app.zura.data.Vibe.NONE) {
            val v = vibrator ?: (if (Build.VERSION.SDK_INT >= 31) c.getSystemService(VibratorManager::class.java)?.defaultVibrator
            else @Suppress("DEPRECATION") c.getSystemService(Vibrator::class.java)).also { vibrator = it }
            runCatching {
                v?.vibrate(VibrationEffect.createWaveform(vibe?.pattern?.takeIf { it.size > 1 } ?: longArrayOf(0, 700, 900), 0))
                vibrating = v != null
            }
        }
        app.zura.DiagLog.record("call", detail = "ringing (sound=${ringtone != null}, vibrate=$vibrating, ringer=${am.ringerMode})")
    }

    @Synchronized fun stop() {
        runCatching { ringtone?.stop() }
        ringtone = null
        if (vibrating) runCatching { vibrator?.cancel() }
        vibrating = false
    }
}
