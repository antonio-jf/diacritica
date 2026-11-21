package app.diacritica.voice

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.diacritica.data.Lang
import app.diacritica.settings.AppSettings
import app.diacritica.ui.components.Gap
import app.diacritica.ui.components.Hairline
import app.diacritica.ui.components.PolyButton
import app.diacritica.ui.components.PolyChip
import app.diacritica.ui.components.PolyIconButton
import app.diacritica.ui.components.PolyIcons
import app.diacritica.ui.components.ScreenHeader
import app.diacritica.ui.components.SectionLabel
import app.diacritica.ui.components.Text
import app.diacritica.ui.components.bodyStyle
import app.diacritica.ui.components.captionStyle
import app.diacritica.ui.components.headwordStyle
import app.diacritica.ui.theme.Diacritica

/** Voice drill: questions out loud, answers by voice, optional Gemini pronunciation check. */
@Composable
fun VoiceDrillScreen(vm: VoiceDrillViewModel, s: AppSettings, onBack: () -> Unit, onOpenSettings: () -> Unit) {
    val st by vm.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var micDenied by remember { mutableStateOf(false) }
    val askMic = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        micDenied = !ok
        if (ok) vm.start()
    }
    fun startWithMic() {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) vm.start()
        else askMic.launch(Manifest.permission.RECORD_AUDIO)
    }
    // The screen stays on during a session.
    val view = LocalView.current
    val running = st.phase != VoicePhase.SETUP && st.phase != VoicePhase.DONE
    DisposableEffect(running) {
        view.keepScreenOn = running
        onDispose { view.keepScreenOn = false }
    }

    Column(Modifier.fillMaxSize()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            PolyIconButton(PolyIcons.Back, "Back", { if (running) vm.finish() else { vm.backToSetup(); onBack() } }, tint = Diacritica.colors.ink)
            ScreenHeader("Voice drill", Modifier.weight(1f))
        }
        when (st.phase) {
            VoicePhase.SETUP -> Setup(st, vm, s, micDenied, ::startWithMic, onOpenSettings)
            VoicePhase.DONE -> Summary(st, vm)
            else -> Round(st, vm, s)
        }
    }
}

// ---------------------------------------------------------------------------------------------- setup

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Setup(st: VoiceUi, vm: VoiceDrillViewModel, s: AppSettings, micDenied: Boolean, onStart: () -> Unit, onOpenSettings: () -> Unit) {
    val c = Diacritica.colors
    val cfg = st.config
    val targets = cfg.targetsFor(st.active)
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = Diacritica.spacing.screen),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text("Questions are asked out loud and you answer by speaking. Keep the screen on and the phone near you.", style = captionStyle())

        Gap(6.dp); SectionLabel("Question types")
        Text("Pick one or more; a session takes turns between them.", style = captionStyle())
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            VoiceMode.entries.forEach { m ->
                val on = m in cfg.modes
                PolyChip(m.label, on, {
                    val now = if (on) cfg.modes - m else cfg.modes + m
                    if (now.isNotEmpty()) vm.setConfig(cfg.copy(modes = VoiceMode.entries.filter { it in now }))
                })
            }
        }
        cfg.modes.forEach { m -> Text("${m.label}: ${m.blurb.replaceFirstChar { it.lowercase() }}", style = captionStyle()) }

        Gap(6.dp); SectionLabel("Words from")
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            VoiceSource.entries.forEach { src ->
                PolyChip(src.label, cfg.source == src, { vm.setConfig(cfg.copy(source = src)) }, count = if (src == VoiceSource.SAVED) st.savedCount else null)
            }
        }
        if (cfg.source == VoiceSource.VOCAB && st.sets.isNotEmpty()) {
            var open by remember { mutableStateOf(false) }
            Box {
                Text(
                    "Set: " + (st.sets.firstOrNull { it.id == cfg.setId }?.title ?: "All sets") + " ▾",
                    style = bodyStyle(c.accent),
                    modifier = Modifier.clip(RoundedCornerShape(4.dp)).clickable(role = Role.Button) { open = true }.padding(vertical = 6.dp),
                )
                DropdownMenu(open, { open = false }) {
                    DropdownMenuItem(text = { Text("All sets") }, onClick = { open = false; vm.setConfig(cfg.copy(setId = null)) })
                    st.sets.forEach { set ->
                        DropdownMenuItem(text = { Text(set.title) }, onClick = { open = false; vm.setConfig(cfg.copy(setId = set.id)) })
                    }
                }
            }
        }
        if (cfg.source == VoiceSource.SENTENCES) Text("Short sentences at your Rehearse level. Spoken sentences are checked word by word; Gemini can check them properly.", style = captionStyle())

        val bases = cfg.basesFor(st.active)
        Gap(6.dp); SectionLabel("Your languages")
        Text("Questions are asked in them (Say it) and you answer in them (Listen and translate).", style = captionStyle())
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            st.active.forEach { l ->
                val on = l in bases
                PolyChip(l.short, on, {
                    val now = if (on) bases - l else bases + l
                    if (now.isNotEmpty()) vm.setConfig(cfg.copy(bases = st.active.filter { it in now }))
                })
            }
        }

        Gap(6.dp); SectionLabel("Practise")
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            st.active.forEach { l ->
                val on = l in targets
                PolyChip(l.short, on, {
                    val now = if (on) targets - l else targets + l
                    vm.setConfig(cfg.copy(targets = now))
                })
            }
        }
        // A language can be in both rows; a question never asks and answers in the same one.
        val pairs = cfg.pairsFor(st.active)
        Text(
            when {
                pairs.isEmpty() -> "Pick at least one language to practise that isn't your only language."
                pairs.size == 1 -> "Questions: ${pairs[0].first.short} → ${pairs[0].second.short}."
                else -> "${pairs.size} language pairs, taking turns (e.g. ${pairs.take(3).joinToString(", ") { "${it.first.short} → ${it.second.short}" }}). A language can be in both rows."
            },
            style = captionStyle(if (pairs.isEmpty()) c.error else c.inkMuted),
        )
        // Offline speech packs: without one, that language is recognized online.
        st.packs?.let { packs ->
            val missing = (targets + bases).distinct().filter { it !in packs }
            if (missing.isEmpty()) {
                Text("Offline speech packs installed for all of these.", style = captionStyle(c.success))
            } else {
                Text("No offline speech pack for ${missing.joinToString(", ") { it.englishName }}: those are recognized online.", style = captionStyle())
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    missing.forEach { l -> PolyButton("Download ${l.short}", { vm.downloadPack(l); vm.refreshPacks() }, primary = false) }
                }
            }
        }

        Gap(6.dp); SectionLabel("Length")
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf(5, 10, 20, 30).forEach { n -> PolyChip("$n", cfg.length == n, { vm.setConfig(cfg.copy(length = n)) }) }
            PolyChip("Endless", cfg.endless, { vm.setConfig(cfg.copy(length = VoiceConfig.ENDLESS)) })
        }

        Gap(4.dp)
        app.diacritica.ui.settings.SwitchRow(
            "Next question by itself", "After the feedback, move on without tapping", cfg.autoNext,
        ) { vm.setConfig(cfg.copy(autoNext = it)) }
        app.diacritica.ui.settings.SwitchRow(
            "Count in Review", "Saved words only: answers update their Review cards that are due (Say it → recall, Listen → listening)",
            cfg.countInReview,
        ) { vm.setConfig(cfg.copy(countInReview = it)) }
        if (s.geminiKey.isBlank()) {
            Text(
                "Pronunciation checks use Gemini: add a key in Settings › Rehearse.", style = captionStyle(c.accent),
                modifier = Modifier.clickable(role = Role.Button, onClick = onOpenSettings).padding(vertical = 6.dp),
            )
        }

        st.error?.let { Text(it, style = captionStyle(c.error)) }
        if (micDenied) Text("Diacritica needs the microphone to hear your answers. Allow it in the phone's app settings.", style = captionStyle(c.error))
        Gap(6.dp)
        PolyButton("Start", onStart, enabled = cfg.pairsFor(st.active).isNotEmpty(), modifier = Modifier.fillMaxWidth())
        Gap(24.dp)
    }
}

// ---------------------------------------------------------------------------------------------- round

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Round(st: VoiceUi, vm: VoiceDrillViewModel, s: AppSettings) {
    val c = Diacritica.colors
    val item = st.item
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = Diacritica.spacing.screen),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        if (st.phase == VoicePhase.PREPARING || item == null) {
            Text("Getting the words ready…", style = captionStyle(), modifier = Modifier.padding(vertical = 24.dp))
            return@Column
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                if (st.config.endless) "Question ${st.index + 1}" else "${st.index + 1} of ${st.items.size}",
                style = captionStyle(), modifier = Modifier.weight(1f),
            )
            val right = st.results.count { it.verdict == VoiceVerdict.RIGHT }
            if (st.results.isNotEmpty()) Text("$right right", style = captionStyle(c.success))
            Spacer(Modifier.width(12.dp))
            Text("End", style = captionStyle(c.accent), modifier = Modifier.clip(RoundedCornerShape(4.dp)).clickable(role = Role.Button) { vm.finish() }.padding(8.dp))
        }
        // Progress bar
        if (!st.config.endless) Box(Modifier.fillMaxWidth().height(3.dp).clip(RoundedCornerShape(2.dp)).background(c.line)) {
            Box(Modifier.fillMaxWidth((st.index + 1f) / st.items.size.coerceAtLeast(1)).height(3.dp).background(c.accent))
        }

        // The question. For listening questions the text stays hidden until you've answered.
        val showAsk = item.mode == VoiceMode.SAY || st.phase == VoicePhase.FEEDBACK
        Column(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(Diacritica.shapes.card)).background(c.surface).padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(
                when (item.mode) {
                    VoiceMode.SAY -> "Say it in ${item.answer.englishName}"
                    VoiceMode.LISTEN -> "Listen, say it in ${item.answer.englishName}"
                    VoiceMode.ECHO -> "Repeat in ${item.answer.englishName}"
                },
                style = captionStyle(c.accent),
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    if (showAsk) item.askText else "🔊  · · ·",
                    style = headwordStyle(if (item.sentence) 20.sp else 28.sp),
                    modifier = Modifier.weight(1f),
                )
                PolyIconButton(PolyIcons.Speaker, "Play again", vm::playQuestion)
            }
        }

        // Listening / heard
        when (st.phase) {
            VoicePhase.ASKING -> Status("Asking…", 0f, listening = false)
            VoicePhase.LISTENING -> Status(st.partial.ifBlank { "Listening… speak now" }, st.level, listening = true)
            VoicePhase.CHECKING -> Status("Checking…", 0f, listening = false)
            else -> {}
        }

        if (st.phase == VoicePhase.FEEDBACK) {
            val v = st.verdict
            if (v == null) {
                Text(st.note ?: "Something went wrong.", style = bodyStyle(c.error))
            } else {
                val col = when (v) {
                    VoiceVerdict.RIGHT -> c.success
                    VoiceVerdict.CLOSE -> c.warning
                    else -> c.error
                }
                Text(v.label + if (st.overridden) " (you said so)" else "", style = headwordStyle(22.sp).copy(color = col))
                st.heard?.best?.let { Text("I heard: “$it”", style = bodyStyle(c.inkMuted)) }
                st.note?.let { Text(it, style = captionStyle()) }
            }
            Hairline()
            SectionLabel("Answer")
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(item.display, style = headwordStyle(if (item.sentence) 19.sp else 24.sp))
                    item.pinyin?.takeIf { item.answer == Lang.ZH }?.let { Text(it, style = captionStyle(c.accent).copy(fontSize = 15.sp)) }
                    val others = item.accepted.filter { it != item.display }.take(3)
                    if (others.isNotEmpty() && !item.sentence) Text("Also right: " + others.joinToString(", "), style = captionStyle())
                }
                PolyIconButton(PolyIcons.Speaker, "Play answer", { vm.playAnswer(false) })
                PolyIconButton(PolyIcons.Slow, "Play slowly", { vm.playAnswer(true) })
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                PolyButton(if (!st.config.endless && st.index + 1 >= st.items.size) "Finish" else "Next", vm::next)
                if (v != null && v != VoiceVerdict.RIGHT) PolyButton("I said it right", vm::markRight, primary = false)
                PolyButton("Try again", vm::tryAgain, primary = false)
                PolyButton("Check pronunciation", vm::checkPronunciation, primary = false)
                if (item.sentence && item.mode != VoiceMode.ECHO && st.heard?.best != null) {
                    PolyButton(if (st.aiLoading) "Checking…" else "Check with Gemini", vm::checkWithGemini, primary = false, enabled = !st.aiLoading)
                }
            }
            if (s.voice.autoNext && v != null) Text("Tap any button to stay on this question.", style = captionStyle())
            Pronunciation(st.pron, vm)
            st.aiError?.let { Text(it, style = captionStyle(c.error)) }
            st.aiFeedback?.let { f ->
                Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(c.surfaceAlt).padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    SectionLabel("Gemini · ${f.verdict.name.lowercase()}")
                    f.corrected?.let { Text(it, style = bodyStyle()) }
                    f.errors.take(3).forEach { e -> Text("• ${e.wrong} → ${e.right}: ${e.explanation}", style = captionStyle(c.ink)) }
                    f.alternatives.firstOrNull()?.let { Text("More natural: $it", style = captionStyle()) }
                }
            }
        }
        Gap(24.dp)
    }
}

@Composable
private fun Status(text: String, level: Float, listening: Boolean) {
    val c = Diacritica.colors
    // The microphone dot grows with your voice (rms dB from the recognizer, about −2 to 10).
    val scale by animateFloatAsState(if (listening) 1f + ((level + 2f) / 12f).coerceIn(0f, 1f) * 0.6f else 1f, label = "micLevel")
    Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(44.dp), contentAlignment = Alignment.Center) {
            Box(Modifier.size(28.dp).scale(scale).clip(CircleShape).background(if (listening) c.accent else c.line))
            androidx.compose.material3.Icon(PolyIcons.Mic, null, tint = if (listening) c.onAccent else c.inkMuted, modifier = Modifier.size(16.dp))
        }
        Spacer(Modifier.width(10.dp))
        Text(text, style = bodyStyle(if (listening) c.ink else c.inkMuted, 17.sp), modifier = Modifier.weight(1f))
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Pronunciation(p: PronState, vm: VoiceDrillViewModel) {
    val c = Diacritica.colors
    when (p) {
        PronState.Idle -> {}
        is PronState.Recording -> Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Status("Say the answer now…", p.level * 12f - 2f, listening = true)
            Text("It stops by itself after a short pause.", style = captionStyle())
        }
        PronState.Checking -> Text("Gemini is listening to your recording…", style = captionStyle())
        is PronState.Error -> Text(p.message, style = captionStyle(c.error), modifier = Modifier.clickable(role = Role.Button, onClick = vm::checkPronunciation).padding(vertical = 6.dp))
        is PronState.Result -> Column(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(c.surfaceAlt).padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            val r = p.p
            val col = when (r.verdict) { "good" -> c.success; "close" -> c.warning; else -> c.error }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Pronunciation", style = bodyStyle().copy(fontWeight = FontWeight.SemiBold), modifier = Modifier.weight(1f))
                Text("${r.score}/100 · ${r.verdict}", style = bodyStyle(col).copy(fontWeight = FontWeight.SemiBold))
            }
            if (r.heard.isNotBlank()) Text("Heard: ${r.heard}", style = captionStyle())
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                r.parts.forEach { part ->
                    Text(
                        part.text,
                        style = TextStyle(fontFamily = Diacritica.fonts.body, fontSize = 15.sp, color = if (part.ok) c.success else c.error, textAlign = TextAlign.Center),
                        modifier = Modifier.clip(RoundedCornerShape(8.dp)).background((if (part.ok) c.success else c.error).copy(alpha = 0.12f)).padding(horizontal = 8.dp, vertical = 4.dp),
                    )
                }
            }
            r.parts.filter { !it.ok && !it.note.isNullOrBlank() }.forEach { Text("${it.text}: ${it.note}", style = captionStyle(c.ink)) }
            r.tips.forEach { Text("• $it", style = captionStyle(c.ink)) }
            Text(
                "Record again", style = captionStyle(c.accent),
                modifier = Modifier.clip(RoundedCornerShape(4.dp)).clickable(role = Role.Button, onClick = vm::checkPronunciation).padding(vertical = 6.dp),
            )
        }
    }
}

// ---------------------------------------------------------------------------------------------- summary

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Summary(st: VoiceUi, vm: VoiceDrillViewModel) {
    val c = Diacritica.colors
    val right = st.results.count { it.verdict == VoiceVerdict.RIGHT }
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = Diacritica.spacing.screen),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text("$right of ${st.results.size} right", style = headwordStyle(26.sp))
        if (st.config.countInReview && st.results.any { it.item.review != null }) Text("Due Review cards were updated.", style = captionStyle())
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (st.results.any { it.verdict != VoiceVerdict.RIGHT }) PolyButton("Practise the missed ones", vm::retryMissed)
            PolyButton("New session", vm::backToSetup, primary = st.results.all { it.verdict == VoiceVerdict.RIGHT })
        }
        Gap(6.dp)
        st.results.forEach { r ->
            Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                val col = when (r.verdict) { VoiceVerdict.RIGHT -> c.success; VoiceVerdict.CLOSE -> c.warning; else -> c.error }
                Box(Modifier.size(8.dp).clip(CircleShape).background(col))
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text("${r.item.askText}  →  ${r.item.display}", style = bodyStyle())
                    Text(r.verdict.label + (r.heard?.let { " · heard “$it”" } ?: ""), style = captionStyle())
                }
            }
            Hairline()
        }
        Gap(24.dp)
    }
}
