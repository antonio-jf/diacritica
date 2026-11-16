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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
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
fun SettingsScreen(
    vm: SettingsViewModel,
    s: AppSettings,
    onBack: (() -> Unit)? = null,
    onOpenAppearance: () -> Unit = {},
    onOpenDataPacks: () -> Unit = {},
) {
    val message by vm.message.collectAsStateWithLifecycle()
    val backupTarget = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri -> uri?.let(vm::exportBackup) }
    val backupSource = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let(vm::restoreBackup) }
    val autoFolder = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri -> uri?.let { vm.autoBackup.enable(it) } }
    var confirmRestore by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }
    // Sections start closed; each header shows a one-line summary of its current values.
    var open by androidx.compose.runtime.saveable.rememberSaveable { androidx.compose.runtime.mutableStateOf(listOf<String>()) }
    val toggle: (String) -> Unit = { k -> open = if (k in open) open - k else open + k }

    LazyColumn(Modifier.fillMaxSize()) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                onBack?.let { PolyIconButton(PolyIcons.Back, "Back", it, tint = Diacritica.colors.ink, modifier = Modifier.padding(start = 4.dp, top = 10.dp)) }
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

        // ------------------------------------------------ Appearance
        if (section("appearance", "Appearance", "${s.theme.title} · ${s.mode.label} · text ${(s.textScale * 100).toInt()}%", open, toggle)) {
            item {
                LinkRow("Theme, mode and accent", "${s.theme.title} · ${s.mode.label} · ${s.accent.label}", onOpenAppearance)
            }
            item {
                Setting("Text size", "${(s.textScale * 100).toInt()}%") {
                    Slider(value = s.textScale, onValueChange = vm::setTextScale, valueRange = 0.85f..1.4f, steps = 10)
                }
            }
            item { SwitchRow("Language colors", "Each language gets its own color", s.languageColors, vm::setLanguageColors) }
        }

        // ------------------------------------------------ Layouts
        if (section("layouts", "Layouts", "Dictionary ${s.dictionaryLayout.label} · Rehearse ${s.rehearseLayout.label} · Reader ${s.readerLayout.label}", open, toggle)) {
            item { ChipSetting("Dictionary", DictionaryLayout.entries, s.dictionaryLayout, { it.label }, vm::setDictionaryLayout) }
            item { ChipSetting("Rehearse feedback", app.diacritica.settings.RehearseLayout.entries, s.rehearseLayout, { it.label }, vm::setRehearseLayout) }
            item { ChipSetting("Reader", app.diacritica.settings.ReaderLayout.entries, s.readerLayout, { it.label }, vm::setReaderLayout) }
            item { ChipSetting("Word popup", app.diacritica.settings.PopupLayout.entries, s.readerPopup, { it.label }, vm::setReaderPopup) }
            item {
                Text("Reset layouts", style = captionStyle(Diacritica.colors.accent),
                    modifier = Modifier.padding(horizontal = 12.dp).clip(androidx.compose.foundation.shape.RoundedCornerShape(4.dp))
                        .clickable(role = Role.Button, onClick = vm::resetLayouts).padding(horizontal = 8.dp, vertical = 10.dp))
            }
        }

        // ------------------------------------------------ Languages
        val langSummary = s.activeLanguages.joinToString(" ") { it.short } + " · search " + (s.sourceLang?.short ?: "auto")
        if (section("languages", "Languages", langSummary, open, toggle)) {
            s.languageOrder.forEachIndexed { i, l ->
                item("lang-${l.code}") {
                    Column(Modifier.padding(horizontal = 20.dp)) {
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Text(l.nativeName, style = bodyStyle(), modifier = Modifier.weight(1f))
                            if (l.nativeName != l.englishName) Text(l.englishName, style = captionStyle(), modifier = Modifier.padding(end = 4.dp))
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
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        PolyChip("Auto", s.sourceLang == null, { vm.setSourceLang(null) })
                        s.activeLanguages.forEach { l -> PolyChip(l.short, s.sourceLang == l, { vm.setSourceLang(l) }) }
                    }
                }
            }
            item { ChipSetting("Chinese characters", ScriptPreference.entries, s.script, { it.label }, vm::setScript) }
        }

        // ------------------------------------------------ Rehearse
        val rehearseSummary = (if (s.geminiKey.isBlank()) "No Gemini key" else "Gemini on") + " · " +
            s.rehearseLanguages.joinToString(" ") { it.short } + " · " + s.levels.sorted().joinToString("/")
        if (section("rehearse", "Rehearse", rehearseSummary, open, toggle)) {
            item { GeminiKeySetting(s.geminiKey, s.geminiModel, vm) }
            item {
                Setting("Practise") {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        s.activeLanguages.forEach { l -> PolyChip(l.short, l in s.rehearseLanguages, { vm.toggleRehearseLang(l) }) }
                    }
                }
            }
            item {
                Setting("Levels") {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf("A2", "B1", "B2").forEach { lv -> PolyChip(lv, lv in s.levels, { vm.toggleLevel(lv) }) }
                    }
                }
            }
            item { SwitchRow("Accents required", "A wrong accent counts as an error", s.strictAccents, vm::setStrictAccents) }
            item { SwitchRow("Tones required", "For Mandarin typed in pinyin", s.strictTones, vm::setStrictTones) }
            item { SwitchRow("Pinyin hint", "Show the pinyin under the Mandarin field", s.pinyinHint, vm::setPinyinHint) }
        }

        // ------------------------------------------------ Review and pronunciation
        val reviewSummary = "${s.newCardsPerDay} new/day · speech ${"%.1f".format(s.speechRate)}×"
        if (section("review", "Review and speech", reviewSummary, open, toggle)) {
            item {
                Setting("New cards per day", "${s.newCardsPerDay}") {
                    Slider(value = s.newCardsPerDay.toFloat(), onValueChange = { vm.setNewCardsPerDay(it.toInt()) }, valueRange = 5f..50f, steps = 8)
                }
            }
            item { SwitchRow("Cards from lookups", "Not only starred words", s.reviewFromHistory, vm::setReviewFromHistory) }
            item {
                Setting("Speech rate", "${"%.1f".format(s.speechRate)}×") {
                    Slider(value = s.speechRate, onValueChange = vm::setSpeechRate, valueRange = 0.5f..1.5f, steps = 9)
                }
            }
        }

        // ------------------------------------------------ Data
        item {
            Column(Modifier.fillMaxWidth().padding(top = 14.dp)) {
                Hairline(heavy = true)
                LinkRow("Data packs", "Dictionary, sentences, translation models, voices", onOpenDataPacks)
                LinkRow("Back up…", "One file with your words, progress, archive and settings") {
                    backupTarget.launch("diacritica-backup-${java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).format(java.util.Date())}.zip")
                }
                LinkRow("Restore…", "Replaces everything in the app, then restarts it") { confirmRestore = true }
                // ---- Automatic backup
                val auto by vm.autoBackup.status.collectAsStateWithLifecycle()
                Text("Automatic backup", style = captionStyle(), modifier = Modifier.padding(start = 20.dp, top = 14.dp, bottom = 4.dp))
                Column(Modifier.padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    if (auto.folder == null) {
                        Text(
                            "Every day, a backup file goes into a folder you choose (Google Drive, a USB stick, Documents…); the last ${app.diacritica.backup.AutoBackup.KEEP} are kept. On your other device, use Restore and pick the newest file there.",
                            style = captionStyle(),
                        )
                        PolyButton("Choose a folder…", { autoFolder.launch(null) }, primary = false)
                    } else {
                        Text("Daily, into “${auto.folderName ?: "the chosen folder"}”.", style = bodyStyle(size = 14.sp))
                        auto.lastAt?.let { at ->
                            Text(
                                java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.MEDIUM, java.text.DateFormat.SHORT).format(java.util.Date(at)) +
                                    " · " + (auto.lastResult ?: ""),
                                style = captionStyle(if (auto.lastResult?.startsWith("Backup saved") == true) Diacritica.colors.inkMuted else Diacritica.colors.error),
                            )
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            PolyButton("Back up now", { vm.autoBackup.runNow() }, primary = false)
                            PolyButton("Change folder", { autoFolder.launch(auto.folder) }, primary = false)
                            PolyButton("Turn off", { vm.autoBackup.disable() }, primary = false)
                        }
                    }
                }
                message?.let { Text(it, style = captionStyle(Diacritica.colors.accent), modifier = Modifier.padding(horizontal = 20.dp, vertical = 6.dp)) }
                // ---- App updates (GitHub Releases)
                val update by vm.updater.state.collectAsStateWithLifecycle()
                var auto by remember { mutableStateOf(vm.updater.autoCheck) }
                Text("App updates", style = captionStyle(), modifier = Modifier.padding(start = 20.dp, top = 14.dp, bottom = 6.dp))
                UpdatePanel(update, vm.updater, compact = false, modifier = Modifier.padding(horizontal = 20.dp))
                Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("Check for updates by itself (once a day)", style = bodyStyle(size = 14.sp), modifier = Modifier.weight(1f))
                    androidx.compose.material3.Switch(checked = auto, onCheckedChange = { auto = it; vm.updater.autoCheck = it })
                }
                Text(
                    "Sources: Wiktionary (CC BY-SA), CC-CEDICT (CC BY-SA), Tatoeba (CC BY 2.0 FR), OPUS. Fonts: SIL Open Font License.",
                    style = captionStyle(), modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp),
                )
            }
        }
        item { Gap(24.dp) }
    }

    if (confirmRestore) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { confirmRestore = false },
            title = { Text("Restore a backup?", style = titleStyle().copy(fontSize = 20.sp)) },
            text = { Text("Everything currently in the app is replaced by the backup, then the app restarts. The backup includes your Gemini key.", style = bodyStyle()) },
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

/**
 * A collapsible section: one row with the title, a summary of its current values and a chevron.
 * Returns whether it is open, so the caller adds its items only then.
 */
private fun androidx.compose.foundation.lazy.LazyListScope.section(
    key: String, title: String, summary: String, open: List<String>, toggle: (String) -> Unit,
): Boolean {
    val isOpen = key in open
    item("sec-$key") {
        Column(Modifier.fillMaxWidth()) {
            Hairline(heavy = isOpen)
            Row(
                Modifier.fillMaxWidth()
                    .background(if (isOpen) Diacritica.colors.surfaceAlt else androidx.compose.ui.graphics.Color.Transparent)
                    .clickable(role = Role.Button, onClickLabel = if (isOpen) "Close" else "Open") { toggle(key) }
                    .padding(horizontal = 20.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(title, style = bodyStyle(size = 16.sp).copy(fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold))
                    if (!isOpen) Text(summary, style = captionStyle(), maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                }
                androidx.compose.material3.Icon(
                    PolyIcons.ChevronDown, contentDescription = null, tint = Diacritica.colors.inkMuted,
                    modifier = Modifier.size(18.dp).graphicsLayer { rotationZ = if (isOpen) 180f else 0f },
                )
            }
            if (isOpen) Hairline()
        }
    }
    return isOpen
}

/** A row that opens another page or runs an action. */
@Composable
private fun LinkRow(title: String, subtitle: String, onClick: () -> Unit) {
    Column(Modifier.fillMaxWidth().clickable(role = Role.Button, onClick = onClick)) {
        Row(Modifier.padding(horizontal = 20.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(title, style = bodyStyle())
                Text(subtitle, style = captionStyle(), maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
            }
            Text("›", style = bodyStyle(Diacritica.colors.inkMuted, 20.sp))
        }
        Hairline(Modifier.padding(horizontal = 20.dp))
    }
}

/** Title and a row of chips (one choice). */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun <T> ChipSetting(title: String, options: List<T>, selected: T, label: (T) -> String, onPick: (T) -> Unit) {
    Setting(title) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            options.forEach { o -> PolyChip(label(o), o == selected, { onPick(o) }) }
        }
    }
}

@Composable
private fun Setting(title: String, value: String? = null, content: @Composable () -> Unit) {
    Column {
        Column(Modifier.padding(horizontal = 20.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
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
        Text("Model", style = captionStyle())
        app.diacritica.rehearse.GeminiClient.CHOICES.forEach { (id, label, note) ->
            Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                PolyChip(label, savedModel == id, { model = id; vm.setGeminiModel(id) })
                Text("  $note", style = captionStyle(), modifier = Modifier.weight(1f))
            }
        }
        Text("If a model uses up its free requests for the day, the app switches to Flash-Lite until they reset. Or type another model ID:", style = captionStyle())
        SettingsField(model, { model = it }, app.diacritica.rehearse.GeminiClient.DEFAULT_MODEL, mask = false)
        if (model != savedModel) PolyButton("Save model", { vm.setGeminiModel(model.trim()) }, primary = false)
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
        Row(Modifier.padding(horizontal = 20.dp, vertical = 6.dp).fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f).padding(end = 12.dp)) {
                Text(title, style = bodyStyle())
                Text(subtitle, style = captionStyle())
            }
            Switch(checked = checked, onCheckedChange = onChange)
        }
        Hairline(Modifier.padding(horizontal = 20.dp))
    }
}

