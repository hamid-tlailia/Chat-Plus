package app.zura.data

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.SharedPreferences
import android.media.AudioAttributes
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import app.zura.R
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.security.MessageDigest

/** Vibration patterns offered for messages and calls. */
enum class Vibe(val pattern: LongArray?) {
    DEFAULT(null),
    NONE(longArrayOf(0)),
    SHORT(longArrayOf(0, 120)),
    DOUBLE(longArrayOf(0, 90, 110, 90)),
    LONG(longArrayOf(0, 500, 200, 500)),
    HEARTBEAT(longArrayOf(0, 100, 120, 100, 600, 100, 120, 100)),
}

/**
 * Notification tone, call ringtone and vibration — for the whole app and per chat / person.
 *
 * A tone value is a sound URI, "" for silent, or null for "default" (follow the global choice,
 * which itself defaults to the phone's). Android fixes a channel's sound when it is created, so
 * every distinct tone+vibration combination gets its own channel id derived from it.
 */
class Sounds(private val prefs: SharedPreferences) {
    private val _version = MutableStateFlow(0)
    /** Bumps on every change, for UI refresh. */
    val version: StateFlow<Int> = _version.asStateFlow()

    private val _haptics = MutableStateFlow(prefs.getBoolean("haptics", true))
    val haptics: StateFlow<Boolean> = _haptics.asStateFlow()
    fun setHaptics(on: Boolean) { _haptics.value = on; prefs.edit().putBoolean("haptics", on).apply() }

    fun messageTone(chatId: String?): String? = prefs.getString(key("tone", chatId), null)
    fun vibe(chatId: String?): Vibe = prefs.getString(key("vibe", chatId), null)?.let { runCatching { Vibe.valueOf(it) }.getOrNull() } ?: Vibe.DEFAULT
    fun ringtone(peerId: String?): String? = prefs.getString(key("ring", peerId), null)
    fun ringVibe(peerId: String?): Vibe = prefs.getString(key("ringvibe", peerId), null)?.let { runCatching { Vibe.valueOf(it) }.getOrNull() } ?: Vibe.DEFAULT

    fun setMessageTone(chatId: String?, v: String?) = put(key("tone", chatId), v)
    fun setVibe(chatId: String?, v: Vibe) = put(key("vibe", chatId), v.name.takeIf { v != Vibe.DEFAULT })
    fun setRingtone(peerId: String?, v: String?) = put(key("ring", peerId), v)
    fun setRingVibe(peerId: String?, v: Vibe) = put(key("ringvibe", peerId), v.name.takeIf { v != Vibe.DEFAULT })

    /** What actually applies: chat override → global → phone default. */
    fun effectiveTone(chatId: String?): String? = messageTone(chatId) ?: messageTone(null)
    fun effectiveVibe(chatId: String?): Vibe = vibe(chatId).takeIf { it != Vibe.DEFAULT } ?: vibe(null)
    fun effectiveRingtone(peerId: String?): String? = ringtone(peerId) ?: ringtone(null)
    fun effectiveRingVibe(peerId: String?): Vibe = ringVibe(peerId).takeIf { it != Vibe.DEFAULT } ?: ringVibe(null)

    /** Notification channel for messages of [chatId] with its tone / vibration. */
    fun messageChannel(c: Context, chatId: String?, defaultId: String): String {
        val tone = effectiveTone(chatId)
        val vibe = effectiveVibe(chatId)
        if (Build.VERSION.SDK_INT < 26) return defaultId
        val nm = c.getSystemService(NotificationManager::class.java)
        if (tone == null && vibe == Vibe.DEFAULT) {
            nm.createNotificationChannel(NotificationChannel(defaultId, c.getString(R.string.messages_channel), NotificationManager.IMPORTANCE_HIGH))
            return defaultId
        }
        val id = "msg_" + hash("$tone|$vibe")
        if (nm.getNotificationChannel(id) == null) nm.createNotificationChannel(
            NotificationChannel(id, c.getString(R.string.messages_channel) + " · " + c.getString(R.string.custom), NotificationManager.IMPORTANCE_HIGH).apply {
                setSound(soundUri(tone, RingtoneManager.TYPE_NOTIFICATION), AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_NOTIFICATION_COMMUNICATION_INSTANT).build())
                vibe.pattern?.let { p -> if (vibe == Vibe.NONE) enableVibration(false) else { enableVibration(true); vibrationPattern = p } }
            },
        )
        return id
    }

    /** Channel that rings (app closed) for a call from [peerId]. */
    fun ringChannel(c: Context, peerId: String?, defaultId: String): String {
        val tone = effectiveRingtone(peerId)
        val vibe = effectiveRingVibe(peerId)
        if (Build.VERSION.SDK_INT < 26 || (tone == null && vibe == Vibe.DEFAULT)) return defaultId
        val nm = c.getSystemService(NotificationManager::class.java)
        val id = "ring_" + hash("$tone|$vibe")
        if (nm.getNotificationChannel(id) == null) nm.createNotificationChannel(
            NotificationChannel(id, c.getString(R.string.incoming_calls_ring) + " · " + c.getString(R.string.custom), NotificationManager.IMPORTANCE_HIGH).apply {
                setSound(
                    soundUri(tone, RingtoneManager.TYPE_RINGTONE),
                    AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_NOTIFICATION_RINGTONE).setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build(),
                )
                if (vibe == Vibe.NONE) enableVibration(false) else { enableVibration(true); vibrationPattern = (vibe.pattern ?: longArrayOf(0, 700, 900)) }
                lockscreenVisibility = android.app.Notification.VISIBILITY_PUBLIC
            },
        )
        return id
    }

    /** null → phone default of [type]; "" → silent. */
    fun soundUri(tone: String?, type: Int): Uri? = when {
        tone == null -> RingtoneManager.getDefaultUri(type)
        tone.isEmpty() -> null
        else -> Uri.parse(tone)
    }

    /** Human name of a tone for the settings rows. */
    fun toneName(c: Context, tone: String?, type: Int): String = when {
        tone == null -> c.getString(R.string.tone_default)
        tone.isEmpty() -> c.getString(R.string.tone_silent)
        else -> runCatching { RingtoneManager.getRingtone(c, Uri.parse(tone))?.getTitle(c) }.getOrNull() ?: c.getString(R.string.custom)
    }

    private fun key(kind: String, id: String?) = if (id == null) "g_$kind" else "${kind}_$id"
    private fun put(k: String, v: String?) {
        prefs.edit().apply { if (v == null) remove(k) else putString(k, v) }.apply()
        _version.value++
    }
    private fun hash(s: String) = MessageDigest.getInstance("SHA-256").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }.take(12)
}
