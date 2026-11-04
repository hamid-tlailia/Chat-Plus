package app.zura.i18n

import android.content.Context
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.nl.languageid.LanguageIdentification
import com.google.mlkit.nl.languageid.LanguageIdentificationOptions
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.TranslatorOptions
import kotlinx.coroutines.tasks.await
import java.util.Locale

/**
 * Message translation that runs entirely on the phone (Google ML Kit). The first translation
 * from a given language downloads that language's model (~30 MB) once; after that it works
 * offline. No message text is sent anywhere.
 *
 * The user picks "from" (auto-detect or a language) and "to"; both are remembered.
 */
object Translator {
    sealed interface Result {
        data class Ok(val text: String, val from: String, val to: String, val detected: Boolean) : Result
        /** Already in the target language. */
        data class Same(val lang: String) : Result
        /** Couldn't tell the language (very short text) — the user should pick it. */
        data object Undetected : Result
        data class Failed(val message: String?) : Result
    }

    const val AUTO = "auto"

    private fun prefs(ctx: Context) = ctx.getSharedPreferences("zura_translate", Context.MODE_PRIVATE)
    fun from(ctx: Context): String = prefs(ctx).getString("from", AUTO) ?: AUTO
    fun to(ctx: Context): String = prefs(ctx).getString("to", null) ?: defaultTarget(ctx)
    fun setFrom(ctx: Context, lang: String) = prefs(ctx).edit().putString("from", lang).apply()
    fun setTo(ctx: Context, lang: String) = prefs(ctx).edit().putString("to", lang).apply()

    private fun defaultTarget(ctx: Context) = if (LocaleEngine.current(ctx) == LocaleEngine.Lang.AR) TranslateLanguage.ARABIC else TranslateLanguage.ENGLISH

    /** Every language the on-device translator supports, as (code, name in the app's language), sorted by name. */
    fun languages(ctx: Context): List<Pair<String, String>> {
        val ui = Locale(LocaleEngine.current(ctx).tag)
        return TranslateLanguage.getAllLanguages().map { it to name(it, ui) }.sortedBy { it.second }
    }

    fun name(code: String, ui: Locale): String = Locale(code).getDisplayLanguage(ui).replaceFirstChar { it.titlecase(ui) }
    fun name(ctx: Context, code: String) = name(code, Locale(LocaleEngine.current(ctx).tag))

    suspend fun translate(ctx: Context, text: String): Result = runCatching {
        val to = to(ctx)
        val chosen = from(ctx)
        val (from, detected) = if (chosen != AUTO) chosen to false else (detect(text) ?: return Result.Undetected) to true
        if (from == to) return Result.Same(from)
        val client = Translation.getClient(TranslatorOptions.Builder().setSourceLanguage(from).setTargetLanguage(to).build())
        try {
            client.downloadModelIfNeeded(DownloadConditions.Builder().build()).await()
            Result.Ok(client.translate(text).await(), from, to, detected)
        } finally {
            client.close()
        }
    }.getOrElse { Result.Failed(it.message) }

    /**
     * Language of [text]. Short words ("Hi") are hard for the model, so: a lenient threshold
     * first, then the alphabet as a hint (Arabic letters → Arabic, Latin → English…).
     */
    private suspend fun detect(text: String): String? {
        val id = LanguageIdentification.getClient(LanguageIdentificationOptions.Builder().setConfidenceThreshold(0.3f).build())
        val tag = runCatching { id.identifyLanguage(text).await() }.getOrNull().also { id.close() }
        tag?.takeIf { it != "und" }?.let { TranslateLanguage.fromLanguageTag(it) }?.let { return it }
        return byScript(text)
    }

    private fun byScript(text: String): String? {
        val letters = text.filter { it.isLetter() }.ifEmpty { return null }
        val block = Character.UnicodeBlock.of(letters.first())
        return when (block) {
            Character.UnicodeBlock.ARABIC, Character.UnicodeBlock.ARABIC_PRESENTATION_FORMS_A, Character.UnicodeBlock.ARABIC_PRESENTATION_FORMS_B -> TranslateLanguage.ARABIC
            Character.UnicodeBlock.BASIC_LATIN, Character.UnicodeBlock.LATIN_1_SUPPLEMENT -> TranslateLanguage.ENGLISH
            Character.UnicodeBlock.CYRILLIC -> TranslateLanguage.RUSSIAN
            Character.UnicodeBlock.GREEK -> TranslateLanguage.GREEK
            Character.UnicodeBlock.HEBREW -> TranslateLanguage.HEBREW
            Character.UnicodeBlock.DEVANAGARI -> TranslateLanguage.HINDI
            Character.UnicodeBlock.HANGUL_SYLLABLES -> TranslateLanguage.KOREAN
            Character.UnicodeBlock.HIRAGANA, Character.UnicodeBlock.KATAKANA -> TranslateLanguage.JAPANESE
            Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS -> TranslateLanguage.CHINESE
            Character.UnicodeBlock.THAI -> TranslateLanguage.THAI
            else -> null
        }
    }
}
