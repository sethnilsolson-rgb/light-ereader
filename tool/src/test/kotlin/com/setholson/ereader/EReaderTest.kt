package com.setholson.ereader

import com.setholson.ereader.data.EpubParser
import com.setholson.ereader.data.FreeBooksCatalog
import com.setholson.ereader.data.OfflineDictionaryService
import com.setholson.ereader.data.TextBookParser
import com.setholson.ereader.data.WifiBookServer
import com.setholson.ereader.db.BookNoteEntity
import com.setholson.ereader.model.BookFormat
import com.setholson.ereader.model.DictionaryPack
import com.setholson.ereader.model.ReaderFontSize
import com.setholson.ereader.model.ReaderPageMode
import com.setholson.ereader.model.WordAnchor
import com.setholson.ereader.model.WordNoteUtils
import com.setholson.ereader.ui.EReaderPage
import com.setholson.ereader.ui.EReaderPageSlice
import com.setholson.ereader.ui.EReaderPaginator
import java.io.File
import java.net.HttpURLConnection
import java.net.URI
import java.nio.file.Files
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

        val guide = TextBookParser.welcomeGuideBook()
        assertEquals(3, guide.chapters.size)
        assertTrue(guide.chapters.all { it.blocks.isNotEmpty() })
    }

    @Test
    fun multiDictionaryParsingAndStemming() {
        // 1. Wordset Modern English JSON
        val wordsetSample = """
            {
              "serendipity": {
                "word": "serendipity",
                "meanings": [
                  {
                    "def": "good luck in making unexpected and fortunate discoveries",
                    "example": "finding that bookstore was pure serendipity",
                    "speech_part": "noun"
                  }
                ]
              }
            }
        """.trimIndent()
        val wsEntries = OfflineDictionaryService.parseWordsetJson(wordsetSample)
        assertEquals(1, wsEntries.size)
        assertEquals(DictionaryPack.MODERN_WORDSET.id, wsEntries[0].dictId)
        assertEquals("serendipity", wsEntries[0].headword)
        assertTrue(wsEntries[0].definition.contains("(noun)"))
        assertTrue(wsEntries[0].definition.contains("pure serendipity"))

        // 2. GCIDE Literary English CSV
        val gcideRow = "Resilient,a.,\"Leaping or springing back; rebounding.\""
        val gcideParsed = OfflineDictionaryService.parseGcideCsvRow(gcideRow)
        assertNotNull(gcideParsed)
        assertEquals("resilient", gcideParsed.first)
        assertEquals("(a.) Leaping or springing back; rebounding.", gcideParsed.second)

        // 3. Webster 1828 CSV
        val w1828Row = "\"PERSEVERANCE\",\"Persistence in any thing undertaken; continued pursuit or prosecution of any business.\""
        val w1828Entry = OfflineDictionaryService.parseCsvRow(w1828Row)
        assertNotNull(w1828Entry)
        assertEquals(DictionaryPack.WEBSTER_1828.id, w1828Entry.dictId)
        assertEquals("perseverance", w1828Entry.headword)

        // 4. Free Dictionary API JSON
        val apiJson = """
            [{"word":"ephemeral","phonetic":"/ɪˈfɛm.ə.rəl/","meanings":[{"partOfSpeech":"adjective","definitions":[{"definition":"Lasting for a very short time.","example":"fashions are ephemeral"}]}]}]
        """.trimIndent()
        val apiParsed = OfflineDictionaryService.parseFreeDictionaryApiJson(apiJson)
        assertNotNull(apiParsed)
        assertEquals("ephemeral", apiParsed.first)
        assertTrue(apiParsed.second.contains("(adjective) Lasting for a very short time."))

        // 5. Extraction & stemming
        assertEquals("journey", OfflineDictionaryService.extractWordAt("The journey started.", 6))
        assertTrue(OfflineDictionaryService.buildEnglishCandidates("running").contains("run"))
        assertTrue(OfflineDictionaryService.buildEnglishCandidates("speaketh").contains("speak"))
        assertTrue(OfflineDictionaryService.buildEnglishCandidates("quietly").contains("quiet"))
    }

    @Test
    fun freeCatalogAndFilenameSanitization() {
        assertTrue(FreeBooksCatalog.BOOKS.size >= 15, "Expected at least 15 curated classics")
        assertEquals(
            FreeBooksCatalog.BOOKS.size,
            FreeBooksCatalog.BOOKS.map { it.id }.toSet().size,
            "All catalog IDs must be unique",
        )
        assertEquals(
            FreeBooksCatalog.BOOKS.size,
            FreeBooksCatalog.BOOKS.map { it.fileName }.toSet().size,
            "All catalog filenames must be unique",
        )
        assertTrue(FreeBooksCatalog.BOOKS.all { it.downloadUrl.startsWith("https://standardebooks.org/") })

        assertEquals("https://example.com/a.epub", FreeBooksCatalog.normalizeUrl("example.com/a.epub"))
        assertEquals("my_book.epub", FreeBooksCatalog.sanitizeBookFileName("dir/my_book.epub?dl=1"))
        assertEquals("document.pdf", FreeBooksCatalog.sanitizeBookFileName("document", "application/pdf"))
    }

    @Test
    fun wifiBookServerUploadListAndDeleteRoundTrip() {
        val tempDir = Files.createTempDirectory("wifi_server_test_").toFile()
        val events = mutableListOf<String>()
        val server = WifiBookServer(tempDir) { msg -> events.add(msg) }
        try {
            val startResult = server.start()
            // Even in environments without a LAN IP, the ServerSocket should bind locally
            val port = server.boundPort
            assertTrue(port > 0, "Expected WifiBookServer to bind a port, result=$startResult")

            // 1. GET / serves HTML upload page
            val htmlConn = (URI("http://127.0.0.1:$port/").toURL().openConnection() as HttpURLConnection)
            assertEquals(200, htmlConn.responseCode)
            val html = htmlConn.inputStream.bufferedReader().readText()
            assertTrue(html.contains("Light Phone E-Reader"))
            htmlConn.disconnect()

            // 2. POST /api/upload streams book bytes to disk
            val bookContent = "CHAPTER 1\n\nHello from Wi-Fi Transfer!"
            val upConn = (URI("http://127.0.0.1:$port/api/upload?name=test_novel.txt").toURL().openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                doOutput = true
                setFixedLengthStreamingMode(bookContent.toByteArray().size)
            }
            upConn.outputStream.use { it.write(bookContent.toByteArray()) }
            assertEquals(200, upConn.responseCode)
            val upBody = upConn.inputStream.bufferedReader().readText()
            assertTrue(upBody.contains("\"ok\":true"))
            upConn.disconnect()

            val savedFile = File(tempDir, "test_novel.txt")
            assertTrue(savedFile.isFile)
            assertEquals(bookContent, savedFile.readText())
            assertTrue(events.any { it.contains("test_novel.txt") })

            // 3. GET /api/books lists the uploaded book
            val listConn = (URI("http://127.0.0.1:$port/api/books").toURL().openConnection() as HttpURLConnection)
            assertEquals(200, listConn.responseCode)
            val listJson = listConn.inputStream.bufferedReader().readText()
            assertTrue(listJson.contains("test_novel.txt"))
            listConn.disconnect()

            // 4. POST /api/delete removes the book
            val delConn = (URI("http://127.0.0.1:$port/api/delete?name=test_novel.txt").toURL().openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
            }
            assertEquals(200, delConn.responseCode)
            delConn.disconnect()
            assertFalse(savedFile.exists())
        } finally {
            server.stop()
            tempDir.deleteRecursively()
        }
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

    @Test
    fun readerPageModeAndPaginatorPermilleMapping() {
        assertEquals(ReaderPageMode.PAGED, ReaderPageMode.SCROLL.toggle())
        assertEquals(ReaderPageMode.SCROLL, ReaderPageMode.PAGED.toggle())
        assertEquals(ReaderPageMode.PAGED, ReaderPageMode.fromName("PAGED"))
        assertEquals(ReaderPageMode.SCROLL, ReaderPageMode.fromName("invalid"))

        val pages = listOf(
            EReaderPage(
                pageIndex = 0,
                slices = listOf(
                    EReaderPageSlice(0, "Chapter 1", isHeading = true, isQuote = false, isContinuation = false),
                    EReaderPageSlice(1, "Opening line of the chapter.", isHeading = false, isQuote = false, isContinuation = false),
                ),
                permilleStart = 0,
            ),
            EReaderPage(
                pageIndex = 1,
                slices = listOf(
                    EReaderPageSlice(2, "Middle paragraph of the PDF chapter.", isHeading = false, isQuote = false, isContinuation = false),
                ),
                permilleStart = 350,
            ),
            EReaderPage(
                pageIndex = 2,
                slices = listOf(
                    EReaderPageSlice(3, "Closing paragraph.", isHeading = false, isQuote = false, isContinuation = false),
                ),
                permilleStart = 720,
            ),
        )

        assertEquals("Opening line of the chapter.", pages[0].previewSnippet)
        assertEquals(0, EReaderPaginator.pageIndexForPermille(pages, 0))
        assertEquals(0, EReaderPaginator.pageIndexForPermille(pages, 349))
        assertEquals(1, EReaderPaginator.pageIndexForPermille(pages, 350))
        assertEquals(1, EReaderPaginator.pageIndexForPermille(pages, 719))
        assertEquals(2, EReaderPaginator.pageIndexForPermille(pages, 720))
        assertEquals(2, EReaderPaginator.pageIndexForPermille(pages, 1000))
        assertEquals(0, EReaderPaginator.pageIndexForPermille(emptyList(), 420))
    }

    @Test
    fun wordNoteExtractToggleExcerptAndHierarchyOrdering() {
        val text = "Call me Ishmael. Some years ago—never mind how long precisely."
        val callAnchor = WordNoteUtils.extractWordAnchor(text, 0, 1)!!
        val meAnchor = WordNoteUtils.extractWordAnchor(text, 0, 5)!!
        val ishmaelAnchor = WordNoteUtils.extractWordAnchor(text, 0, 10)!!
        assertEquals("Ishmael", WordNoteUtils.extractWordText(text, ishmaelAnchor))

        var selected = listOf(callAnchor)
        selected = WordNoteUtils.toggleWordAnchor(selected, ishmaelAnchor)
        selected = WordNoteUtils.toggleWordAnchor(selected, meAnchor)
        assertEquals("Call me Ishmael", WordNoteUtils.buildQuotedExcerpt(selected, { if (it == 0) text else null }))

        // Tapping an underlined word removes its underline
        selected = WordNoteUtils.toggleWordAnchor(selected, meAnchor)
        assertEquals("Call \u2026 Ishmael", WordNoteUtils.buildQuotedExcerpt(selected, { if (it == 0) text else null }))

        // Encode/decode round-trip
        assertEquals(selected, WordNoteUtils.decodeAnchors(WordNoteUtils.encodeAnchors(selected)))

        // Book -> Chapter -> Notes hierarchy ordered newest first at every level
        val notes = listOf(
            BookNoteEntity(
                id = 10,
                bookId = "moby.epub",
                bookTitle = "Moby-Dick",
                format = BookFormat.EPUB.name,
                positionIndex = 0,
                chapterTitle = "Chapter 1: Loomings",
                anchorsEncoded = "0:0:4",
                quotedText = "Call",
                noteText = "Opening line",
                updatedAt = 1000L,
            ),
            BookNoteEntity(
                id = 11,
                bookId = "walden.pdf",
                bookTitle = "Walden",
                format = BookFormat.PDF.name,
                positionIndex = 4,
                chapterTitle = "Page 5",
                anchorsEncoded = "2:0:6",
                quotedText = "simple",
                noteText = "Simplicity",
                updatedAt = 2500L,
            ),
            BookNoteEntity(
                id = 12,
                bookId = "moby.epub",
                bookTitle = "Moby-Dick",
                format = BookFormat.EPUB.name,
                positionIndex = 0,
                chapterTitle = "Chapter 1: Loomings",
                anchorsEncoded = "0:8:15",
                quotedText = "Ishmael",
                noteText = "Narrator name",
                updatedAt = 4000L,
            ),
        )

        val grouped = WordNoteUtils.groupNotesByBookAndChapter(notes)
        assertEquals(2, grouped.size)
        assertEquals("Moby-Dick", grouped[0].bookTitle)
        assertEquals(2, grouped[0].totalNoteCount)
        assertEquals(listOf(12L, 10L), grouped[0].chapters[0].notes.map { it.id })
        assertEquals("Walden", grouped[1].bookTitle)

        // Slice offset underlining
        val sliceAnnotated = WordNoteUtils.buildUnderlinedText(
            text = "Ishmael. Some years",
            paragraphIndex = 0,
            sliceOffsetInParagraph = 8,
            savedAnchors = listOf(WordAnchor(0, 8, 15)),
            selectedAnchors = emptyList(),
        )
        assertEquals(1, sliceAnnotated.spanStyles.size)
        assertEquals(0, sliceAnnotated.spanStyles[0].start)
        assertEquals(7, sliceAnnotated.spanStyles[0].end)
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
