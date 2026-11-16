package app.diacritica.reader

import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import app.diacritica.data.Lang
import app.diacritica.tts.Speaker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** Which languages read-aloud speaks for each sentence. */
enum class ReadAloudMode { FIRST, SECOND, BOTH }

/** What is being read: [index] is the current sentence. */
data class ReadAloudState(val storyId: Long, val title: String, val index: Int, val total: Int)

/**
 * Reads a story aloud sentence by sentence until the end or until stopped. Lives at app level
 * (not in a screen) and runs a small foreground service while playing, so it continues with the
 * screen off; the notification has a Stop button.
 */
class ReadAloud(private val context: Context, private val speaker: Speaker) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var job: Job? = null
    private val _state = MutableStateFlow<ReadAloudState?>(null)
    val state: StateFlow<ReadAloudState?> = _state.asStateFlow()

    /**
     * Starts at sentence [from]. [sentences] holds each sentence by language; [langs] are spoken in
     * order for each one. [onSentence] runs as each sentence starts (to highlight it and save progress).
     */
    fun play(
        storyId: Long, title: String, sentences: List<Map<Lang, String>>, from: Int, langs: List<Lang>,
        rate: Float, pauseMs: Long = 500, onSentence: (Int) -> Unit,
    ) {
        stop()
        if (sentences.isEmpty() || langs.isEmpty()) return
        val start = from.coerceIn(0, sentences.lastIndex)
        _state.value = ReadAloudState(storyId, title, start, sentences.size)
        startService()
        job = scope.launch {
            try {
                for (i in start until sentences.size) {
                    _state.value = ReadAloudState(storyId, title, i, sentences.size)
                    onSentence(i)
                    for (l in langs) {
                        val text = sentences[i][l].orEmpty()
                        if (text.isBlank()) continue
                        // A false result means stopped (or no voice): end playback.
                        if (!speaker.speakAndWait(text, l, rate)) return@launch
                        delay(if (langs.size > 1 && l != langs.last()) pauseMs / 2 else pauseMs)
                    }
                }
            } finally {
                if (job == coroutineContext[Job]) finish()
            }
        }
    }

    fun stop() {
        job?.cancel()
        job = null
        speaker.stop()
        finish()
    }

    private fun finish() {
        _state.value = null
        context.stopService(Intent(context, ReadAloudService::class.java))
    }

    private fun startService() {
        runCatching { ContextCompat.startForegroundService(context, Intent(context, ReadAloudService::class.java)) }
    }
}
