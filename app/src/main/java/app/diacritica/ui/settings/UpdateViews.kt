@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package app.diacritica.ui.settings

import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import app.diacritica.ui.components.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.diacritica.update.UpdateState
import app.diacritica.update.Updater
import app.diacritica.ui.components.PolyButton
import app.diacritica.ui.components.bodyStyle
import app.diacritica.ui.components.captionStyle
import app.diacritica.ui.theme.Diacritica

private fun mb(b: Long) = "%.1f MB".format(b / 1_048_576.0)

/**
 * The update's status and its next action (Download / Install / Try again). [compact] hides the
 * "up to date" and idle states (for Home, which only shows something when there is news).
 */
@Composable
fun UpdatePanel(state: UpdateState, updater: Updater, compact: Boolean, modifier: Modifier = Modifier) {
    val c = Diacritica.colors
    val uri = LocalUriHandler.current
    if (compact && (state is UpdateState.Idle || state is UpdateState.Checking || state is UpdateState.UpToDate)) return
    if (compact && state is UpdateState.Failed && state.release == null) return
    val shape = RoundedCornerShape(Diacritica.shapes.card.coerceIn(8.dp, 14.dp))
    Column(
        modifier.fillMaxWidth().clip(shape).background(if (compact) c.accentSoft.copy(alpha = 0.5f) else c.surface)
            .border(1.dp, if (compact) c.accent.copy(alpha = 0.5f) else c.line, shape).padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        when (state) {
            UpdateState.Idle -> Text("Version ${updater.currentName}", style = bodyStyle())
            UpdateState.Checking -> Text("Checking for updates…", style = bodyStyle())
            UpdateState.UpToDate -> Text("Version ${updater.currentName}: up to date.", style = bodyStyle())
            is UpdateState.Available -> {
                Text("Update available: ${state.release.tag.removePrefix("v")} (${mb(state.release.bytes)})", style = bodyStyle().copy(fontSize = 16.sp))
                Text("You have ${updater.currentName}. The download resumes by itself if the connection drops, and is checked before installing.", style = captionStyle())
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    PolyButton("Download", { updater.download(state.release) })
                    PolyButton("What's new", { uri.openUri(state.release.notesUrl) }, primary = false)
                }
            }
            is UpdateState.Downloading -> {
                Text("Downloading ${state.release.tag.removePrefix("v")}… ${mb(state.done)} of ${mb(state.total)}", style = bodyStyle())
                val f = if (state.total > 0) (state.done.toFloat() / state.total).coerceIn(0f, 1f) else 0f
                Box(Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)).background(c.surfaceAlt)) {
                    Box(Modifier.fillMaxWidth(f).height(6.dp).clip(RoundedCornerShape(3.dp)).background(c.accent))
                }
                Text("You can leave the app; it continues in the background.", style = captionStyle())
            }
            is UpdateState.Ready -> {
                Text("Update ${state.release.tag.removePrefix("v")} downloaded and checked.", style = bodyStyle())
                Text("The first time, Android asks to allow installs from Diacritica: allow it, come back and tap Install again.", style = captionStyle())
                PolyButton("Install", { updater.install(state.file) })
            }
            is UpdateState.Failed -> {
                Text(state.message, style = bodyStyle(c.error, 14.sp))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    state.release?.let { r -> PolyButton("Try again", { updater.download(r) }) }
                    if (!compact) PolyButton("Check again", { updater.check(force = true) }, primary = false)
                }
            }
        }
        if (!compact && state !is UpdateState.Downloading && state !is UpdateState.Ready && state !is UpdateState.Available) {
            PolyButton("Check for updates", { updater.check(force = true) }, primary = false)
        }
    }
}
