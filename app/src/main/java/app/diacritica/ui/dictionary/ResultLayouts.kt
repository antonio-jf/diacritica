package app.diacritica.ui.dictionary

import app.diacritica.ui.components.textScaled
import androidx.compose.foundation.clickable
import app.diacritica.ui.components.captionStyle
import app.diacritica.ui.components.bodyStyle
import app.diacritica.ui.components.PolyIcons
import app.diacritica.ui.components.PolyIconButton
import app.diacritica.ui.components.Gap
import app.diacritica.data.Lang
import androidx.compose.ui.unit.sp
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.material3.Text
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Box
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
        DictionaryLayout.GRID -> gridLayout(results, actions)
        DictionaryLayout.TABLE -> tableLayout(results, actions)
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
            if (r.words.isEmpty()) NoTranslation(r.lang, actions)
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
                    if (r.words.isEmpty()) NoTranslation(r.lang, actions)
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

/** Grid: two tiles per row, one per language: the main word big, the rest as "+2 more". Tap a tile for the details. */
private fun LazyListScope.gridLayout(results: List<LangResult>, actions: WordActions) {
    items(results.chunked(2), key = { row -> "grid-" + row.joinToString("-") { it.lang.code } }) { row ->
        Row(
            Modifier.fillMaxWidth().height(IntrinsicSize.Min).padding(horizontal = 16.dp, vertical = 5.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            row.forEach { r -> GridTile(r, actions, Modifier.weight(1f).fillMaxHeight()) }
            if (row.size == 1) Spacer(Modifier.weight(1f))
        }
    }
}

@Composable
private fun GridTile(r: LangResult, actions: WordActions, modifier: Modifier) {
    val w = r.words.firstOrNull()
    PolySurface(modifier, padding = PaddingValues(12.dp), onClick = w?.let { { actions.onOpen(it) } }) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            LangBadge(r.lang, boxed = true)
            Spacer(Modifier.weight(1f))
            if (w != null) WordActionButtons(w, actions)
        }
        Gap(4.dp)
        if (w == null) {
            NoTranslation(r.lang, actions)
        } else {
            WordTitle(w)
            if (w.lang == Lang.ZH) PinyinLine(w)
            grammarLine(w)?.let { Text(it, style = captionStyle(), maxLines = 2, overflow = TextOverflow.Ellipsis) }
            if (r.words.size > 1) {
                Gap(4.dp)
                Text(
                    "+ " + r.words.drop(1).take(3).joinToString(", ") { it.word } + if (r.words.size > 4) "…" else "",
                    style = captionStyle(), maxLines = 2, overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/** Table: dense rows (language · word · grammar), every translation on its own line; good for comparing at a glance. */
private fun LazyListScope.tableLayout(results: List<LangResult>, actions: WordActions) {
    item("table-head") {
        Column(Modifier.padding(horizontal = 16.dp)) {
            Row(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
                Text("LANG", style = captionStyle(), modifier = Modifier.width(48.dp.textScaled()))
                Text("WORD", style = captionStyle(), modifier = Modifier.weight(1.2f))
                Text("GRAMMAR", style = captionStyle(), modifier = Modifier.weight(1f))
                Spacer(Modifier.width(44.dp))
            }
            Hairline(heavy = true)
        }
    }
    items(results, key = { "table-" + it.lang.code }) { r ->
        Column(Modifier.padding(horizontal = 16.dp)) {
            if (r.words.isEmpty()) {
                Row(Modifier.fillMaxWidth().padding(vertical = 10.dp), verticalAlignment = Alignment.Top) {
                    Box(Modifier.widthIn(min = 48.dp).padding(end = 10.dp)) { LangBadge(r.lang) }
                    NoTranslation(r.lang, actions)
                }
            }
            r.words.forEachIndexed { i, w ->
                Row(
                    Modifier.fillMaxWidth().clickable(role = Role.Button) { actions.onOpen(w) }.padding(vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(Modifier.width(48.dp.textScaled())) { if (i == 0) LangBadge(r.lang) }
                    Column(Modifier.weight(1.2f)) {
                        Text(
                            (w.article?.let { a -> if (a.endsWith("'")) a else "$a " } ?: "") + w.word +
                                if (w.lang == Lang.ZH && w.traditional != null && w.traditional != w.word) " ${w.traditional}" else "",
                            style = bodyStyle(size = 16.sp).copy(fontWeight = if (i == 0) FontWeight.SemiBold else FontWeight.Normal),
                        )
                        w.pinyin?.let { Text(it, style = captionStyle(Diacritica.colors.accent)) }
                    }
                    Text(grammarLine(w) ?: "", style = captionStyle(), modifier = Modifier.weight(1f), maxLines = 2, overflow = TextOverflow.Ellipsis)
                    PolyIconButton(PolyIcons.Speaker, "Play ${w.lang.englishName} pronunciation", { actions.onSpeak(w, false) })
                }
            }
            Hairline()
        }
    }
}
