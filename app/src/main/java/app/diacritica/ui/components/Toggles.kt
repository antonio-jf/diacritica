package app.diacritica.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.animateColorAsState
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import app.diacritica.ui.theme.Diacritica

/** A small on/off switch drawn in the app's own colours (used instead of "✓" marks). */
@Composable
fun ToggleSwitch(checked: Boolean, modifier: Modifier = Modifier) {
    val c = Diacritica.colors
    val track by animateColorAsState(if (checked) c.accent else c.surfaceAlt, label = "track")
    val knobX by animateDpAsState(if (checked) 16.dp else 2.dp, label = "knob")
    Box(
        modifier.size(width = 36.dp, height = 22.dp).clip(RoundedCornerShape(11.dp)).background(track)
            .border(1.dp, if (checked) c.accent else c.line, RoundedCornerShape(11.dp)),
        contentAlignment = Alignment.CenterStart,
    ) {
        Box(Modifier.offset(x = knobX).size(18.dp).clip(CircleShape).background(if (checked) c.onAccent else c.inkMuted))
    }
}

/** A menu entry that turns something on or off: label (+ optional note) and a switch on the right. */
@Composable
fun ToggleMenuItem(label: String, checked: Boolean, onToggle: () -> Unit, note: String? = null) {
    val c = Diacritica.colors
    DropdownMenuItem(
        text = {
            Column {
                Text(label, style = bodyStyle(c.ink))
                note?.let { Text(it, style = captionStyle()) }
            }
        },
        trailingIcon = { ToggleSwitch(checked) },
        onClick = onToggle,
        modifier = Modifier.semantics { stateDescription = if (checked) "On" else "Off" },
    )
}

/** A full-width settings row with a switch. */
@Composable
fun ToggleRow(label: String, checked: Boolean, onToggle: () -> Unit, modifier: Modifier = Modifier, note: String? = null) {
    Row(
        modifier.fillMaxWidth().clickable(role = Role.Switch, onClick = onToggle)
            .semantics { contentDescription = label; stateDescription = if (checked) "On" else "Off" }
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(label, style = bodyStyle())
            note?.let { Text(it, style = captionStyle()) }
        }
        ToggleSwitch(checked, Modifier.padding(start = 12.dp))
    }
}

/** A choice in a menu (one of several): the chosen one gets an accent dot instead of a check mark. */
@Composable
fun ChoiceMenuItem(label: String, selected: Boolean, onClick: () -> Unit, note: String? = null) {
    val c = Diacritica.colors
    DropdownMenuItem(
        text = {
            Column {
                Text(label, style = bodyStyle(if (selected) c.accent else c.ink))
                note?.let { Text(it, style = captionStyle()) }
            }
        },
        leadingIcon = {
            Box(Modifier.size(16.dp).clip(CircleShape).border(1.5.dp, if (selected) c.accent else c.line, CircleShape), contentAlignment = Alignment.Center) {
                if (selected) Box(Modifier.size(8.dp).clip(CircleShape).background(c.accent))
            }
        },
        onClick = onClick,
        modifier = Modifier.semantics { stateDescription = if (selected) "Selected" else "Not selected" },
    )
}

/** A small heading between groups of menu entries. */
@Composable
fun MenuSection(title: String) {
    Text(title.uppercase(), style = captionStyle().copy(letterSpacing = androidx.compose.ui.unit.TextUnit(0.8f, androidx.compose.ui.unit.TextUnitType.Sp)),
        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 10.dp, bottom = 2.dp))
}
