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

enum class ReaderProgressMode(val label: String) {
    BOOK("Book %"),
    CHAPTER("Chapter %"),
    HIDDEN("Off");

    fun next(): ReaderProgressMode = when (this) {
        BOOK -> CHAPTER
        CHAPTER -> HIDDEN
        HIDDEN -> BOOK
    }

    companion object {
        fun fromName(name: String?): ReaderProgressMode =
            entries.firstOrNull { it.name.equals(name, ignoreCase = true) } ?: BOOK
    }
}

enum class DictionaryPack(
    val id: String,
    val displayName: String,
    val shortName: String,
    val description: String,
    val sizeHint: String,
    val isOnlineAutoCache: Boolean = false,
    val isCjk: Boolean = false,
) {
    MODERN_WORDSET(
        id = "modern_wordset",
        displayName = "Modern English (Wordset)",
        shortName = "Modern",
        description = "Contemporary definitions, parts of speech & examples (~100k+ words). Best for modern books.",
        sizeHint = "~12 MB",
    ),
    CONCISE_GCIDE(
        id = "concise_gcide",
        displayName = "Classic Literary English",
        shortName = "Literary",
        description = "Comprehensive everyday & literary English (GCIDE). Great for 19th–20th c. novels.",
        sizeHint = "~15 MB",
    ),
    WEBSTER_1828(
        id = "webster_1828",
        displayName = "Webster's 1828",
        shortName = "1828",
        description = "Historical 1828 definitions. Ideal for scripture & early American literature.",
        sizeHint = "~11 MB",
    ),
    ONLINE_MODERN(
        id = "online_modern",
        displayName = "Live Modern Dictionary",
        shortName = "Online",
        description = "Modern definitions + phonetics on Wi-Fi/cell. Auto-caches every word offline.",
        sizeHint = "0 MB",
        isOnlineAutoCache = true,
    ),
    SPANISH_ENG(
        id = "spa_eng",
        displayName = "Spanish → English",
        shortName = "ES→EN",
        description = "Offline FreeDict Spanish-to-English translations.",
        sizeHint = "~1.6 MB",
    ),
    FRENCH_ENG(
        id = "fra_eng",
        displayName = "French → English",
        shortName = "FR→EN",
        description = "Offline FreeDict French-to-English translations.",
        sizeHint = "~2 MB",
    ),
    CHINESE_ENG(
        id = "zh_eng",
        displayName = "Chinese → English",
        shortName = "ZH→EN",
        description = "Offline CC-CEDICT with Pinyin for Chinese books.",
        sizeHint = "~3.5 MB",
        isCjk = true,
    );

    companion object {
        fun fromId(id: String?): DictionaryPack? = entries.firstOrNull { it.id == id }
    }
}

data class FreeCatalogBook(
    val id: String,
    val title: String,
    val author: String,
    val category: String,
    val year: String,
    val description: String,
    val downloadUrl: String,
    val fileName: String,
)

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

data class DictionaryHit(
    val dictId: String,
    val shortLabel: String,
    val sourceLabel: String,
    val headword: String,
    val definition: String,
)

data class WordLookupResult(
    val query: String,
    val headword: String,
    val definition: String,
    val sourceLabel: String,
    val hits: List<DictionaryHit> = emptyList(),
    val notFound: Boolean = false,
)
