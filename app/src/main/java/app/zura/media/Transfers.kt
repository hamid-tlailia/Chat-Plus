package app.zura.media

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * Progress of media being sent, by message id — compressing (videos) then uploading — so the
 * bubble shows a real percentage instead of an endless spinner.
 */
object Transfers {
    data class Progress(val compressing: Boolean, val fraction: Float)

    private val _progress = MutableStateFlow<Map<String, Progress>>(emptyMap())
    val progress: StateFlow<Map<String, Progress>> = _progress.asStateFlow()

    fun set(messageId: String, compressing: Boolean, fraction: Float) {
        val f = fraction.coerceIn(0f, 1f)
        val cur = _progress.value[messageId]
        // ~1 % steps: smooth enough, without recomposing on every 64 KB.
        if (cur != null && cur.compressing == compressing && f - cur.fraction < 0.01f && f < 1f) return
        _progress.update { it + (messageId to Progress(compressing, f)) }
    }

    fun clear(messageId: String) = _progress.update { it - messageId }
}
