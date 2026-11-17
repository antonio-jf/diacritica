package app.diacritica.mybooks

import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import org.jsoup.nodes.Node
import org.jsoup.nodes.TextNode
import org.jsoup.parser.Parser
import org.jsoup.select.NodeFilter
import java.io.InputStream
import java.util.zip.ZipInputStream

/** A paragraph of a book: its text (line breaks kept for verse) and whether it's a heading. */
data class Para(val text: String, val heading: Boolean = false)

data class ParsedChapter(val title: String, val paragraphs: List<Para>)

data class ParsedBook(
    val title: String,
    val author: String?,
    /** The book's own language tag (dc:language), e.g. "fr", "zh-CN"; null if missing. */
    val language: String?,
    val cover: ByteArray?,
    val chapters: List<ParsedChapter>,
)

class EpubException(message: String) : Exception(message)

/**
 * Reads an EPUB (2 or 3) into chapters of plain paragraphs. Keeps the text, headings and verse line breaks;
 * drops images, tables' layout, page-number markers and footnote references.
 *
 * Chapters follow the table of contents: a file with a TOC entry starts a chapter, an anchor inside a file
 * (file.xhtml#ch2) splits it there, and files without an entry continue the previous chapter. Without any TOC,
 * each file is a chapter named after its first heading.
 */
object EpubParser {
    private const val MAX_TOTAL_BYTES = 250L * 1024 * 1024

    fun parse(input: InputStream): ParsedBook = parse(unzip(input))

    fun parse(files: Map<String, ByteArray>): ParsedBook {
        val container = files["META-INF/container.xml"] ?: throw EpubException("This file isn't an EPUB (no META-INF/container.xml).")
        val opfPath = xml(container).selectFirst("rootfile[full-path]")?.attr("full-path")
            ?: throw EpubException("This EPUB has no package file.")
        checkDrm(files)
        val opf = xml(files[opfPath] ?: throw EpubException("This EPUB's package file ($opfPath) is missing."))
        val base = opfPath.substringBeforeLast('/', "")

        val title = meta(opf, "title")?.takeIf { it.isNotBlank() } ?: "Untitled"
        val author = meta(opf, "creator")
        val language = meta(opf, "language")

        data class Item(val id: String, val path: String, val type: String, val props: String)
        val manifest = opf.select("manifest > item").mapNotNull { e ->
            val href = e.attr("href").takeIf { it.isNotEmpty() } ?: return@mapNotNull null
            Item(e.attr("id"), resolve(base, href), e.attr("media-type"), e.attr("properties"))
        }.associateBy { it.id }

        val spine = opf.select("spine > itemref")
            .filter { it.attr("linear") != "no" }
            .mapNotNull { manifest[it.attr("idref")] }
            .filter { it.type.contains("html") || it.path.endsWith(".xhtml") || it.path.endsWith(".html") || it.path.endsWith(".htm") }
        if (spine.isEmpty()) throw EpubException("This EPUB has no readable chapters.")

        // Table of contents: EPUB 3 nav document, else EPUB 2 NCX.
        val toc = run {
            val nav = manifest.values.firstOrNull { "nav" in it.props.split(' ') }
            val fromNav = nav?.let { n -> files[n.path]?.let { navToc(html(it), n.path.substringBeforeLast('/', "")) } }.orEmpty()
            fromNav.ifEmpty {
                val ncxId = opf.selectFirst("spine")?.attr("toc")
                val ncx = manifest[ncxId] ?: manifest.values.firstOrNull { it.type == "application/x-dtbncx+xml" }
                ncx?.let { n -> files[n.path]?.let { ncxToc(xml(it), n.path.substringBeforeLast('/', "")) } }.orEmpty()
            }
        }
        // Per file: anchors (or null for the file's start) with their titles, in TOC order.
        val tocByFile = LinkedHashMap<String, MutableList<Pair<String?, String>>>()
        for ((target, label) in toc) {
            val file = target.substringBefore('#')
            val frag = target.substringAfter('#', "").ifEmpty { null }
            tocByFile.getOrPut(file) { mutableListOf() }.add(frag to label)
        }

        val chapters = ArrayList<MutableChapter>()
        spine.forEach { item ->
            val doc = html(files[item.path] ?: return@forEach)
            val entries = tocByFile[item.path].orEmpty()
            val anchors = entries.mapNotNull { (f, label) -> f?.let { it to label } }.toMap()
            val startTitle = entries.firstOrNull { it.first == null }?.second
            when {
                startTitle != null -> chapters += MutableChapter(startTitle)
                toc.isEmpty() || chapters.isEmpty() -> chapters += MutableChapter(null)
                // Otherwise: a continuation of the previous chapter (or the first anchor below starts one).
            }
            extract(doc, anchors) { para, newChapterTitle ->
                if (newChapterTitle != null) chapters += MutableChapter(newChapterTitle)
                if (para != null) {
                    if (chapters.isEmpty()) chapters += MutableChapter(null)
                    chapters.last().paragraphs += para
                }
            }
        }
        val out = chapters.filter { ch -> ch.paragraphs.any { it.text.any(Char::isLetterOrDigit) } }.mapIndexed { i, ch ->
            val heading = ch.paragraphs.firstOrNull { it.heading }?.text?.replace('\n', ' ')?.takeIf { it.length <= 90 }
            ParsedChapter(ch.title?.takeIf { it.isNotBlank() } ?: heading ?: "Section ${i + 1}", ch.paragraphs)
        }
        if (out.isEmpty()) throw EpubException("No text found in this EPUB (it may contain only images).")
        return ParsedBook(title.trim(), author?.trim(), language?.trim(), cover(opf, manifest.values.map { Triple(it.id, it.path, it.type to it.props) }, files), out)
    }

    private class MutableChapter(val title: String?) {
        val paragraphs = ArrayList<Para>()
    }

    // ------------------------------------------------------------------ zip, xml, paths

    fun unzip(input: InputStream): Map<String, ByteArray> {
        val out = HashMap<String, ByteArray>()
        var total = 0L
        ZipInputStream(input.buffered()).use { zip ->
            while (true) {
                val e = zip.nextEntry ?: break
                if (e.isDirectory) continue
                val bytes = zip.readBytes()
                total += bytes.size
                if (total > MAX_TOTAL_BYTES) throw EpubException("This EPUB is too large to import.")
                out[e.name.trimStart('/')] = bytes
            }
        }
        if (out.isEmpty()) throw EpubException("This file isn't an EPUB (not a zip archive).")
        return out
    }

    private fun xml(bytes: ByteArray): Document = Jsoup.parse(String(bytes, Charsets.UTF_8), "", Parser.xmlParser())
    private fun html(bytes: ByteArray): Document = Jsoup.parse(String(bytes, Charsets.UTF_8))

    /** Resolves [href] against [base] (both inside the archive), decoding %20 etc. */
    internal fun resolve(base: String, href: String): String {
        val clean = href.substringBefore('#')
        val frag = href.substringAfter('#', "")
        val joined = if (base.isEmpty()) clean else "$base/$clean"
        val parts = ArrayList<String>()
        for (p in joined.split('/')) when (p) {
            "", "." -> {}
            ".." -> if (parts.isNotEmpty()) parts.removeAt(parts.lastIndex)
            else -> parts += p
        }
        val path = runCatching { java.net.URLDecoder.decode(parts.joinToString("/").replace("+", "%2B"), "UTF-8") }.getOrDefault(parts.joinToString("/"))
        return if (frag.isNotEmpty()) "$path#$frag" else path
    }

    /** dc:title, dc:creator, dc:language: prefixed or not. */
    private fun meta(opf: Document, name: String): String? {
        val md = opf.selectFirst("metadata") ?: opf
        return md.getAllElements().firstOrNull { e ->
            val t = e.tagName().lowercase()
            t == "dc:$name" || (t.endsWith(":$name") && t.startsWith("dc")) || t == name
        }?.text()?.takeIf { it.isNotBlank() }
    }

    private fun checkDrm(files: Map<String, ByteArray>) {
        if (files.containsKey("META-INF/rights.xml")) throw EpubException(DRM)
        val enc = files["META-INF/encryption.xml"] ?: return
        // Font obfuscation (IDPF / Adobe) is harmless; anything else encrypted means DRM.
        val algorithms = xml(enc).getAllElements().filter { it.tagName().endsWith("EncryptionMethod") }.map { it.attr("Algorithm") }
        val fontOnly = algorithms.all { it.contains("idpf.org/2008/embedding") || it.contains("ns.adobe.com/pdf/enc#RC") }
        if (algorithms.isNotEmpty() && !fontOnly) throw EpubException(DRM)
    }

    private const val DRM = "This EPUB is copy-protected (DRM), so it can't be opened outside the store's app. DRM-free books work."

    // ------------------------------------------------------------------ table of contents

    private fun navToc(doc: Document, base: String): List<Pair<String, String>> {
        val nav = doc.select("nav").firstOrNull { it.attr("epub:type").contains("toc") || it.attr("role") == "doc-toc" } ?: doc.selectFirst("nav") ?: return emptyList()
        return nav.select("a[href]").mapNotNull { a ->
            val label = a.text().trim().takeIf { it.isNotEmpty() } ?: return@mapNotNull null
            resolve(base, a.attr("href")) to label
        }
    }

    private fun ncxToc(doc: Document, base: String): List<Pair<String, String>> =
        doc.select("navPoint").mapNotNull { p ->
            val label = p.selectFirst("navLabel")?.text()?.trim()?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
            // The navPoint's own content (not a nested one's).
            val src = p.children().firstOrNull { it.tagName().equals("content", true) }?.attr("src") ?: return@mapNotNull null
            resolve(base, src) to label
        }

    // ------------------------------------------------------------------ text

    private val BLOCK = setOf(
        "p", "div", "h1", "h2", "h3", "h4", "h5", "h6", "li", "ul", "ol", "blockquote", "pre", "dt", "dd", "dl",
        "section", "article", "header", "footer", "figure", "figcaption", "table", "tr", "td", "th", "hr", "body", "main", "nav", "address",
    )
    /** Stands for a <br> while collecting text (markup newlines become spaces). */
    private const val BR = '\u2028'
    private val HEADING = setOf("h1", "h2", "h3", "h4", "h5", "h6")
    private val SKIP = setOf("script", "style", "head", "title", "svg", "math", "img", "image", "sup", "audio", "video", "object", "iframe", "rt", "rp")

    private fun skip(e: Element): Boolean {
        if (e.tagName().lowercase() in SKIP) return true
        val type = (e.attr("epub:type") + " " + e.attr("role")).lowercase()
        if (listOf("footnote", "endnote", "rearnote", "noteref", "pagebreak", "doc-pagebreak", "doc-noteref", "doc-endnote").any { it in type }) return true
        val cls = e.className().lowercase()
        return "pagenum" in cls || "pagebreak" in cls || "page-number" in cls
    }

    /**
     * Walks the body, emitting paragraphs; when it reaches an element whose id is one of [anchors], it first emits
     * the start of a new chapter with that title.
     */
    private fun extract(doc: Document, anchors: Map<String, String>, emit: (Para?, String?) -> Unit) {
        val body = doc.body() ?: return
        val buf = StringBuilder()
        var headingDepth = 0
        var bufHeading = false
        fun flush() {
            val text = tidy(buf.toString())
            if (text.isNotEmpty()) emit(Para(text, bufHeading), null)
            buf.setLength(0)
            bufHeading = false
        }
        body.filter(object : NodeFilter {
            override fun head(node: Node, depth: Int): NodeFilter.FilterResult {
                when (node) {
                    is TextNode -> {
                        if (buf.isEmpty() || buf.isBlank()) bufHeading = headingDepth > 0
                        // Line breaks in the markup are just whitespace; only <br> breaks a line (see [BR]).
                        buf.append(node.wholeText.replace('\n', ' ').replace('\r', ' '))
                    }
                    is Element -> {
                        if (skip(node)) return NodeFilter.FilterResult.SKIP_ENTIRELY
                        val tag = node.tagName().lowercase()
                        val id = node.id()
                        if (id.isNotEmpty() && id in anchors) {
                            flush()
                            emit(null, anchors[id])
                        }
                        if (tag == "br") buf.append(BR)
                        if (tag in BLOCK) flush()
                        if (tag in HEADING) headingDepth++
                    }
                }
                return NodeFilter.FilterResult.CONTINUE
            }

            override fun tail(node: Node, depth: Int): NodeFilter.FilterResult {
                if (node is Element) {
                    val tag = node.tagName().lowercase()
                    if (tag in BLOCK) flush()
                    if (tag in HEADING) headingDepth--
                }
                return NodeFilter.FilterResult.CONTINUE
            }
        })
        flush()
    }

    /**
     * Collapses whitespace from the markup, keeping explicit line breaks (verse). Between two Chinese/Japanese
     * characters a markup line break is dropped instead of becoming a space.
     */
    internal fun tidy(raw: String): String {
        val lines = raw.replace('\u00A0', ' ').split(BR).map { line ->
            val sb = StringBuilder()
            var pendingSpace = false
            for (ch in line) {
                if (ch.isWhitespace()) { pendingSpace = sb.isNotEmpty(); continue }
                if (pendingSpace && !(isCjk(sb.last()) && isCjk(ch))) sb.append(' ')
                pendingSpace = false
                sb.append(ch)
            }
            sb.toString()
        }
        return lines.filter { it.isNotBlank() }.joinToString("\n").trim()
    }

    private fun isCjk(ch: Char): Boolean {
        val b = Character.UnicodeBlock.of(ch)
        return b == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS || b == Character.UnicodeBlock.CJK_SYMBOLS_AND_PUNCTUATION ||
            b == Character.UnicodeBlock.HALFWIDTH_AND_FULLWIDTH_FORMS || b == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS_EXTENSION_A ||
            b == Character.UnicodeBlock.HIRAGANA || b == Character.UnicodeBlock.KATAKANA
    }

    // ------------------------------------------------------------------ cover

    private fun cover(opf: Document, items: List<Triple<String, String, Pair<String, String>>>, files: Map<String, ByteArray>): ByteArray? {
        val images = items.filter { it.third.first.startsWith("image/") }
        val byProp = images.firstOrNull { "cover-image" in it.third.second.split(' ') }
        val metaId = opf.select("meta[name=cover]").firstOrNull()?.attr("content")
        val byMeta = images.firstOrNull { it.first == metaId }
        val byName = images.firstOrNull { it.first.contains("cover", true) || it.second.substringAfterLast('/').contains("cover", true) }
        return (byProp ?: byMeta ?: byName)?.let { files[it.second] }
    }
}
