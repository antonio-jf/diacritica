@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package app.diacritica.ui.settings

import app.diacritica.data.Lang
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
import app.diacritica.ui.components.Text
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
    onOpenRehearseSettings: () -> Unit = {},
) {
    val message by vm.message.collectAsStateWithLifecycle()
    val backupTarget = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri -> uri?.let(vm::exportBackup) }
    val backupSource = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let(vm::restoreBackup) }
    val autoFolder = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri -> uri?.let { vm.autoBackup.enable(it) } }
    var confirmRestore by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }
    // Sections start closed; each header shows a one-line summary of its current values.
    var open by androidx.compose.runtime.saveable.rememberSaveable { androidx.compose.runtime.mutableStateOf(listOf<String>()) }
    val toggle: (String) -> Unit = { k -> open = if (k in open) open - k else open + k }
    var query by androidx.compose.runtime.saveable.rememberSaveable { androidx.compose.runtime.mutableStateOf("") }
    val searching = query.isNotBlank()

    LazyColumn(Modifier.fillMaxSize()) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                onBack?.let { PolyIconButton(PolyIcons.Back, "Back", it, tint = Diacritica.colors.ink, modifier = Modifier.padding(start = 4.dp, top = 10.dp)) }
                ScreenHeader("Settings", Modifier.weight(1f))
            }
            SettingsSearch(query, { query = it })
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
        val themeName = s.savedThemes.firstOrNull { it.base == s.theme && it.custom == s.themeCustom && it.accent == s.accent }?.name
            ?: (s.theme.title + if (s.themeCustom.isEmpty) "" else ", changed")
        if (section("appearance", "Appearance", "$themeName · ${s.mode.label} · text ${(s.textScale * 100).toInt()}%", open, toggle, query)) {
            item {
                LinkRow("Theme, fonts, colors and shapes", "$themeName · ${s.mode.label}", onOpenAppearance)
            }
            item {
                Setting("Text size", "${(s.textScale * 100).toInt()}%") {
                    Slider(value = s.textScale, onValueChange = vm::setTextScale, valueRange = 0.85f..1.4f, steps = 10)
                }
            }
            if (Lang.ZH in s.activeLanguages) item {
                Setting("Chinese characters in Reader and Books", "${(s.hanScale * 100).toInt()}%  ·  汉字") {
                    Slider(value = s.hanScale, onValueChange = vm::setHanScale, valueRange = 1f..1.6f, steps = 11)
                }
            }
            item { SwitchRow("Language colors", "Each language gets its own color", s.languageColors, vm::setLanguageColors) }
        }

        // ------------------------------------------------ Layouts
        if (section("layouts", "Layouts", "Dictionary ${s.dictionaryLayout.label} · Rehearse ${s.rehearseLayout.label} · Reader ${s.readerLayout.label}", open, toggle, query)) {
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

        // ------------------------------------------------ Home
        val homeSummary = s.homeOrder.filter { it !in s.homeHidden }.mapNotNull { app.diacritica.ui.home.homeTileNames[it] }.take(4).joinToString(" · ") +
            if (s.homeHidden.isNotEmpty()) " · ${s.homeHidden.size} hidden" else ""
        if (section("home", "Home", homeSummary, open, toggle, query)) {
            item { app.diacritica.ui.home.HomeTilesEditor(s.homeOrder, s.homeHidden, vm::setHome) }
        }

        // ------------------------------------------------ Bottom bar
        val barSummary = (listOf("Home") + s.navTabs.mapNotNull { app.diacritica.ui.Tab.byRoute(it)?.label } + "Settings").joinToString(" · ")
        if (section("bottombar", "Bottom bar", barSummary, open, toggle, query)) {
            item { BottomBarEditor(s.navTabs, vm::setNavTabs) }
        }

        // ------------------------------------------------ Languages
        val langSummary = s.activeLanguages.joinToString(" ") { it.short } + " · search " + (s.sourceLang?.short ?: "auto")
        if (section("languages", "Languages", langSummary, open, toggle, query)) {
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
            s.rehearseLanguages.joinToString(" ") { it.short } + " · level ${s.rehearseLevel}" +
            (if (Lang.ZH in s.activeLanguages) " (Mandarin ${s.rehearseZhLevel})" else "")
        if (section("rehearse", "Rehearse", rehearseSummary, open, toggle, query)) {
            item { GeminiKeySetting(s.geminiKey, s.geminiModel, vm) }
            item { LinkRow("Rehearse settings", "Level, path, length, languages and how answers are checked", onOpenRehearseSettings) }
            item {
                SwitchRow(
                    "Tap words for meanings", "In Rehearse, Tests and Review sentences. Off: practise without help",
                    s.tapLookupPractice, vm::setTapLookupPractice,
                )
            }
        }

        // ------------------------------------------------ Review and pronunciation
        val reviewSummary = "${s.newCardsPerDay} new/day · speech ${"%.1f".format(s.speechRate)}×"
        if (section("review", "Review and speech", reviewSummary, open, toggle, query)) {
            item {
                Setting("New cards per day", "${s.newCardsPerDay}") {
                    Slider(value = s.newCardsPerDay.toFloat(), onValueChange = { vm.setNewCardsPerDay(it.toInt()) }, valueRange = 5f..50f, steps = 8)
                }
            }
            item { SwitchRow("Cards from lookups", "Not only starred words", s.reviewFromHistory, vm::setReviewFromHistory) }
            item {
                SwitchRow(
                    "Translate a sentence", "Saved words: translate a sentence that uses the word into its language",
                    s.reviewTranslateCards, vm::setReviewTranslateCards,
                )
            }
            item {
                SwitchRow(
                    "Use it in a sentence", "Saved words: write your own sentence with the word (checked by Gemini)",
                    s.reviewUseCards, vm::setReviewUseCards,
                )
            }
            item {
                Setting("Speech rate", "${"%.1f".format(s.speechRate)}×") {
                    Slider(value = s.speechRate, onValueChange = vm::setSpeechRate, valueRange = 0.5f..1.5f, steps = 9)
                }
            }
        }

        // ------------------------------------------------ Data
        if (!searching || settingsMatch(query.trim(), SETTINGS_KEYWORDS["data"].orEmpty())) item {
            Column(Modifier.fillMaxWidth().padding(top = 14.dp)) {
                Hairline(heavy = true)
                LinkRow("Data packs", "Dictionary, sentences, translation models, voices", onOpenDataPacks)
                LinkRow("Back up…", "One file with your words, progress, archive and settings") {
                    backupTarget.launch("diacritica-backup-${java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).format(java.util.Date())}.zip")
                }
                LinkRow("Restore…", "Replaces everything in the app, then restarts it") { confirmRestore = true }
                // ---- Automatic backup
                val backupStatus by vm.autoBackup.status.collectAsStateWithLifecycle()
                Text("Automatic backup", style = captionStyle(), modifier = Modifier.padding(start = 20.dp, top = 14.dp, bottom = 4.dp))
                Column(Modifier.padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    if (backupStatus.folder == null) {
                        Text(
                            "Every day, a backup file goes into a folder you choose (Google Drive, a USB stick, Documents…); the last ${app.diacritica.backup.AutoBackup.KEEP} are kept. On your other device, use Restore and pick the newest file there.",
                            style = captionStyle(),
                        )
                        PolyButton("Choose a folder…", { autoFolder.launch(null) }, primary = false)
                    } else {
                        Text("Daily, into “${backupStatus.folderName ?: "the chosen folder"}”.", style = bodyStyle(size = 14.sp))
                        backupStatus.lastAt?.let { at ->
                            Text(
                                java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.MEDIUM, java.text.DateFormat.SHORT).format(java.util.Date(at)) +
                                    " · " + (backupStatus.lastResult ?: ""),
                                style = captionStyle(if (backupStatus.lastResult?.startsWith("Backup saved") == true) Diacritica.colors.inkMuted else Diacritica.colors.error),
                            )
                        }
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            PolyButton("Back up now", { vm.autoBackup.runNow() }, primary = false)
                            PolyButton("Change folder", { autoFolder.launch(backupStatus.folder) }, primary = false)
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
        if (searching && SETTINGS_KEYWORDS.keys.none { settingsMatch(query.trim(), (SECTION_TITLES[it] ?: "") + " " + SETTINGS_KEYWORDS[it]) }) item {
            Text("No settings match “${query.trim()}”.", style = captionStyle(), modifier = Modifier.padding(20.dp))
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
                "Pick a theme to start from, then change any part of it below. Everything applies to the whole app, whatever the layout.",
                style = captionStyle(), modifier = Modifier.padding(horizontal = 20.dp).padding(bottom = 12.dp),
            )
        }
        item { ThemeGallery(s, vm) }
        item {
            Setting("Mode") {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    ThemeMode.entries.forEach { m -> PolyChip(m.label, s.mode == m, { vm.setMode(m) }) }
                }
            }
        }
        item { Column { ThemeBuilder(s, vm) } }
        item { Gap(32.dp) }
    }
}

/**
 * A collapsible section: one row with the title, a summary of its current values and a chevron.
 * Returns whether it is open, so the caller adds its items only then.
 */
private fun androidx.compose.foundation.lazy.LazyListScope.section(
    key: String, title: String, summary: String, open: List<String>, toggle: (String) -> Unit,
    query: String = "",
): Boolean {
    // While searching: only matching sections, already open.
    val q = query.trim()
    if (q.isNotEmpty() && !settingsMatch(q, "$title $summary ${SETTINGS_KEYWORDS[key].orEmpty()}")) return false
    val isOpen = key in open || q.isNotEmpty()
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
internal fun LinkRow(title: String, subtitle: String, onClick: () -> Unit) {
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
internal fun Setting(title: String, value: String? = null, content: @Composable () -> Unit) {
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
        FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            PolyButton("Save key", { vm.setGeminiKey(key) })
            PolyButton(if (show) "Hide" else "Show", { show = !show }, primary = false)
            if (savedKey.isNotBlank()) PolyButton("Test", vm::testGeminiKey, primary = false)
        }
        Gap(4.dp)
        Text("Model", style = captionStyle())
        val models = app.diacritica.rehearse.GeminiClient.MODELS
        models.forEach { m -> ModelCard(m, selected = savedModel == m.id, onPick = { model = m.id; vm.setGeminiModel(m.id) }) }
        // Any other model ID (for a model Google adds later).
        val custom = models.none { it.id == savedModel }
        var other by androidx.compose.runtime.saveable.rememberSaveable { androidx.compose.runtime.mutableStateOf(custom) }
        OtherModelCard(selected = custom, open = other || custom, onToggle = { other = !other }) {
            SettingsField(model, { model = it }, "e.g. gemini-4-flash", mask = false)
            if (model.isNotBlank() && model != savedModel) PolyButton("Use this model", { vm.setGeminiModel(model.trim()) }, primary = false)
        }
        Text("When a model runs out of free requests for the day, the app switches to Flash-Lite until midnight Pacific time (1–2 a.m. in Mexico City).", style = captionStyle())
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
internal fun SwitchRow(title: String, subtitle: String, checked: Boolean, onChange: (Boolean) -> Unit) {
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


/** Choose and order the sections in the bottom bar (Home is always first). */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun BottomBarEditor(current: List<String>, onChange: (List<String>) -> Unit) {
    val c = Diacritica.colors
    val chosen = current.mapNotNull { app.diacritica.ui.Tab.byRoute(it) }.filter { it != app.diacritica.ui.Tab.HOME && it != app.diacritica.ui.Tab.SETTINGS }
    Column(Modifier.padding(horizontal = 20.dp, vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("Home is always first and Settings always last. Pick up to ${app.diacritica.settings.MAX_NAV_TABS} more and set their order; the rest stay on Home.", style = captionStyle())
        Row(verticalAlignment = Alignment.CenterVertically) {
            androidx.compose.material3.Icon(app.diacritica.ui.Tab.HOME.icon, null, tint = c.inkMuted, modifier = Modifier.size(20.dp))
            Text("   Home", style = bodyStyle(c.inkMuted), modifier = Modifier.weight(1f).padding(vertical = 8.dp))
        }
        chosen.forEachIndexed { i, t ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                androidx.compose.material3.Icon(t.icon, null, tint = c.ink, modifier = Modifier.size(20.dp))
                Text("   ${t.label}", style = bodyStyle(), modifier = Modifier.weight(1f))
                val routes = chosen.map { it.route }
                if (i > 0) PolyIconButton(PolyIcons.Up, "Move ${t.label} left", {
                    onChange(routes.toMutableList().apply { add(i - 1, removeAt(i)) })
                }, tint = c.inkMuted)
                if (i < chosen.lastIndex) PolyIconButton(PolyIcons.Down, "Move ${t.label} right", {
                    onChange(routes.toMutableList().apply { add(i + 1, removeAt(i)) })
                }, tint = c.inkMuted)
                PolyIconButton(PolyIcons.Close, "Remove ${t.label} from the bar", { onChange(routes - t.route) }, tint = c.inkMuted)
            }
        }
        val others = app.diacritica.ui.Tab.entries.filter { it != app.diacritica.ui.Tab.HOME && it != app.diacritica.ui.Tab.SETTINGS && it !in chosen }
        if (others.isNotEmpty()) {
            val full = chosen.size >= app.diacritica.settings.MAX_NAV_TABS
            Text(if (full) "The bar is full: remove one to add another." else "Add to the bar", style = captionStyle(), modifier = Modifier.padding(top = 6.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                others.forEach { t ->
                    PolyChip(t.label, selected = false, { if (!full) onChange(chosen.map { it.route } + t.route) }, icon = t.icon)
                }
            }
        }
        if (current != app.diacritica.settings.DEFAULT_NAV_TABS) {
            Text("Reset to Dictionary · Reader · Rehearse · Review", style = captionStyle(c.accent),
                modifier = Modifier.clip(androidx.compose.foundation.shape.RoundedCornerShape(4.dp)).clickable { onChange(app.diacritica.settings.DEFAULT_NAV_TABS) }.padding(vertical = 8.dp))
        }
    }
}

/** One Gemini model: name, what it's best for, speed and quality meters, free daily allowance. */
@Composable
private fun ModelCard(m: app.diacritica.rehearse.GeminiClient.ModelInfo, selected: Boolean, onPick: () -> Unit) {
    val c = Diacritica.colors
    val shape = androidx.compose.foundation.shape.RoundedCornerShape(Diacritica.shapes.card.coerceIn(8.dp, 14.dp))
    Row(
        Modifier.fillMaxWidth().padding(vertical = 4.dp).clip(shape)
            .background(if (selected) c.accentSoft.copy(alpha = 0.45f) else c.surface)
            .border(if (selected) 1.5.dp else 1.dp, if (selected) c.accent else c.line, shape)
            .clickable(role = Role.RadioButton, onClick = onPick).padding(12.dp),
        verticalAlignment = Alignment.Top,
    ) {
        RadioDot(selected, Modifier.padding(top = 3.dp))
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(m.name, style = bodyStyle().copy(fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold), modifier = Modifier.weight(1f, fill = false))
                if (m.recommended) {
                    Spacer(Modifier.width(8.dp))
                    Text("Recommended", style = captionStyle(c.onAccentSoft),
                        modifier = Modifier.clip(androidx.compose.foundation.shape.RoundedCornerShape(4.dp)).background(c.accentSoft).padding(horizontal = 6.dp, vertical = 1.dp))
                }
            }
            Text(m.bestFor, style = captionStyle(c.ink))
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
                Meter("Speed", m.speed)
                Meter("Quality", m.quality)
            }
            Text("Free: ${m.freePerDay} requests a day", style = captionStyle())
        }
    }
}

/** "Other model": a custom model ID, shown when chosen or expanded. */
@Composable
private fun OtherModelCard(selected: Boolean, open: Boolean, onToggle: () -> Unit, content: @Composable () -> Unit) {
    val c = Diacritica.colors
    val shape = androidx.compose.foundation.shape.RoundedCornerShape(Diacritica.shapes.card.coerceIn(8.dp, 14.dp))
    Column(
        Modifier.fillMaxWidth().padding(vertical = 4.dp).clip(shape)
            .background(if (selected) c.accentSoft.copy(alpha = 0.45f) else c.surface)
            .border(if (selected) 1.5.dp else 1.dp, if (selected) c.accent else c.line, shape).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(Modifier.fillMaxWidth().clickable(onClick = onToggle), verticalAlignment = Alignment.CenterVertically) {
            RadioDot(selected)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text("Other model", style = bodyStyle().copy(fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold))
                Text("Type a model ID, for one Google adds later", style = captionStyle())
            }
        }
        if (open) content()
    }
}

@Composable
private fun RadioDot(selected: Boolean, modifier: Modifier = Modifier) {
    val c = Diacritica.colors
    Box(modifier.size(18.dp).clip(androidx.compose.foundation.shape.CircleShape)
        .border(1.5.dp, if (selected) c.accent else c.inkMuted, androidx.compose.foundation.shape.CircleShape), contentAlignment = Alignment.Center) {
        if (selected) Box(Modifier.size(9.dp).clip(androidx.compose.foundation.shape.CircleShape).background(c.accent))
    }
}

/** "Speed ▮▮▯": three small bars. */
@Composable
private fun Meter(label: String, value: Int) {
    val c = Diacritica.colors
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("$label ", style = captionStyle())
        (1..3).forEach { i ->
            Box(Modifier.padding(start = 2.dp).size(width = 10.dp, height = 6.dp)
                .clip(androidx.compose.foundation.shape.RoundedCornerShape(2.dp))
                .background(if (i <= value) c.accent else c.surfaceAlt))
        }
    }
}


/** Words each section answers to in the Settings search, beyond its title and summary. */
private val SETTINGS_KEYWORDS = mapOf(
    "appearance" to "theme mode dark light night accent color colour text size font bigger smaller chinese characters hanzi 汉字 language colors paper tiles midnight index wallpaper fonts serif corners rounded surfaces cards background spacing saved themes my theme",
    "layouts" to "layout dictionary list cards grid table rehearse feedback tabs reader stacked side interlinear numbered word popup sheet tooltip reset",
    "home" to "home tiles cards hide show reorder order customize customise",
    "bottombar" to "bottom bar navigation nav tabs sections",
    "languages" to "languages language order english french german italian spanish mandarin chinese français deutsch italiano español 中文 search from auto detect script simplified traditional",
    "rehearse" to "rehearse tap words meanings look up lookup help gemini key api model ai grading level difficulty path length languages accents tones",
    "review" to "review cards new per day lookups speech rate pronunciation voice tts read aloud speed",
    "data" to "data packs download dictionary sentences backup restore automatic folder update updates version github",
)
private val SECTION_TITLES = mapOf(
    "appearance" to "Appearance", "layouts" to "Layouts", "home" to "Home", "bottombar" to "Bottom bar",
    "languages" to "Languages", "rehearse" to "Rehearse", "review" to "Review and speech", "data" to "Data",
)

/** Every word of the query appears in [text] (case and accents ignored, prefixes count). */
private fun settingsMatch(query: String, text: String): Boolean {
    val words = app.diacritica.data.TextNorm.normalize(text).split(Regex("\\s+"))
    return app.diacritica.data.TextNorm.normalize(query).split(Regex("\\s+")).filter { it.isNotEmpty() }
        .all { q -> words.any { it.startsWith(q) } }
}

@Composable
private fun SettingsSearch(query: String, onChange: (String) -> Unit) {
    val c = Diacritica.colors
    val shape = androidx.compose.foundation.shape.RoundedCornerShape(Diacritica.shapes.field)
    Row(
        Modifier.padding(horizontal = 20.dp, vertical = 6.dp).fillMaxWidth().clip(shape).background(c.surface).padding(start = 12.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        androidx.compose.material3.Icon(PolyIcons.Search, null, tint = c.inkMuted, modifier = Modifier.size(16.dp))
        Box(Modifier.weight(1f).padding(start = 10.dp, top = 11.dp, bottom = 11.dp)) {
            if (query.isEmpty()) Text("Search settings", style = bodyStyle(c.inkMuted, 15.sp))
            androidx.compose.foundation.text.BasicTextField(
                value = query, onValueChange = onChange, singleLine = true,
                textStyle = bodyStyle(size = 15.sp), cursorBrush = androidx.compose.ui.graphics.SolidColor(c.accent),
                keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(autoCorrectEnabled = false, imeAction = androidx.compose.ui.text.input.ImeAction.Search),
                modifier = Modifier.fillMaxWidth(),
            )
        }
        if (query.isNotEmpty()) PolyIconButton(PolyIcons.Close, "Clear search", { onChange("") }, tint = c.inkMuted)
    }
}
