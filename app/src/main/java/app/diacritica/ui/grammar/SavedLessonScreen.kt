package app.diacritica.ui.grammar

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.diacritica.DiacriticaApp
import app.diacritica.tests.Lesson
import app.diacritica.ui.components.PolyIconButton
import app.diacritica.ui.components.PolyIcons
import app.diacritica.ui.components.ScreenHeader
import app.diacritica.ui.components.Text
import app.diacritica.ui.components.bodyStyle
import app.diacritica.ui.theme.Diacritica
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/** One full lesson by its key (opened from Ask); the same page as in Grammar › Full lessons. */
@Composable
fun SavedLessonScreen(key: String, onBack: () -> Unit, onPractise: (Lesson) -> Unit) {
    val context = LocalContext.current
    val container = remember { (context.applicationContext as DiacriticaApp).container }
    LaunchedEffect(Unit) { container.lessons.load() }
    val full by container.lessons.full.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    BackHandler(onBack = onBack)
    val lesson = full[key]
    if (lesson != null) {
        FullLessonPage(
            lesson, onBack = onBack,
            onSpeak = { l, text -> scope.launch { container.speaker.speak(text, l, container.settings.settings.first().speechRate) } },
            onPractise = onPractise,
        )
        return
    }
    val c = Diacritica.colors
    Column(Modifier.fillMaxSize()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            PolyIconButton(PolyIcons.Back, "Back", onBack, tint = c.ink, modifier = Modifier.padding(start = 4.dp, top = 10.dp))
            ScreenHeader("Lesson", Modifier.weight(1f))
        }
        Text("This lesson isn't on the phone any more.", style = bodyStyle(c.inkMuted), modifier = Modifier.padding(horizontal = Diacritica.spacing.screen))
    }
}
