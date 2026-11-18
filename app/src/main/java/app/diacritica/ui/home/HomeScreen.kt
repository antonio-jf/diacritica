package app.diacritica.ui.home

import app.diacritica.ui.theme.hanTransformation
import androidx.compose.foundation.layout.BoxWithConstraints
import app.diacritica.ui.settings.UpdatePanel
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.navigationBarsPadding
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
import app.diacritica.ui.components.Text
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
enum class HomeSection { SAVED, DICTIONARY, READER, REHEARSE, REVIEW, LIBRARY, SETTINGS, TRANSLATE, PROGRESS, TESTS, GRAMMAR, BOOKS, VOCABULARY }

@Composable
fun HomeScreen(
    vm: HomeViewModel,
    settings: AppSettings,
    onSearch: (String, Lang?) -> Unit,
    onOpenStory: (Long) -> Unit,
    onOpen: (HomeSection) -> Unit,
    onChange: (List<String>, Set<String>) -> Unit,
) {
    var customizing by rememberSaveable { mutableStateOf(false) }
    val c = Diacritica.colors
    val st by vm.stats.collectAsStateWithLifecycle()
    val update by vm.updater.state.collectAsStateWithLifecycle()
    val pad = Diacritica.spacing.screen

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = 24.dp)) {
        ScreenHeader("Diacritica")
        Text(
            "${greeting()} · ${DateFormat.getDateInstance(DateFormat.FULL).format(Date())}",
            style = captionStyle(), modifier = Modifier.padding(horizontal = pad),
        )
        Gap(14.dp)
        // Shown only when there's an update (available, downloading, ready to install).
        UpdatePanel(update, vm.updater, compact = true, modifier = Modifier.padding(horizontal = pad).padding(bottom = 14.dp))

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
            // The cards in the order chosen in "Customize Home", without the hidden ones. Consecutive small tiles
            // share a two-column grid; Reader and Review + Rehearse are full width.
            val visible = settings.homeOrder.filter { it !in settings.homeHidden }
            val small = smallTiles(st, settings)
            val runs = mutableListOf<List<String>>()
            visible.forEach { id ->
                val last = runs.lastOrNull()
                if (id in small && last != null && last.first() in small) runs[runs.lastIndex] = last + id else runs += listOf(id)
            }
            runs.forEach { run ->
                when (val id = run.first()) {
                    "reader" -> ReaderCard(st, settings, onOpenStory, onOpen)
                    "practice" -> PracticeCards(st, onOpen)
                    else -> if (id in small) TileGrid(run.mapNotNull { small[it] }, onOpen)
                }
            }
            if (visible.isEmpty()) {
                Text("All cards are hidden.", style = captionStyle(), modifier = Modifier.padding(horizontal = 4.dp))
            }
            Text(
                "Customize Home", style = captionStyle(c.accent),
                modifier = Modifier.align(Alignment.CenterHorizontally).clip(RoundedCornerShape(4.dp))
                    .clickable(role = androidx.compose.ui.semantics.Role.Button) { customizing = true }
                    .padding(horizontal = 12.dp, vertical = 10.dp),
            )
        }
    }

    if (customizing) {
        @OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
        androidx.compose.material3.ModalBottomSheet(
            onDismissRequest = { customizing = false },
            sheetState = androidx.compose.material3.rememberModalBottomSheetState(skipPartiallyExpanded = true),
            containerColor = c.ground,
        ) {
            Column(Modifier.verticalScroll(rememberScrollState()).navigationBarsPadding().padding(bottom = 16.dp)) {
                Text("Customize Home", style = headwordStyle(20.sp), modifier = Modifier.padding(horizontal = 20.dp))
                HomeTilesEditor(settings.homeOrder, settings.homeHidden, onChange)
            }
        }
    }
}

@Composable
private fun ReaderCard(st: HomeStats, settings: AppSettings, onOpenStory: (Long) -> Unit, onOpen: (HomeSection) -> Unit) {
    val c = Diacritica.colors
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
}

@Composable
private fun PracticeCards(st: HomeStats, onOpen: (HomeSection) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
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
    }
}

/** The small cards, by id. */
private fun smallTiles(st: HomeStats, settings: AppSettings): Map<String, Tile> {
    val saved = "${st.savedCount} " + (if (st.savedCount == 1) "word" else "words")
    return linkedMapOf(
        "saved" to Tile(PolyIcons.StarFilled, "Saved", HomeSection.SAVED, saved,
            "$saved you starred, by language and where you found them."),
        "books" to Tile(PolyIcons.Books, "Books", HomeSection.BOOKS, "Your own EPUBs",
            "Import your own EPUBs and read them with the dictionary a tap away."),
        "tests" to Tile(PolyIcons.Check, "Tests", HomeSection.TESTS, "8 sentences, scored",
            "Translate 8 sentences into your languages and get a score, corrections and lessons for your mistakes."),
        "vocabulary" to Tile(PolyIcons.Cards, "Vocabulary", HomeSection.VOCABULARY, "Word sets by topic",
            "Word sets by topic (fruits, colours, professions, places, actions…) with a mini test for each."),
        "grammar" to Tile(PolyIcons.Book, "Grammar", HomeSection.GRAMMAR, "Weak points and lessons",
            "Your weak points from Rehearse and Tests, all your lessons, and practice aimed at each."),
        "translate" to Tile(PolyIcons.Translate, "Translate", HomeSection.TRANSLATE, "Into all your languages",
            "Paste a sentence or a paragraph and get it in all your languages at once. Works offline."),
        "progress" to Tile(PolyIcons.Chart, "Progress", HomeSection.PROGRESS, "Accuracy and mistakes",
            "Accuracy per language, your most common mistakes, Review recall and how much you've read."),
        "library" to Tile(PolyIcons.Library, "Library", HomeSection.LIBRARY, "History and archive",
            "Saved words, search history and the Rehearse archive."),
        "dictionary" to Tile(PolyIcons.Search, "Dictionary", HomeSection.DICTIONARY,
            settings.activeLanguages.joinToString(" ") { it.short },
            settings.activeLanguages.joinToString("  ·  ") { it.nativeName }),
    )
}

/** Phones: a two-column grid of compact tiles. Tablets (or very large text on a phone): one row each. */
@Composable
private fun TileGrid(tiles: List<Tile>, onOpen: (HomeSection) -> Unit) {
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val fontScale = androidx.compose.ui.platform.LocalDensity.current.fontScale
        // Two columns while each tile still gets ~150dp of "normal-size text" width.
        val grid = maxWidth < 600.dp && maxWidth / fontScale >= 300.dp
        if (grid) {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                tiles.chunked(2).forEach { pair ->
                    Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        pair.forEach { t ->
                            SectionCard(t.icon, t.title, Modifier.weight(1f).fillMaxHeight(), onClick = { onOpen(t.section) }) {
                                Text(t.short, style = captionStyle(), maxLines = 2, overflow = TextOverflow.Ellipsis)
                            }
                        }
                        if (pair.size == 1) Spacer(Modifier.weight(1f))
                    }
                }
            }
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                tiles.forEach { t ->
                    SectionCard(t.icon, t.title, onClick = { onOpen(t.section) }) {
                        Text(t.long, style = bodyStyle(), maxLines = 3, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }
    }
}

/** Names for the editor (Home and Settings › Home). */
internal val homeTileNames = mapOf(
    "reader" to "Reader", "practice" to "Review and Rehearse", "saved" to "Saved words", "books" to "Books",
    "vocabulary" to "Vocabulary", "tests" to "Tests", "grammar" to "Grammar", "translate" to "Translate",
    "progress" to "Progress", "library" to "Library", "dictionary" to "Dictionary",
)

/** Reorder Home's cards and show or hide each one. */
@Composable
fun HomeTilesEditor(order: List<String>, hidden: Set<String>, onChange: (List<String>, Set<String>) -> Unit) {
    val c = Diacritica.colors
    Column(Modifier.padding(horizontal = 20.dp, vertical = 6.dp)) {
        Text("The search box is always at the top. Move cards up or down, or hide the ones you don't use.", style = captionStyle())
        Gap(6.dp)
        order.forEachIndexed { i, id ->
            val shown = id !in hidden
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    homeTileNames[id] ?: id, style = bodyStyle(if (shown) c.ink else c.inkMuted),
                    modifier = Modifier.weight(1f).padding(vertical = 4.dp),
                )
                PolyIconButton(PolyIcons.Up, "Move ${homeTileNames[id]} up", {
                    if (i > 0) onChange(order.toMutableList().apply { add(i - 1, removeAt(i)) }, hidden)
                }, tint = if (i == 0) c.line else c.inkMuted)
                PolyIconButton(PolyIcons.Down, "Move ${homeTileNames[id]} down", {
                    if (i < order.lastIndex) onChange(order.toMutableList().apply { add(i + 1, removeAt(i)) }, hidden)
                }, tint = if (i == order.lastIndex) c.line else c.inkMuted)
                PolyIconButton(
                    if (shown) PolyIcons.Eye else PolyIcons.EyeOff, if (shown) "Hide ${homeTileNames[id]}" else "Show ${homeTileNames[id]}",
                    { onChange(order, if (shown) hidden + id else hidden - id) }, tint = if (shown) c.accent else c.inkMuted,
                )
            }
        }
        if (order != app.diacritica.settings.HOME_TILES || hidden.isNotEmpty()) {
            Text(
                "Reset Home", style = captionStyle(c.accent),
                modifier = Modifier.clip(RoundedCornerShape(4.dp)).clickable { onChange(app.diacritica.settings.HOME_TILES, emptySet()) }.padding(vertical = 10.dp),
            )
        }
    }
}

/** A home-screen section: short text for the phone grid, long text for one-per-row (tablets). */
private data class Tile(val icon: ImageVector, val title: String, val section: HomeSection, val short: String, val long: String)

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
                visualTransformation = hanTransformation(),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search, autoCorrectEnabled = false),
                keyboardActions = KeyboardActions(onSearch = { if (q.isNotBlank()) { onSearch(q.trim()); q = "" } }),
                modifier = Modifier.fillMaxWidth(),
            )
        }
        if (q.isNotBlank()) PolyIconButton(PolyIcons.Check, "Search", onClick = { onSearch(q.trim()); q = "" })
    }
}
