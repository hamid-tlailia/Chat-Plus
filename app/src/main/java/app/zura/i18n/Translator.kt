package app.zura.i18n

import android.content.Context
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.nl.languageid.LanguageIdentification
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.TranslatorOptions
import kotlinx.coroutines.tasks.await

/**
 * Message translation that runs entirely on the phone (Google ML Kit). The first translation
 * from a given language downloads that language's model (~30 MB) once; after that it works
 * offline. No message text is sent anywhere.
 */
object Translator {
    sealed interface Result {
        data class Ok(val text: String, val from: String) : Result
        /** Already in the reader's language. */
        data object Same : Result
        data object Unsupported : Result
        data class Failed(val message: String?) : Result
    }

    /** Target = the app's language (Arabic / English). */
    fun target(ctx: Context): String = if (LocaleEngine.current(ctx) == LocaleEngine.Lang.AR) TranslateLanguage.ARABIC else TranslateLanguage.ENGLISH

    suspend fun translate(ctx: Context, text: String, onDownloading: () -> Unit = {}): Result = runCatching {
        val to = target(ctx)
        val detected = LanguageIdentification.getClient().identifyLanguage(text).await()
        if (detected == "und") return Result.Unsupported
        val from = TranslateLanguage.fromLanguageTag(detected) ?: return Result.Unsupported
        if (from == to) return Result.Same
        val client = Translation.getClient(TranslatorOptions.Builder().setSourceLanguage(from).setTargetLanguage(to).build())
        try {
            onDownloading()
            client.downloadModelIfNeeded(DownloadConditions.Builder().build()).await()
            Result.Ok(client.translate(text).await(), from)
        } finally {
            client.close()
        }
    }.getOrElse { Result.Failed(it.message) }
}
