package app.diacritica.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.diacritica.data.Lang
import app.diacritica.data.LookupResult
import app.diacritica.data.WordInfo
import app.diacritica.data.ZhRanking
import app.diacritica.reader.OfficialDictionary
import app.diacritica.ui.theme.Diacritica

/**
 * The one word sheet used everywhere a word is tapped (Reader, Books, Vocabulary, Review and
 * "Look up in Diacritica" from other apps): the same header, the same buttons in the same order
 * (listen · copy/share · save), translations in all your languages, then the source sentence and
 * the Dictionary / reference-dictionary buttons.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun WordSheetBody(
    lang: Lang,
    token: String,
    result: LookupResult?,
    loading: Boolean,
    fetching: Boolean,
    saved: Boolean,
    onSpeak: (WordInfo) -> Unit,
    onToggleSave: (WordInfo) -> Unit,
    modifier: Modifier = Modifier,
    /** Show Mandarin in traditional characters. */
    traditional: Boolean = false,
    /** Languages listed first under Translations (e.g. the Reader's other pane); English follows. */
    firstLangs: List<Lang> = emptyList(),
    /** Tap "of nehmen" in a form note: look the lemma up. */
    onLemma: ((String) -> Unit)? = null,
    /** False-friend warnings and similar notes, under the gloss. */
    notes: @Composable ColumnScope.() -> Unit = {},
    /** Where the word came from: the sentence (and its translation). */
    sentence: String? = null,
    sentenceTranslation: String? = null,
    onOpenDictionary: ((Lang, String) -> Unit)? = null,
    /** Extra buttons after "Open in Dictionary" (e.g. "Open Diacritica" in the overlay). */
    extraActions: @Composable () -> Unit = {},
) {
    val c = Diacritica.colors
    val uri = LocalUriHandler.current
    val hw: WordInfo = result?.headword ?: WordInfo(lang, token)
    val found = result != null && !result.isEmpty
    fun zh(w: WordInfo, base: String) = if (w.lang == Lang.ZH && traditional) w.traditional ?: base else base
    Column(
        modifier.fillMaxWidth().verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp).padding(bottom = 20.dp).navigationBarsPadding(),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            LangBadge(lang, boxed = true)
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                val head = hw.article?.let { a -> if (a.endsWith("'")) a + hw.word else "$a ${hw.word}" } ?: hw.word
                Text(zh(hw, head), style = headwordStyle(Diacritica.type.headwordLarge))
                if (lang == Lang.ZH) {
                    val extra = listOfNotNull(hw.pinyin, hw.traditional?.takeIf { it != hw.word && !traditional }, hw.hsk?.let { ZhRanking.hskLabel(it) })
                    if (extra.isNotEmpty()) Text(extra.joinToString("  ·  "), style = captionStyle(c.accent))
                }
            }
            PolyIconButton(PolyIcons.Speaker, "Pronounce", onClick = { onSpeak(hw) })
            if (found) CopyShareButtons { shareTextFor(hw, result!!.results.filter { it.lang != lang }.mapNotNull { it.words.firstOrNull() }) }
            if (found) {
                PolyIconButton(
                    if (saved) PolyIcons.StarFilled else PolyIcons.Star, if (saved) "Remove from saved" else "Save word",
                    onClick = { onToggleSave(hw) }, tint = if (saved) c.savedStar else c.inkMuted,
                )
            }
        }
        if (lang != Lang.ZH) {
            val meta = listOfNotNull(hw.pos, hw.gender, hw.plural?.let { "pl. $it" }, hw.ipa?.let { "/$it/" }).joinToString(" · ")
            if (meta.isNotEmpty()) Text(meta, style = captionStyle(), modifier = Modifier.padding(top = 4.dp))
        }
        val forms = result?.forms.orEmpty()
        if (forms.isNotEmpty()) {
            FormNotes(token, forms, onLemma = onLemma, modifier = Modifier.padding(top = 4.dp))
        } else if (result?.redirectedFrom != null || (found && !hw.word.equals(token, ignoreCase = true))) {
            Text("“$token” is a form of ${hw.word}", style = captionStyle(c.ink), modifier = Modifier.padding(top = 4.dp))
        }
        VerbPatternNotes(hw, max = 4)
        if (lang == Lang.ZH) HanziBreakdown(hw.word, hw.pinyin, Modifier.padding(top = 8.dp), traditional = traditional, onOpen = onLemma)
        hw.gloss?.let { Text(it, style = bodyStyle(), modifier = Modifier.padding(top = 8.dp)) }
        if (!loading) notes()

        when {
            loading -> { Gap(14.dp); Text("Looking up…", style = captionStyle()) }
            fetching -> { Gap(14.dp); Text("Not in the offline dictionary. Downloading it…", style = captionStyle()) }
            !found -> { Gap(14.dp); Text("Not in the offline dictionary.", style = captionStyle()) }
            else -> {
                val r = result!!
                val concept = r.concepts.getOrNull(r.selected)
                Gap(14.dp)
                SectionLabel(concept?.label?.let { "Meaning · $it" } ?: "Translations")
                Gap(6.dp)
                val order = (firstLangs + Lang.EN).distinct().filter { it != lang }
                r.results.filter { it.lang != lang }
                    .sortedBy { lr -> order.indexOf(lr.lang).let { if (it < 0) 99 else it } }
                    .forEach { lr ->
                        Row(Modifier.fillMaxWidth().padding(vertical = 5.dp), verticalAlignment = Alignment.Top) {
                            Box(Modifier.widthIn(min = 40.dp).padding(end = 10.dp)) { LangBadge(lr.lang) }
                            val words = lr.words.take(3).joinToString(", ") { w ->
                                val base = zh(w, w.article?.let { a -> if (a.endsWith("'")) a + w.word else "$a ${w.word}" } ?: w.word)
                                if (w.lang == Lang.ZH && w.pinyin != null) "$base ${w.pinyin}" else base
                            }
                            Text(words.ifEmpty { "—" }, style = bodyStyle(if (words.isEmpty()) c.inkMuted else c.ink), modifier = Modifier.weight(1f))
                        }
                    }
            }
        }

        if (!sentence.isNullOrBlank()) {
            Gap(14.dp); Hairline(); Gap(10.dp)
            Text(sentence, style = bodyStyle(size = 14.sp))
            if (!sentenceTranslation.isNullOrBlank()) {
                Text(sentenceTranslation, style = bodyStyle(c.inkMuted, 14.sp).copy(fontStyle = FontStyle.Italic))
            }
        }

        Gap(16.dp)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (found && onOpenDictionary != null) PolyButton("Open in Dictionary", onClick = { onOpenDictionary(hw.lang, hw.word) })
            extraActions()
            PolyButton(
                "${OfficialDictionary.name(lang)} ↗",
                onClick = { runCatching { uri.openUri(OfficialDictionary.url(lang, hw.word, hw.traditional)) } },
                primary = false,
            )
        }
    }
}
