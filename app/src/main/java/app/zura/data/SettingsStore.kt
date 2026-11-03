package app.zura.data

import android.content.SharedPreferences
import app.zura.ui.theme.ZuraThemeId
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.UUID

class SettingsStore(private val prefs: SharedPreferences) {

    val userId: String = prefs.getString(K_UID, null) ?: UUID.randomUUID().toString().replace("-", "").take(20)
        .also { prefs.edit().putString(K_UID, it).apply() }

    private val _theme = MutableStateFlow(
        runCatching { ZuraThemeId.valueOf(prefs.getString(K_THEME, null)!!) }.getOrDefault(ZuraThemeId.MIDNIGHT),
    )
    val theme: StateFlow<ZuraThemeId> = _theme.asStateFlow()

    fun setTheme(id: ZuraThemeId) {
        _theme.value = id
        prefs.edit().putString(K_THEME, id.name).apply()
    }

    var lastSeq: Long
        get() = prefs.getLong(K_SEQ, 0)
        set(v) = prefs.edit().putLong(K_SEQ, v).apply()

    private companion object {
        const val K_UID = "uid"
        const val K_THEME = "theme"
        const val K_SEQ = "seq"
    }
}
