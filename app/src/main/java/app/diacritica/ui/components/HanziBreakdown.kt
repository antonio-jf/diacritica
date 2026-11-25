package app.diacritica.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.diacritica.DiacriticaApp
import app.diacritica.data.DictionaryRepository
import app.diacritica.ui.theme.Diacritica

/**
 * A compact list of the characters in a Chinese word, one line each: the character, its pinyin in this
 * word and a short meaning of its own ("起 qǐ  to rise; to get up"). Nothing for single characters.
 * Tap a line to look that character up ([onOpen]).
 */
@Composable
fun HanziBreakdown(
    word: String,
    pinyin: String?,
    modifier: Modifier = Modifier,
    traditional: Boolean = false,
    onOpen: ((String) -> Unit)? = null,
) {
    val app = LocalContext.current.applicationContext as DiacriticaApp
    var chars by remember(word) { mutableStateOf<List<DictionaryRepository.HanziInfo>>(emptyList()) }
    LaunchedEffect(word, pinyin) {
        chars = runCatching { app.container.dictionary.hanziBreakdown(word, pinyin) }.getOrDefault(emptyList())
    }
    if (chars.isEmpty()) return
    val c = Diacritica.colors
    Column(modifier.fillMaxWidth()) {
        SectionLabel("Characters")
        chars.forEach { h ->
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(4.dp))
                    .then(if (onOpen != null) Modifier.clickable(role = Role.Button) { onOpen(h.char) } else Modifier)
                    .padding(vertical = 3.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text(
                    if (traditional) h.traditional ?: h.char else h.char,
                    style = TextStyle(fontFamily = Diacritica.fonts.display, fontSize = 20.sp, color = c.ink),
                )
                Text(
                    tonedPinyin(h.pinyin), style = TextStyle(fontFamily = Diacritica.fonts.body, fontSize = 13.sp, color = c.accent),
                    maxLines = 1, modifier = Modifier.widthIn(min = 44.dp),
                )
                Text(
                    h.meaning.ifEmpty { "—" }, style = captionStyle(c.ink),
                    maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f),
                )
            }
        }
    }
}
