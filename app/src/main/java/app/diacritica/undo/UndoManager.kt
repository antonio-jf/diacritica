package app.diacritica.undo

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** "Removed …" with an Undo button, shown for a few seconds after a deletion. */
data class UndoOffer(val id: Long, val message: String)

/**
 * App-wide Undo for deletions: whoever deletes something calls [offer] with a way to put it back.
 * One offer at a time; a new deletion replaces the previous offer (which then can't be undone).
 */
class UndoManager(private val visibleMs: Long = 6_000) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val _offer = MutableStateFlow<UndoOffer?>(null)
    val offer: StateFlow<UndoOffer?> = _offer.asStateFlow()
    private var restore: (suspend () -> Unit)? = null
    private var timer: Job? = null
    private var next = 1L

    fun offer(message: String, restore: suspend () -> Unit) {
        val o = UndoOffer(next++, message)
        this.restore = restore
        _offer.value = o
        timer?.cancel()
        timer = scope.launch {
            delay(visibleMs)
            if (_offer.value?.id == o.id) dismiss()
        }
    }

    fun undo() {
        val r = restore ?: return
        dismiss()
        scope.launch { runCatching { r() } }
    }

    fun dismiss() {
        _offer.value = null
        restore = null
        timer?.cancel()
    }
}
