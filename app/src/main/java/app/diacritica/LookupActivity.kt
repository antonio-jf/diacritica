package app.diacritica

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import app.diacritica.ui.components.PolyButton
import app.diacritica.ui.components.WordLookup
import app.diacritica.ui.components.WordLookupSheet
import app.diacritica.ui.theme.DiacriticaTheme
import app.diacritica.ui.theme.rememberTokens

class LookupViewModel(c: AppContainer) : ViewModel() {
    val lookup = WordLookup(c, viewModelScope, source = "Other apps")
    val settings = c.settings.settings
}

/**
 * "Look up in Diacritica" from the text-selection menu of any app (Kindle, Chrome…): a word opens in the
 * word sheet on top of that app, without leaving it. Longer text (more than a short phrase) opens in
 * Diacritica's Translate section instead.
 */
class LookupActivity : ComponentActivity() {
    private val container get() = (application as DiacriticaApp).container
    private val vm: LookupViewModel by viewModels {
        viewModelFactory { initializer { LookupViewModel(container) } }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val text = intent.selectedText()
        if (text.isNullOrBlank()) { finish(); return }
        if (isLongText(text)) { openInApp(text); return }
        enableEdgeToEdge()
        if (savedInstanceState == null) vm.lookup.open(text, null)
        setContent {
            val s by vm.settings.collectAsStateWithLifecycle(initialValue = null)
            val settings = s ?: return@setContent
            val word by vm.lookup.word.collectAsStateWithLifecycle()
            // The sheet was swiped away or closed: back to the other app.
            var opened by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }
            LaunchedEffect(word) { if (word != null) opened = true else if (opened) finish() }
            val tokens = rememberTokens(settings.theme, settings.mode, settings.accent, settings.languageColors)
            DiacriticaTheme(tokens, settings.textScale, hanScale = settings.hanScale, systemBars = false) {
                Box(Modifier.fillMaxSize()) {
                    WordLookupSheet(
                        vm.lookup,
                        onOpenDictionary = { _, w -> openInApp(w) },
                        extraActions = {
                            if (word?.result?.isEmpty != false) PolyButton("Open Diacritica", { openInApp(text.trim()) }, primary = false)
                        },
                    )
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        val text = intent.selectedText() ?: return
        if (isLongText(text)) openInApp(text) else vm.lookup.open(text, null)
    }

    /** The same text in the full app (Dictionary for a word, Translate for longer text). */
    private fun openInApp(text: String) {
        startActivity(
            Intent(this, MainActivity::class.java)
                .setAction(Intent.ACTION_PROCESS_TEXT)
                .putExtra(Intent.EXTRA_PROCESS_TEXT, text)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP),
        )
        finish()
    }

    private fun Intent.selectedText(): String? =
        if (action == Intent.ACTION_PROCESS_TEXT) getCharSequenceExtra(Intent.EXTRA_PROCESS_TEXT)?.toString() else null

    companion object {
        /** Same rule as the main app: more than three words or 40 characters goes to Translate. */
        fun isLongText(t: String): Boolean = t.trim().split(Regex("\\s+")).size > 3 || t.length > 40
    }
}
