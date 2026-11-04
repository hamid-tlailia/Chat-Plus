package app.zura.data

import android.content.SharedPreferences
import app.zura.ui.theme.BubbleStyle
import app.zura.ui.theme.ZuraThemeId
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class SettingsStore(private val prefs: SharedPreferences) {

    private val _theme = MutableStateFlow(
        runCatching { ZuraThemeId.valueOf(prefs.getString(K_THEME, null)!!) }.getOrDefault(ZuraThemeId.MIDNIGHT),
    )
    val theme: StateFlow<ZuraThemeId> = _theme.asStateFlow()

    fun setTheme(id: ZuraThemeId) {
        setUseCustom(false)
        _theme.value = id
        prefs.edit().putString(K_THEME, id.name).apply()
    }

    private val _bubbles = MutableStateFlow(
        runCatching { BubbleStyle.valueOf(prefs.getString(K_BUBBLES, null)!!) }.getOrDefault(BubbleStyle.THEME),
    )
    val bubbles: StateFlow<BubbleStyle> = _bubbles.asStateFlow()

    fun setBubbles(b: BubbleStyle) {
        _bubbles.value = b
        prefs.edit().putString(K_BUBBLES, b.name).apply()
    }

    /** Block screenshots/screen recording and hide content in the app switcher. */
    private val _secureScreen = MutableStateFlow(prefs.getBoolean(K_SECURE, false))
    val secureScreen: StateFlow<Boolean> = _secureScreen.asStateFlow()

    fun setSecureScreen(on: Boolean) {
        _secureScreen.value = on
        prefs.edit().putBoolean(K_SECURE, on).apply()
    }

    /** The Theme Studio theme (null = none designed yet) and whether it's the active one. */
    private val _custom = MutableStateFlow(
        prefs.getString(K_CUSTOM, null)?.let { runCatching { app.zura.ui.theme.CustomTheme.json.decodeFromString(app.zura.ui.theme.CustomTheme.serializer(), it) }.getOrNull() },
    )
    val customTheme: StateFlow<app.zura.ui.theme.CustomTheme?> = _custom.asStateFlow()
    private val _useCustom = MutableStateFlow(prefs.getBoolean(K_USE_CUSTOM, false))
    val useCustom: StateFlow<Boolean> = _useCustom.asStateFlow()
    /** Bumped when the custom font / wallpaper files change. */
    val themeFiles = MutableStateFlow(0)

    fun saveCustom(t: app.zura.ui.theme.CustomTheme, apply: Boolean = true) {
        _custom.value = t
        prefs.edit().putString(K_CUSTOM, app.zura.ui.theme.CustomTheme.json.encodeToString(app.zura.ui.theme.CustomTheme.serializer(), t)).apply()
        if (apply) setUseCustom(true)
    }

    fun setUseCustom(on: Boolean) { _useCustom.value = on; prefs.edit().putBoolean(K_USE_CUSTOM, on).apply() }

    /** Set while a chat with disappearing messages is open: blocks screenshots there only. */
    val chatSecure = MutableStateFlow(false)

    private companion object {
        const val K_THEME = "theme"
        const val K_SECURE = "secure_screen"
        const val K_BUBBLES = "bubbles"
        const val K_CUSTOM = "custom_theme"
        const val K_USE_CUSTOM = "use_custom_theme"
    }
}
