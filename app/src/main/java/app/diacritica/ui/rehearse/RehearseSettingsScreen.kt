@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package app.diacritica.ui.rehearse

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import app.diacritica.ui.components.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.diacritica.data.Lang
import app.diacritica.rehearse.Difficulty
import app.diacritica.settings.AppSettings
import app.diacritica.settings.RehearsePath
import app.diacritica.ui.components.Hairline
import app.diacritica.ui.components.PolyChip
import app.diacritica.ui.components.PolyIconButton
import app.diacritica.ui.components.PolyIcons
import app.diacritica.ui.components.SectionLabel
import app.diacritica.ui.components.bodyStyle
import app.diacritica.ui.components.captionStyle
import app.diacritica.ui.settings.LinkRow
import app.diacritica.ui.settings.Setting
import app.diacritica.ui.settings.SwitchRow
import app.diacritica.ui.theme.Diacritica

/**
 * Everything Rehearse-specific in one place: difficulty (1–10, separately for Mandarin), path, length, the
 * language you translate from and into, and how strictly answers are checked. The Gemini key stays in the main
 * Settings because other sections use it too.
 */
@Composable
fun RehearseSettingsScreen(vm: RehearseViewModel, s: AppSettings, onBack: () -> Unit, onOpenSettings: () -> Unit) {
    val c = Diacritica.colors
    val hasZh = Lang.ZH in s.activeLanguages
    val hasEuropean = s.activeLanguages.any { it != Lang.ZH }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        Row(Modifier.padding(horizontal = 8.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            PolyIconButton(PolyIcons.Back, "Back", onBack, tint = c.ink)
            SectionLabel("Rehearse settings", Modifier.weight(1f))
        }
        if (Diacritica.tokens.heavyRules) Hairline(heavy = true)

        Group("Difficulty")
        if (hasEuropean) {
            LevelSlider(
                title = if (hasZh) "Level · European languages" else "Level",
                value = s.rehearseLevel, hint = Difficulty::cefrHint, onChange = vm::setLevel,
            )
        }
        if (hasZh) {
            LevelSlider(title = "Level · Mandarin", value = s.rehearseZhLevel, hint = Difficulty::hskHint, onChange = vm::setZhLevel)
        }
        Text(
            "Sets how rare the words are and how long sentences run. Sentences written by Gemini use these levels " +
                "for each language; for the sentence collections, the Mandarin level applies when Mandarin is the only language you answer in.",
            style = captionStyle(), modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
        )

        Group("What to practise")
        Setting("Path", s.rehearsePath.label) {
            Chips {
                RehearsePath.entries.forEach { p -> PolyChip(p.label, p == s.rehearsePath, { vm.setPath(p) }) }
            }
            Text(s.rehearsePath.blurb, style = captionStyle())
        }
        Setting("Length", if (s.rehearseLength == 1) "1 sentence" else "${s.rehearseLength} sentences") {
            Chips {
                (1..3).forEach { n -> PolyChip(if (n == 1) "1 sentence" else "$n sentences", n == s.rehearseLength, { vm.setLength(n) }) }
            }
            if (s.rehearseLength > 1) Text(
                when (s.rehearsePath) {
                    RehearsePath.FROM_READING -> "Sentences in a row from a story you've opened."
                    RehearsePath.WEAK_SPOTS -> "Weak spots replays rounds as they were."
                    else -> "A short passage written by Gemini in this path's style."
                },
                style = captionStyle(),
            )
        }
        val from = s.rehearseFrom?.takeIf { it in s.activeLanguages }
        Setting("Translate from", from?.englishName ?: "Any language") {
            Chips {
                PolyChip("Any", from == null, { vm.setFrom(null) })
                s.activeLanguages.forEach { l -> PolyChip(l.short, l == from, { vm.setFrom(l) }) }
            }
        }
        Setting("Answer in") {
            Chips {
                s.activeLanguages.forEach { l -> PolyChip(l.short, l in s.rehearseLanguages, { vm.toggleRehearseLang(l) }) }
            }
        }

        Group("Checking")
        SwitchRow("Accents required", "A wrong accent counts as an error", s.strictAccents, vm::setStrictAccents)
        if (hasZh) {
            SwitchRow("Tones required", "For Mandarin typed in pinyin", s.strictTones, vm::setStrictTones)
            SwitchRow("Pinyin hint", "Show the pinyin under the Mandarin field", s.pinyinHint, vm::setPinyinHint)
        }

        Group("Gemini")
        LinkRow(
            "Gemini key and model",
            if (s.geminiKey.isBlank()) "No key yet: checks run offline only" else "In Settings, shared with Translate, Reader and Tests",
            onOpenSettings,
        )
        androidx.compose.foundation.layout.Spacer(Modifier.padding(bottom = 24.dp))
    }
}

@Composable
private fun Group(title: String) {
    Text(title, style = captionStyle(Diacritica.colors.accent), modifier = Modifier.padding(start = 20.dp, top = 18.dp, bottom = 2.dp))
}

@Composable
private fun Chips(content: @Composable () -> Unit) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) { content() }
}

/** A 1–10 slider; the value is saved when you let go, so dragging doesn't write settings on every step. */
@Composable
private fun LevelSlider(title: String, value: Int, hint: (Int) -> String, onChange: (Int) -> Unit) {
    val c = Diacritica.colors
    var drag by remember(value) { mutableFloatStateOf(value.toFloat()) }
    val step = kotlin.math.round(drag).toInt().coerceIn(Difficulty.MIN, Difficulty.MAX)
    Column(Modifier.padding(horizontal = 20.dp, vertical = 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(title, style = bodyStyle(), modifier = Modifier.weight(1f))
            Text("$step", style = bodyStyle(color = c.accent))
            Text(" / 10", style = captionStyle())
        }
        Slider(
            value = drag, onValueChange = { drag = it }, onValueChangeFinished = { onChange(step) },
            valueRange = Difficulty.MIN.toFloat()..Difficulty.MAX.toFloat(), steps = Difficulty.MAX - Difficulty.MIN - 1,
            colors = SliderDefaults.colors(thumbColor = c.accent, activeTrackColor = c.accent),
        )
        Row {
            Text(Difficulty.band(step).description, style = captionStyle(c.ink), modifier = Modifier.weight(1f))
            Text(hint(step), style = captionStyle())
        }
    }
}
