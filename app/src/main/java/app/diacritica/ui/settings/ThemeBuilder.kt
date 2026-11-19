package app.diacritica.ui.settings

import android.os.Build
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.diacritica.data.Lang
import app.diacritica.settings.AccentChoice
import app.diacritica.settings.AppSettings
import app.diacritica.settings.CornerChoice
import app.diacritica.settings.DensityChoice
import app.diacritica.settings.FontChoice
import app.diacritica.settings.HanFont
import app.diacritica.settings.PaletteId
import app.diacritica.settings.SavedTheme
import app.diacritica.settings.SurfaceChoice
import app.diacritica.settings.ThemeCustom
import app.diacritica.settings.ThemeId
import app.diacritica.ui.SettingsViewModel
import app.diacritica.ui.components.Hairline
import app.diacritica.ui.components.LangBadge
import app.diacritica.ui.components.PolyButton
import app.diacritica.ui.components.PolyIconButton
import app.diacritica.ui.components.PolyIcons
import app.diacritica.ui.components.PolySurface
import app.diacritica.ui.components.SectionLabel
import app.diacritica.ui.components.Text
import app.diacritica.ui.components.bodyStyle
import app.diacritica.ui.components.captionStyle
import app.diacritica.ui.components.headwordStyle
import app.diacritica.ui.theme.Diacritica
import app.diacritica.ui.theme.DiacriticaTheme
import app.diacritica.ui.theme.Fonts
import app.diacritica.ui.theme.Themes
import app.diacritica.ui.theme.paletteFor
import app.diacritica.ui.theme.rememberTokens

// ------------------------------------------------------------------ gallery

/** Presets and saved themes side by side, each drawn in its own colors, fonts and shapes. Tap one to use it. */
@Composable
fun ThemeGallery(s: AppSettings, vm: SettingsViewModel) {
    var deleting by remember { mutableStateOf<SavedTheme?>(null) }
    LazyRow(contentPadding = PaddingValues(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        items(ThemeId.entries, key = { "p-${it.name}" }) { t ->
            GalleryCard(
                name = t.title, base = t, accent = AccentChoice.THEME, custom = ThemeCustom(), s = s,
                selected = s.theme == t && s.themeCustom.isEmpty && s.accent == AccentChoice.THEME,
                onClick = { vm.applyTheme(t, AccentChoice.THEME, ThemeCustom()) },
            )
        }
        items(s.savedThemes, key = { "s-${it.name}" }) { t ->
            GalleryCard(
                name = t.name, base = t.base, accent = t.accent, custom = t.custom, s = s,
                selected = s.theme == t.base && s.themeCustom == t.custom && s.accent == t.accent,
                onClick = { vm.applyTheme(t.base, t.accent, t.custom) },
                onDelete = { deleting = t },
            )
        }
    }
    deleting?.let { t ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text("Delete “${t.name}”?") },
            text = { Text("The theme you're using now stays as it is.") },
            confirmButton = { TextButton(onClick = { vm.deleteTheme(s, t.name); deleting = null }) { Text("Delete") } },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun GalleryCard(
    name: String, base: ThemeId, accent: AccentChoice, custom: ThemeCustom, s: AppSettings,
    selected: Boolean, onClick: () -> Unit, onDelete: (() -> Unit)? = null,
) {
    val outer = Diacritica.colors
    val tokens = rememberTokens(base, s.mode, accent, s.languageColors, custom = custom)
    val shape = RoundedCornerShape(16.dp)
    Column(Modifier.width(164.dp)) {
        Box(
            Modifier
                .clip(shape)
                .border(BorderStroke(if (selected) 3.dp else 1.dp, if (selected) outer.accent else outer.line), shape)
                .clickable(role = Role.RadioButton, onClick = onClick)
                .semantics { this.selected = selected },
        ) {
            DiacriticaTheme(tokens, systemBars = false) {
                val c = Diacritica.colors
                Column(Modifier.fillMaxWidth().background(c.ground).padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("Aa", style = headwordStyle(26.sp).copy(color = c.ink), maxLines = 1)
                    PolySurface(padding = PaddingValues(horizontal = 10.dp, vertical = 8.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            LangBadge(Lang.FR, boxed = Diacritica.tokens.surfaceStyle != app.diacritica.ui.theme.SurfaceStyle.HAIRLINE)
                            Spacer(Modifier.width(6.dp))
                            Text("la clé", style = headwordStyle(15.sp), maxLines = 1)
                        }
                        Text("钥匙 yàoshi", style = captionStyle(), maxLines = 1)
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.clip(RoundedCornerShape(Diacritica.shapes.button)).background(c.accent).padding(horizontal = 10.dp, vertical = 4.dp)) {
                            Text("Save", style = TextStyle(fontFamily = Diacritica.fonts.body, fontSize = 11.sp, color = c.onAccent, fontWeight = FontWeight.SemiBold))
                        }
                        Box(Modifier.size(10.dp).clip(CircleShape).background(c.accentSoft))
                        Box(Modifier.size(10.dp).clip(CircleShape).background(c.surfaceAlt))
                    }
                }
            }
        }
        Row(Modifier.padding(top = 6.dp, start = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                name, style = bodyStyle(if (selected) outer.accent else outer.ink, 14.sp).copy(fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal),
                maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f),
            )
            if (onDelete != null) PolyIconButton(PolyIcons.Close, "Delete $name", onDelete, tint = outer.inkMuted)
        }
    }
}

// ------------------------------------------------------------------ builder

/** Each part of the theme on its own. The whole screen redraws as you tap, so it is its own preview. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ThemeBuilder(s: AppSettings, vm: SettingsViewModel) {
    val c = Diacritica.colors
    val t = s.theme
    val cu = s.themeCustom
    fun set(new: ThemeCustom) = vm.setThemeCustom(new)
    var naming by rememberSaveable { mutableStateOf(false) }

    Column(Modifier.padding(horizontal = 20.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            SectionLabel("Customize", Modifier.weight(1f))
            Text(if (cu.isEmpty) "Based on ${t.title}" else "${t.title}, changed", style = captionStyle())
        }
        Text("Start from the theme above, then change any part. Your choices are kept until you pick another theme.", style = captionStyle())
    }

    Part("Headings") {
        FontChoice.entries.forEach { f ->
            val sel = (cu.display ?: Themes.displayFontOf(t)) == f
            Option(sel, { set(cu.copy(display = f.takeIf { it != Themes.displayFontOf(t) })) }) {
                Text("Aa", style = TextStyle(fontFamily = Fonts.family(f), fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = c.ink))
                Spacer(Modifier.width(6.dp))
                OptionLabel(f.label, sel)
            }
        }
    }
    Part("Text") {
        FontChoice.entries.forEach { f ->
            val sel = (cu.body ?: Themes.bodyFontOf(t)) == f
            Option(sel, { set(cu.copy(body = f.takeIf { it != Themes.bodyFontOf(t) })) }) {
                Text(f.label, style = TextStyle(fontFamily = Fonts.family(f), fontSize = 14.sp, color = if (sel) c.onAccentSoft else c.ink))
            }
        }
    }
    Part("Chinese characters", "Serif uses your phone's Song-style font, if it has one.") {
        HanFont.entries.forEach { h ->
            val sel = (cu.han ?: HanFont.SANS) == h
            Option(sel, { set(cu.copy(han = h.takeIf { it != HanFont.SANS })) }) {
                Text(
                    "汉字", style = TextStyle(fontFamily = if (h == HanFont.SERIF) FontFamily.Serif else FontFamily.Default, fontSize = 18.sp, color = c.ink),
                )
                Spacer(Modifier.width(6.dp))
                OptionLabel(h.label.substringBefore(" ·"), sel)
            }
        }
    }
    Part("Background") {
        val dark = c.isDark
        PaletteId.entries.forEach { p ->
            val sel = (cu.palette ?: Themes.paletteOf(t)) == p
            val pal = Themes.palette(p)
            val colors = if (dark) pal.dark else pal.light
            Option(sel, { set(cu.copy(palette = p.takeIf { it != Themes.paletteOf(t) })) }) {
                Box(Modifier.size(18.dp).clip(CircleShape).background(colors.ground).border(1.dp, c.line, CircleShape), contentAlignment = Alignment.Center) {
                    Box(Modifier.size(7.dp).clip(CircleShape).background(colors.accent))
                }
                Spacer(Modifier.width(6.dp))
                OptionLabel(p.label, sel)
            }
        }
    }
    Part("Accent") {
        val pal = paletteFor(t, cu)
        val dark = c.isDark
        AccentChoice.entries.forEach { a ->
            if (a == AccentChoice.DYNAMIC && Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return@forEach
            val swatch: Color? = when (a) {
                AccentChoice.THEME -> if (dark) pal.dark.accent else pal.light.accent
                AccentChoice.ALT1 -> pal.altAccents[0].let { if (dark) it.second else it.first }
                AccentChoice.ALT2 -> pal.altAccents[1].let { if (dark) it.second else it.first }
                AccentChoice.DYNAMIC -> null
            }
            Option(s.accent == a, { vm.setAccent(a) }) {
                swatch?.let { Box(Modifier.size(14.dp).clip(CircleShape).background(it)); Spacer(Modifier.width(6.dp)) }
                OptionLabel(if (a == AccentChoice.THEME) "Default" else if (a == AccentChoice.DYNAMIC) "Wallpaper" else a.label.replace("Alternative", "Alt."), s.accent == a)
            }
        }
    }
    Part("Corners") {
        CornerChoice.entries.forEach { k ->
            val sel = (cu.corners ?: Themes.cornersOf(t)) == k
            Option(sel, { set(cu.copy(corners = k.takeIf { it != Themes.cornersOf(t) })) }) {
                val r = when (k) { CornerChoice.SHARP -> 0.dp; CornerChoice.SOFT -> 4.dp; CornerChoice.ROUND -> 8.dp }
                Box(Modifier.size(16.dp).border(1.5.dp, c.ink, RoundedCornerShape(r)))
                Spacer(Modifier.width(6.dp))
                OptionLabel(k.label, sel)
            }
        }
    }
    Part("Surfaces", "How results, cards and panels are set apart.") {
        SurfaceChoice.entries.forEach { k ->
            val sel = (cu.surface ?: Themes.surfaceOf(t)) == k
            Option(sel, { set(cu.copy(surface = k.takeIf { it != Themes.surfaceOf(t) })) }) {
                SurfaceIcon(k)
                Spacer(Modifier.width(6.dp))
                OptionLabel(k.label, sel)
            }
        }
    }
    Part("Spacing") {
        DensityChoice.entries.forEach { k ->
            val sel = (cu.density ?: Themes.densityOf(t)) == k
            Option(sel, { set(cu.copy(density = k.takeIf { it != Themes.densityOf(t) })) }) { OptionLabel(k.label, sel) }
        }
    }

    FlowRow(
        Modifier.padding(horizontal = 20.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        PolyButton("Save as my theme", { naming = true })
        if (!cu.isEmpty) PolyButton("Reset to ${t.title}", { vm.setThemeCustom(ThemeCustom()) }, primary = false)
    }

    if (naming) {
        var name by rememberSaveable { mutableStateOf(suggestName(s)) }
        val exists = s.savedThemes.any { it.name.equals(SavedTheme.clean(name), ignoreCase = true) }
        AlertDialog(
            onDismissRequest = { naming = false },
            title = { Text("Save this theme") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    OutlinedTextField(value = name, onValueChange = { name = it.take(30) }, singleLine = true, label = { Text("Name") })
                    if (exists) Text("Replaces the saved theme with this name.", style = captionStyle())
                }
            },
            confirmButton = {
                TextButton(enabled = name.isNotBlank(), onClick = { vm.saveTheme(s, name); naming = false }) { Text("Save") }
            },
            dismissButton = { TextButton(onClick = { naming = false }) { Text("Cancel") } },
        )
    }
}

private fun suggestName(s: AppSettings): String {
    var n = s.savedThemes.size + 1
    while (s.savedThemes.any { it.name == "My theme $n" }) n++
    return if (s.savedThemes.isEmpty()) "My theme" else "My theme $n"
}

/** One part of the theme: a title, an optional note and its options. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Part(title: String, note: String? = null, options: @Composable () -> Unit) {
    Column(Modifier.padding(horizontal = 20.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(title, style = bodyStyle())
        note?.let { Text(it, style = captionStyle()) }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) { options() }
    }
    Hairline(Modifier.padding(horizontal = 20.dp))
}

/** A selectable pill whose content can show the option itself (a font, a color, a corner). */
@Composable
private fun Option(selected: Boolean, onClick: () -> Unit, content: @Composable RowScope.() -> Unit) {
    val c = Diacritica.colors
    val shape = RoundedCornerShape(Diacritica.shapes.chip.coerceAtMost(20.dp))
    Row(
        Modifier
            .clip(shape)
            .background(if (selected) c.accentSoft else c.ink.copy(alpha = if (c.isDark) 0.10f else 0.06f))
            .then(if (selected) Modifier.border(1.5.dp, c.accent, shape) else Modifier)
            .clickable(role = Role.RadioButton, onClick = onClick)
            .semantics { this.selected = selected }
            .padding(horizontal = 12.dp, vertical = 7.dp)
            .height(24.dp),
        verticalAlignment = Alignment.CenterVertically,
        content = content,
    )
}

@Composable
private fun OptionLabel(text: String, selected: Boolean) {
    val c = Diacritica.colors
    Text(
        text,
        style = TextStyle(
            fontFamily = Diacritica.fonts.body, fontSize = 13.sp,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            color = if (selected) c.onAccentSoft else c.ink,
        ),
        maxLines = 1,
    )
}

/** A tiny drawing of each surface style. */
@Composable
private fun SurfaceIcon(k: SurfaceChoice) {
    val c = Diacritica.colors
    val m = Modifier.size(width = 18.dp, height = 14.dp)
    when (k) {
        SurfaceChoice.LINES -> Column(m, verticalArrangement = Arrangement.SpaceEvenly) {
            repeat(3) { Box(Modifier.fillMaxWidth().height(1.dp).background(c.inkMuted)) }
        }
        SurfaceChoice.CARDS -> Box(m.clip(RoundedCornerShape(4.dp)).background(c.ink.copy(alpha = 0.18f)))
        SurfaceChoice.OUTLINED -> Box(m.clip(RoundedCornerShape(3.dp)).background(c.ink.copy(alpha = 0.06f)).border(1.dp, c.inkMuted, RoundedCornerShape(3.dp)))
        SurfaceChoice.BOLD -> Box(m.border(2.dp, c.ink))
    }
}
