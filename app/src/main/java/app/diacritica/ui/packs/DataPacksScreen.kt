package app.diacritica.ui.packs

import android.text.format.Formatter
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.diacritica.data.Lang
import app.diacritica.packs.PackInfo
import app.diacritica.settings.AppSettings
import app.diacritica.ui.components.Gap
import app.diacritica.ui.components.Hairline
import app.diacritica.ui.components.LangBadge
import app.diacritica.ui.components.PolyButton
import app.diacritica.ui.components.PolyIconButton
import app.diacritica.ui.components.PolyIcons
import app.diacritica.ui.components.PolySurface
import app.diacritica.ui.components.ScreenHeader
import app.diacritica.ui.components.SectionLabel
import app.diacritica.ui.components.bodyStyle
import app.diacritica.ui.components.captionStyle
import app.diacritica.ui.components.titleStyle
import app.diacritica.ui.theme.Diacritica

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun DataPacksScreen(vm: DataPacksViewModel, settings: AppSettings, onBack: () -> Unit) {
    val c = Diacritica.colors
    val ctx = LocalContext.current
    val st by vm.state.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { vm.refresh() }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let(vm::importDictionary) }
    var confirm by remember { mutableStateOf<PackInfo?>(null) }
    fun size(b: Long) = Formatter.formatShortFileSize(ctx, b)
    val pad = Diacritica.spacing.screen

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = 28.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            PolyIconButton(PolyIcons.Back, "Back", onBack, tint = c.ink, modifier = Modifier.padding(start = 4.dp, top = 10.dp))
            ScreenHeader("Data packs", Modifier.weight(1f))
        }
        Text(
            "Everything the app keeps for offline use. Download bigger packs here instead of building them on a computer.",
            style = captionStyle(), modifier = Modifier.padding(horizontal = pad),
        )
        st.message?.let {
            LaunchedEffect(it) { kotlinx.coroutines.delay(3000); vm.clearMessage() }
            Text(it, style = bodyStyle(c.onAccentSoft), modifier = Modifier.padding(horizontal = pad, vertical = 8.dp).fillMaxWidth()
                .clip(RoundedCornerShape(8.dp)).background(c.accentSoft).padding(10.dp))
        }

        Column(Modifier.padding(horizontal = pad), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Gap(6.dp)
            // ------------------------------------------------ downloadable packs
            SectionLabel("Download")
            when {
                st.catalogLoading -> Text("Looking for packs…", style = captionStyle())
                st.catalogError != null -> PolySurface {
                    Text(st.catalogError!!, style = bodyStyle())
                    Gap(8.dp)
                    PolyButton("Try again", vm::loadCatalog, primary = false)
                }
                st.catalog.isEmpty() -> Text("No packs are published yet.", style = captionStyle())
            }
            st.catalog.forEach { p ->
                val current = when (p.kind) {
                    "dictionary" -> st.dictionarySource.removePrefix("pack:").takeIf { st.dictionarySource.startsWith("pack:") }
                    else -> st.installed[p.id]
                }
                val progress = st.progress[p.id]
                PolySurface {
                    Row(verticalAlignment = Alignment.Top) {
                        Column(Modifier.weight(1f)) {
                            Text(p.title, style = bodyStyle().copy(fontWeight = FontWeight.SemiBold))
                            if (p.description.isNotBlank()) Text(p.description, style = captionStyle())
                        }
                        Text(
                            when {
                                current == null -> ""
                                current == p.version -> "Installed"
                                else -> "Update"
                            },
                            style = captionStyle(if (current != null && current != p.version) c.accent else c.success),
                        )
                    }
                    Gap(6.dp)
                    Text(
                        listOfNotNull(
                            "${size(p.bytes)} download",
                            p.installedBytes.takeIf { it > 0 }?.let { "${size(it)} on the phone" },
                            "version ${p.version}",
                            p.license.ifBlank { null },
                        ).joinToString(" · "),
                        style = captionStyle(),
                    )
                    if (p.languages.isNotEmpty()) {
                        Gap(4.dp)
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            p.languages.mapNotNull { Lang.fromCode(it) }.forEach { LangBadge(it) }
                        }
                    }
                    Gap(10.dp)
                    if (progress != null) {
                        Box(Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)).background(c.line)) {
                            Box(Modifier.fillMaxWidth(progress.fraction).fillMaxHeight().clip(RoundedCornerShape(3.dp)).background(c.accent))
                        }
                        Row(Modifier.padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                if (progress.fraction >= 1f) "Installing…" else "${size(progress.done)} of ${size(progress.total)}",
                                style = captionStyle(), modifier = Modifier.weight(1f),
                            )
                            Text("Cancel", style = captionStyle(c.accent), modifier = Modifier.textLink { vm.cancel(p) }.padding(horizontal = 6.dp, vertical = 8.dp))
                        }
                    } else {
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            if (current != p.version) {
                                PolyButton(if (current == null) "Download" else "Update", {
                                    if (st.metered && p.bytes > 20L * 1024 * 1024) confirm = p else vm.install(p)
                                })
                            }
                            if (current != null) PolyButton("Remove", { vm.remove(p) }, primary = false)
                        }
                    }
                    st.packErrors[p.id]?.let { Gap(6.dp); Text(it, style = captionStyle(c.error)) }
                }
            }

            // ------------------------------------------------ on the phone
            Gap(8.dp)
            SectionLabel("On this phone")
            PolySurface {
                Text("Dictionary", style = bodyStyle().copy(fontWeight = FontWeight.SemiBold))
                st.dictionary?.let { d ->
                    Text(
                        "${d.label} · built ${d.builtAt} · ${size(d.sizeBytes)} · " + when {
                            st.dictionarySource.startsWith("pack:") -> "data pack"
                            st.dictionarySource == "import" -> "imported file"
                            else -> "ships with the app"
                        },
                        style = captionStyle(),
                    )
                }
                Gap(8.dp)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f).padding(end = 10.dp)) {
                        Text("Download missing words", style = bodyStyle(size = 14.sp))
                        Text("From Wiktionary when a word isn't on the phone · ${st.fetchedWords} so far", style = captionStyle())
                    }
                    Switch(checked = st.onlineLookup, onCheckedChange = vm::setOnlineLookup)
                }
                Gap(8.dp)
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    PolyButton("Import file…", { picker.launch(arrayOf("*/*")) }, primary = false)
                    if (st.dictionarySource != "bundled") PolyButton("Use the built-in one", vm::resetDictionary, primary = false)
                }
            }

            PolySurface {
                Text("Rehearse", style = bodyStyle().copy(fontWeight = FontWeight.SemiBold))
                Gap(4.dp)
                DataRow("Everyday (Tatoeba)", if (st.tatoeba > 0) "${st.tatoeba} sentences" + if (st.tatoebaFromPack) " · pack" else "" else "missing")
                listOf("news" to "News", "formal" to "Formal", "encyclopedic" to "Encyclopedic").forEach { (k, label) ->
                    DataRow(label, st.corpora[k]?.let { "$it sentences" + if (st.corporaFromPack) " · pack" else "" } ?: "not installed")
                }
                Gap(6.dp)
                Text("Reader", style = bodyStyle().copy(fontWeight = FontWeight.SemiBold))
                DataRow("Classics in two languages", if (st.books > 0) "${st.books} books" + if (st.booksFromPack) " · pack" else "" else "not installed")
            }

            PolySurface {
                Text("Translation models", style = bodyStyle().copy(fontWeight = FontWeight.SemiBold))
                Text("For Translate on the phone, about 30 MB each. English is built in.", style = captionStyle())
                Gap(6.dp)
                settings.activeLanguages.forEach { l ->
                    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.widthIn(min = 40.dp).padding(end = 10.dp)) { LangBadge(l) }
                        Text(l.nativeName, style = bodyStyle(size = 14.sp), modifier = Modifier.weight(1f))
                        val have = l in st.models
                        when {
                            l in st.modelBusy -> Text("Working…", style = captionStyle())
                            l == Lang.EN -> Text("Built in", style = captionStyle(c.success))
                            have -> {
                                Text("✓ On the phone", style = captionStyle(c.success))
                                Spacer(Modifier.width(10.dp))
                                Text("Delete", style = captionStyle(c.accent), modifier = Modifier.textLink { vm.deleteModel(l) }.padding(horizontal = 6.dp, vertical = 10.dp))
                            }
                            else -> Text("Download", style = captionStyle(c.accent), modifier = Modifier.textLink { vm.downloadModel(l) }.padding(horizontal = 6.dp, vertical = 10.dp))
                        }
                    }
                    Hairline()
                }
            }

            PolySurface {
                Text("Voices", style = bodyStyle().copy(fontWeight = FontWeight.SemiBold))
                Text("Pronunciation uses the phone's text-to-speech voices. Install offline voices for each language in the phone's settings.", style = captionStyle())
                Gap(8.dp)
                PolyButton("Install voices…", vm::openVoiceSettings, primary = false)
            }
        }
    }

    confirm?.let { p ->
        AlertDialog(
            onDismissRequest = { confirm = null },
            title = { Text("Download on mobile data?", style = titleStyle().copy(fontSize = 20.sp)) },
            text = { Text("${p.title} is ${size(p.bytes)}. You're on a metered connection.", style = bodyStyle()) },
            confirmButton = { TextButton(onClick = { confirm = null; vm.install(p) }) { Text("Download", color = c.accent) } },
            dismissButton = { TextButton(onClick = { confirm = null }) { Text("Cancel", color = c.ink) } },
            containerColor = c.ground,
        )
    }
}

@Composable
private fun DataRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
        Text(label, style = bodyStyle(size = 14.sp), modifier = Modifier.weight(1f))
        Text(value, style = captionStyle())
    }
}

/** A small text link with a clipped tap area. */
private fun Modifier.textLink(onClick: () -> Unit): Modifier =
    this.clip(RoundedCornerShape(4.dp)).clickable(onClick = onClick)
