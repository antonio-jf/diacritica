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
    val Copy = stroke("copy", "M9 9h10v11H9z", "M15 9V5H5v11h4")
    val Translate = stroke("translate", "M4 6h9", "M8.5 4v2", "M6 6c1 3 3 5 6 6", "M11 6c-1 3-3 5-6 7", "M13 20l4-9 4 9", "M14.5 17h5")
    val Chart = stroke("chart", "M4 20h16", "M7 16v-5", "M12 16V7", "M17 16v-8")
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
    val Handwriting = stroke("handwriting", "M4 17l2.5-.6 8.8-8.8-1.9-1.9-8.8 8.8z", "M12.2 7.9l1.9 1.9", "M10 20c2-1.8 3.3-1.8 4.5-.4 1.1 1.3 2.5 1.3 5.5-1.6")
    val Undo = stroke("undo", "M9 7L5 11l4 4", "M5 11h9a5 5 0 0 1 0 10h-3")
    val Backspace = stroke("backspace", "M9 5h11v14H9l-6-7z", "M12 9l5 6", "M17 9l-5 6")
    val Share = stroke("share", "M6 12a2 2 0 1 0 0.01 0z", "M18 6a2 2 0 1 0 0.01 0z", "M18 18a2 2 0 1 0 0.01 0z", "M8 11l8-4", "M8 13l8 4")
    val Approx = stroke("approx", "M5 10c2.3-2 4.7-2 7 0s4.7 2 7 0", "M5 15c2.3-2 4.7-2 7 0s4.7 2 7 0")
    val Question = stroke("question", "M9.5 9a2.5 2.5 0 1 1 3.6 2.25c-.7.35-1.1 1-1.1 1.75v.6", "M12 17.2v.1")
    val Quiz = stroke("quiz", "M9 4h6v3H9z", "M7 5.5H5.5v14.5h13V5.5H17", "M9 13.5l2 2 4-4")
    val Grammar = stroke("grammar", "M3.5 18l4-12 4 12", "M5 14h5", "M14.5 18v-5.5a2.5 2.5 0 0 1 5 0V18", "M14.5 15h5")
    val Play = stroke("play", "M8 5.5v13l10-6.5z", filled = true)
    val Stop = stroke("stop", "M7 7h10v10H7z", filled = true)
    val Check = stroke("check", "M5 12l5 5 9-10")
    val Archive = stroke("archive", "M4 5h16v4H4z", "M5 9v10h14V9", "M10 13h4")
    val Trash = stroke("trash", "M5 7h14", "M9 7V4h6v3", "M7 7l1 13h8l1-13")
    val Plus = stroke("plus", "M12 5v14", "M5 12h14")
    val Bookmark = stroke("bookmark", "M7 4h10v16l-5-4-5 4z")
    val BookmarkFilled = stroke("bookmark-filled", "M7 4h10v16l-5-4-5 4z", filled = true)
    val Explain = stroke("explain", "M4 6h16", "M4 11h10", "M4 16h7", "M17 14l1 2 2 1-2 1-1 2-1-2-2-1 2-1z")
    val Marker = stroke("marker", "M14.5 4.5l5 5-8 8H6.5v-5z", "M4 20h7")
    val Note = stroke("note", "M6 4h9l3 3v13H6z", "M9 11h6", "M9 15h6")
    /** An open book: the Books section (your EPUBs). */
    val Books = stroke("books", "M3 6.5c3-1.6 6-1.6 9 0V19c-3-1.6-6-1.6-9 0z", "M12 6.5c3-1.6 6-1.6 9 0V19c-3-1.6-6-1.6-9 0z")
    val TextSize = stroke("text-size", "M4 18l4.5-12L13 18", "M5.7 14h5.6", "M15 18l3-8 3 8", "M16 15.5h4")
    val Toc = stroke("toc", "M9 6h11", "M9 12h11", "M9 18h11", "M4.5 6h.01", "M4.5 12h.01", "M4.5 18h.01")
}
