package com.setholson.ereader.data

import android.graphics.Bitmap
import com.setholson.ereader.db.BookBookmarkEntity
import com.setholson.ereader.db.BookProgressEntity
import com.setholson.ereader.db.ReaderDatabase
import com.setholson.ereader.db.ReaderSettingEntity
import com.setholson.ereader.model.BookFormat
import com.setholson.ereader.model.BookMetadata
import com.setholson.ereader.model.DictionaryPack
import com.setholson.ereader.model.FreeCatalogBook
import com.setholson.ereader.model.LibraryBook
import com.setholson.ereader.model.ParsedBook
import com.setholson.ereader.model.ReaderFontSize
import com.setholson.ereader.model.ReaderProgressMode
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
    val booksRootDir: File
        get() = File(File(appFilesDir, LightFileProvider.SHARED_DIR), ReaderEntryPoint.BOOKS_RELATIVE_DIR).apply {
            if (!exists()) mkdirs()
        }

    private val metadataCache = ConcurrentHashMap<String, BookMetadata>()
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

    val primaryDictIdFlow: Flow<String?> = settingsMap.map { map ->
        map[KEY_PRIMARY_DICT]?.takeIf { it.isNotBlank() }
    }

    val onlineDictEnabledFlow: Flow<Boolean> = settingsMap.map { map ->
        map[KEY_ONLINE_DICT_ENABLED]?.toBooleanStrictOrNull() ?: false
    }

    val dictCountsFlow: Flow<Map<String, Int>> = database.dictDao().getCountsByDict().map { rows ->
        rows.associate { it.dictId to it.entryCount }
    }

    fun isPositionBookmarked(bookId: String, positionIndex: Int): Flow<Boolean> =
        database.bookmarkDao().isPositionBookmarked(bookId, positionIndex)

    suspend fun setFontSize(size: ReaderFontSize) = withContext(Dispatchers.IO) {
        database.settingDao().putSetting(ReaderSettingEntity(KEY_FONT_SIZE, size.name))
    }

    suspend fun getFontSizeSync(): ReaderFontSize = withContext(Dispatchers.IO) {
        ReaderFontSize.fromName(database.settingDao().getSettingSync(KEY_FONT_SIZE))
    }

    suspend fun setProgressMode(mode: ReaderProgressMode) = withContext(Dispatchers.IO) {
        database.settingDao().putSetting(ReaderSettingEntity(KEY_PROGRESS_MODE, mode.name))
    }

    suspend fun getProgressModeSync(): ReaderProgressMode = withContext(Dispatchers.IO) {
        ReaderProgressMode.fromName(database.settingDao().getSettingSync(KEY_PROGRESS_MODE))
    }

    suspend fun setPdfDarkInvert(enabled: Boolean) = withContext(Dispatchers.IO) {
        database.settingDao().putSetting(ReaderSettingEntity(KEY_PDF_DARK_INVERT, enabled.toString()))
    }

    suspend fun getPdfDarkInvertSync(): Boolean = withContext(Dispatchers.IO) {
        database.settingDao().getSettingSync(KEY_PDF_DARK_INVERT)?.toBooleanStrictOrNull() ?: true
    }

    suspend fun setPrimaryDictionary(pack: DictionaryPack) = withContext(Dispatchers.IO) {
        database.settingDao().putSetting(ReaderSettingEntity(KEY_PRIMARY_DICT, pack.id))
    }

    suspend fun setOnlineDictionaryEnabled(enabled: Boolean) = withContext(Dispatchers.IO) {
        database.settingDao().putSetting(ReaderSettingEntity(KEY_ONLINE_DICT_ENABLED, enabled.toString()))
        val currentPrimary = database.settingDao().getSettingSync(KEY_PRIMARY_DICT)
        if (enabled && currentPrimary.isNullOrBlank()) {
            database.settingDao().putSetting(ReaderSettingEntity(KEY_PRIMARY_DICT, DictionaryPack.ONLINE_MODERN.id))
        } else if (!enabled && currentPrimary == DictionaryPack.ONLINE_MODERN.id) {
            pickFallbackPrimaryDict()
        }
    }

    suspend fun isAnyDictionaryReadySync(): Boolean = withContext(Dispatchers.IO) {
        val online = database.settingDao().getSettingSync(KEY_ONLINE_DICT_ENABLED)?.toBooleanStrictOrNull() ?: false
        if (online) return@withContext true
        database.dictDao().countAll() > 0
    }

    suspend fun downloadDictionaryPack(
        pack: DictionaryPack,
        onProgress: (String) -> Unit,
    ): Result<Int> = withContext(Dispatchers.IO) {
        if (pack.isOnlineAutoCache) {
            setOnlineDictionaryEnabled(true)
            onProgress("Live Modern Dictionary enabled")
            return@withContext Result.success(database.dictDao().countForDict(pack.id))
        }
        val result = OfflineDictionaryService.downloadPack(pack, database.dictDao(), onProgress)
        result.onSuccess { count ->
            if (count > 0) {
                val currentPrimary = database.settingDao().getSettingSync(KEY_PRIMARY_DICT)
                if (currentPrimary.isNullOrBlank()) {
                    database.settingDao().putSetting(ReaderSettingEntity(KEY_PRIMARY_DICT, pack.id))
                }
            }
        }
        result
    }

    suspend fun removeDictionaryPack(pack: DictionaryPack) = withContext(Dispatchers.IO) {
        if (pack.isOnlineAutoCache) {
            setOnlineDictionaryEnabled(false)
        }
        database.dictDao().deleteByDict(pack.id)
        val currentPrimary = database.settingDao().getSettingSync(KEY_PRIMARY_DICT)
        if (currentPrimary == pack.id) {
            pickFallbackPrimaryDict()
        }
    }

    private suspend fun pickFallbackPrimaryDict() {
        val counts = database.dictDao().getCountsByDictSync()
        val firstInstalled = DictionaryPack.entries.firstOrNull { pack ->
            !pack.isOnlineAutoCache && (counts.firstOrNull { it.dictId == pack.id }?.entryCount ?: 0) > 0
        }
        val online = database.settingDao().getSettingSync(KEY_ONLINE_DICT_ENABLED)?.toBooleanStrictOrNull() ?: false
        val nextId = firstInstalled?.id ?: if (online) DictionaryPack.ONLINE_MODERN.id else ""
        database.settingDao().putSetting(ReaderSettingEntity(KEY_PRIMARY_DICT, nextId))
    }

    suspend fun lookupWord(text: String, charIndex: Int): WordLookupResult? = withContext(Dispatchers.IO) {
        if (!isAnyDictionaryReadySync()) return@withContext null
        val primaryId = database.settingDao().getSettingSync(KEY_PRIMARY_DICT)
        val onlineEnabled = database.settingDao().getSettingSync(KEY_ONLINE_DICT_ENABLED)?.toBooleanStrictOrNull() ?: false
        OfflineDictionaryService.lookupAtOffset(
            text = text,
            charIndex = charIndex,
            dao = database.dictDao(),
            primaryDictId = primaryId,
            onlineEnabled = onlineEnabled,
        )
    }

    // -------------------------------------------------------------------------
    // Free Classics Catalog & Direct URL Download
    // -------------------------------------------------------------------------
    fun isCatalogBookDownloaded(book: FreeCatalogBook): Boolean =
        File(booksRootDir, book.fileName).isFile

    suspend fun downloadCatalogBook(
        book: FreeCatalogBook,
        onProgress: (String) -> Unit,
    ): Result<File> = withContext(Dispatchers.IO) {
        val result = FreeBooksCatalog.downloadBookToDir(
            urlString = book.downloadUrl,
            targetDir = booksRootDir,
            suggestedFileName = book.fileName,
            onProgress = onProgress,
        )
        result.onSuccess {
            ReaderEntryPoint.notifyLibraryChanged()
        }
        result
    }

    suspend fun downloadBookFromUrl(
        url: String,
        onProgress: (String) -> Unit,
    ): Result<File> = withContext(Dispatchers.IO) {
        val result = FreeBooksCatalog.downloadBookToDir(
            urlString = url,
            targetDir = booksRootDir,
            suggestedFileName = null,
            onProgress = onProgress,
        )
        result.onSuccess {
            ReaderEntryPoint.notifyLibraryChanged()
        }
        result
    }

    // -------------------------------------------------------------------------
    // Library Scanning, Reading & Bookmarks
    // -------------------------------------------------------------------------
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
            IllegalStateException("Book file not found. It may have been deleted.")
        )
        val cacheKey = "$bookId|${file.lastModified()}"
        parsedBookCache[cacheKey]?.let { return@withContext Result.success(it) }

        val parsed = when (BookFormat.fromExtension(file.extension)) {
            BookFormat.EPUB -> EpubParser.parse(file)
            BookFormat.TXT -> TextBookParser.parse(file)
            else -> Result.failure(IllegalArgumentException("Unsupported text book format: ${file.extension}"))
        }
        parsed.onSuccess { book ->
            if (parsedBookCache.size >= 4) parsedBookCache.clear()
            parsedBookCache[cacheKey] = book
        }
        parsed
    }

    suspend fun renderPdfPage(
        bookId: String,
        pageIndex: Int,
        targetWidthPx: Int = 2160,
    ): Result<RenderedPdfPage> = withContext(Dispatchers.IO) {
        val file = resolveBookFile(bookId) ?: return@withContext Result.failure(
            IllegalStateException("PDF file not found.")
        )
        PdfBookLoader.renderPage(file, pageIndex, targetWidthPx)
    }

    suspend fun renderPdfViewportTile(
        bookId: String,
        pageIndex: Int,
        viewportWidthPx: Int,
        viewportHeightPx: Int,
        totalScale: Float,
        translateX: Float,
        translateY: Float,
    ): Result<Bitmap> = withContext(Dispatchers.IO) {
        val file = resolveBookFile(bookId) ?: return@withContext Result.failure(
            IllegalStateException("PDF file not found.")
        )
        PdfBookLoader.renderViewportTile(
            file = file,
            pageIndex = pageIndex,
            viewportWidthPx = viewportWidthPx,
            viewportHeightPx = viewportHeightPx,
            totalScale = totalScale,
            translateX = translateX,
            translateY = translateY,
        )
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

    fun getBookmarksForPosition(bookId: String, positionIndex: Int): Flow<List<BookBookmarkEntity>> =
        database.bookmarkDao().getBookmarksForPosition(bookId, positionIndex)

    suspend fun updateScrollProgress(bookId: String, scrollPermille: Int) = withContext(Dispatchers.IO) {
        database.progressDao().updateScrollIndex(bookId, scrollPermille.coerceIn(0, 1000))
    }

    suspend fun toggleBookmark(
        bookId: String,
        bookTitle: String,
        format: BookFormat,
        positionIndex: Int,
        positionLabel: String,
        snippet: String,
        currentlyBookmarked: Boolean,
        scrollPermille: Int = 0,
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
                    snippet = snippet.take(120),
                    scrollPermille = scrollPermille.coerceIn(0, 1000),
                )
            )
        }
    }

    suspend fun toggleScrollBookmark(
        bookId: String,
        bookTitle: String,
        format: BookFormat,
        positionIndex: Int,
        chapterTitle: String,
        scrollPermille: Int,
        snippet: String,
        matchingBookmark: BookBookmarkEntity?,
    ) = withContext(Dispatchers.IO) {
        if (matchingBookmark != null) {
            database.bookmarkDao().deleteBookmark(matchingBookmark)
        } else {
            val clamped = scrollPermille.coerceIn(0, 1000)
            val pct = (clamped / 10).coerceIn(0, 100)
            val label = if (pct > 0) "$chapterTitle • $pct% in" else chapterTitle
            database.bookmarkDao().insertBookmark(
                BookBookmarkEntity(
                    bookId = bookId,
                    bookTitle = bookTitle,
                    format = format.name,
                    positionIndex = positionIndex,
                    positionLabel = label,
                    snippet = snippet.take(120),
                    scrollPermille = clamped,
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
        if (deleted) ReaderEntryPoint.notifyLibraryChanged()
        deleted
    }

    companion object {
        const val DATABASE_NAME = "light_ereader.db"
        private const val KEY_FONT_SIZE = "font_size"
        private const val KEY_PROGRESS_MODE = "progress_mode"
        private const val KEY_PDF_DARK_INVERT = "pdf_dark_invert"
        private const val KEY_PRIMARY_DICT = "primary_dict"
        private const val KEY_ONLINE_DICT_ENABLED = "online_dict_enabled"

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
