package app.diacritica.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.diacritica.handwriting.Handwriting
import app.diacritica.handwriting.InkPoint
import app.diacritica.ui.theme.Diacritica
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * A writing square for Mandarin characters (with 田字格 guide lines). Candidates appear as you
 * write; tapping one inserts it and clears the square. Recognition runs on the phone; the model
 * downloads once (about 20 MB) the first time.
 *
 * [before] is the text already in the field (helps pick between look-alike characters).
 */
@Composable
fun HandwritingPad(
    before: String,
    onInsert: (String) -> Unit,
    onBackspace: () -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val c = Diacritica.colors
    val scope = rememberCoroutineScope()
    val strokes = remember { mutableStateListOf<List<InkPoint>>() }
    val current = remember { mutableStateListOf<InkPoint>() }
    var candidates by remember { mutableStateOf(emptyList<String>()) }
    var status by remember { mutableStateOf<String?>(null) }
    var padSize by remember { mutableStateOf(IntSize.Zero) }
    var job by remember { mutableStateOf<Job?>(null) }

    fun recognize() {
        job?.cancel()
        if (strokes.isEmpty()) { candidates = emptyList(); return }
        val snapshot = strokes.toList()
        job = scope.launch {
            delay(150)
            if (!Handwriting.recognizer.isDownloaded()) status = "Downloading the handwriting model (first time only, about 20 MB)…"
            candidates = runCatching {
                Handwriting.recognizer.recognize(snapshot, padSize.width.toFloat(), padSize.height.toFloat(), before)
            }.onFailure { status = "Couldn't recognize: ${it.message ?: "model not available"}. Connect to the internet once to download it." }
                .onSuccess { status = null }
                .getOrDefault(candidates)
        }
    }

    fun clear() { strokes.clear(); current.clear(); candidates = emptyList(); job?.cancel() }

    val shape = RoundedCornerShape(Diacritica.shapes.card.coerceIn(8.dp, 14.dp))
    Column(modifier.fillMaxWidth().clip(shape).background(c.surface).border(1.dp, c.line, shape).padding(8.dp)) {
        // Candidates
        Row(
            Modifier.fillMaxWidth().heightIn(min = 44.dp).horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically,
        ) {
            if (candidates.isEmpty()) {
                Text(status ?: "Write a character below", style = captionStyle(), modifier = Modifier.padding(horizontal = 6.dp))
            }
            candidates.forEach { cand ->
                Text(
                    cand,
                    style = TextStyle(fontFamily = Diacritica.fonts.display, fontSize = 22.sp, color = c.ink),
                    modifier = Modifier.clip(RoundedCornerShape(6.dp)).background(c.accentSoft)
                        .clickable { onInsert(cand); clear() }.padding(horizontal = 12.dp, vertical = 4.dp),
                )
            }
        }
        // Writing square
        val ink = c.ink
        val guide = c.line
        Box(
            Modifier.fillMaxWidth().height(220.dp).padding(vertical = 6.dp).clip(RoundedCornerShape(6.dp)).background(c.ground)
                .border(1.dp, c.line, RoundedCornerShape(6.dp))
                .onSizeChanged { padSize = it }
                .semantics { contentDescription = "Handwriting area: write a Chinese character" }
                .pointerInput(Unit) {
                    awaitEachGesture {
                        val down = awaitFirstDown()
                        val t0 = System.currentTimeMillis()
                        current.clear()
                        current += InkPoint(down.position.x, down.position.y, t0)
                        down.consume()
                        while (true) {
                            val event = awaitPointerEvent()
                            val ch = event.changes.firstOrNull { it.id == down.id } ?: break
                            if (!ch.pressed) break
                            if (ch.positionChange() != Offset.Zero) {
                                current += InkPoint(ch.position.x, ch.position.y, System.currentTimeMillis())
                                ch.consume()
                            }
                        }
                        if (current.isNotEmpty()) strokes += current.toList()
                        current.clear()
                        recognize()
                    }
                },
        ) {
            Canvas(Modifier.fillMaxSize()) {
                val dash = PathEffect.dashPathEffect(floatArrayOf(10f, 10f))
                val w = size.width; val h = size.height
                val side = minOf(w, h)
                val left = (w - side) / 2
                // 田字格: a square with dashed centre lines and diagonals
                drawRect(guide, Offset(left, 0f), androidx.compose.ui.geometry.Size(side, side), style = Stroke(1.5f))
                drawLine(guide, Offset(left, h / 2), Offset(left + side, h / 2), 1f, pathEffect = dash)
                drawLine(guide, Offset(w / 2, 0f), Offset(w / 2, h), 1f, pathEffect = dash)
                drawLine(guide, Offset(left, 0f), Offset(left + side, side), 1f, pathEffect = dash)
                drawLine(guide, Offset(left + side, 0f), Offset(left, side), 1f, pathEffect = dash)
                val pen = Stroke(width = 5.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round)
                (strokes + listOf(current.toList())).forEach { s ->
                    if (s.isEmpty()) return@forEach
                    val path = Path().apply {
                        moveTo(s[0].x, s[0].y)
                        if (s.size == 1) lineTo(s[0].x + 0.1f, s[0].y)
                        s.drop(1).forEach { lineTo(it.x, it.y) }
                    }
                    drawPath(path, ink, style = pen)
                }
            }
        }
        // Controls
        Row(verticalAlignment = Alignment.CenterVertically) {
            PolyIconButton(PolyIcons.Undo, "Undo last stroke", { if (strokes.isNotEmpty()) { strokes.removeAt(strokes.lastIndex); recognize() } }, tint = c.ink)
            PolyIconButton(PolyIcons.Trash, "Clear the square", { clear() }, tint = c.ink)
            PolyIconButton(PolyIcons.Backspace, "Delete last character", onBackspace, tint = c.ink)
            Spacer(Modifier.weight(1f))
            Text("Close", style = captionStyle(c.accent), modifier = Modifier.clip(RoundedCornerShape(4.dp)).clickable(onClick = onClose).padding(horizontal = 12.dp, vertical = 10.dp))
        }
    }
}
