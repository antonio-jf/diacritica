package app.diacritica.ui.translate

import app.diacritica.ui.theme.hanTransformation
import app.diacritica.ui.theme.roomyForHan
import app.diacritica.ui.components.ChoiceMenuItem
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import app.diacritica.ui.components.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.diacritica.data.Lang
import app.diacritica.settings.AppSettings
import app.diacritica.settings.TranslateEngine
import app.diacritica.ui.components.Gap
import app.diacritica.ui.components.LangBadge
import app.diacritica.ui.components.PolyButton
import app.diacritica.ui.components.HandwritingPad
import app.diacritica.ui.components.PolyChip
import app.diacritica.ui.components.PolyIconButton
import app.diacritica.ui.components.PolyIcons
import app.diacritica.ui.components.PolySurface
import app.diacritica.ui.components.ScreenHeader
import app.diacritica.ui.components.bodyStyle
import app.diacritica.ui.components.captionStyle
import app.diacritica.ui.theme.Diacritica

@Composable
fun TranslateScreen(
    vm: TranslateViewModel,
    settings: AppSettings,
    onBack: () -> Unit,
    onLookUp: (String, Lang) -> Unit,
) {
    val c = Diacritica.colors
    val st by vm.state.collectAsStateWithLifecycle()
    val clipboard = LocalClipboardManager.current
    val pad = Diacritica.spacing.screen
    var showPad by rememberSaveable { mutableStateOf(false) }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = 24.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            PolyIconButton(PolyIcons.Back, "Back", onBack, tint = c.ink, modifier = Modifier.padding(start = 4.dp, top = 10.dp))
            ScreenHeader("Translate", Modifier.weight(1f))
        }
        Column(Modifier.padding(horizontal = pad), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            // Engine and source language
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SourcePicker(st.source, settings.activeLanguages, vm::setSource)
                Spacer(Modifier.weight(1f))
                TranslateEngine.entries.forEach { e -> PolyChip(e.label, settings.translateEngine == e, { vm.setEngine(e) }) }
            }
            Text(
                if (settings.translateEngine == TranslateEngine.ON_DEVICE) "On the phone, works offline. Each language downloads once (about 30 MB)."
                else "Gemini: more natural, needs internet and your key.",
                style = captionStyle(),
            )
            if (settings.translateEngine == TranslateEngine.GEMINI) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Grammar suggestions", style = bodyStyle(size = 15.sp))
                        Text("Lessons about the language you translate from", style = captionStyle())
                    }
                    androidx.compose.material3.Switch(
                        checked = settings.translateGrammar, onCheckedChange = vm::setGrammar,
                        colors = androidx.compose.material3.SwitchDefaults.colors(checkedTrackColor = c.accent),
                    )
                }
            }
            InputBox(st.input, vm::setInput, onClear = vm::clear, lang = st.source)
            if (showPad) {
                HandwritingPad(
                    before = st.input,
                    onInsert = { vm.setInput(st.input + it) },
                    onBackspace = { vm.setInput(st.input.dropLast(1)) },
                    onClose = { showPad = false },
                )
            } else if (Lang.ZH in settings.activeLanguages && (st.source == null || st.source == Lang.ZH)) {
                Row(
                    Modifier.clip(RoundedCornerShape(4.dp)).clickable { showPad = true }.padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(PolyIcons.Handwriting, null, tint = c.accent, modifier = Modifier.size(18.dp))
                    Text("  Write Chinese by hand", style = captionStyle(c.accent))
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("${st.input.length} / ${TranslateViewModel.MAX_CHARS}", style = captionStyle(), modifier = Modifier.weight(1f))
                PolyButton(if (st.busy) "Translating…" else "Translate", vm::translate, enabled = st.input.isNotBlank() && !st.busy)
            }
            st.error?.let { Text(it, style = bodyStyle(c.error)) }
            st.notice?.let { Text(it, style = captionStyle(c.accent)) }
            st.detected?.let { Text("Detected: ${it.englishName}", style = captionStyle()) }
            if (st.downloading.isNotEmpty()) {
                Text("Downloading " + st.downloading.joinToString { it.englishName } + " (first time only)…", style = captionStyle(c.accent))
            }
        }
        Gap(8.dp)
        val order = settings.activeLanguages.filter { it in st.results.keys }
        order.forEach { l ->
            val r = st.results[l] ?: return@forEach
            Box(Modifier.padding(horizontal = pad, vertical = 5.dp)) {
                PolySurface {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        LangBadge(l, boxed = true)
                        Spacer(Modifier.width(8.dp))
                        Text(l.nativeName, style = captionStyle(c.ink), modifier = Modifier.weight(1f))
                        if (r.text != null) {
                            PolyIconButton(PolyIcons.Speaker, "Read ${l.englishName} aloud", { vm.speak(l, r.text) })
                            PolyIconButton(PolyIcons.Copy, "Copy ${l.englishName}", { clipboard.setText(AnnotatedString(r.text)) }, tint = c.inkMuted)
                        }
                    }
                    when {
                        r.loading -> Text("…", style = bodyStyle(c.inkMuted))
                        r.error != null -> Text(r.error, style = captionStyle(c.error))
                        r.text != null -> {
                            SelectionContainer {
                                Text(r.text, style = TextStyle(fontFamily = Diacritica.fonts.reading, fontSize = 18.sp, lineHeight = 26.sp, color = c.ink))
                            }
                            r.pinyin?.let { Text(it, style = captionStyle(c.accent), modifier = Modifier.padding(top = 2.dp)) }
                            Gap(6.dp)
                            Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                                ActionLink(if (r.savedToReview) "Added to Review" else "Add to Review", enabled = !r.savedToReview) { vm.saveToReview(l) }
                                ActionLink("Translate from here") { vm.fromResult(l) }
                                // A single word: the Dictionary has much more about it.
                                if (r.text.trim().split(Regex("\\s+")).size == 1 || (l == Lang.ZH && r.text.length <= 4)) {
                                    ActionLink("Dictionary") { onLookUp(r.text.trim().trimEnd('.', '。', '!', '?'), l) }
                                }
                            }
                        }
                    }
                }
            }
        }
        // Grammar worth a lesson in the text you translated (in its own language).
        val from = st.from
        if (from != null && (st.grammarLoading || st.grammar.isNotEmpty() || st.grammarError != null)) {
            Column(Modifier.padding(horizontal = pad, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                app.diacritica.ui.components.SectionLabel("${from.englishName} grammar in your text")
                when {
                    st.grammarLoading -> Text("Looking for grammar worth a lesson…", style = captionStyle())
                    st.grammarError != null && st.grammar.isEmpty() -> Text("No grammar suggestions: ${st.grammarError}", style = captionStyle())
                }
                st.grammar.forEach { g ->
                    PolySurface {
                        Text(g.title, style = app.diacritica.ui.components.headwordStyle(17.sp))
                        if (g.pattern.isNotBlank()) Text(g.pattern, style = captionStyle(c.accent))
                        if (g.inPassage.isNotBlank()) Text("In your text: ${g.inPassage}", style = bodyStyle(c.inkMuted, 14.sp))
                        Gap(4.dp)
                        Text(g.explanation, style = bodyStyle(size = 15.sp))
                        Gap(8.dp)
                        if (g.title in st.lessonsMade) Text("✓ Writing the lesson: it'll be in Grammar › Full lessons", style = captionStyle(c.success))
                        else PolyButton("Make a lesson", { vm.makeLesson(g) }, primary = false)
                    }
                }
            }
        }
        if (st.results.isNotEmpty() && st.engineUsed != null) {
            Text(
                if (st.engineUsed == TranslateEngine.GEMINI) "Translated by Gemini." else "Translated on the phone (ML Kit).",
                style = captionStyle(), modifier = Modifier.padding(horizontal = pad, vertical = 8.dp),
            )
        }
    }
}

@Composable
private fun ActionLink(text: String, enabled: Boolean = true, onClick: () -> Unit) {
    Text(
        text, style = captionStyle(if (enabled) Diacritica.colors.accent else Diacritica.colors.inkMuted),
        modifier = Modifier.clip(RoundedCornerShape(4.dp)).clickable(enabled = enabled, onClick = onClick).padding(vertical = 6.dp),
    )
}

@Composable
private fun InputBox(value: String, onChange: (String) -> Unit, onClear: () -> Unit, lang: Lang? = null) {
    val c = Diacritica.colors
    val shape = RoundedCornerShape(Diacritica.shapes.field.coerceAtMost(14.dp))
    Box(
        Modifier.fillMaxWidth().clip(shape).background(c.surface)
            .border(BorderStroke(if (Diacritica.tokens.heavyRules) 2.dp else 1.dp, if (Diacritica.tokens.heavyRules) c.rule else c.line), shape)
            .padding(start = 12.dp, end = 4.dp, top = 12.dp, bottom = 12.dp),
    ) {
        if (value.isEmpty()) Text("Type or paste text in any of your languages…", style = bodyStyle(c.inkMuted, 17.sp))
        BasicTextField(
            value = value, onValueChange = onChange,
            textStyle = TextStyle(fontFamily = Diacritica.fonts.reading, fontSize = 17.sp, color = c.ink, lineHeight = 24.sp).roomyForHan(),
            visualTransformation = hanTransformation(),
            cursorBrush = SolidColor(c.accent),
            keyboardOptions = app.diacritica.ui.components.keyboardFor(lang),
            modifier = Modifier.fillMaxWidth().padding(end = 40.dp).heightIn(min = 96.dp).semantics { contentDescription = "Text to translate" },
        )
        if (value.isNotEmpty()) {
            PolyIconButton(PolyIcons.Close, "Clear", onClear, tint = c.inkMuted, modifier = Modifier.align(Alignment.TopEnd))
        }
    }
}

/** "From: Detect ▾" — detect the language, or pick one. */
@Composable
private fun SourcePicker(current: Lang?, langs: List<Lang>, onPick: (Lang?) -> Unit) {
    val c = Diacritica.colors
    var open by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(Diacritica.shapes.chip)
    Box {
        Row(
            Modifier.clip(shape).background(c.surface).border(1.dp, c.ink.copy(alpha = 0.28f), shape)
                .clickable { open = true }.padding(start = 12.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("From: " + (current?.nativeName ?: "Detect"), style = bodyStyle(size = 13.sp))
            Icon(PolyIcons.ChevronDown, null, tint = c.inkMuted, modifier = Modifier.padding(start = 4.dp).size(16.dp))
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }, containerColor = c.ground) {
            ChoiceMenuItem("Detect language", current == null, { open = false; onPick(null) })
            langs.forEach { l ->
                ChoiceMenuItem(l.nativeName, l == current, { open = false; onPick(l) }, note = l.englishName)
            }
        }
    }
}
