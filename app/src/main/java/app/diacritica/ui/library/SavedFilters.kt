package app.diacritica.ui.library

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.diacritica.data.Lang
import app.diacritica.data.TextNorm
import app.diacritica.data.user.SavedWord
import app.diacritica.ui.components.Gap
import app.diacritica.ui.components.PolyButton
import app.diacritica.ui.components.PolyChip
import app.diacritica.ui.components.PolyIconButton
import app.diacritica.ui.components.PolyIcons
import app.diacritica.ui.components.SectionLabel
import app.diacritica.ui.components.Text
import app.diacritica.ui.components.bodyStyle
import app.diacritica.ui.components.captionStyle
import app.diacritica.ui.theme.hanTransformation
import app.diacritica.ui.components.headwordStyle
import app.diacritica.ui.theme.Diacritica
import java.util.Calendar

/** When a word was saved. */
enum class SavedWhen(val label: String) {
    ANY("Any time"), TODAY("Today"), WEEK("Last 7 days"), MONTH("Last 30 days"), YEAR("This year"), OLDER("Older than 30 days");

    fun matches(at: Long, now: Long = System.currentTimeMillis()): Boolean {
        val day = 24L * 60 * 60 * 1000
        return when (this) {
            ANY -> true
            TODAY -> at >= startOfDay(now)
            WEEK -> at >= startOfDay(now) - 6 * day
            MONTH -> at >= startOfDay(now) - 29 * day
            YEAR -> Calendar.getInstance().apply { timeInMillis = at }.get(Calendar.YEAR) ==
                Calendar.getInstance().apply { timeInMillis = now }.get(Calendar.YEAR)
            OLDER -> at < startOfDay(now) - 29 * day
        }
    }

    private fun startOfDay(t: Long) = Calendar.getInstance().apply {
        timeInMillis = t; set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
    }.timeInMillis
}

enum class SavedSort(val label: String) { NEWEST("Newest first"), OLDEST("Oldest first"), AZ("A–Z") }

/** Everything that narrows the saved list. */
data class SavedFilter(
    val query: String = "",
    val lang: String? = null,
    val source: String? = null,
    val time: SavedWhen = SavedWhen.ANY,
    val sort: SavedSort = SavedSort.NEWEST,
) {
    /** Filters chosen in the sheet (not the search text or the sort). */
    val activeCount: Int get() = listOf(lang != null, source != null, time != SavedWhen.ANY).count { it }

    fun apply(all: List<SavedWord>): List<SavedWord> {
        val q = TextNorm.normalize(query.trim())
        val out = all.filter { w ->
            (lang == null || w.lang == lang) &&
                (source == null || sourceLabel(w.deck) == source) &&
                time.matches(w.at) &&
                (q.isEmpty() || TextNorm.normalize(w.word).contains(q) || TextNorm.normalize(w.note.orEmpty()).contains(q))
        }
        return when (sort) {
            SavedSort.NEWEST -> out.sortedByDescending { it.at }
            SavedSort.OLDEST -> out.sortedBy { it.at }
            SavedSort.AZ -> out.sortedBy { TextNorm.normalize(it.word) }
        }
    }
}

/** Search box plus the Filter button (with a count of active filters). */
@Composable
fun SavedSearchBar(filter: SavedFilter, onQuery: (String) -> Unit, onOpenFilters: () -> Unit, modifier: Modifier = Modifier) {
    val c = Diacritica.colors
    val shape = RoundedCornerShape(Diacritica.shapes.field)
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(
            Modifier.weight(1f).clip(shape).background(c.surface).padding(start = 12.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(PolyIcons.Search, null, tint = c.inkMuted, modifier = Modifier.size(16.dp))
            Box(Modifier.weight(1f).padding(start = 10.dp, top = 12.dp, bottom = 12.dp)) {
                if (filter.query.isEmpty()) Text("Search saved words", style = bodyStyle(c.inkMuted, 15.sp))
                BasicTextField(
                    value = filter.query, onValueChange = onQuery, singleLine = true,
                    textStyle = bodyStyle(size = 15.sp), cursorBrush = SolidColor(c.accent),
                    visualTransformation = hanTransformation(),
                    keyboardOptions = KeyboardOptions(autoCorrectEnabled = false, imeAction = ImeAction.Search),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            if (filter.query.isNotEmpty()) PolyIconButton(PolyIcons.Close, "Clear search", { onQuery("") }, tint = c.inkMuted)
        }
        Box {
            Row(
                Modifier.clip(shape).background(if (filter.activeCount > 0) c.accentSoft else c.surface)
                    .clickable(role = Role.Button, onClick = onOpenFilters).padding(horizontal = 14.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(PolyIcons.Filter, null, tint = if (filter.activeCount > 0) c.onAccentSoft else c.ink, modifier = Modifier.size(18.dp))
                Text(
                    "Filter", style = bodyStyle(if (filter.activeCount > 0) c.onAccentSoft else c.ink, 14.sp),
                    modifier = Modifier.padding(start = 6.dp),
                )
            }
            if (filter.activeCount > 0) Box(
                Modifier.align(Alignment.TopEnd).offset(x = 4.dp, y = (-4).dp).size(18.dp).clip(CircleShape).background(c.accent),
                contentAlignment = Alignment.Center,
            ) { Text("${filter.activeCount}", style = captionStyle(c.onAccent).copy(fontSize = 11.sp, fontWeight = FontWeight.SemiBold)) }
        }
    }
}

/** Language, source, date and sort, in a bottom sheet. Changes apply as you tap. */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun SavedFilterSheet(all: List<SavedWord>, filter: SavedFilter, onChange: (SavedFilter) -> Unit, onDismiss: () -> Unit) {
    val c = Diacritica.colors
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true), containerColor = c.ground) {
        Column(
            Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).padding(bottom = 16.dp).navigationBarsPadding(),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text("Filter saved words", style = headwordStyle(20.sp))
            Text("${filter.apply(all).size} of ${all.size} words", style = captionStyle())

            Gap(8.dp); SectionLabel("Language")
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                PolyChip("All", filter.lang == null, { onChange(filter.copy(lang = null)) })
                Lang.defaultOrder.filter { l -> all.any { it.lang == l.code } }.forEach { l ->
                    PolyChip("${l.short} ${all.count { it.lang == l.code }}", filter.lang == l.code, { onChange(filter.copy(lang = l.code)) })
                }
            }

            Gap(8.dp); SectionLabel("Saved from")
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                PolyChip("Anywhere", filter.source == null, { onChange(filter.copy(source = null)) })
                all.map { sourceLabel(it.deck) }.distinct().sorted().forEach { s ->
                    PolyChip(s, filter.source == s, { onChange(filter.copy(source = s)) })
                }
            }

            Gap(8.dp); SectionLabel("Saved")
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                SavedWhen.entries.forEach { t ->
                    val n = all.count { t.matches(it.at) }
                    PolyChip(if (t == SavedWhen.ANY) t.label else "${t.label} $n", filter.time == t, { onChange(filter.copy(time = t)) })
                }
            }

            Gap(8.dp); SectionLabel("Sort")
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                SavedSort.entries.forEach { s -> PolyChip(s.label, filter.sort == s, { onChange(filter.copy(sort = s)) }) }
            }

            Gap(14.dp)
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                PolyButton("Show ${filter.apply(all).size}", onDismiss)
                if (filter.activeCount > 0 || filter.sort != SavedSort.NEWEST) {
                    PolyButton("Clear filters", { onChange(SavedFilter(query = filter.query)) }, primary = false)
                }
            }
        }
    }
}
