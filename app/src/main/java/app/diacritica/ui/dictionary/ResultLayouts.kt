package app.diacritica.ui.dictionary

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import app.diacritica.data.LangResult
import app.diacritica.settings.DictionaryLayout
import app.diacritica.ui.components.Hairline
import app.diacritica.ui.components.LangBadge
import app.diacritica.ui.components.PolySurface
import app.diacritica.ui.theme.Diacritica

/**
 * The Dictionary results, in the layout the user chose for this section.
 * Layouts differ ONLY in arrangement; all styling comes from the global theme tokens.
 */
fun LazyListScope.dictionaryResults(layout: DictionaryLayout, results: List<LangResult>, actions: WordActions) {
    when (layout) {
        DictionaryLayout.LIST -> listLayout(results, actions)
        DictionaryLayout.CARDS -> cardsLayout(results, actions)
    }
}

/** List: one row per language, language code in a narrow column, hairline separators. */
private fun LazyListScope.listLayout(results: List<LangResult>, actions: WordActions) {
    items(results, key = { "list-" + it.lang.code }) { r ->
        Column(Modifier.padding(horizontal = 20.dp)) {
            ListRow(r, actions)
            Hairline()
        }
    }
}

@Composable
private fun ListRow(r: LangResult, actions: WordActions) {
    Row(Modifier.fillMaxWidth().padding(vertical = Diacritica.spacing.row), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        LangBadge(r.lang, Modifier.padding(top = 6.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            if (r.words.isEmpty()) NoTranslation(r.lang)
            r.words.forEach { w ->
                Row(verticalAlignment = Alignment.Top) {
                    WordBody(
                        w, showGloss = false,
                        modifier = Modifier.weight(1f).clickable(role = Role.Button) { actions.onOpen(w) },
                    )
                    WordActionButtons(w, actions)
                }
            }
        }
    }
}

/** Cards: one card per language with a badge; the first word is prominent, alternatives below. */
private fun LazyListScope.cardsLayout(results: List<LangResult>, actions: WordActions) {
    items(results, key = { "card-" + it.lang.code }) { r ->
        PolySurface(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 5.dp),
            padding = PaddingValues(horizontal = 14.dp, vertical = 12.dp),
        ) {
            Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                LangBadge(r.lang, boxed = true)
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    if (r.words.isEmpty()) NoTranslation(r.lang)
                    r.words.forEachIndexed { i, w ->
                        if (i > 0) Hairline()
                        Row(verticalAlignment = Alignment.Top) {
                            WordBody(
                                w, showGloss = i == 0 && r.lang.code == "en",
                                modifier = Modifier.weight(1f).clickable(role = Role.Button) { actions.onOpen(w) },
                            )
                            WordActionButtons(w, actions)
                        }
                    }
                }
            }
        }
    }
}
