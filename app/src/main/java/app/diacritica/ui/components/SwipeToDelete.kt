package app.diacritica.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.diacritica.ui.theme.Diacritica

/**
 * A row you can swipe away (either direction) to delete. Pairs with the app's Undo bar, so a
 * swipe by mistake is one tap to reverse.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SwipeToDelete(onDelete: () -> Unit, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    val delete by rememberUpdatedState(onDelete)
    val state = rememberSwipeToDismissBoxState(
        confirmValueChange = { v -> if (v != SwipeToDismissBoxValue.Settled) { delete(); true } else false },
    )
    val c = Diacritica.colors
    SwipeToDismissBox(
        state = state,
        modifier = modifier,
        backgroundContent = {
            val toStart = state.dismissDirection == SwipeToDismissBoxValue.EndToStart
            Row(
                Modifier.fillMaxSize().background(c.error.copy(alpha = 0.14f)).padding(horizontal = 24.dp),
                horizontalArrangement = if (toStart) Arrangement.End else Arrangement.Start,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(PolyIcons.Trash, null, tint = c.error)
                Text("  Delete", style = captionStyle(c.error))
            }
        },
    ) { content() }
}
