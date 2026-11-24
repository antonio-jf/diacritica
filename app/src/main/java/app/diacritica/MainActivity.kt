package app.diacritica

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.runtime.mutableStateOf
import app.diacritica.ui.AppRoot
import app.diacritica.ui.DictionaryViewModel
import app.diacritica.ui.SettingsViewModel
import app.diacritica.ui.appViewModelFactory

class MainActivity : ComponentActivity() {
    private val container get() = (application as DiacriticaApp).container
    private val settingsVm: SettingsViewModel by viewModels { appViewModelFactory(container) }
    private val dictVm: DictionaryViewModel by viewModels { appViewModelFactory(container) }
    private val reviewVm: app.diacritica.ui.review.ReviewViewModel by viewModels { appViewModelFactory(container) }
    private val rehearseVm: app.diacritica.ui.rehearse.RehearseViewModel by viewModels { appViewModelFactory(container) }
    private val readerVm: app.diacritica.ui.reader.ReaderViewModel by viewModels { appViewModelFactory(container) }
    private val homeVm: app.diacritica.ui.home.HomeViewModel by viewModels { appViewModelFactory(container) }
    private val translateVm: app.diacritica.ui.translate.TranslateViewModel by viewModels { appViewModelFactory(container) }
    private val testsVm: app.diacritica.ui.tests.TestsViewModel by viewModels { appViewModelFactory(container) }
    private val grammarVm: app.diacritica.ui.grammar.GrammarViewModel by viewModels { appViewModelFactory(container) }
    private val booksVm: app.diacritica.ui.books.BooksViewModel by viewModels { appViewModelFactory(container) }
    private val vocabVm: app.diacritica.ui.vocab.VocabularyViewModel by viewModels { appViewModelFactory(container) }
    private val voiceVm: app.diacritica.voice.VoiceDrillViewModel by viewModels { appViewModelFactory(container) }
    private val progressVm: app.diacritica.ui.progress.ProgressViewModel by viewModels { appViewModelFactory(container) }
    private val packsVm: app.diacritica.ui.packs.DataPacksViewModel by viewModels { appViewModelFactory(container) }

    /** Text from "Look up in Diacritica" (the text-selection menu in other apps). */
    private val sharedText = mutableStateOf<String?>(null)
    /** Lesson of the day opened from its notification. */
    private val openDaily = mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        sharedText.value = intent.processText()
        openDaily.value = intent.getStringExtra(app.diacritica.grammar.DailyNotifier.EXTRA_TOPIC)
        // Looks for a newer release on GitHub at most once a day (and resumes an update download).
        container.updater.check()
        setContent { AppRoot(settingsVm, dictVm, reviewVm, rehearseVm, readerVm, homeVm, translateVm, progressVm, packsVm, testsVm, grammarVm, booksVm, vocabVm, voiceVm, container.undo, sharedText.value, openDaily.value, onDailyOpened = { openDaily.value = null }) }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        intent.processText()?.let { sharedText.value = it }
        intent.getStringExtra(app.diacritica.grammar.DailyNotifier.EXTRA_TOPIC)?.let { openDaily.value = it }
    }

    private fun Intent.processText(): String? =
        if (action == Intent.ACTION_PROCESS_TEXT) getCharSequenceExtra(Intent.EXTRA_PROCESS_TEXT)?.toString() else null
}
