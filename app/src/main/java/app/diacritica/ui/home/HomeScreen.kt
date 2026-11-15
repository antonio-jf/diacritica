package app.diacritica.ui.home

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.diacritica.data.Lang
import app.diacritica.settings.AppSettings
import app.diacritica.ui.components.Gap
import app.diacritica.ui.components.PolyChip
import app.diacritica.ui.components.PolyIconButton
import app.diacritica.ui.components.PolyIcons
import app.diacritica.ui.components.PolySurface
import app.diacritica.ui.components.ScreenHeader
import app.diacritica.ui.components.SectionLabel
import app.diacritica.ui.components.bodyStyle
import app.diacritica.ui.components.captionStyle
import app.diacritica.ui.components.headwordStyle
import app.diacritica.ui.theme.Diacritica
import java.text.DateFormat
import java.util.Calendar
import java.util.Date

/** The parts of the app Home links to. */
enum class HomeSection { DICTIONARY, READER, REHEARSE, REVIEW, LIBRARY, SETTINGS, TRANSLATE }

@Composable
fun HomeScreen(
    vm: HomeViewModel,
    settings: AppSettings,
    onSearch: (String, Lang?) -> Unit,
    onOpenStory: (Long) -> Unit,
    onOpen: (HomeSection) -> Unit,
) {
    val c = Diacritica.colors
    val st by vm.stats.collectAsStateWithLifecycle()
    val pad = Diacritica.spacing.screen

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = 24.dp)) {
        ScreenHeader("Diacritica") {
            PolyIconButton(PolyIcons.Settings, "Settings", onClick = { onOpen(HomeSection.SETTINGS) }, tint = c.ink)
        }
        Text(
            "${greeting()} · ${DateFormat.getDateInstance(DateFormat.FULL).format(Date())}",
            style = captionStyle(), modifier = Modifier.padding(horizontal = pad),
        )
        Gap(14.dp)

        // ---------------------------------------------------- Dictionary
        Column(Modifier.padding(horizontal = pad)) {
            SearchBox(onSearch = { onSearch(it, null) })
            if (st.recent.isNotEmpty()) {
                Gap(8.dp)
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    st.recent.forEach { h ->
                        val lang = Lang.fromCode(h.lang)
                        PolyChip("${lang?.short ?: h.lang} · ${h.query}", selected = false, onClick = { onSearch(h.query, lang) })
                    }
                }
            }
        }
        Gap(18.dp)

        Column(Modifier.padding(horizontal = pad), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            // ------------------------------------------------ Reader
            val story = st.story
            SectionCard(
                PolyIcons.Book, "Reader",
                onClick = { if (story != null) onOpenStory(story.id) else onOpen(HomeSection.READER) },
            ) {
                if (story == null) {
                    Text("Parallel stories in two languages at once.", style = bodyStyle())
                    Gap(4.dp)
                    Text("Open the Reader to start", style = captionStyle(c.accent))
                } else {
                    val a = settings.readerLangA
                    val b = settings.readerLangB
                    val started = story.lastOpenedAt != null
                    Text(if (started) "Continue reading" else "Start reading", style = captionStyle(c.accent))
                    Gap(2.dp)
                    Text(st.storyTitles[a] ?: st.storyTitles[Lang.EN] ?: "Story", style = headwordStyle(19.sp), maxLines = 2, overflow = TextOverflow.Ellipsis)
                    st.storyTitles[b]?.let {
                        Text(it, style = bodyStyle(c.inkMuted, 14.sp).copy(fontStyle = FontStyle.Italic), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    Gap(8.dp)
                    val n = story.sentenceCount.coerceAtLeast(1)
                    val at = if (started) story.lastSentence + 1 else 0
                    Bar(at.toFloat() / n)
                    Gap(4.dp)
                    Text(
                        "${a.short} → ${b.short} · ${story.level} · sentence $at of ${story.sentenceCount} · ${st.storyCount} " +
                            if (st.storyCount == 1) "story" else "stories",
                        style = captionStyle(),
                    )
                }
            }

            // ------------------------------------------------ Review + Rehearse side by side
            Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                SectionCard(PolyIcons.Cards, "Review", Modifier.weight(1f).fillMaxHeight(), onClick = { onOpen(HomeSection.REVIEW) }) {
                    BigNumber(st.due + st.newCards)
                    Text(
                        when {
                            st.due + st.newCards == 0 -> "All done for now"
                            st.newCards == 0 -> "cards due"
                            else -> "${st.due} due · ${st.newCards} new"
                        },
                        style = captionStyle(),
                    )
                }
                SectionCard(PolyIcons.Pencil, "Rehearse", Modifier.weight(1f).fillMaxHeight(), onClick = { onOpen(HomeSection.REHEARSE) }) {
                    BigNumber(st.roundsThisWeek)
                    Text(
                        if (st.roundsThisWeek == 0) "rounds this week · start one" else "rounds this week" + (st.accuracyThisWeek?.let { " · $it% right" } ?: ""),
                        style = captionStyle(),
                    )
                }
            }
            st.lastRound?.let { r ->
                Text(
                    "Last rehearsal: “${r.prompt}”  ${r.correct}/${r.total}",
                    style = captionStyle(), maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(horizontal = 4.dp),
                )
            }

            // ------------------------------------------------ Translate
            SectionCard(PolyIcons.Translate, "Translate", onClick = { onOpen(HomeSection.TRANSLATE) }) {
                Text("Paste a sentence or a paragraph and get it in all your languages at once. Works offline.", style = bodyStyle())
            }

            // ------------------------------------------------ Library
            SectionCard(PolyIcons.Library, "Library", onClick = { onOpen(HomeSection.LIBRARY) }) {
                Text(
                    "${st.savedCount} saved " + (if (st.savedCount == 1) "word" else "words") + " · search history · Rehearse archive",
                    style = bodyStyle(),
                )
            }

            // ------------------------------------------------ Dictionary (browse)
            SectionCard(PolyIcons.Search, "Dictionary", onClick = { onOpen(HomeSection.DICTIONARY) }) {
                Text(
                    settings.activeLanguages.joinToString("  ·  ") { it.nativeName },
                    style = bodyStyle(), maxLines = 2, overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

private fun greeting(): String = when (Calendar.getInstance().get(Calendar.HOUR_OF_DAY)) {
    in 5..11 -> "Good morning"
    in 12..18 -> "Good afternoon"
    else -> "Good evening"
}

@Composable
private fun SectionCard(
    icon: ImageVector,
    title: String,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
    content: @Composable ColumnScope.() -> Unit,
) {
    val c = Diacritica.colors
    PolySurface(modifier, onClick = onClick) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, contentDescription = null, tint = c.accent, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            SectionLabel(title, Modifier.weight(1f))
            Text("›", style = bodyStyle(c.inkMuted, 18.sp))
        }
        Gap(8.dp)
        content()
    }
}

@Composable
private fun BigNumber(n: Int) {
    Text("$n", style = headwordStyle(Diacritica.type.headwordLarge).copy(color = Diacritica.colors.ink))
}

@Composable
private fun Bar(fraction: Float) {
    val c = Diacritica.colors
    Box(Modifier.fillMaxWidth().height(3.dp).clip(RoundedCornerShape(2.dp)).background(c.line)) {
        Box(Modifier.fillMaxWidth(fraction.coerceIn(0f, 1f)).fillMaxHeight().background(c.accent))
    }
}

@Composable
private fun SearchBox(onSearch: (String) -> Unit) {
    val c = Diacritica.colors
    var q by rememberSaveable { mutableStateOf("") }
    val shape = RoundedCornerShape(Diacritica.shapes.field)
    Row(
        Modifier.fillMaxWidth().clip(shape).background(c.surface)
            .border(BorderStroke(if (Diacritica.tokens.heavyRules) 2.dp else 1.dp, if (Diacritica.tokens.heavyRules) c.rule else c.line), shape)
            .padding(start = 12.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(PolyIcons.Search, contentDescription = null, tint = c.inkMuted, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(10.dp))
        Box(Modifier.weight(1f).padding(vertical = 13.dp)) {
            if (q.isEmpty()) Text("Look up a word in any language", style = bodyStyle(c.inkMuted, 16.sp))
            BasicTextField(
                value = q, onValueChange = { q = it }, singleLine = true,
                textStyle = bodyStyle(size = 16.sp), cursorBrush = SolidColor(c.accent),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search, autoCorrectEnabled = false),
                keyboardActions = KeyboardActions(onSearch = { if (q.isNotBlank()) { onSearch(q.trim()); q = "" } }),
                modifier = Modifier.fillMaxWidth(),
            )
        }
        if (q.isNotBlank()) PolyIconButton(PolyIcons.Check, "Search", onClick = { onSearch(q.trim()); q = "" })
    }
}
