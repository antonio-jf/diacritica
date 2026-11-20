package app.diacritica.ui.library

import androidx.compose.foundation.background
import app.diacritica.ui.components.SwipeToDelete
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import app.diacritica.ui.components.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import app.diacritica.ui.components.EmptyHint
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.diacritica.data.Lang
import app.diacritica.ui.DictionaryViewModel
import app.diacritica.ui.components.Hairline
import app.diacritica.ui.components.PolyChip
import app.diacritica.ui.components.PolyIconButton
import app.diacritica.ui.components.PolyIcons
import app.diacritica.ui.components.ScreenHeader
import app.diacritica.ui.components.SectionLabel
import app.diacritica.ui.components.bodyStyle
import app.diacritica.ui.components.captionStyle
import app.diacritica.ui.components.headwordStyle
import app.diacritica.ui.theme.Diacritica
import java.text.DateFormat
import java.util.Date

/** Saved words and search history. Decks, Anki/CSV export and the story shelf come in later versions. */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
fun LibraryScreen(vm: DictionaryViewModel, onOpenArchive: () -> Unit, onOpen: (String, Lang?) -> Unit) {
    // Tapping a saved word or a past search shows its meaning in the word sheet, over the list,
    // instead of leaving Library; "Open in Dictionary" in the sheet still goes to the full entry.
    val application = androidx.compose.ui.platform.LocalContext.current.applicationContext as app.diacritica.DiacriticaApp
    val sheetScope = androidx.compose.runtime.rememberCoroutineScope()
    val lookup = androidx.compose.runtime.remember { app.diacritica.ui.components.WordLookup(application.container, sheetScope, source = "Library") }
    app.diacritica.ui.components.WordLookupSheet(lookup, onOpenDictionary = { l, w -> onOpen(w, l) })
    var tab by rememberSaveable { mutableIntStateOf(0) }
    val allSaved by vm.saved.collectAsStateWithLifecycle()
    // Search and filters for the saved list (language, source, date, sort); the sheet holds the filters.
    var query by rememberSaveable { mutableStateOf("") }
    var langFilter by rememberSaveable { mutableStateOf<String?>(null) }
    var sourceFilter by rememberSaveable { mutableStateOf<String?>(null) }
    var timeFilter by rememberSaveable { mutableStateOf(SavedWhen.ANY) }
    var sort by rememberSaveable { mutableStateOf(SavedSort.NEWEST) }
    val filter = SavedFilter(query, langFilter, sourceFilter, timeFilter, sort)
    val setFilter: (SavedFilter) -> Unit = { f -> query = f.query; langFilter = f.lang; sourceFilter = f.source; timeFilter = f.time; sort = f.sort }
    var showFilters by rememberSaveable { mutableStateOf(false) }
    val saved = filter.apply(allSaved)
    if (showFilters) SavedFilterSheet(allSaved, filter, setFilter, onDismiss = { showFilters = false })
    // Day and month in the phone's own order and words ("20 Nov", "11月20日", "20. Nov.").
    val shortDate = java.util.Locale.getDefault().let { loc ->
        java.text.SimpleDateFormat(android.text.format.DateFormat.getBestDateTimePattern(loc, "dMMM"), loc)
    }
    val history by vm.history.collectAsStateWithLifecycle()
    val fmt = DateFormat.getDateInstance(DateFormat.MEDIUM)

    LazyColumn(Modifier.fillMaxSize()) {
        item {
            ScreenHeader("Library") {
                if (tab == 1 && history.isNotEmpty()) PolyIconButton(PolyIcons.Trash, "Clear history", vm::clearHistory, tint = Diacritica.colors.inkMuted)
            }
            androidx.compose.foundation.layout.FlowRow(Modifier.padding(horizontal = Diacritica.spacing.screen, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                PolyChip("Saved (${allSaved.size})", tab == 0, { tab = 0 })
                PolyChip("History", tab == 1, { tab = 1 })
                PolyChip("Rehearse archive", false, onOpenArchive)
            }
        }
        if (tab == 0) {
            val sources = allSaved.map { sourceLabel(it.deck) }.distinct()
            if (allSaved.isNotEmpty()) item {
                Column(Modifier.padding(horizontal = Diacritica.spacing.screen, vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    SavedSearchBar(filter, onQuery = { query = it }, onOpenFilters = { showFilters = true })
                    // What's narrowing the list, in one quiet line, with a way to clear it.
                    val active = listOfNotNull(
                        langFilter?.let { Lang.fromCode(it)?.short ?: it }, sourceFilter,
                        timeFilter.takeIf { it != SavedWhen.ANY }?.label, sort.takeIf { it != SavedSort.NEWEST }?.label,
                    )
                    if (active.isNotEmpty() || query.isNotBlank()) Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            "${saved.size} of ${allSaved.size}" + if (active.isNotEmpty()) " · " + active.joinToString(" · ") else "",
                            style = captionStyle(), modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            "Clear", style = captionStyle(Diacritica.colors.accent),
                            modifier = Modifier.clickable(role = Role.Button) { setFilter(SavedFilter()) }.padding(horizontal = 6.dp, vertical = 6.dp),
                        )
                    }
                }
            }
            if (allSaved.isEmpty()) item {
                EmptyHint(
                    "No saved words yet",
                    "Tap ☆ on a word in the Dictionary, Reader, Books or Vocabulary. Saved words become Review cards and feed Rehearse › My words.",
                )
            } else if (saved.isEmpty()) item {
                Text(
                    if (query.isNotBlank()) "No saved words match “${query.trim()}”" + (if (filter.activeCount > 0) " with these filters." else ".")
                    else "No saved words match these filters.",
                    style = captionStyle(), modifier = Modifier.padding(20.dp),
                )
            }
            items(saved, key = { "s-${it.lang}:${it.word}" }) { w ->
                val lang = Lang.fromCode(w.lang)
                SwipeToDelete(onDelete = { vm.removeSaved(w) }) {
                Column(Modifier.background(Diacritica.colors.ground).padding(horizontal = 20.dp)) {
                    Row(
                        Modifier.fillMaxWidth().clickable(role = Role.Button) { lookup.open(w.word, lang) }.padding(vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        SectionLabel(lang?.short ?: w.lang)
                        Column(Modifier.weight(1f)) {
                            Text(w.word, style = headwordStyle())
                            val src = sourceLabel(w.deck)
                            Text(
                                listOfNotNull(src.takeIf { sources.size > 1 }, shortDate.format(Date(w.at)), w.note).joinToString(" · "),
                                style = captionStyle(), maxLines = 1, overflow = TextOverflow.Ellipsis,
                            )
                        }
                        PolyIconButton(PolyIcons.Close, "Remove ${w.word}", { vm.removeSaved(w) }, tint = Diacritica.colors.inkMuted)
                    }
                    Hairline()
                }
                }
            }
        } else {
            if (history.isEmpty()) item { EmptyHint("No searches yet", "Words you look up in the Dictionary, Reader and Books are listed here.") }
            items(history, key = { "h-${it.id}" }) { h ->
                val lang = Lang.fromCode(h.lang)
                SwipeToDelete(onDelete = { vm.deleteHistory(h) }) {
                Column(Modifier.background(Diacritica.colors.ground).padding(horizontal = 20.dp)) {
                    Row(
                        Modifier.fillMaxWidth().clickable(role = Role.Button) { lookup.open(h.query, lang) }.padding(vertical = 13.dp),
                        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        SectionLabel(lang?.short ?: h.lang)
                        Text(h.query, style = bodyStyle(), modifier = Modifier.weight(1f))
                        Text(fmt.format(Date(h.at)), style = captionStyle())
                    }
                    Hairline()
                }
                }
            }
        }
    }
}

/** Where a saved word came from (stored in its deck); words saved before this existed show as "Earlier". */
internal fun sourceLabel(deck: String): String = if (deck == "Saved" || deck.isBlank()) "Earlier" else deck
