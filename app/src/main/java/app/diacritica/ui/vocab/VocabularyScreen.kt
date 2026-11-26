@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package app.diacritica.ui.vocab

import app.diacritica.ui.components.tonedPinyin
import app.diacritica.ui.components.pinyinAppender
import androidx.compose.ui.text.buildAnnotatedString
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.diacritica.data.Lang
import app.diacritica.ui.components.Gap
import app.diacritica.ui.components.PolyButton
import app.diacritica.ui.components.PolyChip
import app.diacritica.ui.components.PolyIconButton
import app.diacritica.ui.components.PolyIcons
import app.diacritica.ui.components.ScreenHeader
import app.diacritica.ui.components.SectionLabel
import app.diacritica.ui.components.Text
import app.diacritica.ui.components.bodyStyle
import app.diacritica.ui.components.captionStyle
import app.diacritica.ui.components.titleStyle
import app.diacritica.ui.theme.Diacritica
import app.diacritica.vocab.VocabCheck
import app.diacritica.vocab.VocabSet
import app.diacritica.vocab.VocabWord

/** Words to speak: the first form, without "(m)"/"(f)". */
private fun spoken(s: String) = VocabCheck.alternatives(s).firstOrNull().orEmpty().replace(Regex("\\s*\\((m|f|m/f)\\)$"), "")

@Composable
fun VocabularyScreen(vm: VocabularyViewModel, onBack: () -> Unit, onOpenWord: ((Lang, String) -> Unit)? = null) {
    val st by vm.state.collectAsStateWithLifecycle()
    // Tap "Details" under a word: the shared word sheet (same as Reader and Books).
    app.diacritica.ui.components.WordLookupSheet(vm.lookup, onOpenDictionary = onOpenWord)
    val quiz = st.quiz
    val open = st.open?.let { vm.set(it) }
    when {
        quiz != null && open != null -> {
            BackHandler { vm.closeQuiz() }
            QuizView(vm, st, open, quiz)
        }
        open != null -> {
            BackHandler { vm.open(null) }
            SetView(vm, st, open)
        }
        st.catalog -> {
            BackHandler { vm.openCatalog(false) }
            CatalogView(vm, st)
        }
        else -> SetList(vm, st, onBack)
    }
}

@Composable
private fun LangPickers(vm: VocabularyViewModel, st: VocabUiState) {
    Text("Learning", style = captionStyle())
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        st.langs.forEach { l -> PolyChip(l.short, st.lang == l, { vm.setLang(l) }) }
    }
    Text("Shown in", style = captionStyle())
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        (listOf(Lang.EN, Lang.ES) + st.langs).distinct().filter { it != st.lang }.forEach { l -> PolyChip(l.short, st.from == l, { vm.setFrom(l) }) }
    }
}

@Composable
private fun SetList(vm: VocabularyViewModel, st: VocabUiState, onBack: () -> Unit) {
    val c = Diacritica.colors
    val pad = Diacritica.spacing.screen
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = 24.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            PolyIconButton(PolyIcons.Back, "Back", onBack, tint = c.ink, modifier = Modifier.padding(start = 4.dp, top = 10.dp))
            ScreenHeader("Vocabulary", Modifier.weight(1f))
        }
        Column(Modifier.padding(horizontal = pad), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Word sets by topic. Learn a set, then test yourself on it.", style = bodyStyle(c.inkMuted, 14.sp))
            if (st.loading) { Text("Loading…", style = captionStyle()); return@Column }
            LangPickers(vm, st)
            st.message?.let { LaunchedEffect(it) { kotlinx.coroutines.delay(2500); vm.clearMessage() }; Text(it, style = captionStyle(c.success)) }
            Gap(4.dp)
            val shape = RoundedCornerShape(Diacritica.shapes.card.coerceIn(8.dp, 14.dp))
            Row(
                Modifier.fillMaxWidth().clip(shape).background(c.accentSoft.copy(alpha = 0.45f)).border(1.dp, c.accent.copy(alpha = 0.5f), shape)
                    .clickable { vm.openCatalog(true) }.padding(14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text("More topics", style = titleStyle().copy(fontSize = 16.sp))
                    Text(
                        "${app.diacritica.vocab.VocabCatalog.topics.size} more: plants, science, engineering, business, music… or any topic you name." +
                            if (st.writing.isNotEmpty()) "  ·  Writing ${st.writing.size}…" else "",
                        style = captionStyle(),
                    )
                }
                Text("›", style = titleStyle().copy(fontSize = 22.sp, color = c.accent))
            }
            val (added, bundled) = st.sets.partition { it.custom }
            (listOfNotNull(added.takeIf { it.isNotEmpty() }?.let { "Added by you" to it }) + bundled.groupBy { it.category }.toList()).forEach { (category, sets) ->
                Gap(6.dp)
                SectionLabel(category)
                BoxWithConstraints(Modifier.fillMaxWidth()) {
                    val fontScale = androidx.compose.ui.platform.LocalDensity.current.fontScale
                    val cols = when {
                        maxWidth >= 600.dp -> 3
                        maxWidth / fontScale >= 300.dp -> 2
                        else -> 1
                    }
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        sets.chunked(cols).forEach { row ->
                            Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                row.forEach { s -> SetCard(s, st, Modifier.weight(1f).fillMaxHeight()) { vm.open(s.id) } }
                                repeat(cols - row.size) { Spacer(Modifier.weight(1f)) }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SetCard(s: VocabSet, st: VocabUiState, modifier: Modifier, onOpen: () -> Unit) {
    val c = Diacritica.colors
    val shape = RoundedCornerShape(Diacritica.shapes.card.coerceIn(8.dp, 14.dp))
    val best = st.best["${s.id}:${st.lang.code}"]
    Column(
        modifier.clip(shape).background(c.surface).border(1.dp, c.line, shape).clickable(onClick = onOpen).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        Text(s.title, style = titleStyle().copy(fontSize = 16.sp))
        // A taste of the set in the language you're learning.
        Text(s.words.take(3).joinToString(" · ") { VocabCheck.headword(it.form(st.lang), st.lang) }, style = captionStyle(c.inkMuted), maxLines = 1)
        Text(
            "${s.words.size} words" + (best?.let { " · best $it%" } ?: ""),
            style = captionStyle(if (best != null && best >= 90) c.success else c.inkMuted),
        )
    }
}

@Composable
private fun SetView(vm: VocabularyViewModel, st: VocabUiState, set: VocabSet) {
    val c = Diacritica.colors
    val pad = Diacritica.spacing.screen
    val saved by vm.savedKeys.collectAsStateWithLifecycle()
    var mode by rememberSaveable { mutableStateOf(QuizMode.CHOOSE) }
    // Your other languages, under each word when it's opened.
    val others = st.langs.filter { it != st.lang && it != st.from }
    var allOpen by rememberSaveable(set.id) { mutableStateOf(false) }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).navigationBarsPadding().padding(bottom = 24.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            PolyIconButton(PolyIcons.Back, "Back", { vm.open(null) }, tint = c.ink, modifier = Modifier.padding(start = 4.dp, top = 10.dp))
            ScreenHeader(set.title, Modifier.weight(1f))
            if (set.custom) {
                var confirm by remember { mutableStateOf(false) }
                PolyIconButton(PolyIcons.Trash, "Remove this set", { confirm = true }, tint = c.inkMuted, modifier = Modifier.padding(top = 10.dp, end = 4.dp))
                if (confirm) {
                    androidx.compose.material3.AlertDialog(
                        onDismissRequest = { confirm = false },
                        title = { Text("Remove “${set.title}”?") },
                        text = { Text("You can add it again from More topics. Words you added to Review stay there.") },
                        confirmButton = { androidx.compose.material3.TextButton(onClick = { confirm = false; vm.deleteSet(set.id) }) { Text("Remove") } },
                        dismissButton = { androidx.compose.material3.TextButton(onClick = { confirm = false }) { Text("Cancel") } },
                    )
                }
            }
        }
        Column(Modifier.padding(horizontal = pad), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            if (set.custom) Text("Written by Gemini for you. Double-check anything that looks odd in the dictionary.", style = captionStyle())
            Text(
                "${set.words.size} words in ${st.lang.englishName}, shown in ${st.from.englishName}. Tap ☆ to add a word to Review" +
                    if (others.isNotEmpty()) ", or a word to see it in ${others.joinToString(", ") { it.short }}." else ".",
                style = captionStyle(),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                if (others.isNotEmpty()) PolyChip("All languages", allOpen, { allOpen = !allOpen })
                Spacer(Modifier.weight(1f))
                PolyChip("List", !st.grid, { vm.setGrid(false) }, icon = PolyIcons.Layout)
                PolyChip("Grid", st.grid, { vm.setGrid(true) }, icon = PolyIcons.Grip)
            }
            Gap(4.dp)
            val keyOf = { w: VocabWord -> "${st.lang.code}:${VocabCheck.headword(w.form(st.lang), st.lang)}" }
            if (st.grid) {
                var sheet by remember(set.id) { mutableStateOf<VocabWord?>(null) }
                WordGrid(set.words, st.lang, st.from, others, allOpen, saved, keyOf,
                    onSpeak = { w, l -> vm.speak(l, spoken(w.form(l))) }, onSave = { vm.toggleSaved(it) }, onOpen = { sheet = it })
                sheet?.let { w ->
                    WordTileSheet(
                        w, st.lang, st.from, others, keyOf(w) in saved,
                        onSpeak = { l -> vm.speak(l, spoken(w.form(l))) }, onSave = { vm.toggleSaved(w) },
                        onDetails = { sheet = null; vm.lookup.open(VocabCheck.headword(w.form(st.lang), st.lang), st.lang) },
                        onDismiss = { sheet = null },
                    )
                }
            } else set.words.forEach { w ->
                WordRow(
                    w, st.lang, st.from, others, allOpen, keyOf(w) in saved,
                    onSpeak = { l -> vm.speak(l, spoken(w.form(l))) }, onSave = { vm.toggleSaved(w) },
                    onDetails = { vm.lookup.open(VocabCheck.headword(w.form(st.lang), st.lang), st.lang) },
                )
            }
            Gap(14.dp)
            val shape = RoundedCornerShape(Diacritica.shapes.card.coerceIn(8.dp, 14.dp))
            Column(
                Modifier.fillMaxWidth().clip(shape).background(c.accentSoft.copy(alpha = 0.45f)).border(1.dp, c.accent.copy(alpha = 0.5f), shape).padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text("Mini test", style = titleStyle().copy(fontSize = 17.sp))
                Text(
                    "Each word in ${st.from.englishName}; you answer in ${st.lang.englishName}. " +
                        "Choose picks from four; Type checks spelling" + (if (st.lang == Lang.ZH) " (characters or pinyin)." else ", accents and the article."),
                    style = captionStyle(),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    QuizMode.entries.forEach { m -> PolyChip(m.label, mode == m, { mode = m }) }
                }
                PolyButton("Start the test", { vm.startQuiz(set.id, mode) })
            }
        }
    }
}

@Composable
private fun WordRow(
    w: VocabWord, lang: Lang, from: Lang, others: List<Lang>, allOpen: Boolean, saved: Boolean,
    onSpeak: (Lang) -> Unit, onSave: () -> Unit, onDetails: () -> Unit,
) {
    val c = Diacritica.colors
    var open by rememberSaveable(w.form(Lang.EN)) { mutableStateOf(false) }
    val shown = open || allOpen
    Column(Modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp))
                .clickable { open = !open }.padding(vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
                Text(w.form(lang), style = TextStyle(fontFamily = Diacritica.fonts.reading, fontSize = if (lang == Lang.ZH) 20.sp else 17.sp, color = c.ink))
                if (lang == Lang.ZH) Text(tonedPinyin(w.pinyin), style = captionStyle(c.accent))
                Text(pinyinAppender().let { py -> buildAnnotatedString { append(w.form(from)); if (from == Lang.ZH) { append("  "); py(w.pinyin) } } }, style = captionStyle())
            }
            run {
                androidx.compose.material3.Icon(
                    PolyIcons.ChevronDown, if (shown) "Hide other languages" else "Show other languages", tint = c.inkMuted,
                    modifier = Modifier.padding(horizontal = 4.dp).width(16.dp).graphicsLayer { rotationZ = if (shown) 180f else 0f },
                )
            }
            PolyIconButton(PolyIcons.Speaker, "Listen", { onSpeak(lang) }, tint = c.inkMuted)
            PolyIconButton(if (saved) PolyIcons.StarFilled else PolyIcons.Star, if (saved) "Remove from Review" else "Add to Review", onSave, tint = if (saved) c.accent else c.inkMuted)
        }
        if (shown) {
            Column(
                Modifier.fillMaxWidth().padding(start = 2.dp, bottom = 6.dp).clip(RoundedCornerShape(8.dp)).background(c.surface).padding(horizontal = 10.dp, vertical = 6.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                others.forEach { l ->
                    Row(Modifier.fillMaxWidth().clickable { onSpeak(l) }.padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(l.short, style = captionStyle(c.accent), modifier = Modifier.width(36.dp))
                        Text(pinyinAppender().let { py -> buildAnnotatedString { append(w.form(l)); if (l == Lang.ZH) { append("  "); py(w.pinyin) } } }, style = bodyStyle(size = 14.sp), modifier = Modifier.weight(1f))
                    }
                }
                Text(
                    "Details ›", style = captionStyle(c.accent),
                    modifier = Modifier.clip(RoundedCornerShape(4.dp)).clickable(onClick = onDetails).padding(vertical = 6.dp),
                )
            }
        }
    }
}

@Composable
private fun QuizView(vm: VocabularyViewModel, st: VocabUiState, set: VocabSet, q: Quiz) {
    val c = Diacritica.colors
    val pad = Diacritica.spacing.screen
    st.message?.let { LaunchedEffect(it) { kotlinx.coroutines.delay(2000); vm.clearMessage() } }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).navigationBarsPadding().imePadding().padding(bottom = 24.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            PolyIconButton(PolyIcons.Close, "End the test", { vm.closeQuiz() }, tint = c.ink, modifier = Modifier.padding(start = 4.dp, top = 10.dp))
            Text(
                set.title + if (!q.done) "  ·  ${q.index + 1} / ${q.questions.size}" else "",
                style = captionStyle(c.ink), modifier = Modifier.padding(top = 10.dp),
            )
        }
        Column(Modifier.padding(horizontal = pad), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            if (q.done) { QuizResults(vm, st, set, q); return@Column }
            val cur = q.current ?: return@Column
            Gap(16.dp)
            Text(
                cur.word.form(st.from),
                style = titleStyle().copy(fontSize = if (st.from == Lang.ZH) 30.sp else 26.sp),
                textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth(),
            )
            if (st.from == Lang.ZH) Text(tonedPinyin(cur.word.pinyin), style = captionStyle(c.accent), textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
            Text("in ${st.lang.englishName}", style = captionStyle(), textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
            Gap(10.dp)
            val right = cur.word.form(st.lang)
            if (q.mode == QuizMode.CHOOSE) {
                cur.options.forEach { o ->
                    val shape = RoundedCornerShape(Diacritica.shapes.button.coerceAtMost(16.dp))
                    val bg = when {
                        q.answered == null -> c.surface
                        o == right -> c.success.copy(alpha = 0.18f)
                        o == q.answered -> c.error.copy(alpha = 0.16f)
                        else -> c.surface
                    }
                    Box(
                        Modifier.fillMaxWidth().clip(shape).background(bg).border(1.dp, c.line, shape)
                            .clickable(enabled = q.answered == null) { vm.answer(o) }.padding(horizontal = 16.dp, vertical = 14.dp),
                    ) {
                        Text(o, style = bodyStyle(size = if (st.lang == Lang.ZH) 19.sp else 16.sp))
                    }
                }
            } else {
                val focus = remember { FocusRequester() }
                LaunchedEffect(q.index) { runCatching { focus.requestFocus() } }
                val shape = RoundedCornerShape(Diacritica.shapes.field)
                Box(Modifier.fillMaxWidth().clip(shape).background(c.surface).border(1.dp, c.line, shape).padding(14.dp)) {
                    if (q.input.isEmpty()) Text(if (st.lang == Lang.ZH) "Characters or pinyin" else "With its article, if it has one", style = bodyStyle(c.inkMuted))
                    BasicTextField(
                        value = q.input, onValueChange = vm::setInput, singleLine = true, enabled = q.answered == null,
                        textStyle = bodyStyle(size = 17.sp), cursorBrush = SolidColor(c.accent),
                        keyboardOptions = app.diacritica.ui.components.keyboardFor(st.lang, KeyboardOptions(imeAction = ImeAction.Done, autoCorrectEnabled = false)),
                        keyboardActions = KeyboardActions(onDone = { if (q.answered == null && q.input.isNotBlank()) vm.answer(q.input) else if (q.answered != null) vm.next() }),
                        modifier = Modifier.fillMaxWidth().focusRequester(focus),
                    )
                }
                if (q.answered == null) PolyButton("Check", { vm.answer(q.input) }, enabled = q.input.isNotBlank())
            }
            q.result?.let { r ->
                val (label, color) = when (r.grade) {
                    VocabCheck.Grade.RIGHT -> "Right" to c.success
                    VocabCheck.Grade.ALMOST -> "Almost" to c.warning
                    VocabCheck.Grade.WRONG -> "Not quite" to c.error
                }
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(label, style = bodyStyle(color, 16.sp).copy(fontWeight = FontWeight.SemiBold))
                    if (r.grade != VocabCheck.Grade.RIGHT || q.mode == QuizMode.TYPE) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(pinyinAppender().let { py -> buildAnnotatedString { append(right); if (st.lang == Lang.ZH) { append("  "); py(cur.word.pinyin) } } }, style = bodyStyle(size = 16.sp), modifier = Modifier.weight(1f))
                            PolyIconButton(PolyIcons.Speaker, "Listen", { vm.speak(st.lang, spoken(right)) }, tint = c.inkMuted)
                        }
                    }
                    r.note?.let { Text(it, style = captionStyle()) }
                }
                PolyButton(if (q.index + 1 < q.questions.size) "Next" else "See results", { vm.next() })
            }
        }
    }
}

@Composable
private fun QuizResults(vm: VocabularyViewModel, st: VocabUiState, set: VocabSet, q: Quiz) {
    val c = Diacritica.colors
    val missed = vm.missed(q)
    val right = q.grades.count { it == VocabCheck.Grade.RIGHT }
    Gap(16.dp)
    Text("${q.score}%", style = titleStyle().copy(fontSize = 40.sp, color = if (q.score >= 80) c.success else if (q.score >= 50) c.warning else c.error))
    Text("$right of ${q.questions.size} right" + (q.grades.count { it == VocabCheck.Grade.ALMOST }.takeIf { it > 0 }?.let { " · $it almost" } ?: ""), style = bodyStyle())
    st.message?.let { Text(it, style = captionStyle(c.success)) }
    if (missed.isNotEmpty()) {
        Gap(6.dp)
        SectionLabel("To work on")
        missed.forEach { w ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(pinyinAppender().let { py -> buildAnnotatedString { append(w.form(st.lang)); if (st.lang == Lang.ZH) { append("  "); py(w.pinyin) } } }, style = bodyStyle(size = 16.sp))
                    Text(w.form(st.from), style = captionStyle())
                }
                PolyIconButton(PolyIcons.Speaker, "Listen", { vm.speak(st.lang, spoken(w.form(st.lang))) }, tint = c.inkMuted)
            }
        }
    }
    Gap(8.dp)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (missed.isNotEmpty()) {
            PolyButton("Retry the ${missed.size} missed", { vm.startQuiz(set.id, q.mode, missed) })
            PolyButton("Add them to Review", { vm.saveAll(missed) }, primary = false)
        }
        PolyButton("Test the whole set again", { vm.startQuiz(set.id, q.mode) }, primary = missed.isEmpty())
        PolyButton("Back to the words", { vm.closeQuiz() }, primary = false)
    }
}

@Composable
private fun CatalogView(vm: VocabularyViewModel, st: VocabUiState) {
    val c = Diacritica.colors
    val pad = Diacritica.spacing.screen
    var count by rememberSaveable { mutableStateOf(20) }
    var advanced by rememberSaveable { mutableStateOf(false) }
    var own by rememberSaveable { mutableStateOf("") }
    val have = st.sets.filter { it.custom }.associateBy { it.id }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).navigationBarsPadding().imePadding().padding(bottom = 24.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            PolyIconButton(PolyIcons.Back, "Back", { vm.openCatalog(false) }, tint = c.ink, modifier = Modifier.padding(start = 4.dp, top = 10.dp))
            ScreenHeader("More topics", Modifier.weight(1f))
        }
        Column(Modifier.padding(horizontal = pad), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                "Pick a topic and Gemini writes its words in all six languages, with articles and pinyin. " +
                    "It's kept on your phone, so it works offline afterwards.",
                style = bodyStyle(c.inkMuted, 14.sp),
            )
            Text("Words per set", style = captionStyle())
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) { listOf(12, 20, 30).forEach { n -> PolyChip("$n", count == n, { count = n }) } }
            Text("Level", style = captionStyle())
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                PolyChip("Everyday", !advanced, { advanced = false })
                PolyChip("Advanced", advanced, { advanced = true })
            }
            st.catalogError?.let { Text(it, style = captionStyle(c.error)) }
            st.message?.let { LaunchedEffect(it) { kotlinx.coroutines.delay(2500); vm.clearMessage() }; Text(it, style = captionStyle(c.success)) }

            Gap(4.dp)
            SectionLabel("Your own topic")
            val shape = RoundedCornerShape(Diacritica.shapes.field)
            Box(Modifier.fillMaxWidth().clip(shape).background(c.surface).border(1.dp, c.line, shape).padding(12.dp)) {
                if (own.isEmpty()) Text("e.g. photography, the human brain, camping, wine tasting", style = bodyStyle(c.inkMuted))
                BasicTextField(
                    value = own, onValueChange = { own = it.take(80) }, singleLine = true,
                    textStyle = bodyStyle(), cursorBrush = SolidColor(c.accent), modifier = Modifier.fillMaxWidth(),
                )
            }
            val ownBusy = st.writing.any { it.startsWith("custom-") }
            PolyButton(if (ownBusy) "Writing…" else "Write this set", { vm.addTopic(null, own, count, advanced); own = "" }, enabled = own.isNotBlank() && !ownBusy)

            app.diacritica.vocab.VocabCatalog.topics.groupBy { it.category }.forEach { (category, topics) ->
                Gap(6.dp)
                SectionLabel(category)
                topics.forEach { t ->
                    val added = have[t.id]
                    val busy = t.id in st.writing
                    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(t.title, style = bodyStyle(size = 16.sp).copy(fontWeight = FontWeight.Medium))
                            Text(if (added != null) "${added.words.size} words · added" else t.hint, style = captionStyle(if (added != null) c.success else c.inkMuted))
                        }
                        Spacer(Modifier.width(8.dp))
                        when {
                            added != null -> PolyButton("Open", { vm.open(t.id) }, primary = false)
                            busy -> Text("Writing…", style = captionStyle(c.accent))
                            else -> PolyButton("Add", { vm.addTopic(t, null, count, advanced) }, primary = false)
                        }
                    }
                }
            }
        }
    }
}

/** Words as tiles: 2 columns on phones, more on wider screens. Tap a tile for all its languages. */
@Composable
private fun WordGrid(
    words: List<VocabWord>, lang: Lang, from: Lang, others: List<Lang>, allOpen: Boolean, saved: Set<String>,
    keyOf: (VocabWord) -> String, onSpeak: (VocabWord, Lang) -> Unit, onSave: (VocabWord) -> Unit, onOpen: (VocabWord) -> Unit,
) {
    val c = Diacritica.colors
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val cols = (maxWidth / 165.dp).toInt().coerceIn(2, 5)
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            words.chunked(cols).forEach { row ->
                Row(Modifier.fillMaxWidth().height(androidx.compose.foundation.layout.IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    row.forEach { w ->
                        val isSaved = keyOf(w) in saved
                        Column(
                            Modifier.weight(1f).fillMaxHeight().clip(RoundedCornerShape(Diacritica.shapes.card.coerceIn(8.dp, 14.dp)))
                                .background(c.surface).clickable { onOpen(w) }.padding(start = 10.dp, top = 8.dp, bottom = 4.dp, end = 2.dp),
                        ) {
                            Text(w.form(lang), style = TextStyle(fontFamily = Diacritica.fonts.reading, fontSize = if (lang == Lang.ZH) 22.sp else 17.sp, color = c.ink), maxLines = 2)
                            if (lang == Lang.ZH) Text(tonedPinyin(w.pinyin), style = captionStyle(c.accent), maxLines = 1)
                            Text(pinyinAppender().let { py -> buildAnnotatedString { append(w.form(from)); if (from == Lang.ZH) { append("  "); py(w.pinyin) } } }, style = captionStyle(), maxLines = 2)
                            if (allOpen) others.forEach { l ->
                                Text(pinyinAppender().let { py -> buildAnnotatedString { append(l.short + "  "); append(w.form(l)); if (l == Lang.ZH) { append("  "); py(w.pinyin) } } }, style = captionStyle(), maxLines = 1)
                            }
                            Spacer(Modifier.weight(1f))
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                                PolyIconButton(PolyIcons.Speaker, "Listen", { onSpeak(w, lang) }, tint = c.inkMuted)
                                PolyIconButton(if (isSaved) PolyIcons.StarFilled else PolyIcons.Star, if (isSaved) "Remove from Review" else "Add to Review", { onSave(w) }, tint = if (isSaved) c.accent else c.inkMuted)
                            }
                        }
                    }
                    repeat(cols - row.size) { Spacer(Modifier.weight(1f)) }
                }
            }
        }
    }
}

/** A grid tile opened: the word in every language of the set, each one tap to listen. */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
private fun WordTileSheet(
    w: VocabWord, lang: Lang, from: Lang, others: List<Lang>, saved: Boolean,
    onSpeak: (Lang) -> Unit, onSave: () -> Unit, onDetails: () -> Unit, onDismiss: () -> Unit,
) {
    val c = Diacritica.colors
    androidx.compose.material3.ModalBottomSheet(onDismissRequest = onDismiss, containerColor = c.ground) {
        Column(Modifier.padding(horizontal = 20.dp).padding(bottom = 20.dp).navigationBarsPadding(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(w.form(lang), style = TextStyle(fontFamily = Diacritica.fonts.reading, fontSize = if (lang == Lang.ZH) 30.sp else 24.sp, color = c.ink))
                    if (lang == Lang.ZH) Text(tonedPinyin(w.pinyin), style = bodyStyle(c.accent))
                }
                PolyIconButton(PolyIcons.Speaker, "Listen", { onSpeak(lang) })
                PolyIconButton(if (saved) PolyIcons.StarFilled else PolyIcons.Star, if (saved) "Remove from Review" else "Add to Review", onSave, tint = if (saved) c.accent else c.inkMuted)
            }
            Gap(6.dp)
            (listOf(from) + others).forEach { l ->
                Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(6.dp)).clickable { onSpeak(l) }.padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(l.short, style = captionStyle(c.accent), modifier = Modifier.width(40.dp))
                    Text(pinyinAppender().let { py -> buildAnnotatedString { append(w.form(l)); if (l == Lang.ZH) { append("  "); py(w.pinyin) } } }, style = bodyStyle(size = 16.sp), modifier = Modifier.weight(1f))
                    androidx.compose.material3.Icon(PolyIcons.Speaker, "Listen", tint = c.inkMuted, modifier = Modifier.width(18.dp))
                }
            }
            Text(
                "Details ›", style = captionStyle(c.accent),
                modifier = Modifier.clip(RoundedCornerShape(4.dp)).clickable(onClick = onDetails).padding(vertical = 8.dp),
            )
        }
    }
}
