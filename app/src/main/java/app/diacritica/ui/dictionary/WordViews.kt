package app.diacritica.ui.dictionary

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.diacritica.data.Articles
import app.diacritica.data.Lang
import app.diacritica.data.WordInfo
import app.diacritica.ui.components.PolyIconButton
import app.diacritica.ui.components.PolyIcons
import app.diacritica.ui.components.captionStyle
import app.diacritica.ui.components.exampleStyle
import app.diacritica.ui.components.headwordStyle
import app.diacritica.ui.theme.Diacritica

/** Callbacks shared by every Dictionary layout, so layouts only differ in arrangement. */
data class WordActions(
    val onSpeak: (WordInfo, Boolean) -> Unit,
    val onToggleSave: (WordInfo) -> Unit,
    val onOpen: (WordInfo) -> Unit,
    val isSaved: (WordInfo) -> Boolean,
)

/** "la clé", with the article muted; Mandarin shows simplified + traditional. */
@Composable
fun WordTitle(w: WordInfo, large: Boolean = false) {
    val c = Diacritica.colors
    val size = if (large) Diacritica.type.headwordLarge else Diacritica.type.headword
    if (w.lang == Lang.ZH) {
        Text(
            buildAnnotatedString {
                append(w.word)
                if (w.traditional != null) withStyle(SpanStyle(color = c.inkMuted, fontSize = size * 0.72f)) { append("  " + w.traditional) }
            },
            style = headwordStyle(size * 1.08f),
        )
    } else {
        Text(
            buildAnnotatedString {
                if (w.article != null) {
                    withStyle(SpanStyle(color = c.inkMuted, fontWeight = FontWeight.Normal)) {
                        append(w.article); if (!w.article.endsWith("'")) append(" ")
                    }
                }
                append(w.word)
            },
            style = headwordStyle(size),
        )
    }
}

/** Grammar line: "f · pl. clés", "m · pl. Schlüssel · gen. Schlüssels", "measure word 把 bǎ". */
fun grammarLine(w: WordInfo): String? {
    val parts = buildList {
        if (w.lang == Lang.ZH) {
            w.measure?.let { add("measure word $it") }
        } else {
            Articles.genderShort(w.gender)?.let { add(it) }
            if (w.gender == null && w.pos != null && w.pos != "noun") add(w.pos)
            w.plural?.let { add("pl. $it") }
            w.genitive?.let { add("gen. $it") }
            w.ipa?.let { add(it) }
        }
    }
    return parts.joinToString(" · ").ifBlank { null }
}

@Composable
fun PinyinLine(w: WordInfo) {
    if (w.pinyin == null) return
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(w.pinyin, style = TextStyle(fontFamily = Diacritica.fonts.body, fontSize = 15.sp, color = Diacritica.colors.accent, fontWeight = FontWeight.Medium))
        w.hsk?.let { HskBadge(it) }
    }
}

@Composable
fun HskBadge(level: Int) {
    val c = Diacritica.colors
    Box(
        Modifier.clip(RoundedCornerShape(Diacritica.shapes.chip.coerceAtMost(6.dp))).background(c.accentSoft)
            .padding(horizontal = 6.dp, vertical = 2.dp),
    ) {
        Text("HSK $level", style = TextStyle(fontFamily = Diacritica.fonts.label, fontSize = 11.sp, color = c.onAccentSoft, fontWeight = FontWeight.SemiBold))
    }
}

@Composable
fun ExampleLine(w: WordInfo) {
    val ex = w.example ?: return
    Column {
        Text(ex, style = exampleStyle())
        w.exampleEn?.let { Text(it, style = captionStyle()) }
    }
}

@Composable
fun WordActionButtons(w: WordInfo, actions: WordActions) {
    Row {
        PolyIconButton(PolyIcons.Speaker, "Play ${w.lang.englishName} pronunciation", { actions.onSpeak(w, false) })
        val saved = actions.isSaved(w)
        PolyIconButton(
            if (saved) PolyIcons.StarFilled else PolyIcons.Star,
            if (saved) "Remove from saved" else "Save word", { actions.onToggleSave(w) },
            tint = if (saved) Diacritica.colors.savedStar else Diacritica.colors.inkMuted,
        )
    }
}

@Composable
fun NoTranslation(lang: Lang) {
    Text("No ${lang.englishName} translation for this meaning in the dictionary yet.", style = captionStyle())
}

/** Body of one word: title, grammar, pinyin, gloss and example. Used by every layout. */
@Composable
fun WordBody(w: WordInfo, showGloss: Boolean, modifier: Modifier = Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(3.dp)) {
        WordTitle(w)
        if (w.lang == Lang.ZH) PinyinLine(w)
        grammarLine(w)?.let { Text(it, style = captionStyle()) }
        if (showGloss || w.source != null) w.gloss?.let { Text(it, style = captionStyle(Diacritica.colors.ink), maxLines = 2) }
        w.source?.let { Text("Matched by definition ($it)", style = captionStyle()) }
        ExampleLine(w)
    }
}
