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
    var tab by rememberSaveable { mutableIntStateOf(0) }
    val allSaved by vm.saved.collectAsStateWithLifecycle()
    // Filters for the saved list: one language and/or one source (where the word was saved from).
    var langFilter by rememberSaveable { mutableStateOf<String?>(null) }
    var sourceFilter by rememberSaveable { mutableStateOf<String?>(null) }
    val saved = allSaved.filter { (langFilter == null || it.lang == langFilter) && (sourceFilter == null || sourceLabel(it.deck) == sourceFilter) }
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
            val langs = allSaved.map { it.lang }.distinct()
            val sources = allSaved.map { sourceLabel(it.deck) }.distinct().sorted()
            if (langs.size > 1 || sources.size > 1) item {
                Column(Modifier.padding(horizontal = Diacritica.spacing.screen, vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    if (langs.size > 1) androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        PolyChip("All", langFilter == null, { langFilter = null })
                        Lang.defaultOrder.filter { it.code in langs }.forEach { l ->
                            PolyChip("${l.short} ${allSaved.count { it.lang == l.code }}", langFilter == l.code, { langFilter = if (langFilter == l.code) null else l.code })
                        }
                    }
                    if (sources.size > 1) androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        PolyChip("Anywhere", sourceFilter == null, { sourceFilter = null })
                        sources.forEach { src -> PolyChip(src, sourceFilter == src, { sourceFilter = if (sourceFilter == src) null else src }) }
                    }
                }
            }
            if (allSaved.isEmpty()) item {
                EmptyHint(
                    "No saved words yet",
                    "Tap ☆ on a word in the Dictionary, Reader, Books or Vocabulary. Saved words become Review cards and feed Rehearse › My words.",
                )
            } else if (saved.isEmpty()) item {
                Text("No saved words match these filters.", style = captionStyle(), modifier = Modifier.padding(20.dp))
            }
            items(saved, key = { "s-${it.lang}:${it.word}" }) { w ->
                val lang = Lang.fromCode(w.lang)
                SwipeToDelete(onDelete = { vm.removeSaved(w) }) {
                Column(Modifier.background(Diacritica.colors.ground).padding(horizontal = 20.dp)) {
                    Row(
                        Modifier.fillMaxWidth().clickable(role = Role.Button) { onOpen(w.word, lang) }.padding(vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        SectionLabel(lang?.short ?: w.lang)
                        Column(Modifier.weight(1f)) {
                            Text(w.word, style = headwordStyle())
                            val src = sourceLabel(w.deck)
                            Text(listOfNotNull(src.takeIf { sources.size > 1 }, w.note).joinToString(" · ").ifEmpty { src }, style = captionStyle(), maxLines = 1, overflow = TextOverflow.Ellipsis)
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
                        Modifier.fillMaxWidth().clickable(role = Role.Button) { onOpen(h.query, lang) }.padding(vertical = 13.dp),
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
