package com.setholson.ereader.data

import com.setholson.ereader.model.BookChapter
import com.setholson.ereader.model.BookMetadata
import com.setholson.ereader.model.ParsedBook
import com.setholson.ereader.model.TextBlock
import java.io.File

/**
 * Parses plain text (.txt) and Markdown (.md) books with smart automatic chapter detection
 * and automatic section splitting for long books so scrolling stays instantaneous.
 */
object TextBookParser {

    const val WELCOME_GUIDE_ID = "builtin://welcome-guide"
    private const val MAX_WORDS_PER_AUTO_SECTION = 2200

    private val CHAPTER_HEADING_REGEX = Regex(
        """^(?:#{1,3}\s+(.+)|((?:CHAPTER|BOOK|PART|SECTION|ACT|SCENE|PROLOGUE|EPILOGUE|INTRODUCTION|PREFACE)\b.{0,80}))$""",
        RegexOption.IGNORE_CASE,
    )

    fun peekMetadata(file: File): BookMetadata {
        val title = EpubParser.cleanFilenameTitle(file.nameWithoutExtension)
        return BookMetadata(title = title, author = "Plain Text", totalPositions = 1)
    }

    fun parse(file: File): Result<ParsedBook> = runCatching {
        val raw = file.readText(Charsets.UTF_8)
            .removePrefix("\uFEFF")
            .replace("\r\n", "\n")
            .replace('\r', '\n')
        val title = EpubParser.cleanFilenameTitle(file.nameWithoutExtension)
        val isMarkdown = file.extension.equals("md", ignoreCase = true) ||
            file.extension.equals("markdown", ignoreCase = true)
        parseText(title = title, author = if (isMarkdown) "Markdown" else "Plain Text", rawText = raw, isMarkdown = isMarkdown)
    }

    internal fun parseText(
        title: String,
        author: String,
        rawText: String,
        isMarkdown: Boolean = false,
    ): ParsedBook {
        val paragraphs = rawText
            .split(Regex("""\n{2,}"""))
            .map { it.trim() }
            .filter { it.isNotBlank() }

        if (paragraphs.isEmpty()) {
            return ParsedBook(
                title = title,
                author = author,
                chapters = listOf(
                    BookChapter(0, "Empty Document", listOf(TextBlock("This file contains no text.")))
                ),
            )
        }

        val chapters = mutableListOf<BookChapter>()
        var currentTitle = title
        var currentBlocks = mutableListOf<TextBlock>()
        var currentWordCount = 0

        fun flushChapter(fallbackName: String) {
            if (currentBlocks.isNotEmpty()) {
                chapters.add(
                    BookChapter(
                        index = chapters.size,
                        title = currentTitle.ifBlank { fallbackName },
                        blocks = currentBlocks,
                    )
                )
                currentBlocks = mutableListOf()
                currentWordCount = 0
            }
        }

        for (para in paragraphs) {
            val firstLine = para.lineSequence().firstOrNull()?.trim().orEmpty()
            val singleLine = !para.contains('\n')
            val headingMatch = if (singleLine && firstLine.length <= 90) CHAPTER_HEADING_REGEX.matchEntire(firstLine) else null

            if (headingMatch != null) {
                flushChapter("Section ${chapters.size + 1}")
                val headingText = (headingMatch.groupValues[1].ifBlank { headingMatch.groupValues[2] })
                    .trim()
                currentTitle = headingText
                currentBlocks.add(TextBlock(text = headingText, isHeading = true))
                continue
            }

            val isQuote = isMarkdown && para.startsWith(">")
            val cleaned = cleanMarkdownInline(para, isMarkdown)
            val words = cleaned.split(Regex("""\s+""")).size

            if (currentWordCount > 0 && currentWordCount + words > MAX_WORDS_PER_AUTO_SECTION) {
                val baseName = if (chapters.isEmpty() && currentTitle == title) "Part 1" else currentTitle
                currentTitle = baseName
                flushChapter("Part ${chapters.size + 1}")
                currentTitle = "Part ${chapters.size + 1}"
            }

            currentBlocks.add(TextBlock(text = cleaned, isQuote = isQuote))
            currentWordCount += words
        }

        flushChapter(if (chapters.isEmpty()) title else "Part ${chapters.size + 1}")

        return ParsedBook(
            title = title,
            author = author,
            chapters = chapters,
        )
    }

    private fun cleanMarkdownInline(text: String, isMarkdown: Boolean): String {
        if (!isMarkdown) return text.replace(Regex("""\s*\n\s*"""), " ")
        return text
            .lines()
            .joinToString("\n") { line ->
                line.removePrefix(">").trim()
                    .replace(Regex("""\*\*(.+?)\*\*"""), "$1")
                    .replace(Regex("""__(.+?)__"""), "$1")
                    .replace(Regex("""\*(.+?)\*"""), "$1")
                    .replace(Regex("""`([^`]+)`"""), "$1")
            }
            .trim()
    }

    fun welcomeGuideBook(): ParsedBook = ParsedBook(
        title = "E-Reader Quick Guide",
        author = "Light Phone E-Reader",
        chapters = listOf(
            BookChapter(
                index = 0,
                title = "Adding Your Books",
                blocks = listOf(
                    TextBlock("Welcome to E-Reader", isHeading = true),
                    TextBlock("Read DRM-free EPUB books, PDF documents, and TXT or Markdown files cleanly offline on your Light Phone III."),
                    TextBlock("How to Upload Books from Your Computer", isHeading = true),
                    TextBlock("1. Connect your Light Phone III and computer to the same Wi-Fi network."),
                    TextBlock("2. On your Light Phone, open Settings → Tool Manager."),
                    TextBlock("3. Open the Tool Manager web address shown on your phone in your computer's browser."),
                    TextBlock("4. Select E-Reader → Books (EPUB, PDF, TXT) and drag-and-drop your .epub, .pdf, .txt, or .md files."),
                    TextBlock("5. Return to E-Reader and your books will appear automatically in your Library (or tap Refresh Library)."),
                    TextBlock("Tip: Free DRM-free classics can be downloaded as EPUB from Standard Ebooks (standardebooks.org) or Project Gutenberg (gutenberg.org) on your computer and dropped straight into Tool Manager.", isQuote = true),
                ),
            ),
            BookChapter(
                index = 1,
                title = "Reading Controls & Gestures",
                blocks = listOf(
                    TextBlock("Reading Controls", isHeading = true),
                    TextBlock("• Swipe left to move to the next chapter or PDF page."),
                    TextBlock("• Swipe right to move to the previous chapter or PDF page (swipe from the left edge to return to your Library)."),
                    TextBlock("• Tap Aa in the top sub-header to cycle Small, Normal, and Large reading text sizes."),
                    TextBlock("• Tap Chapters (≡) to jump to any chapter in your book's Table of Contents."),
                    TextBlock("• Tap the Bookmark icon in the top-right corner to save any chapter or PDF page to Bookmarks."),
                    TextBlock("PDF Viewing Features", isHeading = true),
                    TextBlock("• Tap Zoom (1x / 1.5x / 2x / 2.5x) to zoom into small PDF text and drag to pan around the page."),
                    TextBlock("• Tap Invert to switch a white-background PDF to true OLED Dark Mode."),
                    TextBlock("• Tap the page number (e.g. Page 1 / 40) to jump directly to any page."),
                ),
            ),
            BookChapter(
                index = 2,
                title = "Offline Dictionary & Themes",
                blocks = listOf(
                    TextBlock("Light & Dark Theme", isHeading = true),
                    TextBlock("Tap the Settings gear icon in the top-right corner of the Library screen to switch between Dark Mode and Light Mode, or toggle automatic Dark Mode inversion for PDFs."),
                    TextBlock("Press-and-Hold Offline Dictionary", isHeading = true),
                    TextBlock("Inside Settings, you can optionally download Noah Webster's 1828 Dictionary onto your phone."),
                    TextBlock("Once downloaded, press and hold any word while reading an EPUB or TXT book to pop up its definition right on the page—100% offline."),
                ),
            ),
        ),
    )
}
