package app.diacritica.handwriting

import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.common.model.RemoteModelManager
import com.google.mlkit.vision.digitalink.DigitalInkRecognition
import com.google.mlkit.vision.digitalink.DigitalInkRecognitionModel
import com.google.mlkit.vision.digitalink.DigitalInkRecognitionModelIdentifier
import com.google.mlkit.vision.digitalink.DigitalInkRecognizer
import com.google.mlkit.vision.digitalink.DigitalInkRecognizerOptions
import com.google.mlkit.vision.digitalink.Ink
import com.google.mlkit.vision.digitalink.RecognitionContext
import com.google.mlkit.vision.digitalink.WritingArea
import kotlinx.coroutines.tasks.await

/** One point of a pen stroke, in pixels, with its time in milliseconds. */
data class InkPoint(val x: Float, val y: Float, val t: Long)

/**
 * Mandarin handwriting recognition with ML Kit Digital Ink (simplified characters, zh-Hani-CN).
 * The model (~20 MB) downloads once on first use; recognition then works offline.
 */
class HandwritingRecognizer {
    private val models by lazy { RemoteModelManager.getInstance() }
    private val model: DigitalInkRecognitionModel by lazy {
        val id = DigitalInkRecognitionModelIdentifier.fromLanguageTag(LANGUAGE_TAG)
            ?: error("Handwriting model $LANGUAGE_TAG is not available")
        DigitalInkRecognitionModel.builder(id).build()
    }
    private var recognizer: DigitalInkRecognizer? = null

    suspend fun isDownloaded(): Boolean = runCatching { models.isModelDownloaded(model).await() }.getOrDefault(false)

    suspend fun download() {
        models.download(model, DownloadConditions.Builder().build()).await()
    }

    suspend fun delete() {
        recognizer?.close(); recognizer = null
        models.deleteDownloadedModel(model).await()
    }

    /**
     * Candidate characters or words for [strokes], best first. [before] is the text already typed,
     * which helps the recognizer choose between similar characters.
     */
    suspend fun recognize(strokes: List<List<InkPoint>>, width: Float, height: Float, before: String = ""): List<String> {
        if (strokes.none { it.isNotEmpty() }) return emptyList()
        if (!isDownloaded()) download()
        val r = recognizer ?: DigitalInkRecognition.getClient(DigitalInkRecognizerOptions.builder(model).build()).also { recognizer = it }
        val ink = Ink.builder().apply {
            strokes.filter { it.isNotEmpty() }.forEach { s ->
                addStroke(Ink.Stroke.builder().apply { s.forEach { p -> addPoint(Ink.Point.create(p.x, p.y, p.t)) } }.build())
            }
        }.build()
        val ctx = RecognitionContext.builder().setPreContext(before.takeLast(20)).setWritingArea(WritingArea(width, height)).build()
        return r.recognize(ink, ctx).await().candidates.map { it.text }.filter { it.isNotBlank() }.distinct().take(MAX_CANDIDATES)
    }

    companion object {
        const val LANGUAGE_TAG = "zh-Hani-CN"
        const val MAX_CANDIDATES = 8
    }
}
