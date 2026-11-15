package app.diacritica.translate

import app.diacritica.data.Lang
import app.diacritica.data.TextNorm
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.common.model.RemoteModelManager
import com.google.mlkit.nl.languageid.LanguageIdentification
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.TranslateRemoteModel
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.Translator
import com.google.mlkit.nl.translate.TranslatorOptions
import kotlinx.coroutines.tasks.await

/**
 * Offline translation with ML Kit. Each language's model (~30 MB) downloads once, on first use;
 * after that translation works without internet. English is ML Kit's pivot, so its model is always present.
 */
class OnDeviceTranslator {
    private val translators = HashMap<Pair<Lang, Lang>, Translator>()
    private val identifier by lazy { LanguageIdentification.getClient() }
    private val models by lazy { RemoteModelManager.getInstance() }

    /** The language of [text] among the app's six, or null if unsure. */
    suspend fun detect(text: String): Lang? {
        if (TextNorm.containsHan(text)) return Lang.ZH
        val tag = runCatching { identifier.identifyLanguage(text).await() }.getOrNull() ?: return null
        return fromTag(tag)
    }

    /** Languages whose model is already on the phone. */
    suspend fun downloaded(): Set<Lang> = runCatching {
        models.getDownloadedModels(TranslateRemoteModel::class.java).await().mapNotNull { fromTag(it.language) }.toSet()
    }.getOrDefault(emptySet())

    /**
     * Translates [text] from [from] to [to]. [onDownload] is called first if a model must be downloaded.
     * Throws if a model can't be downloaded (e.g. offline the first time).
     */
    suspend fun translate(text: String, from: Lang, to: Lang, onDownload: (Lang) -> Unit = {}): String {
        if (from == to) return text
        val have = downloaded()
        listOf(from, to).filter { it != Lang.EN && it !in have }.forEach { onDownload(it) }
        val t = translators.getOrPut(from to to) {
            Translation.getClient(TranslatorOptions.Builder().setSourceLanguage(code(from)).setTargetLanguage(code(to)).build())
        }
        t.downloadModelIfNeeded(DownloadConditions.Builder().build()).await()
        return t.translate(text).await()
    }

    /** Downloads one language's model now (Data packs page). */
    suspend fun downloadModel(lang: Lang) {
        models.download(TranslateRemoteModel.Builder(code(lang)).build(), DownloadConditions.Builder().build()).await()
    }

    /** Deletes one language's model to free space; it downloads again on next use. English can't be removed. */
    suspend fun deleteModel(lang: Lang) {
        if (lang == Lang.EN) return
        translators.entries.removeAll { (k, t) -> (k.first == lang || k.second == lang).also { if (it) t.close() } }
        models.deleteDownloadedModel(TranslateRemoteModel.Builder(code(lang)).build()).await()
    }

    fun close() { translators.values.forEach { it.close() }; translators.clear() }

    companion object {
        fun code(l: Lang): String = when (l) {
            Lang.EN -> TranslateLanguage.ENGLISH
            Lang.FR -> TranslateLanguage.FRENCH
            Lang.DE -> TranslateLanguage.GERMAN
            Lang.IT -> TranslateLanguage.ITALIAN
            Lang.ES -> TranslateLanguage.SPANISH
            Lang.ZH -> TranslateLanguage.CHINESE
        }

        /** ML Kit/BCP-47 tags ("fr", "zh", "zh-Latn", "und") → the app's languages. */
        fun fromTag(tag: String): Lang? = when (tag.lowercase().substringBefore('-')) {
            "en" -> Lang.EN; "fr" -> Lang.FR; "de" -> Lang.DE; "it" -> Lang.IT; "es" -> Lang.ES; "zh" -> Lang.ZH
            else -> null
        }
    }
}
