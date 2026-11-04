package app.zura.data

import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Stealth mode. What I reveal to contacts: read receipts, "typing…", online / last seen —
 * globally, plus a per-person "ghost" switch that hides all three from that one contact.
 *
 * Fair-play rule (as in other messengers): if I hide my read receipts or last seen from someone,
 * I don't see theirs either.
 */
class Privacy(private val prefs: SharedPreferences) {
    private val _readReceipts = MutableStateFlow(prefs.getBoolean(K_READ, true))
    val readReceipts: StateFlow<Boolean> = _readReceipts.asStateFlow()
    private val _typing = MutableStateFlow(prefs.getBoolean(K_TYPING, true))
    val typing: StateFlow<Boolean> = _typing.asStateFlow()
    private val _lastSeen = MutableStateFlow(prefs.getBoolean(K_SEEN, true))
    val lastSeen: StateFlow<Boolean> = _lastSeen.asStateFlow()
    private val _ghost = MutableStateFlow(prefs.getStringSet(K_GHOST, emptySet())!!.toSet())
    /** Contacts I'm invisible to (no receipts, typing or last seen). */
    val ghost: StateFlow<Set<String>> = _ghost.asStateFlow()

    fun setReadReceipts(on: Boolean) { _readReceipts.value = on; prefs.edit().putBoolean(K_READ, on).apply() }
    fun setTyping(on: Boolean) { _typing.value = on; prefs.edit().putBoolean(K_TYPING, on).apply() }
    fun setLastSeen(on: Boolean) { _lastSeen.value = on; prefs.edit().putBoolean(K_SEEN, on).apply() }
    fun setGhost(uid: String, on: Boolean) {
        _ghost.value = if (on) _ghost.value + uid else _ghost.value - uid
        prefs.edit().putStringSet(K_GHOST, _ghost.value).apply()
    }

    fun receiptsTo(uid: String) = _readReceipts.value && uid !in _ghost.value
    fun typingTo(uid: String) = _typing.value && uid !in _ghost.value
    fun lastSeenTo(uid: String) = _lastSeen.value && uid !in _ghost.value

    private companion object {
        const val K_READ = "priv_read"
        const val K_TYPING = "priv_typing"
        const val K_SEEN = "priv_seen"
        const val K_GHOST = "priv_ghost"
    }
}
