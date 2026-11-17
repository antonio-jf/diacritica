package app.diacritica.ui.books

import android.graphics.BitmapFactory
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import app.diacritica.ui.components.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.diacritica.data.Lang
import app.diacritica.mybooks.MyBook
import app.diacritica.ui.components.Gap
import app.diacritica.ui.components.LangBadge
import app.diacritica.ui.components.PolyButton
import app.diacritica.ui.components.PolyIconButton
import app.diacritica.ui.components.PolyIcons
import app.diacritica.ui.components.PolySurface
import app.diacritica.ui.components.ScreenHeader
import app.diacritica.ui.components.bodyStyle
import app.diacritica.ui.components.captionStyle
import app.diacritica.ui.components.headwordStyle
import app.diacritica.ui.theme.Diacritica
import java.io.File

/** Books: your own EPUBs, read in one language with the dictionary a tap away. */
@Composable
fun BooksScreen(vm: BooksViewModel, onBack: (() -> Unit)?, onOpen: (Long) -> Unit) {
    val c = Diacritica.colors
    val books by vm.books.collectAsStateWithLifecycle()
    val lib by vm.library.collectAsStateWithLifecycle()
    val pad = Diacritica.spacing.screen
    // Some file managers label EPUBs as zip or "binary": accept those too, the parser checks the contents.
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let(vm::import) }
    fun importBook() = pick.launch(arrayOf("application/epub+zip", "application/zip", "application/octet-stream"))
    LaunchedEffect(lib.openId) { lib.openId?.let { vm.opened(); onOpen(it) } }
    var deleting by remember { mutableStateOf<MyBook?>(null) }

    Column(Modifier.fillMaxSize()) {
        ScreenHeader("Books") {
            if (books.isNotEmpty()) PolyIconButton(PolyIcons.Plus, "Import an EPUB", onClick = ::importBook, tint = c.ink)
            onBack?.let { PolyIconButton(PolyIcons.Close, "Close", onClick = it, tint = c.inkMuted) }
        }
        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = pad, end = pad, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            if (lib.importing) item {
                PolySurface {
                    Text("Importing…", style = bodyStyle())
                    Gap(8.dp)
                    LinearProgressIndicator(Modifier.fillMaxWidth(), color = c.accent, trackColor = c.line)
                }
            }
            (lib.error ?: lib.message)?.let { msg ->
                item {
                    PolySurface(onClick = vm::clearMessage) {
                        Text(msg, style = bodyStyle(if (lib.error != null) c.warning else c.ink))
                        Text("Tap to dismiss", style = captionStyle())
                    }
                }
            }
            if (books.isEmpty() && !lib.importing) item { EmptyShelf(onImport = ::importBook) }
            items(books, key = { it.id }) { b -> BookRow(b, onClick = { onOpen(b.id) }, onDelete = { deleting = b }) }
        }
    }

    deleting?.let { b ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            containerColor = c.ground,
            title = { Text("Remove “${b.title}”?", style = headwordStyle(19.sp)) },
            text = { Text("Your reading position goes with it. Words you saved from it stay in your Library and Review.", style = bodyStyle()) },
            confirmButton = { TextButton(onClick = { vm.delete(b.id); deleting = null }) { Text("Remove", color = c.warning) } },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text("Cancel", color = c.ink) } },
        )
    }
}

@Composable
private fun EmptyShelf(onImport: () -> Unit) {
    val c = Diacritica.colors
    PolySurface {
        Text("Bring your own books", style = headwordStyle(20.sp))
        Gap(6.dp)
        Text(
            "Import an EPUB and read it in its own language. Tap any word for the dictionary in all your languages, " +
                "save it to Review with the sentence it came from, and pick up where you left off.",
            style = bodyStyle(),
        )
        Gap(10.dp)
        Text(
            "Works with DRM-free EPUBs: Standard Ebooks, Project Gutenberg, Wikisource, many independent publishers, or your own files. " +
                "Books bought on Kindle, Kobo or Google Play are usually copy-protected and can't be opened outside their apps.",
            style = captionStyle(),
        )
        Gap(14.dp)
        PolyButton("Import an EPUB", onClick = onImport)
    }
}

@Composable
private fun BookRow(b: MyBook, onClick: () -> Unit, onDelete: () -> Unit) {
    val c = Diacritica.colors
    val lang = Lang.fromCode(b.lang)
    PolySurface(onClick = onClick, padding = PaddingValues(start = 12.dp, end = 4.dp, top = 12.dp, bottom = 12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Cover(b, Modifier.width(52.dp).height(76.dp))
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(b.title, style = headwordStyle(17.sp), maxLines = 2, overflow = TextOverflow.Ellipsis)
                b.author?.let { Text(it, style = captionStyle(), maxLines = 1, overflow = TextOverflow.Ellipsis) }
                Gap(6.dp)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    lang?.let { LangBadge(it); Spacer(Modifier.width(8.dp)) }
                    Text(
                        if (b.openedAt == null) "Not started · ${b.chapters} chapters"
                        else "Chapter ${b.chapter + 1} of ${b.chapters} · ${(b.progress * 100).toInt()}%",
                        style = captionStyle(), maxLines = 1,
                    )
                }
                if (b.openedAt != null) {
                    Gap(6.dp)
                    Box(Modifier.fillMaxWidth().height(3.dp).clip(RoundedCornerShape(2.dp)).background(c.line)) {
                        Box(Modifier.fillMaxWidth(b.progress.coerceIn(0f, 1f)).height(3.dp).background(c.accent))
                    }
                }
            }
            PolyIconButton(PolyIcons.Trash, "Remove book", onClick = onDelete, tint = c.inkMuted)
        }
    }
}

/** The cover thumbnail, or the title's first letter on a tinted card. */
@Composable
internal fun Cover(b: MyBook, modifier: Modifier) {
    val c = Diacritica.colors
    val bitmap = remember(b.cover) { b.cover?.let { p -> runCatching { BitmapFactory.decodeFile(p)?.asImageBitmap() }.getOrNull() } }
    val shape = RoundedCornerShape(4.dp)
    if (bitmap != null && File(b.cover!!).exists()) {
        Image(bitmap, contentDescription = null, contentScale = ContentScale.Crop, modifier = modifier.clip(shape))
    } else {
        Box(modifier.clip(shape).background(c.accentSoft), contentAlignment = Alignment.Center) {
            Text(b.title.trim().take(1).uppercase(), style = headwordStyle(24.sp).copy(color = c.accent))
        }
    }
}
