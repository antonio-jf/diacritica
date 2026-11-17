package app.diacritica.ui.onboarding

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import app.diacritica.ui.components.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.diacritica.settings.AppSettings
import app.diacritica.settings.ThemeId
import app.diacritica.ui.components.PolyButton
import app.diacritica.ui.components.captionStyle
import app.diacritica.ui.components.titleStyle
import app.diacritica.ui.settings.ThemePreviewCard
import app.diacritica.ui.theme.Diacritica
import app.diacritica.ui.theme.DiacriticaTheme

/** First launch: pick a theme only (Paper preselected). Layouts start at their defaults. */
@Composable
fun ThemePickerScreen(settings: AppSettings, onDone: (ThemeId) -> Unit) {
    var choice by rememberSaveable { mutableStateOf(ThemeId.PAPER) }
    // The picker itself is drawn in the theme being previewed, so the whole screen updates live.
    DiacriticaTheme(settings.copy(theme = choice)) {
        Box(Modifier.fillMaxSize().background(Diacritica.colors.ground).statusBarsPadding().navigationBarsPadding()) {
            LazyColumn(
                Modifier.fillMaxSize(),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(start = 20.dp, end = 20.dp, top = 24.dp, bottom = 100.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                item {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("Pick a theme", style = titleStyle())
                        Text(
                            "It applies to the whole app. You can change it, and each section's layout, later in Settings.",
                            style = captionStyle(),
                        )
                    }
                }
                items(ThemeId.entries) { t ->
                    ThemePreviewCard(t, settings.mode, settings.accent, settings.languageColors, selected = t == choice, onClick = { choice = t })
                }
            }
            Box(Modifier.align(androidx.compose.ui.Alignment.BottomCenter).fillMaxWidth().background(Diacritica.colors.ground).padding(20.dp)) {
                PolyButton("Continue with ${choice.title}", { onDone(choice) }, modifier = Modifier.fillMaxWidth())
            }
        }
    }
}
