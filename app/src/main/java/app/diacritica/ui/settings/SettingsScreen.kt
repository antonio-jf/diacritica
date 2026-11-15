package app.diacritica.ui.settings

import android.os.Build
import android.text.format.Formatter
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.Spacer
import androidx.compose.ui.semantics.Role
import androidx.compose.foundation.clickable
import androidx.compose.foundation.border
import androidx.compose.runtime.setValue
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.diacritica.data.ScriptPreference
import app.diacritica.settings.AccentChoice
import app.diacritica.settings.AppSettings
import app.diacritica.settings.DictionaryLayout
import app.diacritica.settings.ThemeId
import app.diacritica.settings.ThemeMode
import app.diacritica.ui.SettingsViewModel
import app.diacritica.ui.components.Gap
import app.diacritica.ui.components.Hairline
import app.diacritica.ui.components.PolyButton
import app.diacritica.ui.components.PolyChip
import app.diacritica.ui.components.PolyIconButton
import app.diacritica.ui.components.PolyIcons
import app.diacritica.ui.components.ScreenHeader
import app.diacritica.ui.components.SectionLabel
import app.diacritica.ui.components.bodyStyle
import app.diacritica.ui.components.captionStyle
import app.diacritica.ui.components.titleStyle
import app.diacritica.ui.theme.Diacritica
import app.diacritica.ui.theme.Themes

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SettingsScreen(vm: SettingsViewModel, s: AppSettings, onBack: (() -> Unit)? = null, onOpenAppearance: () -> Unit = {}) {
    val info by vm.dictInfo.collectAsStateWithLifecycle()
    val message by vm.message.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let(vm::importDictionary) }
    val backupTarget = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri -> uri?.let(vm::exportBackup) }
    val backupSource = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let(vm::restoreBackup) }
    var confirmRestore by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }

    LazyColumn(Modifier.fillMaxSize()) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                onBack?.let { app.diacritica.ui.components.PolyIconButton(app.diacritica.ui.components.PolyIcons.Back, "Back", it, tint = Diacritica.colors.ink, modifier = Modifier.padding(start = 4.dp, top = 10.dp)) }
                ScreenHeader("Settings", Modifier.weight(1f))
            }
        }
        message?.let { m ->
            item {
                Row(
                    Modifier.padding(horizontal = 20.dp, vertical = 6.dp).fillMaxWidth().background(Diacritica.colors.accentSoft).padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(m, style = bodyStyle(Diacritica.colors.onAccentSoft), modifier = Modifier.weight(1f))
                    PolyIconButton(PolyIcons.Close, "Dismiss", vm::clearMessage, tint = Diacritica.colors.onAccentSoft)
                }
            }
        }

        // ------------------------------------------------ Appearance (global)
        section("Appearance", "Colors, fonts and shapes apply to the whole app, whatever the layout.")
        item {
            val spec = Themes.spec(s.theme)
            Column(Modifier.fillMaxWidth().clickable(role = Role.Button, onClick = onOpenAppearance)) {
                Row(Modifier.padding(horizontal = 20.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        listOf(spec.light.ground, spec.light.accent, spec.dark.ground).forEach { col ->
                            Box(Modifier.size(22.dp).clip(CircleShape).background(col).border(1.dp, Diacritica.colors.line, CircleShape))
                        }
                    }
                    Spacer(Modifier.width(14.dp))
                    Column(Modifier.weight(1f)) {
                        Text("Theme: ${s.theme.title}", style = bodyStyle())
                        Text("${s.mode.label} · ${s.accent.label}", style = captionStyle())
                    }
                    Text("Change ›", style = captionStyle(Diacritica.colors.accent))
                }
                Hairline(Modifier.padding(horizontal = 20.dp))
            }
        }
        item {
            Setting("Text size", "${(s.textScale * 100).toInt()}%") {
                Slider(value = s.textScale, onValueChange = vm::setTextScale, valueRange = 0.85f..1.4f, steps = 10)
            }
        }
        item { SwitchRow("Language colors", "Give each language its own color everywhere", s.languageColors, vm::setLanguageColors) }

        // ------------------------------------------------ Layouts (per section)
        section("Layouts", "Each section can use its own layout. The theme stays the same.")
        item {
            Setting("Dictionary results") {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    DictionaryLayout.entries.forEach { l -> PolyChip(l.label, s.dictionaryLayout == l, { vm.setDictionaryLayout(l) }) }
                }
            }
        }
        item {
            Setting("Rehearse feedback") {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    app.diacritica.settings.RehearseLayout.entries.forEach { l -> PolyChip(l.label, s.rehearseLayout == l, { vm.setRehearseLayout(l) }) }
                }
            }
        }
        item {
            Setting("Reader") {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    app.diacritica.settings.ReaderLayout.entries.forEach { l -> PolyChip(l.label, s.readerLayout == l, { vm.setReaderLayout(l) }) }
                }
            }
        }
        item { Box(Modifier.padding(horizontal = 20.dp, vertical = 8.dp)) { PolyButton("Reset layouts", vm::resetLayouts, primary = false) } }

        // ------------------------------------------------ Languages
        section("Languages", "Turn languages on or off and set their order. At least two must stay on.")
        s.languageOrder.forEachIndexed { i, l ->
            item("lang-${l.code}") {
                Column(Modifier.padding(horizontal = 20.dp)) {
                    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(l.nativeName, style = bodyStyle())
                            Text(l.englishName, style = captionStyle())
                        }
                        PolyIconButton(PolyIcons.Up, "Move ${l.englishName} up", { vm.moveLanguage(l, -1) },
                            tint = if (i == 0) Diacritica.colors.line else Diacritica.colors.inkMuted)
                        PolyIconButton(PolyIcons.Down, "Move ${l.englishName} down", { vm.moveLanguage(l, 1) },
                            tint = if (i == s.languageOrder.lastIndex) Diacritica.colors.line else Diacritica.colors.inkMuted)
                        Switch(checked = l in s.enabled, onCheckedChange = { vm.toggleLanguage(l, it) })
                    }
                    Hairline()
                }
            }
        }
        item {
            Setting("Search from") {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    PolyChip("Auto-detect", s.sourceLang == null, { vm.setSourceLang(null) })
                    s.activeLanguages.forEach { l -> PolyChip(l.short, s.sourceLang == l, { vm.setSourceLang(l) }) }
                }
            }
        }
        item {
            Setting("Chinese characters") {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    ScriptPreference.entries.forEach { p -> PolyChip(p.label, s.script == p, { vm.setScript(p) }) }
                }
            }
        }

        // ------------------------------------------------ Rehearse
        section("Rehearse", "Translation practice. Gemini gives the full grammar explanations; without a key, answers get a quick offline check.")
        item { GeminiKeySetting(s.geminiKey, s.geminiModel, vm) }
        item {
            Setting("Languages to practise", "translate into these") {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    s.activeLanguages.forEach { l -> PolyChip(l.short, l in s.rehearseLanguages, { vm.toggleRehearseLang(l) }) }
                }
            }
        }
        item {
            Setting("Sentence levels") {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    listOf("A2", "B1", "B2").forEach { lv -> PolyChip(lv, lv in s.levels, { vm.toggleLevel(lv) }) }
                }
            }
        }
        item { SwitchRow("Accents required", "A missing or wrong accent counts as an error", s.strictAccents, vm::setStrictAccents) }
        item { SwitchRow("Tones required", "For Mandarin answers typed in pinyin", s.strictTones, vm::setStrictTones) }
        item { SwitchRow("Pinyin hint", "Show the reference's pinyin under the Mandarin field", s.pinyinHint, vm::setPinyinHint) }

        // ------------------------------------------------ Review
        section("Review", "Flashcards for your saved words, scheduled with FSRS spaced repetition.")
        item { SwitchRow("Cards from search history", "Also make cards for words you looked up, not only starred ones", s.reviewFromHistory, vm::setReviewFromHistory) }
        item {
            Setting("New cards per day", "${s.newCardsPerDay}") {
                Slider(value = s.newCardsPerDay.toFloat(), onValueChange = { vm.setNewCardsPerDay(it.toInt()) }, valueRange = 5f..50f, steps = 8)
            }
        }

        // ------------------------------------------------ Speech
        section("Pronunciation", "Uses the phone's text-to-speech. Install offline voices for each language.")
        item {
            Setting("Speech rate", "${"%.1f".format(s.speechRate)}×") {
                Slider(value = s.speechRate, onValueChange = vm::setSpeechRate, valueRange = 0.5f..1.5f, steps = 9)
            }
        }
        item { Box(Modifier.padding(horizontal = 20.dp, vertical = 4.dp)) { PolyButton("Install voices…", vm::openVoiceSettings, primary = false) } }

        // ------------------------------------------------ Dictionary data
        section("Dictionary data", null)
        item {
            SwitchRow(
                "Download missing words",
                "When a word isn't stored on the phone, fetch it from Wiktionary (kaikki.org) and save it for offline use. " +
                    "${vm.fetchedCount()} downloads so far.",
                s.onlineLookup, vm::setOnlineLookup,
            )
        }
        item {
            Column(Modifier.padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                info?.let { d ->
                    Text(d.label, style = bodyStyle())
                    Text(
                        "Built ${d.builtAt} · ${Formatter.formatShortFileSize(context, d.sizeBytes)} · ${d.languages.joinToString(" ") { it.short }}",
                        style = captionStyle(),
                    )
                }
                Text(
                    "Build the full dictionary on a computer with pipeline/build_dictionary.py (see README), then import the .db file here.",
                    style = captionStyle(),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    PolyButton("Import dictionary…", { picker.launch(arrayOf("*/*")) })
                    PolyButton("Use bundled", vm::resetDictionary, primary = false)
                }
                Gap(6.dp)
                Text(
                    "Sources: Wiktionary (via Wiktextract, CC BY-SA), CC-CEDICT (CC BY-SA). Fonts under the SIL Open Font License.",
                    style = captionStyle(),
                )
            }
        }

        // ------------------------------------------------ Backup
        section("Backup & restore", "One file with your saved words, history, Review progress, Rehearse archive, stories, downloaded words and settings.")
        item {
            Column(Modifier.padding(horizontal = 20.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "The backup includes your Gemini key, so keep the file private. Restoring replaces everything in the app and restarts it.",
                    style = captionStyle(),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    PolyButton("Back up…", {
                        backupTarget.launch("diacritica-backup-${java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).format(java.util.Date())}.zip")
                    })
                    PolyButton("Restore…", { confirmRestore = true }, primary = false)
                }
                // The status line at the top of Settings is out of view down here, so repeat it.
                message?.let { Text(it, style = captionStyle(Diacritica.colors.accent)) }
            }
        }
        item { Gap(32.dp) }
    }

    if (confirmRestore) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { confirmRestore = false },
            title = { Text("Restore a backup?", style = titleStyle().copy(fontSize = 20.sp)) },
            text = { Text("Everything currently in the app is replaced by the backup, then the app restarts.", style = bodyStyle()) },
            confirmButton = {
                androidx.compose.material3.TextButton(onClick = { confirmRestore = false; backupSource.launch(arrayOf("application/zip", "application/octet-stream", "*/*")) }) {
                    Text("Choose file", color = Diacritica.colors.accent)
                }
            },
            dismissButton = { androidx.compose.material3.TextButton(onClick = { confirmRestore = false }) { Text("Cancel", color = Diacritica.colors.ink) } },
            containerColor = Diacritica.colors.ground,
        )
    }
}

/** Theme, light/dark mode and accent, on their own page (the previews are large). */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AppearanceScreen(vm: SettingsViewModel, s: AppSettings, onBack: () -> Unit) {
    LazyColumn(Modifier.fillMaxSize()) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                PolyIconButton(PolyIcons.Back, "Back", onBack, tint = Diacritica.colors.ink, modifier = Modifier.padding(start = 4.dp, top = 10.dp))
                ScreenHeader("Appearance", Modifier.weight(1f))
            }
            Text(
                "Colors, fonts and shapes apply to the whole app, whatever the layout. Tap a theme to use it.",
                style = captionStyle(), modifier = Modifier.padding(horizontal = 20.dp).padding(bottom = 12.dp),
            )
        }
        item {
            Column(Modifier.padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                ThemeId.entries.forEach { t ->
                    ThemePreviewCard(t, s.mode, s.accent, s.languageColors, selected = t == s.theme, onClick = { vm.setTheme(t) })
                }
            }
        }
        item {
            Setting("Mode") {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    ThemeMode.entries.forEach { m -> PolyChip(m.label, s.mode == m, { vm.setMode(m) }) }
                }
            }
        }
        item {
            val spec = Themes.spec(s.theme)
            val dark = Diacritica.colors.isDark
            Setting("Accent") {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    AccentChoice.entries.forEach { a ->
                        if (a == AccentChoice.DYNAMIC && Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return@forEach
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            val swatch = when (a) {
                                AccentChoice.THEME -> if (dark) spec.dark.accent else spec.light.accent
                                AccentChoice.ALT1 -> spec.altAccents[0].let { if (dark) it.second else it.first }
                                AccentChoice.ALT2 -> spec.altAccents[1].let { if (dark) it.second else it.first }
                                AccentChoice.DYNAMIC -> null
                            }
                            swatch?.let { Box(Modifier.size(14.dp).clip(CircleShape).background(it)) }
                            PolyChip(a.label, s.accent == a, { vm.setAccent(a) })
                        }
                    }
                }
            }
        }
        item { Gap(32.dp) }
    }
}

/** A clearly separated section: gap, full-width rule, then a tinted header band. */
private fun androidx.compose.foundation.lazy.LazyListScope.section(title: String, subtitle: String?) {
    item("sec-$title") {
        Column(Modifier.fillMaxWidth().padding(top = 24.dp)) {
            Hairline(heavy = true)
            Column(
                Modifier.fillMaxWidth().background(Diacritica.colors.surfaceAlt).padding(horizontal = 20.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(3.dp),
            ) {
                Text(title, style = titleStyle().copy(fontSize = Diacritica.type.headword))
                subtitle?.let { Text(it, style = captionStyle()) }
            }
            Hairline()
        }
    }
}

@Composable
private fun Setting(title: String, value: String? = null, content: @Composable () -> Unit) {
    Column {
        Column(Modifier.padding(horizontal = 20.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row {
                Text(title, style = bodyStyle(), modifier = Modifier.weight(1f))
                value?.let { Text(it, style = captionStyle()) }
            }
            content()
        }
        Hairline(Modifier.padding(horizontal = 20.dp))
    }
}

@Composable
private fun GeminiKeySetting(savedKey: String, savedModel: String, vm: SettingsViewModel) {
    var key by androidx.compose.runtime.saveable.rememberSaveable(savedKey) { androidx.compose.runtime.mutableStateOf(savedKey) }
    var model by androidx.compose.runtime.saveable.rememberSaveable(savedModel) { androidx.compose.runtime.mutableStateOf(savedModel) }
    var show by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }
    Setting("Gemini API key", if (savedKey.isBlank()) "not set" else "saved") {
        Text("Create one for free at aistudio.google.com › Get API key. It is stored only on this phone.", style = captionStyle())
        SettingsField(key, { key = it }, "Paste your API key", mask = !show)
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            PolyButton("Save key", { vm.setGeminiKey(key) })
            PolyButton(if (show) "Hide" else "Show", { show = !show }, primary = false)
            if (savedKey.isNotBlank()) PolyButton("Test", vm::testGeminiKey, primary = false)
        }
        Text("Model (change only if Google renames it)", style = captionStyle())
        SettingsField(model, { model = it }, "gemini-3.5-flash", mask = false)
        if (model != savedModel) PolyButton("Save model", { vm.setGeminiModel(model) }, primary = false)
    }
}

@Composable
private fun SettingsField(value: String, onChange: (String) -> Unit, placeholder: String, mask: Boolean) {
    val c = Diacritica.colors
    val shape = androidx.compose.foundation.shape.RoundedCornerShape(Diacritica.shapes.field.coerceAtMost(12.dp))
    Box(
        Modifier.fillMaxWidth().clip(shape).background(c.surface)
            .border(androidx.compose.foundation.BorderStroke(1.dp, c.line), shape).padding(12.dp),
    ) {
        if (value.isEmpty()) Text(placeholder, style = bodyStyle(c.inkMuted))
        androidx.compose.foundation.text.BasicTextField(
            value = value, onValueChange = onChange, singleLine = true,
            textStyle = bodyStyle(),
            cursorBrush = androidx.compose.ui.graphics.SolidColor(c.accent),
            visualTransformation = if (mask) androidx.compose.ui.text.input.PasswordVisualTransformation() else androidx.compose.ui.text.input.VisualTransformation.None,
            keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(autoCorrectEnabled = false),
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun SwitchRow(title: String, subtitle: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Column {
        Row(Modifier.padding(horizontal = 20.dp, vertical = 12.dp).fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f).padding(end = 12.dp)) {
                Text(title, style = bodyStyle())
                Text(subtitle, style = captionStyle())
            }
            Switch(checked = checked, onCheckedChange = onChange)
        }
        Hairline(Modifier.padding(horizontal = 20.dp))
    }
}

