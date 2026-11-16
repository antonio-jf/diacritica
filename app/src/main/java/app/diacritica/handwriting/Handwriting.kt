package app.diacritica.handwriting

/** One recognizer for the whole app (the model stays loaded between uses). */
object Handwriting {
    val recognizer: HandwritingRecognizer by lazy { HandwritingRecognizer() }
}
