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
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
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
fun LibraryScreen(vm: DictionaryViewModel, onOpenArchive: () -> Unit, onOpenSettings: () -> Unit, onOpen: (String, Lang?) -> Unit) {
    var tab by rememberSaveable { mutableIntStateOf(0) }
    val saved by vm.saved.collectAsStateWithLifecycle()
    val history by vm.history.collectAsStateWithLifecycle()
    val fmt = DateFormat.getDateInstance(DateFormat.MEDIUM)

    LazyColumn(Modifier.fillMaxSize()) {
        item {
            ScreenHeader("Library") {
                if (tab == 1 && history.isNotEmpty()) PolyIconButton(PolyIcons.Trash, "Clear history", vm::clearHistory, tint = Diacritica.colors.inkMuted)
                PolyIconButton(PolyIcons.Settings, "Settings", onOpenSettings, tint = Diacritica.colors.ink)
            }
            androidx.compose.foundation.layout.FlowRow(Modifier.padding(horizontal = Diacritica.spacing.screen, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                PolyChip("Saved (${saved.size})", tab == 0, { tab = 0 })
                PolyChip("History", tab == 1, { tab = 1 })
                PolyChip("Rehearse archive", false, onOpenArchive)
            }
        }
        if (tab == 0) {
            if (saved.isEmpty()) item {
                Text("Tap ☆ next to any word to save it here.", style = captionStyle(), modifier = Modifier.padding(20.dp))
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
                            w.note?.let { Text(it, style = captionStyle(), maxLines = 1, overflow = TextOverflow.Ellipsis) }
                        }
                        PolyIconButton(PolyIcons.Close, "Remove ${w.word}", { vm.removeSaved(w) }, tint = Diacritica.colors.inkMuted)
                    }
                    Hairline()
                }
                }
            }
        } else {
            if (history.isEmpty()) item { Text("No searches yet.", style = captionStyle(), modifier = Modifier.padding(20.dp)) }
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
