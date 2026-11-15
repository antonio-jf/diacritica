package app.diacritica.ui.dictionary

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.diacritica.data.Articles
import app.diacritica.data.DetailResult
import app.diacritica.data.EntryDetail
import app.diacritica.data.Lang
import app.diacritica.data.WordInfo
import app.diacritica.data.ZhReading
import app.diacritica.ui.DictionaryViewModel
import app.diacritica.ui.components.Gap
import app.diacritica.ui.components.Hairline
import app.diacritica.ui.components.PolyButton
import app.diacritica.ui.components.PolyIconButton
import app.diacritica.ui.components.PolyIcons
import app.diacritica.ui.components.PolySurface
import app.diacritica.ui.components.SectionLabel
import app.diacritica.ui.components.bodyStyle
import app.diacritica.ui.components.captionStyle
import app.diacritica.ui.components.exampleStyle
import app.diacritica.ui.theme.Diacritica

/** Full entry: every sense with examples, inflection table, Mandarin readings, and "look up from here". */
@Composable
fun DetailScreen(
    vm: DictionaryViewModel,
    lang: Lang,
    word: String,
    onBack: () -> Unit,
    onPivot: (Lang, String) -> Unit,
) {
    var detail by remember { mutableStateOf<DetailResult?>(null) }
    LaunchedEffect(lang, word) { detail = runCatching { vm.detail(lang, word) }.getOrNull() }
    val savedKeys by vm.savedKeys.collectAsStateWithLifecycle()
    val d = detail
    val first = d?.entries?.firstOrNull()
    val zh = d?.zh?.firstOrNull()
    val info = when {
        lang == Lang.ZH -> WordInfo(lang, zh?.simplified ?: word, traditional = zh?.let { z -> z.traditional.takeIf { it != z.simplified } }, pinyin = zh?.pinyin, measure = zh?.measure, hsk = zh?.hsk)
        else -> WordInfo(
            lang, first?.word ?: word, article = Articles.forNoun(lang, first?.word ?: word, first?.gender, first?.pos),
            gender = first?.gender, pos = first?.pos, plural = first?.plural, ipa = first?.ipa,
        )
    }
    val saved = "${info.lang.code}:${info.word}" in savedKeys

    LazyColumn(Modifier.fillMaxSize()) {
        item {
            Row(Modifier.padding(horizontal = 8.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                PolyIconButton(PolyIcons.Back, "Back", onBack, tint = Diacritica.colors.ink)
                SectionLabel(lang.englishName)
            }
            if (Diacritica.tokens.heavyRules) Hairline(heavy = true)
        }
        item {
            Column(Modifier.padding(horizontal = Diacritica.spacing.screen, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                WordTitle(info, large = true)
                if (lang == Lang.ZH) PinyinLine(info)
                grammarLine(info)?.let { Text(it, style = captionStyle()) }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    PolyIconButton(PolyIcons.Speaker, "Play pronunciation", { vm.speak(info, false) })
                    PolyIconButton(PolyIcons.Slow, "Play slowly", { vm.speak(info, true) })
                    PolyIconButton(if (saved) PolyIcons.StarFilled else PolyIcons.Star, if (saved) "Remove from saved" else "Save word", { vm.toggleSave(info) },
                        tint = if (saved) Diacritica.colors.savedStar else Diacritica.colors.inkMuted)
                }
                PolyButton("Look up from ${lang.englishName}", { onPivot(lang, info.word) }, primary = false)
            }
        }
        if (d == null) return@LazyColumn
        if (d.entries.isEmpty() && d.zh.isEmpty()) item {
            Text("No detailed entry for this word in the installed dictionary.", style = captionStyle(), modifier = Modifier.padding(20.dp))
        }
        d.zh.forEachIndexed { i, r -> item("zh$i") { ZhCard(r) } }
        d.entries.forEachIndexed { i, e -> item("e$i") { EntryCard(e) } }
        item { Gap(24.dp) }
    }
}

@Composable
private fun ZhCard(r: ZhReading) {
    PolySurface(Modifier.padding(horizontal = 16.dp, vertical = 6.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(r.pinyin, style = bodyStyle(Diacritica.colors.accent))
            if (r.traditional != r.simplified) Text("${r.simplified} · ${r.traditional}", style = captionStyle())
            r.hsk?.let { HskBadge(it) }
        }
        Gap(6.dp)
        r.definitions.forEachIndexed { i, def -> Text("${i + 1}. $def", style = bodyStyle()) }
        r.measure?.let { Gap(6.dp); Text("Measure word: $it", style = captionStyle()) }
    }
}

@Composable
private fun EntryCard(e: EntryDetail) {
    PolySurface(Modifier.padding(horizontal = 16.dp, vertical = 6.dp)) {
        SectionLabel(listOfNotNull(e.pos, Articles.genderLabel(e.gender)).joinToString(" · ").ifBlank { "entry" })
        Gap(6.dp)
        e.senses.forEachIndexed { i, s ->
            Column(Modifier.padding(vertical = 4.dp)) {
                Text("${i + 1}. ${s.gloss}", style = bodyStyle())
                s.example?.let { Text(it, style = exampleStyle(), modifier = Modifier.padding(start = 16.dp, top = 2.dp)) }
                s.exampleEn?.let { Text(it, style = captionStyle(), modifier = Modifier.padding(start = 16.dp)) }
            }
        }
        if (e.forms.isNotEmpty()) {
            Gap(12.dp)
            Hairline()
            Gap(10.dp)
            FormsSection(e.lang, e.forms)
        }
    }
}
