package app.diacritica.ui.packs

import android.content.Context
import android.net.ConnectivityManager
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.diacritica.AppContainer
import app.diacritica.data.DictionaryInfo
import app.diacritica.data.Lang
import app.diacritica.packs.PackInfo
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class PackProgress(val done: Long, val total: Long) {
    val fraction: Float get() = if (total <= 0) 0f else (done.toFloat() / total).coerceIn(0f, 1f)
}

data class DataPacksUiState(
    val catalogLoading: Boolean = true,
    val catalog: List<PackInfo> = emptyList(),
    val catalogError: String? = null,
    val installed: Map<String, String> = emptyMap(),
    val progress: Map<String, PackProgress> = emptyMap(),
    val packErrors: Map<String, String> = emptyMap(),
    val dictionary: DictionaryInfo? = null,
    /** "bundled", "import" or "pack:<version>". */
    val dictionarySource: String = "bundled",
    val fetchedWords: Int = 0,
    val onlineLookup: Boolean = true,
    val tatoeba: Int = 0,
    val tatoebaFromPack: Boolean = false,
    val corpora: Map<String, Int> = emptyMap(),
    val corporaFromPack: Boolean = false,
    val books: Int = 0,
    val booksFromPack: Boolean = false,
    val models: Set<Lang> = emptySet(),
    val modelBusy: Set<Lang> = emptySet(),
    val metered: Boolean = false,
    val message: String? = null,
)

class DataPacksViewModel(private val c: AppContainer, private val context: Context) : ViewModel() {
    private val _state = MutableStateFlow(DataPacksUiState())
    val state: StateFlow<DataPacksUiState> = _state.asStateFlow()
    private val jobs = HashMap<String, Job>()

    fun refresh() {
        viewModelScope.launch { loadLocal() }
        loadCatalog()
    }

    private suspend fun loadLocal() {
        val s = c.settings.settings.first()
        val cm = context.getSystemService(ConnectivityManager::class.java)
        _state.update {
            it.copy(
                dictionary = runCatching { c.dictionaryDb.info() }.getOrNull(),
                dictionarySource = c.dictionaryDb.source(),
                fetchedWords = c.dictionaryDb.fetchedCount(),
                onlineLookup = s.onlineLookup,
                tatoeba = runCatching { c.sentences.countsBySource().values.sum() }.getOrDefault(0),
                tatoebaFromPack = c.sentences.packFile.exists(),
                corpora = runCatching { c.corpora.countsBySource() }.getOrDefault(emptyMap()),
                corporaFromPack = c.corpora.packFile.exists(),
                books = runCatching { c.books.count() }.getOrDefault(0),
                booksFromPack = c.books.packFile.exists(),
                models = c.translator.downloaded() + Lang.EN,
                installed = c.packs.installed(),
                metered = cm?.isActiveNetworkMetered == true,
            )
        }
    }

    fun loadCatalog() {
        _state.update { it.copy(catalogLoading = true, catalogError = null) }
        viewModelScope.launch {
            try {
                val cat = c.packs.catalog()
                _state.update { it.copy(catalogLoading = false, catalog = cat.packs) }
            } catch (e: Exception) {
                val msg = if (!c.online.isOnline()) "You're offline. Connect to see the packs you can download."
                else e.message ?: "Couldn't load the list of packs."
                _state.update { it.copy(catalogLoading = false, catalogError = msg) }
            }
        }
    }

    fun install(p: PackInfo) {
        if (jobs[p.id]?.isActive == true) return
        _state.update { it.copy(progress = it.progress + (p.id to PackProgress(0, p.bytes)), packErrors = it.packErrors - p.id) }
        jobs[p.id] = viewModelScope.launch {
            try {
                c.packs.install(p) { done, total -> _state.update { st -> st.copy(progress = st.progress + (p.id to PackProgress(done, total))) } }
                _state.update { it.copy(message = "${p.title} installed.") }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(packErrors = it.packErrors + (p.id to (e.message ?: "Download failed."))) }
            } finally {
                _state.update { it.copy(progress = it.progress - p.id) }
                loadLocal()
            }
        }
    }

    fun cancel(p: PackInfo) { jobs[p.id]?.cancel() }

    fun remove(p: PackInfo) {
        viewModelScope.launch {
            runCatching { c.packs.remove(p) }
            _state.update { it.copy(message = "${p.title} removed.") }
            loadLocal()
        }
    }

    fun downloadModel(l: Lang) = modelOp(l) {
        if (!c.online.isOnline()) throw IllegalStateException("You're offline.")
        c.translator.downloadModel(l)
    }

    fun deleteModel(l: Lang) = modelOp(l) { c.translator.deleteModel(l) }

    private fun modelOp(l: Lang, op: suspend () -> Unit) {
        _state.update { it.copy(modelBusy = it.modelBusy + l) }
        viewModelScope.launch {
            val err = runCatching { op() }.exceptionOrNull()
            _state.update { it.copy(modelBusy = it.modelBusy - l, message = err?.let { e -> "${l.englishName}: ${e.message ?: "failed"}" }) }
            loadLocal()
        }
    }

    fun setOnlineLookup(v: Boolean) { viewModelScope.launch { c.settings.setOnlineLookup(v); loadLocal() } }

    fun importDictionary(uri: Uri) {
        viewModelScope.launch {
            _state.update { it.copy(message = "Importing…") }
            val err = c.dictionaryDb.import(uri)
            _state.update { it.copy(message = err ?: "Dictionary imported.") }
            loadLocal()
        }
    }

    fun resetDictionary() {
        viewModelScope.launch {
            c.dictionaryDb.resetToBundled()
            _state.update { it.copy(message = "Using the dictionary that ships with the app.") }
            loadLocal()
        }
    }

    fun openVoiceSettings() = c.speaker.openVoiceSettings()
    fun clearMessage() = _state.update { it.copy(message = null) }
}
