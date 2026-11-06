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

    /** The app's look (backgrounds, text, cards). Chats keep their own bubble style. */
    fun setTheme(id: ZuraThemeId) {
        _theme.value = id
        prefs.edit().putString(K_THEME, id.name).apply()
        _effective.value = compute()
    }

    // ---------------------------------------------------------------- automatic day / night

    private fun themeOf(key: String, def: ZuraThemeId) = runCatching { ZuraThemeId.valueOf(prefs.getString(key, null)!!) }.getOrDefault(def)

    private val _auto = MutableStateFlow(prefs.getBoolean(K_AUTO, false))
    /** Switch between [dayTheme] and [nightTheme] at sunrise / sunset. */
    val autoTheme: StateFlow<Boolean> = _auto.asStateFlow()
    private val _day = MutableStateFlow(themeOf(K_DAY, ZuraThemeId.PEARL))
    val dayTheme: StateFlow<ZuraThemeId> = _day.asStateFlow()
    private val _night = MutableStateFlow(themeOf(K_NIGHT, ZuraThemeId.MIDNIGHT))
    val nightTheme: StateFlow<ZuraThemeId> = _night.asStateFlow()
    /** Is the sun up where the phone is? Kept current by the sun clock. */
    private val _daylight = MutableStateFlow(true)
    val daylight: StateFlow<Boolean> = _daylight.asStateFlow()

    fun setAutoTheme(on: Boolean) {
        if (on && !_auto.value) {
            // Start from what they use now: it becomes the day or the night theme.
            if (app.zura.ui.theme.ZuraPalettes.of(_theme.value).isDark) setNightTheme(_theme.value) else setDayTheme(_theme.value)
        }
        _auto.value = on; prefs.edit().putBoolean(K_AUTO, on).apply(); _effective.value = compute()
    }
    fun setDayTheme(id: ZuraThemeId) { _day.value = id; prefs.edit().putString(K_DAY, id.name).apply(); _effective.value = compute() }
    fun setNightTheme(id: ZuraThemeId) { _night.value = id; prefs.edit().putString(K_NIGHT, id.name).apply(); _effective.value = compute() }
    fun setDaylight(day: Boolean) { if (_daylight.value != day) { _daylight.value = day; _effective.value = compute() } }

    private fun compute() = if (_auto.value) (if (_daylight.value) _day.value else _night.value) else _theme.value
    private val _effective = MutableStateFlow(compute())
    /** The theme to draw with right now (manual choice, or day / night when automatic). */
    val effectiveTheme: StateFlow<ZuraThemeId> = _effective.asStateFlow()

    // ---------------------------------------------------------------- per-chat look

    private val _chatStyles = MutableStateFlow(
        prefs.all.filterKeys { it.startsWith(K_CHAT_STYLE) }.mapNotNull { (k, v) -> (v as? String)?.let { k.removePrefix(K_CHAT_STYLE) to it } }.toMap(),
    )
    /** chatId → bubble style name ([BubbleStyle] or [STYLE_CUSTOM]); chosen in the chat's profile. */
    val chatStyles: StateFlow<Map<String, String>> = _chatStyles.asStateFlow()

    /** For chats without their own choice: whatever the old app-wide picker was set to (nothing changes on update). */
    val defaultChatStyle: String get() = if (_useCustom.value) STYLE_CUSTOM else _bubbles.value.name

    fun setChatStyle(chatId: String, style: String?) {
        _chatStyles.value = if (style == null) _chatStyles.value - chatId else _chatStyles.value + (chatId to style)
        prefs.edit().apply { if (style == null) remove(K_CHAT_STYLE + chatId) else putString(K_CHAT_STYLE + chatId, style) }.apply()
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

    companion object {
        /** Chat style: the Theme Studio design (colours, roundness, font, wallpaper). */
        const val STYLE_CUSTOM = "CUSTOM"
        private const val K_CHAT_STYLE = "chat_style_"
        private const val K_AUTO = "theme_auto"
        private const val K_DAY = "theme_day"
        private const val K_NIGHT = "theme_night"
        private const val K_THEME = "theme"
        private const val K_SECURE = "secure_screen"
        private const val K_BUBBLES = "bubbles"
        private const val K_CUSTOM = "custom_theme"
        private const val K_USE_CUSTOM = "use_custom_theme"
    }
}
