package app.diacritica.mybooks

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class EpubParserTest {
    private fun zip(files: Map<String, String>, binary: Map<String, ByteArray> = emptyMap()): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { z ->
            z.putNextEntry(ZipEntry("mimetype")); z.write("application/epub+zip".toByteArray()); z.closeEntry()
            (files.mapValues { it.value.toByteArray() } + binary).forEach { (name, bytes) ->
                z.putNextEntry(ZipEntry(name)); z.write(bytes); z.closeEntry()
            }
        }
        return out.toByteArray()
    }

    private val container = """<?xml version="1.0"?>
        <container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container">
          <rootfiles><rootfile full-path="OEBPS/content.opf" media-type="application/oebps-package+xml"/></rootfiles>
        </container>"""

    private fun xhtml(body: String) = """<?xml version="1.0" encoding="utf-8"?>
        <html xmlns="http://www.w3.org/1999/xhtml" xmlns:epub="http://www.idpf.org/2007/ops"><head><title>x</title></head>
        <body>$body</body></html>"""

    /** EPUB 3: nav TOC, a cover page without text, two chapters in one file split by an anchor, a third file. */
    private fun epub3(): ByteArray = zip(mapOf(
        "META-INF/container.xml" to container,
        "OEBPS/content.opf" to """<?xml version="1.0"?>
            <package xmlns="http://www.idpf.org/2007/opf" version="3.0">
              <metadata xmlns:dc="http://purl.org/dc/elements/1.1/">
                <dc:title>Le Petit Livre</dc:title><dc:creator>Jeanne Exemple</dc:creator><dc:language>fr-FR</dc:language>
              </metadata>
              <manifest>
                <item id="nav" href="nav.xhtml" media-type="application/xhtml+xml" properties="nav"/>
                <item id="cov" href="images/cover.jpg" media-type="image/jpeg" properties="cover-image"/>
                <item id="c0" href="text/cover.xhtml" media-type="application/xhtml+xml"/>
                <item id="c1" href="text/part%201.xhtml" media-type="application/xhtml+xml"/>
                <item id="c2" href="text/ch3.xhtml" media-type="application/xhtml+xml"/>
              </manifest>
              <spine><itemref idref="c0"/><itemref idref="c1"/><itemref idref="c2"/></spine>
            </package>""",
        "OEBPS/nav.xhtml" to xhtml("""<nav epub:type="toc"><ol>
              <li><a href="text/part%201.xhtml">Chapitre premier</a></li>
              <li><a href="text/part%201.xhtml#ch2">Chapitre deux</a></li>
              <li><a href="text/ch3.xhtml">Chapitre trois</a></li></ol></nav>"""),
        "OEBPS/text/cover.xhtml" to xhtml("""<div><img src="../images/cover.jpg"/></div>"""),
        "OEBPS/text/part 1.xhtml" to xhtml("""
              <h1>Chapitre premier</h1>
              <p>Il était une fois<sup><a epub:type="noteref" href="#n1">1</a></sup>   un   pêcheur.</p>
              <p><span class="pagenum">[Pg 2]</span>Il <i>vivait</i> près de la mer.</p>
              <h2 id="ch2">Chapitre deux</h2>
              <p class="verse">Premier vers<br/>Second vers</p>
              <aside epub:type="footnote" id="n1"><p>Une note.</p></aside>"""),
        "OEBPS/text/ch3.xhtml" to xhtml("""<section><h2>Chapitre trois</h2><div>Un texte sans paragraphe.</div><p>&nbsp;</p></section>"""),
    ), binary = mapOf("OEBPS/images/cover.jpg" to byteArrayOf(1, 2, 3)))

    @Test fun readsMetadataAndCover() {
        val b = EpubParser.parse(ByteArrayInputStream(epub3()))
        assertEquals("Le Petit Livre", b.title)
        assertEquals("Jeanne Exemple", b.author)
        assertEquals("fr-FR", b.language)
        assertNotNull(b.cover)
        assertEquals(3, b.cover!!.size)
    }

    @Test fun chaptersFollowTheTocIncludingAnchors() {
        val b = EpubParser.parse(ByteArrayInputStream(epub3()))
        assertEquals(listOf("Chapitre premier", "Chapitre deux", "Chapitre trois"), b.chapters.map { it.title })
    }

    @Test fun keepsTextDropsNotesAndPageNumbers() {
        val ch1 = EpubParser.parse(ByteArrayInputStream(epub3())).chapters[0].paragraphs
        assertEquals(Para("Chapitre premier", heading = true), ch1[0])
        assertEquals("Il était une fois un pêcheur.", ch1[1].text)
        assertEquals("Il vivait près de la mer.", ch1[2].text)
        assertFalse(ch1.any { "note" in it.text || "Pg" in it.text })
    }

    @Test fun keepsVerseLineBreaks() {
        val ch2 = EpubParser.parse(ByteArrayInputStream(epub3())).chapters[1].paragraphs
        assertEquals("Premier vers\nSecond vers", ch2.last().text)
    }

    @Test fun textDirectlyInDivsIsAParagraph() {
        val ch3 = EpubParser.parse(ByteArrayInputStream(epub3())).chapters[2].paragraphs
        assertEquals(listOf("Chapitre trois", "Un texte sans paragraphe."), ch3.map { it.text })
    }

    /** EPUB 2: NCX table of contents, no prefix quirks, Chinese text broken across markup lines. */
    @Test fun readsEpub2WithNcxAndChinese() {
        val bytes = zip(mapOf(
            "META-INF/container.xml" to container,
            "OEBPS/content.opf" to """<?xml version="1.0"?>
                <package xmlns="http://www.idpf.org/2007/opf" version="2.0">
                  <metadata xmlns:dc="http://purl.org/dc/elements/1.1/"><dc:title>小书</dc:title><dc:language>zh</dc:language>
                    <meta name="cover" content="img"/></metadata>
                  <manifest>
                    <item id="ncx" href="toc.ncx" media-type="application/x-dtbncx+xml"/>
                    <item id="img" href="c.png" media-type="image/png"/>
                    <item id="a" href="a.html" media-type="application/xhtml+xml"/>
                  </manifest>
                  <spine toc="ncx"><itemref idref="a"/></spine>
                </package>""",
            "OEBPS/toc.ncx" to """<?xml version="1.0"?><ncx xmlns="http://www.daisy.org/z3986/2005/ncx/"><navMap>
                <navPoint id="p1"><navLabel><text>第一章</text></navLabel><content src="a.html"/></navPoint></navMap></ncx>""",
            "OEBPS/a.html" to xhtml("<p>我每天早上\n  七点起床。</p><p>你呢？</p>"),
        ), binary = mapOf("OEBPS/c.png" to byteArrayOf(9)))
        val b = EpubParser.parse(ByteArrayInputStream(bytes))
        assertEquals("小书", b.title)
        assertEquals("zh", b.language)
        assertEquals("第一章", b.chapters.single().title)
        assertEquals(listOf("我每天早上七点起床。", "你呢？"), b.chapters.single().paragraphs.map { it.text })
        assertTrue(b.cover!!.contentEquals(byteArrayOf(9)))
    }

    @Test fun withoutTocEachFileIsAChapterNamedByItsHeading() {
        val bytes = zip(mapOf(
            "META-INF/container.xml" to container,
            "OEBPS/content.opf" to """<package version="2.0"><metadata><dc:title>T</dc:title></metadata>
                <manifest><item id="a" href="a.xhtml" media-type="application/xhtml+xml"/><item id="b" href="b.xhtml" media-type="application/xhtml+xml"/></manifest>
                <spine><itemref idref="a"/><itemref idref="b"/></spine></package>""",
            "OEBPS/a.xhtml" to xhtml("<h1>One</h1><p>First.</p>"),
            "OEBPS/b.xhtml" to xhtml("<p>No heading here.</p>"),
        ))
        assertEquals(listOf("One", "Section 2"), EpubParser.parse(ByteArrayInputStream(bytes)).chapters.map { it.title })
    }

    @Test fun refusesDrm() {
        val bytes = zip(mapOf(
            "META-INF/container.xml" to container,
            "META-INF/encryption.xml" to """<encryption><EncryptedData><EncryptionMethod Algorithm="http://www.w3.org/2001/04/xmlenc#aes128-cbc"/></EncryptedData></encryption>""",
            "OEBPS/content.opf" to "<package/>",
        ))
        try { EpubParser.parse(ByteArrayInputStream(bytes)); fail("expected DRM error") } catch (e: EpubException) {
            assertTrue(e.message!!.contains("DRM"))
        }
    }

    @Test fun refusesNonEpub() {
        try { EpubParser.parse(ByteArrayInputStream("hello".toByteArray())); fail() } catch (e: EpubException) {}
    }

    @Test fun resolvesRelativeAndEncodedPaths() {
        assertEquals("OEBPS/text/part 1.xhtml", EpubParser.resolve("OEBPS", "text/part%201.xhtml"))
        assertEquals("OEBPS/images/a.jpg", EpubParser.resolve("OEBPS/text", "../images/a.jpg"))
        assertEquals("a.xhtml#x", EpubParser.resolve("", "a.xhtml#x"))
    }
}
