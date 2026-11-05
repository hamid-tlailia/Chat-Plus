package app.zura.ui.chat

import android.content.Context
import app.zura.R
import app.zura.core.model.TTL_AFTER_READ
import app.zura.data.ChatRepository

/** Disappearing-message timers offered to the user (null = off). */
val DISAPPEAR_OPTIONS: List<Long?> = listOf(null, TTL_AFTER_READ, 3_600_000L, 86_400_000L, 7 * 86_400_000L)

fun ttlLabel(ctx: Context, ms: Long?): String = ctx.getString(
    when (ms) {
        null, 0L -> R.string.ttl_off
        TTL_AFTER_READ -> R.string.ttl_read
        3_600_000L -> R.string.ttl_1h
        86_400_000L -> R.string.ttl_24h
        else -> R.string.ttl_7d
    },
)

/** Text of a system line (screenshot, timer change) for the chat, the list and notifications. */
fun eventText(ctx: Context, code: String?, mine: Boolean): String {
    if (code == app.zura.core.storage.VaultStore.EVENT_HISTORY_RESET) return ctx.getString(R.string.history_reset_note)
    ChatRepository.parseTimer(code)?.let { ms ->
        return if (ms == 0L) ctx.getString(if (mine) R.string.timer_off_you else R.string.timer_off_them)
        else ctx.getString(if (mine) R.string.timer_set_you else R.string.timer_set_them, ttlLabel(ctx, ms))
    }
    return ctx.getString(if (mine) R.string.you_took_screenshot else R.string.they_took_screenshot)
}

fun eventIcon(code: String?): String = when {
    code == app.zura.core.storage.VaultStore.EVENT_HISTORY_RESET -> "ℹ️"
    ChatRepository.parseTimer(code) != null -> "⏱"
    else -> "📸"
}
