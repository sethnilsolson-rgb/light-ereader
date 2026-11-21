package com.setholson.ereader.model

enum class BookFormat(val badge: String, val extensions: Set<String>) {
    EPUB("EPUB", setOf("epub")),
    PDF("PDF", setOf("pdf")),
    TXT("TXT", setOf("txt", "md", "markdown")),
    GUIDE("GUIDE", emptySet());

    companion object {
        fun fromExtension(ext: String): BookFormat? {
            val lower = ext.lowercase()
            return entries.firstOrNull { lower in it.extensions }
        }
    }
}

enum class ReaderFontSize(val label: String, val displayLabel: String) {
    COMPACT("Small", "Aa-"),
    REGULAR("Normal", "Aa"),
    LARGE("Large", "Aa+");

    fun next(): ReaderFontSize = when (this) {
        COMPACT -> REGULAR
        REGULAR -> LARGE
        LARGE -> COMPACT
    }

    companion object {
        fun fromName(name: String?): ReaderFontSize =
            entries.firstOrNull { it.name.equals(name, ignoreCase = true) } ?: REGULAR
    }
}

data class LibraryBook(
    val id: String, // relative path inside books dir, or "builtin://welcome-guide"
    val fileName: String,
    val title: String,
    val author: String,
    val format: BookFormat,
    val fileSizeBytes: Long,
    val lastModifiedMs: Long,
    val currentPosition: Int = 0, // chapter index (EPUB/TXT) or 0-based page index (PDF)
    val totalPositions: Int = 0,
    val progressPercent: Int = 0,
    val lastReadAt: Long? = null,
) {
    val isBuiltIn: Boolean
        get() = format == BookFormat.GUIDE
}

data class TextBlock(
    val text: String,
    val isHeading: Boolean = false,
    val isQuote: Boolean = false,
)

data class BookChapter(
    val index: Int,
    val title: String,
    val blocks: List<TextBlock>,
)

data class ParsedBook(
    val title: String,
    val author: String,
    val chapters: List<BookChapter>,
)

data class BookMetadata(
    val title: String,
    val author: String,
    val totalPositions: Int = 0,
)

data class WordLookupResult(
    val query: String,
    val headword: String,
    val definition: String,
    val sourceLabel: String,
    val notFound: Boolean = false,
)
