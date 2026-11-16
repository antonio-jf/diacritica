package app.diacritica.tests

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.io.File

/** Test attempts (drafts, pending and reviewed) in files/tests/attempts.json. Small enough to keep in memory. */
class TestStore(private val context: Context) {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = false }
    private val serializer = ListSerializer(TestAttempt.serializer())
    private val lock = Mutex()
    private val _attempts = MutableStateFlow<List<TestAttempt>>(emptyList())
    val attempts: StateFlow<List<TestAttempt>> = _attempts.asStateFlow()
    private var loaded = false

    val file: File get() = File(context.filesDir, "tests/attempts.json")

    /** The bundled test sets. */
    val tests: List<TestSet> by lazy {
        runCatching { TestBank.parse(context.assets.open("tests/tests.json").bufferedReader().use { it.readText() }) }.getOrDefault(emptyList())
    }

    fun test(id: String): TestSet? = tests.firstOrNull { it.id == id } ?: generated.value.firstOrNull { it.id == id }

    // ---------------------------------------------------------------- tests written on demand

    val generatedFile: File get() = File(context.filesDir, "tests/generated.json")
    private val setSerializer = ListSerializer(TestSet.serializer())
    private val _generated = MutableStateFlow<List<TestSet>>(emptyList())
    /** Tests Gemini wrote for you, newest first. */
    val generated: StateFlow<List<TestSet>> = _generated.asStateFlow()

    private suspend fun loadGenerated() = withContext(Dispatchers.IO) {
        if (_generated.value.isEmpty() && generatedFile.exists()) {
            _generated.value = runCatching { json.decodeFromString(setSerializer, generatedFile.readText()) }.getOrDefault(emptyList())
        }
    }

    suspend fun addGenerated(t: TestSet) = lock.withLock {
        loadGenerated()
        _generated.update { listOf(t) + it.filter { x -> x.id != t.id } }
        writeGenerated()
    }

    suspend fun deleteGenerated(id: String) = lock.withLock {
        loadGenerated()
        _generated.update { it.filter { x -> x.id != id } }
        writeGenerated()
    }

    private suspend fun writeGenerated() = withContext(Dispatchers.IO) {
        generatedFile.parentFile?.mkdirs()
        val tmp = File(generatedFile.path + ".tmp")
        tmp.writeText(json.encodeToString(setSerializer, _generated.value))
        tmp.renameTo(generatedFile)
    }

    suspend fun load() = lock.withLock { ensureLoaded(); loadGenerated() }

    private suspend fun ensureLoaded() {
        if (loaded) return
        val fromFile = withContext(Dispatchers.IO) {
            runCatching { if (file.exists()) json.decodeFromString(serializer, file.readText()) else emptyList() }.getOrDefault(emptyList())
        }
        // Anything changed in memory before the file was read wins.
        _attempts.update { mem -> sorted(fromFile.filter { f -> mem.none { it.id == f.id } } + mem) }
        loaded = true
    }

    private fun sorted(list: List<TestAttempt>) = list.sortedByDescending { it.submittedAt ?: it.startedAt }

    fun get(id: Long): TestAttempt? = _attempts.value.firstOrNull { it.id == id }

    /** Updates memory at once (so fast typing never reads a stale copy), then writes the file. */
    suspend fun save(a: TestAttempt) {
        _attempts.update { list -> sorted(list.filter { it.id != a.id } + a) }
        lock.withLock { ensureLoaded(); write() }
    }

    /** Changes one attempt in memory right away; returns the new value. Call [save] or [persist] to write it. */
    fun edit(id: Long, change: (TestAttempt) -> TestAttempt): TestAttempt? {
        var out: TestAttempt? = null
        _attempts.update { list -> list.map { if (it.id == id) change(it).also { n -> out = n } else it } }
        return out
    }

    suspend fun persist() = lock.withLock { ensureLoaded(); write() }

    suspend fun delete(id: Long) {
        _attempts.update { list -> list.filter { it.id != id } }
        lock.withLock { ensureLoaded(); write() }
    }

    /** Re-reads the file (after a restore from backup). */
    suspend fun reload() = lock.withLock { loaded = false; ensureLoaded() }

    private suspend fun write() = withContext(Dispatchers.IO) {
        file.parentFile?.mkdirs()
        val tmp = File(file.path + ".tmp")
        tmp.writeText(json.encodeToString(serializer, _attempts.value))
        tmp.renameTo(file)
    }
}
