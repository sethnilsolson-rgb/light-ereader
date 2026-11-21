package com.setholson.ereader.data

import android.graphics.Bitmap
import com.setholson.ereader.db.BookBookmarkEntity
import com.setholson.ereader.db.BookProgressEntity
import com.setholson.ereader.db.ReaderDatabase
import com.setholson.ereader.db.ReaderSettingEntity
import com.setholson.ereader.model.BookFormat
import com.setholson.ereader.model.BookMetadata
import com.setholson.ereader.model.LibraryBook
import com.setholson.ereader.model.ParsedBook
import com.setholson.ereader.model.ReaderFontSize
import com.setholson.ereader.model.WordLookupResult
import com.thelightphone.toolmanager.LightFileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.ConcurrentHashMap

class ReaderRepository(
    private val database: ReaderDatabase,
    private val appFilesDir: File,
) {
    private val booksRootDir: File
        get() = File(File(appFilesDir, LightFileProvider.SHARED_DIR), ReaderEntryPoint.BOOKS_RELATIVE_DIR).apply {
            if (!exists()) mkdirs()
        }

    // Metadata cache keyed by "bookId|lastModified" to keep library scanning instantaneous
    private val metadataCache = ConcurrentHashMap<String, BookMetadata>()
    // Parsed book cache keyed by "bookId|lastModified" for instant chapter/TOC navigation
    private val parsedBookCache = ConcurrentHashMap<String, ParsedBook>()

    val bookmarks: Flow<List<BookBookmarkEntity>> = database.bookmarkDao().getAllBookmarks()
    val bookmarkCount: Flow<Int> = database.bookmarkDao().getBookmarkCount()
    val allProgress: Flow<List<BookProgressEntity>> = database.progressDao().getAllProgress()
    val mostRecentProgress: Flow<BookProgressEntity?> = database.progressDao().getMostRecentProgress()

    val settingsMap: Flow<Map<String, String>> = database.settingDao().getAllSettings().map { rows ->
        rows.associate { it.key to it.value }
    }

    val fontSizeFlow: Flow<ReaderFontSize> = settingsMap.map { map ->
        ReaderFontSize.fromName(map[KEY_FONT_SIZE])
    }

    val pdfDarkInvertFlow: Flow<Boolean> = settingsMap.map { map ->
        map[KEY_PDF_DARK_INVERT]?.toBooleanStrictOrNull() ?: true
    }

    val dictEntryCountFlow: Flow<Int> = settingsMap.map { map ->
        map[KEY_DICT_COUNT]?.toIntOrNull() ?: 0
    }

    fun isPositionBookmarked(bookId: String, positionIndex: Int): Flow<Boolean> =
        database.bookmarkDao().isPositionBookmarked(bookId, positionIndex)

    suspend fun setFontSize(size: ReaderFontSize) = withContext(Dispatchers.IO) {
        database.settingDao().putSetting(ReaderSettingEntity(KEY_FONT_SIZE, size.name))
    }

    suspend fun getFontSizeSync(): ReaderFontSize = withContext(Dispatchers.IO) {
        ReaderFontSize.fromName(database.settingDao().getSettingSync(KEY_FONT_SIZE))
    }

    suspend fun setPdfDarkInvert(enabled: Boolean) = withContext(Dispatchers.IO) {
        database.settingDao().putSetting(ReaderSettingEntity(KEY_PDF_DARK_INVERT, enabled.toString()))
    }

    suspend fun getPdfDarkInvertSync(): Boolean = withContext(Dispatchers.IO) {
        database.settingDao().getSettingSync(KEY_PDF_DARK_INVERT)?.toBooleanStrictOrNull() ?: true
    }

    suspend fun isDictionaryReadySync(): Boolean = withContext(Dispatchers.IO) {
        val count = database.settingDao().getSettingSync(KEY_DICT_COUNT)?.toIntOrNull() ?: 0
        count > 0 || database.dictDao().countEntries() > 100
    }

    suspend fun downloadOfflineDictionary(onProgress: (String) -> Unit): Result<Int> =
        withContext(Dispatchers.IO) {
            val result = OfflineDictionaryService.downloadDictionary(database.dictDao(), onProgress)
            result.onSuccess { count ->
                database.settingDao().putSetting(ReaderSettingEntity(KEY_DICT_COUNT, count.toString()))
            }
            result
        }

    suspend fun removeOfflineDictionary() = withContext(Dispatchers.IO) {
        database.dictDao().clearAll()
        database.settingDao().putSetting(ReaderSettingEntity(KEY_DICT_COUNT, "0"))
    }

    suspend fun lookupWord(text: String, charIndex: Int): WordLookupResult? = withContext(Dispatchers.IO) {
        if (!isDictionaryReadySync()) return@withContext null
        OfflineDictionaryService.lookupAtOffset(text, charIndex, database.dictDao())
    }

    /**
     * Scans the Tool Manager shared books folder for all supported books (.epub, .pdf, .txt, .md),
     * merges saved reading progress, and includes the built-in Quick Guide so new users always
     * have instructions and a sample book ready on first launch.
     */
    suspend fun scanLibrary(progressList: List<BookProgressEntity>): List<LibraryBook> =
        withContext(Dispatchers.IO) {
            val progressById = progressList.associateBy { it.bookId }
            val root = booksRootDir
            val discovered = mutableListOf<LibraryBook>()

            root.walkTopDown()
                .filter { it.isFile && !it.name.startsWith(".") }
                .forEach { file ->
                    val format = BookFormat.fromExtension(file.extension) ?: return@forEach
                    val relId = file.relativeTo(root).path.replace('\\', '/')
                    val cacheKey = "$relId|${file.lastModified()}"
                    val meta = metadataCache.getOrPut(cacheKey) {
                        when (format) {
                            BookFormat.EPUB -> EpubParser.peekMetadata(file)
                            BookFormat.PDF -> PdfBookLoader.peekMetadata(file)
                            BookFormat.TXT -> TextBookParser.peekMetadata(file)
                            BookFormat.GUIDE -> BookMetadata("E-Reader Quick Guide", "Light Phone E-Reader", 3)
                        }
                    }

                    val prog = progressById[relId]
                    val total = (prog?.totalPositions ?: meta.totalPositions).coerceAtLeast(1)
                    val current = prog?.currentPosition ?: 0
                    val pct = prog?.progressPercent ?: 0

                    discovered.add(
                        LibraryBook(
                            id = relId,
                            fileName = file.name,
                            title = meta.title,
                            author = meta.author,
                            format = format,
                            fileSizeBytes = file.length(),
                            lastModifiedMs = file.lastModified(),
                            currentPosition = current,
                            totalPositions = total,
                            progressPercent = pct,
                            lastReadAt = prog?.lastReadAt,
                        )
                    )
                }

            val sortedUserBooks = discovered.sortedWith(
                compareByDescending<LibraryBook> { it.lastReadAt ?: 0L }
                    .thenBy { it.title.lowercase() }
            )

            val guideProg = progressById[TextBookParser.WELCOME_GUIDE_ID]
            val guideBook = LibraryBook(
                id = TextBookParser.WELCOME_GUIDE_ID,
                fileName = "E-Reader Quick Guide",
                title = "E-Reader Quick Guide",
                author = "How to add & read books",
                format = BookFormat.GUIDE,
                fileSizeBytes = 0L,
                lastModifiedMs = 0L,
                currentPosition = guideProg?.currentPosition ?: 0,
                totalPositions = 3,
                progressPercent = guideProg?.progressPercent ?: 0,
                lastReadAt = guideProg?.lastReadAt,
            )

            sortedUserBooks + guideBook
        }

    fun resolveBookFile(bookId: String): File? {
        if (bookId == TextBookParser.WELCOME_GUIDE_ID) return null
        val root = booksRootDir.canonicalFile
        val candidate = File(root, bookId).canonicalFile
        if (!candidate.path.startsWith(root.path)) return null
        return candidate.takeIf { it.isFile }
    }

    suspend fun loadTextBook(bookId: String): Result<ParsedBook> = withContext(Dispatchers.IO) {
        if (bookId == TextBookParser.WELCOME_GUIDE_ID) {
            return@withContext Result.success(TextBookParser.welcomeGuideBook())
        }
        val file = resolveBookFile(bookId) ?: return@withContext Result.failure(
            IllegalStateException("Book file not found. It may have been removed in Tool Manager.")
        )
        val cacheKey = "$bookId|${file.lastModified()}"
        parsedBookCache[cacheKey]?.let { return@withContext Result.success(it) }

        val parsed = when (BookFormat.fromExtension(file.extension)) {
            BookFormat.EPUB -> EpubParser.parse(file)
            BookFormat.TXT -> TextBookParser.parse(file)
            else -> Result.failure(IllegalArgumentException("Unsupported text book format: ${file.extension}"))
        }
        parsed.onSuccess { book ->
            // Keep cache bounded to 4 recently opened books
            if (parsedBookCache.size >= 4) parsedBookCache.clear()
            parsedBookCache[cacheKey] = book
        }
        parsed
    }

    suspend fun renderPdfPage(
        bookId: String,
        pageIndex: Int,
        targetWidthPx: Int = 1080,
    ): Result<Pair<Bitmap, Int>> = withContext(Dispatchers.IO) {
        val file = resolveBookFile(bookId) ?: return@withContext Result.failure(
            IllegalStateException("PDF file not found.")
        )
        PdfBookLoader.renderPage(file, pageIndex, targetWidthPx)
    }

    suspend fun getProgressSync(bookId: String): BookProgressEntity? = withContext(Dispatchers.IO) {
        database.progressDao().getProgressSync(bookId)
    }

    suspend fun saveProgress(
        bookId: String,
        title: String,
        author: String,
        format: BookFormat,
        positionIndex: Int,
        totalPositions: Int,
        scrollIndex: Int = 0,
    ) = withContext(Dispatchers.IO) {
        val safeTotal = totalPositions.coerceAtLeast(1)
        val safePos = positionIndex.coerceIn(0, safeTotal - 1)
        val percent = (((safePos + 1).toFloat() / safeTotal.toFloat()) * 100f).toInt().coerceIn(1, 100)
        database.progressDao().upsertProgress(
            BookProgressEntity(
                bookId = bookId,
                title = title,
                author = author,
                format = format.name,
                currentPosition = safePos,
                totalPositions = safeTotal,
                scrollIndex = scrollIndex,
                progressPercent = percent,
                lastReadAt = System.currentTimeMillis(),
            )
        )
    }

    suspend fun toggleBookmark(
        bookId: String,
        bookTitle: String,
        format: BookFormat,
        positionIndex: Int,
        positionLabel: String,
        snippet: String,
        currentlyBookmarked: Boolean,
    ) = withContext(Dispatchers.IO) {
        if (currentlyBookmarked) {
            database.bookmarkDao().deletePositionBookmark(bookId, positionIndex)
        } else {
            database.bookmarkDao().insertBookmark(
                BookBookmarkEntity(
                    bookId = bookId,
                    bookTitle = bookTitle,
                    format = format.name,
                    positionIndex = positionIndex,
                    positionLabel = positionLabel,
                    snippet = snippet.take(100),
                )
            )
        }
    }

    suspend fun deleteBookmark(bookmark: BookBookmarkEntity) = withContext(Dispatchers.IO) {
        database.bookmarkDao().deleteBookmark(bookmark)
    }

    suspend fun deleteBook(bookId: String): Boolean = withContext(Dispatchers.IO) {
        if (bookId == TextBookParser.WELCOME_GUIDE_ID) return@withContext false
        val file = resolveBookFile(bookId)
        val deleted = file?.delete() ?: false
        database.progressDao().deleteProgress(bookId)
        database.bookmarkDao().deleteAllForBook(bookId)
        metadataCache.keys.filter { it.startsWith("$bookId|") }.forEach { metadataCache.remove(it) }
        parsedBookCache.keys.filter { it.startsWith("$bookId|") }.forEach { parsedBookCache.remove(it) }
        deleted
    }

    companion object {
        const val DATABASE_NAME = "light_ereader.db"
        private const val KEY_FONT_SIZE = "font_size"
        private const val KEY_PDF_DARK_INVERT = "pdf_dark_invert"
        private const val KEY_DICT_COUNT = "dict_count"

        @Volatile
        private var instance: ReaderRepository? = null

        fun getInstance(
            dbProvider: () -> ReaderDatabase,
            filesDir: File,
        ): ReaderRepository {
            return instance ?: synchronized(this) {
                instance ?: ReaderRepository(dbProvider(), filesDir).also { instance = it }
            }
        }
    }
}
