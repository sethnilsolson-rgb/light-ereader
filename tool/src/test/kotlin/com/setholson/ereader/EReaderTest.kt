package com.setholson.ereader

import com.setholson.ereader.data.EpubParser
import com.setholson.ereader.data.OfflineDictionaryService
import com.setholson.ereader.data.TextBookParser
import com.setholson.ereader.model.BookFormat
import com.setholson.ereader.model.ReaderFontSize
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class EReaderTest {

    @Test
    fun epub2ZipParsingWithNcxTocAndHtmlEntities() {
        val tempFile = File.createTempFile("sample_epub2_", ".epub")
        try {
            writeZip(
                tempFile,
                mapOf(
                    "META-INF/container.xml" to """
                        <?xml version="1.0"?>
                        <container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container">
                          <rootfiles>
                            <rootfile full-path="OEBPS/content.opf" media-type="application/oebps-package+xml"/>
                          </rootfiles>
                        </container>
                    """.trimIndent(),
                    "OEBPS/content.opf" to """
                        <?xml version="1.0" encoding="utf-8"?>
                        <package xmlns="http://www.idpf.org/2007/opf" version="2.0">
                          <metadata xmlns:dc="http://purl.org/dc/elements/1.1/">
                            <dc:title>Meditations &amp; Notes</dc:title>
                            <dc:creator>Marcus Aurelius</dc:creator>
                          </metadata>
                          <manifest>
                            <item id="ncx" href="toc.ncx" media-type="application/x-dtbncx+xml"/>
                            <item id="ch1" href="Text/ch1.xhtml" media-type="application/xhtml+xml"/>
                            <item id="ch2" href="Text/ch2.xhtml" media-type="application/xhtml+xml"/>
                          </manifest>
                          <spine toc="ncx">
                            <itemref idref="ch1"/>
                            <itemref idref="ch2"/>
                          </spine>
                        </package>
                    """.trimIndent(),
                    "OEBPS/toc.ncx" to """
                        <?xml version="1.0" encoding="UTF-8"?>
                        <ncx xmlns="http://www.daisy.org/z3986/2005/ncx/">
                          <navMap>
                            <navPoint id="p1"><navLabel><text>Book One</text></navLabel><content src="Text/ch1.xhtml"/></navPoint>
                            <navPoint id="p2"><navLabel><text>Book Two</text></navLabel><content src="Text/ch2.xhtml#start"/></navPoint>
                          </navMap>
                        </ncx>
                    """.trimIndent(),
                    "OEBPS/Text/ch1.xhtml" to """
                        <html><head><style>body{color:black}</style></head><body>
                          <h1>Book I</h1>
                          <p>From my grandfather Verus&mdash;I learned &ldquo;good morals.&rdquo;</p>
                          <blockquote>Waste no more time arguing what a good man should be. Be one.</blockquote>
                        </body></html>
                    """.trimIndent(),
                    "OEBPS/Text/ch2.xhtml" to """
                        <html><body>
                          <h2>Book II</h2>
                          <p>Begin the morning by saying to thyself&#8217;s mind: stay calm.</p>
                        </body></html>
                    """.trimIndent(),
                ),
            )

            val meta = EpubParser.peekMetadata(tempFile)
            assertEquals("Meditations & Notes", meta.title)
            assertEquals("Marcus Aurelius", meta.author)
            assertEquals(2, meta.totalPositions)

            val parsed = EpubParser.parse(tempFile).getOrThrow()
            assertEquals("Meditations & Notes", parsed.title)
            assertEquals("Marcus Aurelius", parsed.author)
            assertEquals(2, parsed.chapters.size)
            assertEquals("Book One", parsed.chapters[0].title)
            assertEquals("Book Two", parsed.chapters[1].title)

            val ch1Blocks = parsed.chapters[0].blocks
            assertTrue(ch1Blocks[0].isHeading)
            assertEquals("Book I", ch1Blocks[0].text)
            assertEquals("From my grandfather Verus—I learned “good morals.”", ch1Blocks[1].text)
            assertTrue(ch1Blocks[2].isQuote)
            assertTrue(parsed.chapters[1].blocks[1].text.contains("thyself’s"))
        } finally {
            tempFile.delete()
        }
    }

    @Test
    fun epub3ZipParsingWithNavDocument() {
        val tempFile = File.createTempFile("sample_epub3_", ".epub")
        try {
            writeZip(
                tempFile,
                mapOf(
                    "META-INF/container.xml" to """
                        <container><rootfiles><rootfile full-path="content.opf"/></rootfiles></container>
                    """.trimIndent(),
                    "content.opf" to """
                        <package version="3.0">
                          <metadata><dc:title>Pride and Prejudice</dc:title><dc:creator>Jane Austen</dc:creator></metadata>
                          <manifest>
                            <item id="nav" href="nav.xhtml" properties="nav" media-type="application/xhtml+xml"/>
                            <item id="c1" href="ch01.xhtml" media-type="application/xhtml+xml"/>
                          </manifest>
                          <spine><itemref idref="c1"/></spine>
                        </package>
                    """.trimIndent(),
                    "nav.xhtml" to """
                        <html><body><nav epub:type="toc"><ol><li><a href="ch01.xhtml">Chapter 1: Netherfield</a></li></ol></nav></body></html>
                    """.trimIndent(),
                    "ch01.xhtml" to """
                        <html><body><p>It is a truth universally acknowledged.</p></body></html>
                    """.trimIndent(),
                ),
            )

            val parsed = EpubParser.parse(tempFile).getOrThrow()
            assertEquals("Pride and Prejudice", parsed.title)
            assertEquals(1, parsed.chapters.size)
            assertEquals("Chapter 1: Netherfield", parsed.chapters.first().title)
            assertEquals("It is a truth universally acknowledged.", parsed.chapters.first().blocks.first().text)
        } finally {
            tempFile.delete()
        }
    }

    @Test
    fun textAndMarkdownChapterSplitting() {
        val markdown = """
            # Prologue

            > A quiet reflection to begin.

            It was a **clear** morning.

            ## Chapter 1

            The journey started at dawn.
        """.trimIndent()

        val parsedMd = TextBookParser.parseText("My Notes", "Markdown", markdown, isMarkdown = true)
        assertEquals(2, parsedMd.chapters.size)
        assertEquals("Prologue", parsedMd.chapters[0].title)
        assertTrue(parsedMd.chapters[0].blocks.any { it.isQuote && it.text.contains("quiet reflection") })
        assertTrue(parsedMd.chapters[0].blocks.any { it.text == "It was a clear morning." })
        assertEquals("Chapter 1", parsedMd.chapters[1].title)

        // Plain text with classic CHAPTER headings
        val plain = """
            CHAPTER I

            First paragraph of chapter one.

            CHAPTER II

            First paragraph of chapter two.
        """.trimIndent()
        val parsedPlain = TextBookParser.parseText("Classic", "Plain Text", plain, isMarkdown = false)
        assertEquals(2, parsedPlain.chapters.size)
        assertEquals("CHAPTER I", parsedPlain.chapters[0].title)
        assertEquals("CHAPTER II", parsedPlain.chapters[1].title)

        // Built-in Welcome Guide is complete
        val guide = TextBookParser.welcomeGuideBook()
        assertEquals(3, guide.chapters.size)
        assertTrue(guide.chapters.all { it.blocks.isNotEmpty() })
    }

    @Test
    fun dictionaryCsvParsingAndEnglishStemming() {
        val row = "\"PERSEVERANCE\",\"Persistence in any thing undertaken; continued pursuit or prosecution of any business.\""
        val entry = OfflineDictionaryService.parseCsvRow(row)
        assertNotNull(entry)
        assertEquals("perseverance", entry.headword)
        assertTrue(entry.definition.contains("Persistence"))

        assertEquals("journey", OfflineDictionaryService.extractWordAt("The journey started.", 6))

        val candidates = OfflineDictionaryService.buildEnglishCandidates("running")
        assertTrue(candidates.contains("run"), "Expected stem 'run' in $candidates")

        val archaic = OfflineDictionaryService.buildEnglishCandidates("speaketh")
        assertTrue(archaic.contains("speak"), "Expected stem 'speak' in $archaic")
    }

    @Test
    fun formatDetectionAndFontCycling() {
        assertEquals(BookFormat.EPUB, BookFormat.fromExtension("epub"))
        assertEquals(BookFormat.PDF, BookFormat.fromExtension("PDF"))
        assertEquals(BookFormat.TXT, BookFormat.fromExtension("md"))
        assertEquals(null, BookFormat.fromExtension("docx"))

        assertEquals(ReaderFontSize.LARGE, ReaderFontSize.REGULAR.next())
        assertEquals(ReaderFontSize.COMPACT, ReaderFontSize.LARGE.next())
        assertEquals(ReaderFontSize.REGULAR, ReaderFontSize.COMPACT.next())
        assertFalse(BookFormat.EPUB.extensions.isEmpty())
    }

    private fun writeZip(target: File, entries: Map<String, String>) {
        ZipOutputStream(target.outputStream()).use { zos ->
            entries.forEach { (name, content) ->
                zos.putNextEntry(ZipEntry(name))
                zos.write(content.toByteArray(Charsets.UTF_8))
                zos.closeEntry()
            }
        }
    }
}
