package app.zura.i18n

import android.app.Activity
import android.app.LocaleManager
import android.content.Context
import android.content.res.Configuration
import android.os.Build
import android.os.LocaleList
import java.util.Locale

/**
 * Bilingual engine (English ⇄ Arabic) without AppCompat.
 *
 * - API 33+: uses the platform per-app language (LocaleManager). The choice also appears in
 *   system Settings → Apps → Zura → Language (enabled by generateLocaleConfig in Gradle).
 * - API 26–32: persists the choice and wraps the base Context in attachBaseContext.
 *
 * Layout direction follows the locale automatically: Compose reads LocalLayoutDirection from
 * the Configuration, so every Row, padding(start/end), Arrangement and icon mirrors for Arabic.
 */
object LocaleEngine {

    enum class Lang(val tag: String, val rtl: Boolean) { EN("en", false), AR("ar", true) }

    private const val PREFS = "zura_locale"
    private const val KEY = "lang"

    fun current(context: Context): Lang {
        val tag = if (Build.VERSION.SDK_INT >= 33) {
            context.getSystemService(LocaleManager::class.java).applicationLocales.get(0)?.language
        } else {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, null)
        } ?: Locale.getDefault().language
        return if (tag == "ar") Lang.AR else Lang.EN
    }

    /** Call from Activity.attachBaseContext. No-op on 33+ where the platform does it. */
    fun wrap(base: Context): Context {
        if (Build.VERSION.SDK_INT >= 33) return base
        val lang = current(base)
        val locale = Locale.forLanguageTag(lang.tag)
        Locale.setDefault(locale)
        val config = Configuration(base.resources.configuration).apply {
            setLocale(locale)
            setLayoutDirection(locale)
        }
        return base.createConfigurationContext(config)
    }

    fun set(activity: Activity, lang: Lang) {
        if (Build.VERSION.SDK_INT >= 33) {
            // Platform recreates the activity with the new configuration.
            activity.getSystemService(LocaleManager::class.java).applicationLocales = LocaleList.forLanguageTags(lang.tag)
        } else {
            activity.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY, lang.tag).apply()
            activity.recreate()
        }
    }
}
