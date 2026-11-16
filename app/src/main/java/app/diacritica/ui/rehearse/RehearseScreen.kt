package app.diacritica.ui.rehearse

import androidx.compose.ui.platform.LocalView
import androidx.compose.runtime.DisposableEffect
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.compose.rememberLauncherForActivityResult
import android.speech.RecognizerIntent
import android.content.Intent
import android.app.Activity
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.diacritica.data.Lang
import app.diacritica.rehearse.LangFeedback
import app.diacritica.rehearse.Verdict
import app.diacritica.rehearse.WordDiff
import app.diacritica.settings.AppSettings
import app.diacritica.rehearse.GrammarTopics
import app.diacritica.settings.RehearsePath
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.DropdownMenu
import app.diacritica.settings.RehearseLayout
import app.diacritica.ui.components.Gap
import app.diacritica.ui.components.Hairline
import app.diacritica.ui.components.InlineMarkdown
import app.diacritica.ui.components.LangBadge
import app.diacritica.ui.components.PolyButton
import app.diacritica.ui.components.PolyChip
import app.diacritica.ui.components.PolyIconButton
import app.diacritica.ui.components.PolyIcons
import app.diacritica.ui.components.PolySurface
import app.diacritica.ui.components.ScreenHeader
import app.diacritica.ui.components.SectionLabel
import app.diacritica.ui.components.MenuSection
import app.diacritica.ui.components.ToggleMenuItem
import app.diacritica.ui.components.bodyStyle
import app.diacritica.ui.components.captionStyle
import app.diacritica.ui.theme.Diacritica
import app.diacritica.ui.theme.SurfaceStyle

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun RehearseScreen(vm: RehearseViewModel, settings: AppSettings, onOpenSettings: () -> Unit, onOpenArchive: () -> Unit) {
    LaunchedEffect(Unit) { vm.ensureStarted() }
    val st by vm.state.collectAsStateWithLifecycle()
    val handsFree by vm.handsFree.collectAsStateWithLifecycle()
    val listen by vm.listen.collectAsStateWithLifecycle()
    // Hands-free: open the system voice input in the requested language; its result goes back to the round.
    val voice = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { res ->
        val text = if (res.resultCode == Activity.RESULT_OK) res.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull() else null
        vm.onHeard(text)
    }
    LaunchedEffect(listen) {
        val l = listen ?: return@LaunchedEffect
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE, l.locale.toLanguageTag())
            .putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
            .putExtra(RecognizerIntent.EXTRA_PROMPT, "Say it in ${l.englishName}")
        runCatching { voice.launch(intent) }.onFailure { vm.onHeard(null) }
    }
    // Keep the screen on during hands-free rounds.
    val view = LocalView.current
    DisposableEffect(handsFree != null) {
        view.keepScreenOn = handsFree != null
        onDispose { view.keepScreenOn = false }
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        ScreenHeader("Rehearse") {
            if (st.roundsDone > 0) Text("${st.roundsDone} done", style = captionStyle())
            PolyIconButton(
                if (handsFree != null) PolyIcons.Stop else PolyIcons.Mic,
                if (handsFree != null) "Stop hands-free" else "Hands-free: listen and answer by voice",
                { if (handsFree != null) vm.stopHandsFree() else vm.startHandsFree() },
                tint = if (handsFree != null) Diacritica.colors.accent else Diacritica.colors.inkMuted,
            )
            PolyIconButton(PolyIcons.Archive, "Rehearse archive", onOpenArchive, tint = Diacritica.colors.inkMuted)
        }
        // One tidy row: practice path, grammar focus (Grammar path only) and the languages you answer in.
        FlowRow(
            Modifier.padding(horizontal = Diacritica.spacing.screen, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            PathPicker(settings.rehearsePath, onPick = vm::setPath)
            if (settings.rehearsePath == RehearsePath.GRAMMAR) FocusPicker(settings, onPick = vm::setGrammarFocus)
            LanguagePicker(settings, onToggle = vm::toggleRehearseLang)
        }
        handsFree?.let { status ->
            Row(
                Modifier.fillMaxWidth().padding(horizontal = Diacritica.spacing.screen, vertical = 6.dp)
                    .clip(RoundedCornerShape(8.dp)).background(Diacritica.colors.accentSoft).padding(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text("Hands-free", style = captionStyle(Diacritica.colors.onAccentSoft))
                    Text(status, style = bodyStyle(Diacritica.colors.onAccentSoft))
                }
                PolyButton("Stop", vm::stopHandsFree, primary = false)
            }
        }
        if (!st.loading) st.notice?.let {
            Text(it, style = captionStyle(), modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp))
        }
        when {
            st.loading -> Text(
                if (settings.rehearsePath.online) "Writing a new sentence…" else "Loading…",
                style = captionStyle(), modifier = Modifier.padding(20.dp),
            )
            st.error != null -> Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(st.error!!, style = bodyStyle())
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    PolyButton("Try again", vm::next)
                    PolyButton("Use Everyday", { vm.setPath(RehearsePath.EVERYDAY) }, primary = false)
                }
            }
            st.empty -> Text("No sentences available. Check Settings › Rehearse levels.", style = captionStyle(), modifier = Modifier.padding(20.dp))
            else -> {
                if (settings.geminiKey.isBlank()) KeyBanner(onOpenSettings)
                PromptCard(st, onSpeak = vm::speakPrompt)
                Gap(12.dp)
                if (!st.submitted) {
                    st.targets.forEach { l ->
                        AnswerField(l, st.answers[l].orEmpty(), { vm.setAnswer(l, it) }, hint = if (l == Lang.ZH && settings.pinyinHint) st.references[l]?.firstOrNull() else null, vm = vm)
                    }
                    Row(Modifier.padding(horizontal = 16.dp, vertical = 12.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        PolyButton("Check", vm::submit, modifier = Modifier.weight(1f), enabled = st.targets.any { st.answers[it]?.isNotBlank() == true })
                        PolyButton("Skip", vm::next, primary = false)
                    }
                } else {
                    Feedback(st, settings, vm)
                    Row(Modifier.padding(horizontal = 16.dp, vertical = 14.dp)) {
                        PolyButton("Next sentence", vm::next, modifier = Modifier.fillMaxWidth())
                    }
                }
                Text(
                    when {
                        st.source == "Tatoeba" -> "Sentences: Tatoeba (CC BY 2.0 FR)" + if (st.generated.isNotEmpty()) " · some references written by Gemini" else ""
                        else -> st.source + if (st.source != "Written by Gemini" && st.generated.isNotEmpty()) " · some references written by Gemini" else ""
                    },
                    style = captionStyle(), modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
                )
            }
        }
    }
}

/** "Path: Everyday ▾": a menu of practice paths, each with one line on what it does. */
@Composable
private fun PathPicker(current: RehearsePath, onPick: (RehearsePath) -> Unit) {
    val c = Diacritica.colors
    var open by remember { mutableStateOf(false) }
    Box {
        DropChip(current.label) { open = true }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }, containerColor = c.ground) {
            RehearsePath.entries.forEach { p ->
                DropdownMenuItem(
                    text = {
                        Column(Modifier.padding(vertical = 2.dp)) {
                            Text(p.label + if (p.online) "  ·  online" else "", style = bodyStyle(if (p == current) c.accent else c.ink))
                            Text(p.blurb, style = captionStyle())
                        }
                    },
                    onClick = { open = false; if (p != current) onPick(p) },
                )
            }
        }
    }
}

/** "FR · DE · ZH ▾": the languages to practise in Rehearse (separate from the Dictionary's), toggled in a menu. */
@Composable
private fun LanguagePicker(settings: AppSettings, onToggle: (Lang) -> Unit) {
    val c = Diacritica.colors
    var open by remember { mutableStateOf(false) }
    val chosen = settings.activeLanguages.filter { it in settings.rehearseLanguages }
    Box {
        DropChip(if (chosen.isEmpty()) "Languages" else chosen.joinToString(" · ") { it.short }) { open = true }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }, containerColor = c.ground) {
            MenuSection("Practise in")
            settings.activeLanguages.forEach { l ->
                ToggleMenuItem(l.englishName, l in settings.rehearseLanguages, { onToggle(l) })
            }
        }
    }
}

/** Grammar focus: the structure to practise, limited to the ones that matter for your languages. */
@Composable
private fun FocusPicker(settings: AppSettings, onPick: (String) -> Unit) {
    val c = Diacritica.colors
    var open by remember { mutableStateOf(false) }
    val current = GrammarTopics.byKey(settings.grammarFocus)
    Box {
        DropChip(current.label) { open = true }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }, containerColor = c.ground) {
            GrammarTopics.forLanguages(settings.rehearseLanguages).forEach { t ->
                DropdownMenuItem(
                    text = {
                        val langs = if (t.langs.isEmpty()) "" else "  ·  " + t.langs.filter { it in settings.activeLanguages }.joinToString(" ") { it.short }
                        Text(t.label + langs, style = bodyStyle(if (t.key == current.key) c.accent else c.ink))
                    },
                    onClick = { open = false; if (t.key != current.key) onPick(t.key) },
                )
            }
        }
    }
}

/** An outlined button with a ▾, styled like the chips. */
@Composable
private fun DropChip(text: String, onClick: () -> Unit) {
    val c = Diacritica.colors
    val shape = RoundedCornerShape(Diacritica.shapes.chip)
    Row(
        Modifier.clip(shape).background(c.surface).border(1.dp, c.ink.copy(alpha = 0.28f), shape)
            .clickable(onClick = onClick).padding(start = 12.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text, style = bodyStyle(size = 13.sp), maxLines = 1)
        Icon(PolyIcons.ChevronDown, contentDescription = null, tint = c.inkMuted, modifier = Modifier.padding(start = 4.dp).size(16.dp))
    }
}

@Composable
private fun KeyBanner(onOpenSettings: () -> Unit) {
    Column(
        Modifier.padding(horizontal = 16.dp, vertical = 6.dp).fillMaxWidth().clip(RoundedCornerShape(Diacritica.shapes.card))
            .background(Diacritica.colors.accentSoft).padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text("Answers are checked offline only (exact matches and accents). Add a Gemini API key for full grammar explanations.",
            style = bodyStyle(Diacritica.colors.onAccentSoft))
        PolyButton("Open Settings", onOpenSettings, primary = false)
    }
}

@Composable
private fun PromptCard(st: RehearseUiState, onSpeak: () -> Unit) {
    PolySurface(Modifier.padding(horizontal = 16.dp), padding = PaddingValues(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            SectionLabel("Translate from ${st.promptLang?.englishName ?: ""}" + (st.group?.level?.let { " · $it" } ?: ""), Modifier.weight(1f))
            PolyIconButton(PolyIcons.Speaker, "Play sentence", onSpeak)
        }
        st.note?.let {
            Text(it, style = captionStyle(Diacritica.colors.accent), modifier = Modifier.padding(bottom = 6.dp))
        }
        Text(st.prompt, style = TextStyle(fontFamily = Diacritica.fonts.reading, fontSize = 21.sp, color = Diacritica.colors.ink, lineHeight = 29.sp))
        st.practiceWord?.let { (l, w) ->
            Gap(8.dp)
            Text("Practising: $w (${l.englishName})", style = captionStyle(Diacritica.colors.accent))
        }
    }
}

@Composable
private fun AnswerField(lang: Lang, value: String, onChange: (String) -> Unit, hint: String?, vm: RehearseViewModel) {
    val c = Diacritica.colors
    val shape = RoundedCornerShape(Diacritica.shapes.field.coerceAtMost(14.dp))
    var pinyinHint by remember(hint) { mutableStateOf<String?>(null) }
    LaunchedEffect(hint) { pinyinHint = hint?.let { vm.pinyinOf(it) }?.ifBlank { null } }
    Column(Modifier.padding(horizontal = 16.dp, vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            LangBadge(lang)
            Text(lang.nativeName, style = captionStyle(c.ink))
            if (lang == Lang.ZH) Text("characters or pinyin with tones · simpler is fine", style = captionStyle())
        }
        Box(
            Modifier.fillMaxWidth().clip(shape).background(c.surface)
                .border(BorderStroke(if (Diacritica.tokens.heavyRules) 2.dp else 1.dp, if (Diacritica.tokens.heavyRules) c.rule else c.line), shape)
                .padding(horizontal = 12.dp, vertical = 12.dp),
        ) {
            if (value.isEmpty()) Text("Your ${lang.englishName} translation…", style = bodyStyle(c.inkMuted, 17.sp))
            BasicTextField(
                value = value, onValueChange = onChange,
                textStyle = TextStyle(fontFamily = Diacritica.fonts.reading, fontSize = 17.sp, color = c.ink, lineHeight = 24.sp),
                cursorBrush = SolidColor(c.accent),
                keyboardOptions = KeyboardOptions(autoCorrectEnabled = false),
                modifier = Modifier.fillMaxWidth().heightIn(min = 26.dp).semantics { contentDescription = "${lang.englishName} answer" },
            )
        }
        pinyinHint?.let { Text("Hint: $it", style = captionStyle(c.accent)) }
    }
}

// ---------------------------------------------------------------- feedback

@Composable
private fun Feedback(st: RehearseUiState, settings: AppSettings, vm: RehearseViewModel) {
    // Summary strip: one verdict per language.
    Row(
        Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically,
    ) {
        val ok = st.feedback.values.count { it.verdict == Verdict.CORRECT || it.verdict == Verdict.ACCEPTABLE }
        Text("$ok / ${st.targets.size}", style = TextStyle(fontFamily = Diacritica.fonts.display, fontSize = 20.sp, fontWeight = FontWeight.SemiBold, color = Diacritica.colors.ink))
        if (st.checking) Text("Checking with Gemini…", style = captionStyle(Diacritica.colors.accent))
    }
    st.aiError?.let { err ->
        Row(Modifier.padding(start = 20.dp, end = 12.dp, top = 4.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(err, style = captionStyle(Diacritica.colors.warning), modifier = Modifier.weight(1f))
            if (!err.contains("API key")) {
                androidx.compose.material3.TextButton(onClick = vm::retryAiCheck) { Text("Check again", style = captionStyle(Diacritica.colors.accent)) }
            }
        }
    }
    Gap(8.dp)
    when (settings.rehearseLayout) {
        RehearseLayout.TABS -> TabsFeedback(st, vm)
        RehearseLayout.STACKED -> StackedFeedback(st, vm)
        RehearseLayout.TILES -> TilesFeedback(st, vm)
        RehearseLayout.REPORT -> ReportFeedback(st, vm)
    }
}

@Composable
fun verdictColor(v: Verdict): Color = when (v) {
    Verdict.CORRECT -> Diacritica.colors.success
    Verdict.ACCEPTABLE -> Diacritica.colors.warning
    Verdict.INCORRECT -> Diacritica.colors.error
    Verdict.UNGRADED -> Diacritica.colors.inkMuted
}

fun verdictLabel(v: Verdict): String = when (v) {
    Verdict.CORRECT -> "Correct"
    Verdict.ACCEPTABLE -> "Acceptable"
    Verdict.INCORRECT -> "Needs a fix"
    Verdict.UNGRADED -> "Not checked"
}

/** Drawn icon for a verdict (replaces the old ✓ ≈ ✕ ? glyphs). */
fun verdictIcon(v: Verdict): androidx.compose.ui.graphics.vector.ImageVector = when (v) {
    Verdict.CORRECT -> PolyIcons.Check; Verdict.ACCEPTABLE -> PolyIcons.Approx; Verdict.INCORRECT -> PolyIcons.Close; Verdict.UNGRADED -> PolyIcons.Question
}

/** Layout 1: one tab per language. */
@Composable
private fun TabsFeedback(st: RehearseUiState, vm: RehearseViewModel) {
    var selected by rememberSaveable(st.group?.id) {
        mutableStateOf(st.targets.firstOrNull { st.feedback[it]?.verdict == Verdict.INCORRECT }?.code ?: st.targets.firstOrNull()?.code)
    }
    Row(
        Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        st.targets.forEach { l ->
            val f = st.feedback[l]
            PolyChip(l.short, selected == l.code, { selected = l.code }, icon = f?.let { verdictIcon(it.verdict) }, iconTint = f?.let { verdictColor(it.verdict) })
        }
    }
    Hairline(Modifier.padding(horizontal = 16.dp))
    val lang = st.targets.firstOrNull { it.code == selected } ?: return
    st.feedback[lang]?.let { FeedbackPanel(it, lang in st.addedToReview, vm::speak, { vm.addToReview(lang) }, Modifier.padding(horizontal = 16.dp, vertical = 12.dp), sourceLabel = sourceName(st)) }
}

/** Layout 2: all languages stacked; correct ones collapsed. */
@Composable
private fun StackedFeedback(st: RehearseUiState, vm: RehearseViewModel) {
    Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        st.targets.forEach { l ->
            val f = st.feedback[l] ?: return@forEach
            var open by rememberSaveable(st.group?.id, l) { mutableStateOf(f.verdict != Verdict.CORRECT) }
            PolySurface(highlighted = f.verdict == Verdict.INCORRECT, padding = PaddingValues(14.dp), onClick = { open = !open }) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    LangBadge(l, boxed = true)
                    Text(f.answer.ifBlank { "—" }, style = bodyStyle(), modifier = Modifier.weight(1f), maxLines = if (open) 4 else 1)
                    Text(verdictLabel(f.verdict), style = captionStyle(verdictColor(f.verdict)))
                }
                if (open) {
                    Gap(10.dp)
                    FeedbackPanel(f, l in st.addedToReview, vm::speak, { vm.addToReview(l) }, Modifier, showAnswerLine = false, sourceLabel = sourceName(st))
                }
            }
        }
    }
}

/** Short source name for reference labels ("Tatoeba", "Europarl", "a Reader story"…). */
private fun sourceName(st: RehearseUiState): String = when {
    st.source.startsWith("From “") -> "the story"
    st.source == "Weak spot" -> "earlier round"
    else -> st.source.substringBefore(" (")
}

/** Layout 3: a tile per language with its verdict and number of errors; the chosen tile opens below. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TilesFeedback(st: RehearseUiState, vm: RehearseViewModel) {
    val c = Diacritica.colors
    var selected by rememberSaveable(st.group?.id) {
        mutableStateOf(st.targets.firstOrNull { st.feedback[it]?.verdict == Verdict.INCORRECT }?.code ?: st.targets.firstOrNull()?.code)
    }
    FlowRow(
        Modifier.padding(horizontal = 16.dp), maxItemsInEachRow = 3,
        horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        st.targets.forEach { l ->
            val f = st.feedback[l] ?: return@forEach
            val on = selected == l.code
            val shape = RoundedCornerShape(Diacritica.shapes.card.coerceAtMost(14.dp))
            Column(
                Modifier.weight(1f).clip(shape)
                    .background(if (on) c.accentSoft else c.surface)
                    .border(if (on) 2.dp else 1.dp, if (on) c.accent else c.line, shape)
                    .clickable(role = Role.Tab) { selected = l.code }
                    .padding(10.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                LangBadge(l)
                androidx.compose.material3.Icon(verdictIcon(f.verdict), verdictLabel(f.verdict), tint = verdictColor(f.verdict), modifier = Modifier.size(28.dp))
                Text(
                    if (f.errors.isEmpty()) verdictLabel(f.verdict) else "${f.errors.size} " + if (f.errors.size == 1) "error" else "errors",
                    style = captionStyle(), maxLines = 1,
                )
            }
        }
    }
    val lang = st.targets.firstOrNull { it.code == selected } ?: return
    st.feedback[lang]?.let {
        PolySurface(Modifier.padding(horizontal = 16.dp, vertical = 12.dp), padding = PaddingValues(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                LangBadge(lang, boxed = true)
                Text(lang.nativeName, style = captionStyle(c.ink))
            }
            Gap(8.dp)
            FeedbackPanel(it, lang in st.addedToReview, vm::speak, { vm.addToReview(lang) }, Modifier, sourceLabel = sourceName(st))
        }
    }
}

/** Layout 4: a compact report, one row per language (answer with corrections, errors in one line each); "Why" opens the explanations. */
@Composable
private fun ReportFeedback(st: RehearseUiState, vm: RehearseViewModel) {
    val c = Diacritica.colors
    Column(Modifier.padding(horizontal = 16.dp)) {
        Hairline(heavy = true)
        st.targets.forEach { l ->
            val f = st.feedback[l] ?: return@forEach
            var open by rememberSaveable(st.group?.id, l) { mutableStateOf(false) }
            Column(Modifier.fillMaxWidth().padding(vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    LangBadge(l)
                    Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                        androidx.compose.material3.Icon(verdictIcon(f.verdict), null, tint = verdictColor(f.verdict), modifier = Modifier.size(16.dp))
                        Text("  " + verdictLabel(f.verdict), style = captionStyle(verdictColor(f.verdict)))
                    }
                    if (f.errors.isNotEmpty()) Text(f.errors.map { it.type.replace('-', ' ') }.distinct().joinToString(" · "), style = captionStyle(), maxLines = 1)
                }
                val corrected = f.corrected?.let { plain(it) }
                Text(
                    if (corrected != null && RuleCheckerBridge.differs(f.answer, corrected)) diffText(f.answer, corrected) else AnnotatedString(f.answer.ifBlank { "—" }),
                    style = TextStyle(fontFamily = Diacritica.fonts.reading, fontSize = 16.sp, color = c.ink, lineHeight = 22.sp),
                )
                f.errors.forEach { e ->
                    if (e.wrong.isNotBlank() || e.right.isNotBlank()) Text(buildAnnotatedString {
                        withStyle(SpanStyle(color = c.error, textDecoration = TextDecoration.LineThrough)) { append(plain(e.wrong)) }
                        append(" → ")
                        withStyle(SpanStyle(color = c.success, fontWeight = FontWeight.SemiBold)) { append(plain(e.right)) }
                    }, style = captionStyle(c.ink))
                }
                if (f.reference != null && f.verdict != Verdict.CORRECT) {
                    Text("Ref.: ${f.reference}", style = captionStyle(), maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    if (f.errors.isNotEmpty() || f.alternatives.isNotEmpty() || f.note != null) {
                        Text(if (open) "Hide" else "Why", style = captionStyle(c.accent),
                            modifier = Modifier.clickable(role = Role.Button) { open = !open }.padding(vertical = 6.dp))
                    }
                    if (l in st.addedToReview) Text("Added to Review", style = captionStyle(c.success), modifier = Modifier.padding(vertical = 6.dp))
                    else Text("+ Review", style = captionStyle(c.accent), modifier = Modifier.clickable(role = Role.Button) { vm.addToReview(l) }.padding(vertical = 6.dp))
                }
                if (open) FeedbackPanel(f, l in st.addedToReview, vm::speak, { vm.addToReview(l) }, Modifier.padding(top = 4.dp), showAnswerLine = false, sourceLabel = sourceName(st))
            }
            Hairline()
        }
    }
}

@Composable
internal fun FeedbackPanel(
    f: LangFeedback,
    added: Boolean,
    onSpeak: (Lang, String) -> Unit,
    onAddToReview: () -> Unit,
    modifier: Modifier,
    showAnswerLine: Boolean = true,
    /** Where human references come from, e.g. "Tatoeba" or "Europarl". */
    sourceLabel: String = "Tatoeba",
) {
    val c = Diacritica.colors
    Column(modifier, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(verdictLabel(f.verdict) + if (!f.byAi && f.verdict != Verdict.UNGRADED) " · offline check" else "", style = captionStyle(verdictColor(f.verdict)))
        if (showAnswerLine || f.corrected != null) {
            SectionLabel("Your answer")
            val corrected = f.corrected?.let { plain(it) }
            if (corrected != null && RuleCheckerBridge.differs(f.answer, corrected)) {
                Text(diffText(f.answer, corrected), style = TextStyle(fontFamily = Diacritica.fonts.reading, fontSize = 18.sp, color = c.ink, lineHeight = 26.sp))
            } else {
                Text(f.answer.ifBlank { "—" }, style = TextStyle(fontFamily = Diacritica.fonts.reading, fontSize = 18.sp, color = c.ink, lineHeight = 26.sp))
            }
        }
        f.errors.forEach { e ->
            Column(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(Diacritica.shapes.card.coerceAtMost(12.dp))).background(c.surfaceAlt).padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                SectionLabel(e.type.replace('-', ' '), color = c.error)
                if (e.wrong.isNotBlank() || e.right.isNotBlank()) {
                    Text(buildAnnotatedString {
                        withStyle(SpanStyle(color = c.error, textDecoration = TextDecoration.LineThrough)) { append(plain(e.wrong)) }
                        append("  →  ")
                        withStyle(SpanStyle(color = c.success, fontWeight = FontWeight.SemiBold)) { append(plain(e.right)) }
                    }, style = bodyStyle())
                }
                Text(InlineMarkdown.render(e.explanation, c.ink), style = bodyStyle(size = 14.sp))
            }
        }
        if (f.alternatives.isNotEmpty()) {
            SectionLabel("More natural")
            f.alternatives.forEach { a ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(InlineMarkdown.render(a), style = bodyStyle(), modifier = Modifier.weight(1f))
                    PolyIconButton(PolyIcons.Speaker, "Play", { onSpeak(f.lang, plain(a)) })
                }
            }
        }
        f.reference?.let { r ->
            SectionLabel(if (f.referenceGenerated) "Reference (written by Gemini)" else "Reference ($sourceLabel)")
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(r, style = bodyStyle(c.inkMuted), modifier = Modifier.weight(1f))
                PolyIconButton(PolyIcons.Speaker, "Play reference", { onSpeak(f.lang, r) })
            }
        }
        f.note?.let { Text(InlineMarkdown.render(it), style = captionStyle()) }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            if (added) Text("Added to Review", style = captionStyle(c.success))
            else Text("+ Add to Review", style = captionStyle(c.accent), modifier = Modifier.clickable(role = Role.Button, onClick = onAddToReview).padding(vertical = 12.dp))
        }
    }
}

/** Markdown stripped to plain text (for diffs, speech and the wrong → right line). */
private fun plain(s: String): String = InlineMarkdown.render(s).text

private object RuleCheckerBridge {
    fun differs(a: String, b: String) = app.diacritica.rehearse.RuleChecker.loose(a) != app.diacritica.rehearse.RuleChecker.loose(b) || a.trim() != b.trim()
}

/** Your answer with removed words struck through and added words highlighted. */
@Composable
internal fun diffText(answer: String, corrected: String): AnnotatedString {
    val c = Diacritica.colors
    val joiner = if (app.diacritica.data.TextNorm.containsHan(corrected)) "" else " "
    return buildAnnotatedString {
        WordDiff.diff(answer, corrected).forEachIndexed { i, seg ->
            if (i > 0) append(joiner)
            when (seg.op) {
                WordDiff.Op.SAME -> append(seg.text)
                WordDiff.Op.REMOVED -> withStyle(SpanStyle(color = c.error, textDecoration = TextDecoration.LineThrough)) { append(seg.text) }
                WordDiff.Op.ADDED -> withStyle(SpanStyle(color = c.onAccentSoft, background = c.accentSoft, fontWeight = FontWeight.SemiBold)) { append(seg.text) }
            }
        }
    }
}
