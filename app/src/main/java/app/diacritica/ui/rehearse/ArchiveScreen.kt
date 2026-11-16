package app.diacritica.ui.rehearse

import app.diacritica.ui.components.PolyButton
import kotlinx.coroutines.launch
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.diacritica.data.Lang
import app.diacritica.data.user.RehearseRound
import app.diacritica.rehearse.LangFeedback
import app.diacritica.ui.components.Gap
import app.diacritica.ui.components.Hairline
import app.diacritica.ui.components.LangBadge
import app.diacritica.ui.components.PolyIconButton
import app.diacritica.ui.components.PolyIcons
import app.diacritica.ui.components.PolySurface
import app.diacritica.ui.components.SectionLabel
import app.diacritica.ui.components.bodyStyle
import app.diacritica.ui.components.captionStyle
import app.diacritica.ui.components.titleStyle
import app.diacritica.ui.theme.Diacritica
import java.text.DateFormat
import java.util.Date

/** Every past Rehearse round: sentence, score and per-language verdicts, searchable. */
@Composable
fun ArchiveScreen(vm: RehearseViewModel, onBack: () -> Unit, onOpen: (Long) -> Unit) {
    val rounds by vm.archive.collectAsStateWithLifecycle()
    val query by vm.archiveSearch.collectAsStateWithLifecycle()
    val fmt = remember { DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT) }

    LazyColumn(Modifier.fillMaxSize()) {
        item {
            Row(Modifier.padding(horizontal = 8.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                PolyIconButton(PolyIcons.Back, "Back", onBack, tint = Diacritica.colors.ink)
                Text("Rehearse archive", style = titleStyle().copy(fontSize = Diacritica.type.headword), modifier = Modifier.weight(1f))
                Text("${rounds.size}", style = captionStyle())
                Box(Modifier.padding(end = 12.dp))
            }
            if (Diacritica.tokens.heavyRules) Hairline(heavy = true)
            SearchBox(query, vm::setArchiveSearch)
        }
        if (rounds.isEmpty()) item {
            Text(
                if (query.isBlank()) "Rounds you check in Rehearse are saved here, with your answers and all the feedback."
                else "Nothing matches “$query”.",
                style = captionStyle(), modifier = Modifier.padding(20.dp),
            )
        }
        items(rounds, key = { it.id }) { r -> val f = vm.decode(r); RoundRow(r, f, vm.needsAiCheck(f), fmt) { onOpen(r.id) } }
        item { Gap(24.dp) }
    }
}

@Composable
private fun SearchBox(value: String, onChange: (String) -> Unit) {
    val c = Diacritica.colors
    val shape = RoundedCornerShape(Diacritica.shapes.field.coerceAtMost(14.dp))
    Box(
        Modifier.padding(horizontal = 16.dp, vertical = 8.dp).fillMaxWidth().clip(shape).background(c.surface)
            .border(BorderStroke(1.dp, c.line), shape).padding(12.dp),
    ) {
        if (value.isEmpty()) Text("Search sentences, answers, explanations…", style = bodyStyle(c.inkMuted))
        BasicTextField(
            value = value, onValueChange = onChange, singleLine = true, textStyle = bodyStyle(),
            cursorBrush = SolidColor(c.accent),
            modifier = Modifier.fillMaxWidth().semantics { contentDescription = "Search archive" },
        )
    }
}

@Composable
private fun RoundRow(r: RehearseRound, feedback: List<LangFeedback>, unchecked: Boolean, fmt: DateFormat, onClick: () -> Unit) {
    Column(Modifier.padding(horizontal = 20.dp)) {
        Column(
            Modifier.fillMaxWidth().clickable(role = Role.Button, onClick = onClick).padding(vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(fmt.format(Date(r.at)) + " · " + app.diacritica.rehearse.Difficulty.label(r.level) + (r.practiceWord?.let { " · $it" } ?: ""), style = captionStyle(), modifier = Modifier.weight(1f))
                Text("${r.correct} / ${r.total}", style = captionStyle(Diacritica.colors.ink))
            }
            Text(r.prompt, style = TextStyle(fontFamily = Diacritica.fonts.reading, fontSize = 16.sp, color = Diacritica.colors.ink), maxLines = 2, overflow = TextOverflow.Ellipsis)
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                feedback.forEach { f ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(f.lang.short, style = captionStyle(verdictColor(f.verdict)))
                        androidx.compose.material3.Icon(verdictIcon(f.verdict), verdictLabel(f.verdict), tint = verdictColor(f.verdict), modifier = Modifier.padding(start = 3.dp).size(14.dp))
                    }
                }
            }
            if (unchecked) Text("Not checked by Gemini yet", style = captionStyle(Diacritica.colors.accent))
        }
        Hairline()
    }
}

/** One archived round in full: the sentence and every language's feedback. */
@Composable
fun ArchiveDetailScreen(vm: RehearseViewModel, id: Long, onBack: () -> Unit) {
    var round by remember { mutableStateOf<RehearseRound?>(null) }
    var added by remember { mutableStateOf(setOf<Lang>()) }
    var checking by remember { mutableStateOf(false) }
    var checkError by remember { mutableStateOf<String?>(null) }
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    LaunchedEffect(id) { round = vm.round(id) }
    val fmt = remember { DateFormat.getDateTimeInstance(DateFormat.LONG, DateFormat.SHORT) }
    val r = round
    LazyColumn(Modifier.fillMaxSize()) {
        item {
            Row(Modifier.padding(horizontal = 8.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                PolyIconButton(PolyIcons.Back, "Back", onBack, tint = Diacritica.colors.ink)
                SectionLabel(r?.let { fmt.format(Date(it.at)) } ?: "", Modifier.weight(1f))
                if (r != null) PolyIconButton(PolyIcons.Trash, "Delete this round", { vm.deleteRound(r.id); onBack() }, tint = Diacritica.colors.inkMuted)
            }
            if (Diacritica.tokens.heavyRules) Hairline(heavy = true)
        }
        if (r == null) return@LazyColumn
        item {
            PolySurface(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), padding = PaddingValues(16.dp)) {
                val lang = Lang.fromCode(r.promptLang)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    SectionLabel("From ${lang?.englishName ?: r.promptLang} · ${app.diacritica.rehearse.Difficulty.label(r.level)}", Modifier.weight(1f))
                    lang?.let { l -> PolyIconButton(PolyIcons.Speaker, "Play sentence", { vm.speak(l, r.prompt) }) }
                }
                Text(r.prompt, style = TextStyle(fontFamily = Diacritica.fonts.reading, fontSize = 20.sp, color = Diacritica.colors.ink, lineHeight = 28.sp))
                r.practiceWord?.let { Gap(6.dp); Text("Practised: $it", style = captionStyle(Diacritica.colors.accent)) }
                Gap(6.dp)
                Text("Score ${r.correct} / ${r.total}", style = captionStyle())
            }
        }
        val feedback = vm.decode(r)
        if (vm.needsAiCheck(feedback) || checking) item {
            // The Gemini check didn't run (busy, offline, a limit): run it now on the saved answers.
            PolySurface(Modifier.padding(horizontal = 16.dp, vertical = 6.dp), padding = PaddingValues(14.dp)) {
                Text(
                    if (checking) "Checking with Gemini…" else "Only the quick offline check ran for this round.",
                    style = bodyStyle(),
                )
                checkError?.let { Gap(4.dp); Text(it, style = captionStyle(Diacritica.colors.warning)) }
                Gap(10.dp)
                PolyButton(
                    if (checkError == null) "Check with Gemini" else "Try again",
                    {
                        checking = true; checkError = null
                        scope.launch {
                            try { round = vm.recheckRound(r.id) }
                            catch (e: Exception) { checkError = e.message ?: "The check failed." }
                            finally { checking = false }
                        }
                    },
                    enabled = !checking,
                )
            }
        }
        items(feedback, key = { it.lang.code }) { f ->
            PolySurface(Modifier.padding(horizontal = 16.dp, vertical = 6.dp), padding = PaddingValues(14.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    LangBadge(f.lang, boxed = true)
                    Text(f.lang.englishName, style = bodyStyle(), modifier = Modifier.weight(1f))
                }
                Gap(8.dp)
                FeedbackPanel(
                    f, f.lang in added, vm::speak,
                    { vm.addToReviewFrom(r.prompt, f); added = added + f.lang },
                    Modifier,
                )
            }
        }
        item { Gap(24.dp) }
    }
}
