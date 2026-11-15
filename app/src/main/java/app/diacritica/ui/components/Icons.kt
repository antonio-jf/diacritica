package app.diacritica.ui.components

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.unit.dp

/** Stroke icons (24×24, drawn with currentColor via Icon's tint). */
object PolyIcons {
    private fun stroke(name: String, vararg paths: String, width: Float = 1.8f, filled: Boolean = false): ImageVector {
        val b = ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f)
        for (p in paths) {
            b.addPath(
                pathData = PathParser().parsePathString(p).toNodes(),
                fill = if (filled) SolidColor(Color.Black) else null,
                stroke = SolidColor(Color.Black),
                strokeLineWidth = width,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            )
        }
        return b.build()
    }

    val Speaker = stroke("speaker", "M4 9v6h4l5 4V5L8 9H4z", "M16 9a4 4 0 0 1 0 6")
    val Slow = stroke("slow", "M4 9v6h4l5 4V5L8 9H4z", "M17 10.5h4", "M17 13.5h4")
    val Star = stroke("star", "M12 3l2.7 5.6 6.1.9-4.4 4.3 1 6.1L12 17l-5.4 2.9 1-6.1L3.2 9.5l6.1-.9z")
    /** Saved state: filled, so it reads clearly even when accent and muted colors are close. */
    val StarFilled = stroke("star-filled", "M12 3l2.7 5.6 6.1.9-4.4 4.3 1 6.1L12 17l-5.4 2.9 1-6.1L3.2 9.5l6.1-.9z", filled = true)
    val Mic = stroke("mic", "M9 6a3 3 0 0 1 6 0v5a3 3 0 0 1 -6 0z", "M5 11a7 7 0 0 0 14 0", "M12 18v3")
    val Search = stroke("search", "M11 5a6 6 0 1 1 0 12a6 6 0 0 1 0 -12z", "M20 20l-4.5-4.5")
    val Close = stroke("close", "M6 6l12 12", "M18 6L6 18")
    val Back = stroke("back", "M15 5l-7 7 7 7")
    val Layout = stroke("layout", "M4 5h16", "M4 12h16", "M4 19h16")
    val Cards = stroke("cards", "M5 4h14a2 2 0 0 1 2 2v4a2 2 0 0 1 -2 2H5a2 2 0 0 1 -2 -2V6a2 2 0 0 1 2 -2z",
        "M5 14h14a2 2 0 0 1 2 2v2a2 2 0 0 1 -2 2H5a2 2 0 0 1 -2 -2v-2a2 2 0 0 1 2 -2z")
    val Home = stroke("home", "M4 11l8-7 8 7", "M6 9.5V20h12V9.5", "M10 20v-6h4v6")
    val Book = stroke("book", "M5 4h10a3 3 0 0 1 3 3v13H8a3 3 0 0 1 -3 -3z", "M5 17a3 3 0 0 1 3 -3h10")
    val Library = stroke("library", "M5 4v16", "M10 4v16", "M15 4l4 16")
    val Settings = stroke("settings", "M12 9a3 3 0 1 1 0 6a3 3 0 0 1 0 -6z",
        "M19 12l2-1-1-3-2.3.2-1.2-1.6.6-2.2-2.8-1.4L13 4h-2l-1.3 1.6-2.8-1.4.6 2.2-1.2 1.6L4 8l-1 3 2 1-2 1 1 3 2.3-.2 1.2 1.6-.6 2.2 2.8 1.4L11 20h2l1.3-1.6 2.8 1.4-.6-2.2 1.2-1.6L20 16l1-3z")
    val Swap = stroke("swap", "M7 4L4 7l3 3", "M4 7h13", "M17 20l3-3-3-3", "M20 17H7")
    val ChevronDown = stroke("chevron", "M6 9l6 6 6-6")
    val Up = stroke("up", "M12 19V5", "M6 11l6-6 6 6")
    val Down = stroke("down", "M12 5v14", "M6 13l6 6 6-6")
    val Pivot = stroke("pivot", "M14 5h5v5", "M19 5l-8 8", "M18 14v5H5V6h5")
    val Pencil = stroke("pencil", "M4 20l4-1 11-11-3-3L5 16z", "M14 6l3 3")
    val Check = stroke("check", "M5 12l5 5 9-10")
    val Archive = stroke("archive", "M4 5h16v4H4z", "M5 9v10h14V9", "M10 13h4")
    val Trash = stroke("trash", "M5 7h14", "M9 7V4h6v3", "M7 7l1 13h8l1-13")
}
