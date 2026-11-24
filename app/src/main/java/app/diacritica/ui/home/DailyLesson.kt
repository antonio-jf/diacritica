package app.diacritica.ui.home

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.diacritica.DiacriticaApp
import app.diacritica.grammar.DailyNotifier
import app.diacritica.grammar.DailyTopic
import app.diacritica.grammar.DailyTopics
import app.diacritica.tests.Lesson
import app.diacritica.ui.components.Gap
import app.diacritica.ui.components.LangBadge
import app.diacritica.ui.components.PolyButton
import app.diacritica.ui.components.PolyChip
import app.diacritica.ui.components.PolyIconButton
import app.diacritica.ui.components.PolyIcons
import app.diacritica.ui.components.PolySurface
import app.diacritica.ui.components.ScreenHeader
import app.diacritica.ui.components.SectionLabel
import app.diacritica.ui.components.Text
import app.diacritica.ui.components.bodyStyle
import app.diacritica.ui.components.captionStyle
import app.diacritica.ui.components.headwordStyle
import app.diacritica.ui.theme.Diacritica
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/** The Lesson of the day card at the top of Home: open it, pick another, keep it for later, daily reminder. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun DailyLessonCard(onOpen: (String) -> Unit) {
    val context = LocalContext.current
    val daily = remember { (context.applicationContext as DiacriticaApp).container.daily }
    val st by daily.state.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    LaunchedEffect(Unit) { daily.today() }
    var reminder by rememberSaveable { mutableStateOf(false) }
    val c = Diacritica.colors
    val t = st.today ?: return
    PolySurface(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Lesson of the day", style = captionStyle(c.accent).copy(fontWeight = FontWeight.SemiBold), modifier = Modifier.weight(1f))
            LangBadge(t.lang, boxed = true)
        }
        Gap(6.dp)
        Text(t.title, style = headwordStyle(20.sp))
        Text(t.pattern, style = captionStyle(c.accent))
        Gap(4.dp)
        Text(t.blurb, style = bodyStyle(size = 15.sp))
        Gap(10.dp)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            PolyButton(if (t.id in st.written) "Open lesson" else "Learn it", { onOpen(t.id) })
            PolyButton("Another topic", { scope.launch { daily.another() } }, primary = false)
            PolyButton("Later", { scope.launch { daily.later() } }, primary = false)
        }
        if (st.later.isNotEmpty()) {
            Gap(10.dp)
            Text("Saved for later", style = captionStyle())
            Gap(4.dp)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                st.later.forEach { l -> PolyChip("${l.lang.short} · ${l.title}", false, { onOpen(l.id) }) }
            }
        }
        Gap(8.dp)
        Text(
            if (st.notify) "🔔 Daily reminder at %d:%02d".format(st.notifyAt / 60, st.notifyAt % 60) else "🔔 Daily reminder: off",
            style = captionStyle(c.inkMuted),
            modifier = Modifier.clip(RoundedCornerShape(4.dp)).clickable(role = Role.Button) { reminder = true }.padding(vertical = 6.dp),
        )
    }
    if (reminder) ReminderSheet(onDismiss = { reminder = false })
}

/** Turn the daily notification on or off and choose its time. Asks for notification permission (Android 13+). */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun ReminderSheet(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val daily = remember { (context.applicationContext as DiacriticaApp).container.daily }
    val st by daily.state.collectAsStateWithLifecycle()
    val c = Diacritica.colors
    var denied by remember { mutableStateOf(false) }
    fun apply(on: Boolean, at: Int = st.notifyAt) {
        daily.setNotify(on, at)
        DailyNotifier.schedule(context)
    }
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        denied = !ok
        if (ok) apply(true)
    }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true), containerColor = c.ground) {
        Column(Modifier.padding(horizontal = 20.dp).padding(bottom = 20.dp).navigationBarsPadding(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Daily reminder", style = headwordStyle(20.sp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("A notification with the day's topic, once a day.", style = bodyStyle(size = 15.sp), modifier = Modifier.weight(1f))
                Switch(
                    checked = st.notify,
                    onCheckedChange = { on ->
                        val needs = on && Build.VERSION.SDK_INT >= 33 &&
                            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
                        if (needs) ask.launch(Manifest.permission.POST_NOTIFICATIONS) else apply(on)
                    },
                    colors = SwitchDefaults.colors(checkedTrackColor = c.accent),
                )
            }
            if (denied) Text("Notifications are blocked for Diacritica. Allow them in the phone's app settings.", style = captionStyle(c.error))
            if (st.notify) {
                SectionLabel("Time")
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf(7 * 60, 8 * 60, 9 * 60, 12 * 60, 18 * 60, 20 * 60, 21 * 60 + 30).forEach { m ->
                        PolyChip("%d:%02d".format(m / 60, m % 60), st.notifyAt == m, { apply(true, m) })
                    }
                }
                Text("It comes around that time (Android may shift it by a few minutes to save battery).", style = captionStyle())
            }
        }
    }
}

/** A day's topic opened: the full lesson, written by Gemini the first time and kept. */
@Composable
fun DailyLessonScreen(id: String, onBack: () -> Unit, onPractise: (Lesson) -> Unit) {
    val context = LocalContext.current
    val container = remember { (context.applicationContext as DiacriticaApp).container }
    val topic: DailyTopic? = remember(id) { DailyTopics.all.firstOrNull { it.id == id } }
    val full by container.lessons.full.collectAsStateWithLifecycle()
    var key by remember(id) { mutableStateOf<String?>(null) }
    var error by remember(id) { mutableStateOf<String?>(null) }
    var attempt by remember(id) { mutableStateOf(0) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(id, attempt) {
        if (topic == null) return@LaunchedEffect
        error = null
        container.daily.open(topic).onSuccess { key = it }.onFailure { error = it.message ?: "Couldn't write the lesson." }
    }
    val lesson = key?.let { full[it] }
    if (lesson != null) {
        BackHandler(onBack = onBack)
        app.diacritica.ui.grammar.FullLessonPage(
            lesson, onBack = onBack,
            onSpeak = { l, text -> scope.launch { container.speaker.speak(text, l, container.settings.settings.first().speechRate) } },
            onPractise = onPractise,
        )
        return
    }
    val c = Diacritica.colors
    Column(Modifier.fillMaxSize().background(c.ground).verticalScroll(rememberScrollState())) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            PolyIconButton(PolyIcons.Back, "Back", onBack, tint = c.ink, modifier = Modifier.padding(start = 4.dp, top = 10.dp))
            ScreenHeader("Lesson of the day", Modifier.weight(1f))
        }
        Column(Modifier.padding(horizontal = Diacritica.spacing.screen), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (topic == null) { Text("This topic isn't available any more.", style = bodyStyle()); return@Column }
            Row(verticalAlignment = Alignment.CenterVertically) {
                LangBadge(topic.lang, boxed = true); Spacer(Modifier.width(8.dp))
                Text(topic.lang.nativeName, style = captionStyle())
            }
            Text(topic.title, style = headwordStyle(24.sp))
            Text(topic.pattern, style = captionStyle(c.accent).copy(fontSize = 15.sp))
            Text(topic.blurb, style = bodyStyle())
            Gap(8.dp)
            if (error != null) {
                Text(error!!, style = bodyStyle(c.error))
                PolyButton("Try again", { attempt++ })
            } else {
                Text("Gemini is writing the full lesson (how it's formed, each use with examples, common mistakes). It's kept afterwards, so this only happens once.", style = captionStyle())
            }
        }
    }
}
