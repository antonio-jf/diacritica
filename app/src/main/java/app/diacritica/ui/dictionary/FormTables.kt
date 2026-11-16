package app.diacritica.ui.dictionary

import app.diacritica.ui.components.textScaled
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.diacritica.data.FormRow
import app.diacritica.data.Lang
import app.diacritica.ui.components.Hairline
import app.diacritica.ui.components.SectionLabel
import app.diacritica.ui.components.bodyStyle
import app.diacritica.ui.components.captionStyle
import app.diacritica.ui.theme.Diacritica

/**
 * Turns Wiktextract's flat form list ("tue · 1st sg. subj. subjunctive i") into readable tables:
 * - key forms (infinitive, participles, auxiliary…) as label → form
 * - one conjugation table per mood + tense, persons down, singular | plural across
 * - one declension table for nouns (case × number)
 * - anything left over in "Other forms"
 */
data class FormTables(
    val keyForms: List<Pair<String, String>>,
    val conjugations: List<ConjugationTable>,
    val declension: DeclensionTable?,
    val other: List<Pair<String, String>>,
)

data class ConjugationTable(val title: String, val cells: Map<Pair<Int, Boolean>, String>)
data class DeclensionTable(val rows: List<Triple<String, String?, String?>>)

private val moods = listOf(
    "indicative" to "Indicative",
    "subjunctive-i" to "Subjunctive I",
    "subjunctive-ii" to "Subjunctive II",
    "subjunctive" to "Subjunctive",
    "conditional" to "Conditional",
    "imperative" to "Imperative",
)
private val tenses = listOf(
    "present" to "Present",
    "imperfect" to "Imperfect",
    "preterite" to "Preterite",
    "past" to "Past",
    "perfect" to "Perfect",
    "pluperfect" to "Pluperfect",
    "past-anterior" to "Past anterior",
    "future" to "Future",
    "future-perfect" to "Future perfect",
)
private val cases = listOf("nominative" to "Nominative", "accusative" to "Accusative", "dative" to "Dative", "genitive" to "Genitive")
private val keyFormOrder = listOf(
    setOf("infinitive") to "Infinitive",
    setOf("participle", "present") to "Present participle",
    setOf("gerund") to "Gerund",
    setOf("participle", "past") to "Past participle",
    setOf("participle") to "Participle",
    setOf("auxiliary") to "Auxiliary",
)
/** Variant forms that would make a person cell ambiguous; shown under "Other forms" instead. */
private val variantTags = setOf("formal", "informal", "vos-form", "voseo", "archaic", "dated", "rare", "dialectal", "obsolete", "alternative")

fun buildFormTables(forms: List<FormRow>): FormTables {
    val keyForms = LinkedHashMap<String, MutableList<String>>()
    val conj = LinkedHashMap<String, MutableMap<Pair<Int, Boolean>, MutableList<String>>>()
    val decl = LinkedHashMap<String, Pair<MutableList<String>, MutableList<String>>>()
    val other = ArrayList<Pair<String, String>>()

    for (f in forms) {
        val tags = f.tags.split(',').map { it.trim() }.filter { it.isNotEmpty() }.toSet()
        val person = when {
            "first-person" in tags -> 1
            "second-person" in tags -> 2
            "third-person" in tags -> 3
            else -> null
        }
        val plural = "plural" in tags
        val mood = moods.firstOrNull { it.first in tags }
        val tense = tenses.firstOrNull { it.first in tags }
        val case = cases.firstOrNull { it.first in tags }

        when {
            person != null && (mood != null || tense != null) && tags.none { it in variantTags } -> {
                val title = listOfNotNull(mood?.second ?: "Indicative", tense?.second).joinToString(" · ")
                conj.getOrPut(title) { mutableMapOf() }.getOrPut(person to plural) { mutableListOf() }.addIfNew(f.form)
            }
            case != null && person == null -> {
                val pair = decl.getOrPut(case.second) { mutableListOf<String>() to mutableListOf() }
                (if (plural) pair.second else pair.first).addIfNew(f.form)
            }
            person == null -> {
                val key = keyFormOrder.firstOrNull { (need, _) -> tags.containsAll(need) }?.second
                if (key != null) keyForms.getOrPut(key) { mutableListOf() }.addIfNew(f.form)
                else other += f.form to humanize(tags)
            }
            else -> other += f.form to humanize(tags)
        }
    }

    // Order tables: by mood, then tense, following the lists above.
    fun order(title: String): Int {
        val m = moods.indexOfFirst { title.startsWith(it.second) }.let { if (it < 0) 99 else it }
        val t = tenses.indexOfFirst { title.endsWith(it.second) }.let { if (it < 0) 99 else it }
        return m * 100 + t
    }
    return FormTables(
        keyForms = keyFormOrder.mapNotNull { (_, label) -> keyForms[label]?.let { label to it.joinToString(" / ") } },
        conjugations = conj.entries.sortedBy { order(it.key) }
            .map { (title, cells) -> ConjugationTable(title, cells.mapValues { it.value.joinToString(" / ") }) },
        declension = if (decl.isEmpty()) null else DeclensionTable(
            cases.mapNotNull { (_, label) ->
                decl[label]?.let { (sg, pl) -> Triple(label, sg.joinToString(" / ").ifBlank { null }, pl.joinToString(" / ").ifBlank { null }) }
            },
        ),
        other = other.distinct(),
    )
}

private fun MutableList<String>.addIfNew(s: String) { if (s !in this) add(s) }

private val tagNames = mapOf(
    "first-person" to "1st person", "second-person" to "2nd person", "third-person" to "3rd person",
    "singular" to "singular", "plural" to "plural", "masculine" to "masculine", "feminine" to "feminine",
    "neuter" to "neuter", "diminutive" to "diminutive", "augmentative" to "augmentative",
)

private fun humanize(tags: Set<String>): String =
    tags.joinToString(", ") { tagNames[it] ?: it.replace('-', ' ') }.ifBlank { "form" }

private fun pronouns(lang: Lang): Map<Pair<Int, Boolean>, String> {
    val p = when (lang) {
        Lang.DE -> listOf("ich", "du", "er/sie/es", "wir", "ihr", "sie")
        Lang.FR -> listOf("je", "tu", "il/elle", "nous", "vous", "ils/elles")
        Lang.IT -> listOf("io", "tu", "lui/lei", "noi", "voi", "loro")
        Lang.ES -> listOf("yo", "tú", "él/ella", "nosotros", "vosotros", "ellos/ellas")
        else -> listOf("I", "you", "he/she", "we", "you", "they")
    }
    return mapOf(
        (1 to false) to p[0], (2 to false) to p[1], (3 to false) to p[2],
        (1 to true) to p[3], (2 to true) to p[4], (3 to true) to p[5],
    )
}

// ---------------------------------------------------------------- UI

@Composable
fun FormsSection(lang: Lang, forms: List<FormRow>) {
    val t = buildFormTables(forms)
    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        if (t.keyForms.isNotEmpty()) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                SectionLabel("Key forms")
                t.keyForms.forEach { (label, form) -> KeyValue(label, form) }
            }
        }
        t.declension?.let { DeclensionCard(it) }
        if (t.conjugations.isNotEmpty()) {
            SectionLabel("Conjugation")
            val pr = pronouns(lang)
            t.conjugations.forEach { ConjugationCard(it, pr) }
        }
        if (t.other.isNotEmpty()) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                SectionLabel("Other forms")
                t.other.forEach { (form, label) -> KeyValue(label, form) }
            }
        }
    }
}

@Composable
private fun KeyValue(label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
        Text(label, style = captionStyle(), modifier = Modifier.width(150.dp.textScaled()))
        Text(value, style = bodyStyle(), modifier = Modifier.weight(1f))
    }
}

@Composable
private fun TableBox(title: String, content: @Composable () -> Unit) {
    val c = Diacritica.colors
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(Diacritica.shapes.card.coerceAtMost(12.dp)))
            .background(c.surfaceAlt).padding(horizontal = 12.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(title, style = TextStyle(fontFamily = Diacritica.fonts.label, fontSize = Diacritica.type.caption, fontWeight = FontWeight.SemiBold, color = c.ink))
        content()
    }
}

/** Persons down, singular | plural across: "ich tue   wir tun". */
@Composable
private fun ConjugationCard(table: ConjugationTable, pronouns: Map<Pair<Int, Boolean>, String>) {
    TableBox(table.title) {
        val persons = (1..3).filter { p -> table.cells.containsKey(p to false) || table.cells.containsKey(p to true) }
        persons.forEachIndexed { i, p ->
            if (i > 0) Hairline()
            Row(Modifier.fillMaxWidth()) {
                PersonCell(pronouns.getValue(p to false), table.cells[p to false], Modifier.weight(1f))
                PersonCell(pronouns.getValue(p to true), table.cells[p to true], Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun PersonCell(pronoun: String, form: String?, modifier: Modifier) {
    Row(modifier.padding(vertical = 3.dp)) {
        Text(pronoun, style = captionStyle(), modifier = Modifier.width(76.dp.textScaled()))
        Text(form ?: "—", style = bodyStyle(if (form == null) Diacritica.colors.inkMuted else Diacritica.colors.ink), modifier = Modifier.weight(1f))
    }
}

@Composable
private fun DeclensionCard(table: DeclensionTable) {
    TableBox("Declension") {
        Row(Modifier.fillMaxWidth()) {
            Text("", modifier = Modifier.width(96.dp.textScaled()))
            Text("Singular", style = captionStyle(), modifier = Modifier.weight(1f))
            Text("Plural", style = captionStyle(), modifier = Modifier.weight(1f))
        }
        table.rows.forEachIndexed { i, (case, sg, pl) ->
            if (i > 0) Hairline()
            Row(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
                Text(case, style = captionStyle(), modifier = Modifier.width(96.dp.textScaled()))
                Text(sg ?: "—", style = bodyStyle(), modifier = Modifier.weight(1f))
                Text(pl ?: "—", style = bodyStyle(), modifier = Modifier.weight(1f))
            }
        }
    }
}
