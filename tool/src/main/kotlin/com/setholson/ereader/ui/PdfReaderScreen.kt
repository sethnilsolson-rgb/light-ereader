package com.setholson.ereader.ui

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.material3.HorizontalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewModelScope
import com.setholson.ereader.data.CachedPdfTextBook
import com.setholson.ereader.data.PdfExtractionMode
import com.setholson.ereader.data.PdfTextExtractionResult
import com.setholson.ereader.data.ReaderRepository
import com.setholson.ereader.data.RenderedPdfPage
import com.setholson.ereader.db.BookBookmarkEntity
import com.setholson.ereader.db.BookNoteEntity
import com.setholson.ereader.db.ReaderDatabase
import com.setholson.ereader.model.BookFormat
import com.setholson.ereader.model.ReaderFontSize
import com.setholson.ereader.model.ReaderPageMode
import com.setholson.ereader.model.ReaderProgressMode
import com.setholson.ereader.model.TextBlock
import com.setholson.ereader.model.WordAnchor
import com.setholson.ereader.model.WordLookupResult
import com.setholson.ereader.model.WordNoteUtils
import com.thelightphone.sdk.LightScreen
import com.thelightphone.sdk.LightViewModel
import com.thelightphone.sdk.SealedLightActivity
import com.thelightphone.sdk.SimpleLightScreen
import com.thelightphone.sdk.buildDatabase
import com.thelightphone.sdk.rememberKeyboardOptions
import com.thelightphone.sdk.ui.LightBarButton
import com.thelightphone.sdk.ui.LightIcons
import com.thelightphone.sdk.ui.LightScrollView
import com.thelightphone.sdk.ui.LightText
import com.thelightphone.sdk.ui.LightTextVariant
import com.thelightphone.sdk.ui.LightTheme
import com.thelightphone.sdk.ui.LightThemeController
import com.thelightphone.sdk.ui.LightThemeTokens
import com.thelightphone.sdk.ui.LightTopBar
import com.thelightphone.sdk.ui.LightTopBarCenter
import com.thelightphone.sdk.ui.gridUnitsAsDp
import com.thelightphone.sdk.ui.lightClickable
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

private const val MIN_PDF_SCALE = 1.0f
private const val MAX_PDF_SCALE = 8.0f
private const val PDF_BRIEF_SCROLL_MEMORY_MS = 180_000L

private val INVERT_COLOR_FILTER = ColorFilter.colorMatrix(
    ColorMatrix(
        floatArrayOf(
            -1f, 0f, 0f, 0f, 255f,
            0f, -1f, 0f, 0f, 255f,
            0f, 0f, -1f, 0f, 255f,
            0f, 0f, 0f, 1f, 0f,
        )
    )
)

data class CrispPdfTile(
    val bitmap: Bitmap,
    val pageIndex: Int,
    val rotationDegrees: Int,
    val scale: Float,
    val offsetX: Float,
    val offsetY: Float,
)

class PdfReaderViewModel(
    private val bookId: String,
    private val bookTitle: String,
    private val explicitStartPage: Int?,
    private val explicitStartScrollPermille: Int?,
    private val preferTextMode: Boolean,
    private val repository: ReaderRepository,
) : LightViewModel<Unit>() {

    private val _pageIndex = MutableStateFlow(0)
    val pageIndex: StateFlow<Int> = _pageIndex.asStateFlow()

    private val _pageCount = MutableStateFlow(0)
    val pageCount: StateFlow<Int> = _pageCount.asStateFlow()

    private val _targetScrollPermille = MutableStateFlow(0)
    val targetScrollPermille: StateFlow<Int> = _targetScrollPermille.asStateFlow()

    private val _scrollRequestToken = MutableStateFlow(0L)
    val scrollRequestToken: StateFlow<Long> = _scrollRequestToken.asStateFlow()

    private val _renderedPage = MutableStateFlow<RenderedPdfPage?>(null)
    val renderedPage: StateFlow<RenderedPdfPage?> = _renderedPage.asStateFlow()

    private val _crispTile = MutableStateFlow<CrispPdfTile?>(null)
    val crispTile: StateFlow<CrispPdfTile?> = _crispTile.asStateFlow()

    private val _isLoading = MutableStateFlow(true)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _errorText = MutableStateFlow<String?>(null)
    val errorText: StateFlow<String?> = _errorText.asStateFlow()

    private val _pageBookmarks = MutableStateFlow<List<BookBookmarkEntity>>(emptyList())
    val pageBookmarks: StateFlow<List<BookBookmarkEntity>> = _pageBookmarks.asStateFlow()

    private val _invertColors = MutableStateFlow(false)
    val invertColors: StateFlow<Boolean> = _invertColors.asStateFlow()

    private val _rotationDegrees = MutableStateFlow(0)
    val rotationDegrees: StateFlow<Int> = _rotationDegrees.asStateFlow()

    private val _progressMode = MutableStateFlow(ReaderProgressMode.BOOK)
    val progressMode: StateFlow<ReaderProgressMode> = _progressMode.asStateFlow()

    // Book / Text reading mode state
    private val _isTextMode = MutableStateFlow(false)
    val isTextMode: StateFlow<Boolean> = _isTextMode.asStateFlow()

    private val _isExtractingText = MutableStateFlow(false)
    val isExtractingText: StateFlow<Boolean> = _isExtractingText.asStateFlow()

    private val _extractionProgressText = MutableStateFlow<String?>(null)
    val extractionProgressText: StateFlow<String?> = _extractionProgressText.asStateFlow()

    private val _textNotice = MutableStateFlow<String?>(null)
    val textNotice: StateFlow<String?> = _textNotice.asStateFlow()

    private val _extractedBook = MutableStateFlow<CachedPdfTextBook?>(null)
    val extractedBook: StateFlow<CachedPdfTextBook?> = _extractedBook.asStateFlow()

    private val _extractionMode = MutableStateFlow(PdfExtractionMode.SMART_LAYOUT)
    val extractionMode: StateFlow<PdfExtractionMode> = _extractionMode.asStateFlow()

    private val _fontSize = MutableStateFlow(ReaderFontSize.REGULAR)
    val fontSize: StateFlow<ReaderFontSize> = _fontSize.asStateFlow()

    private val _pageMode = MutableStateFlow(ReaderPageMode.SCROLL)
    val pageMode: StateFlow<ReaderPageMode> = _pageMode.asStateFlow()

    private val _isFullScreen = MutableStateFlow(false)
    val isFullScreen: StateFlow<Boolean> = _isFullScreen.asStateFlow()

    private val _pageNotes = MutableStateFlow<List<BookNoteEntity>>(emptyList())
    val pageNotes: StateFlow<List<BookNoteEntity>> = _pageNotes.asStateFlow()

    private val _heldWord = MutableStateFlow<HeldWordContext?>(null)
    val heldWord: StateFlow<HeldWordContext?> = _heldWord.asStateFlow()

    private val _isSelectingNoteWords = MutableStateFlow(false)
    val isSelectingNoteWords: StateFlow<Boolean> = _isSelectingNoteWords.asStateFlow()

    private val _selectedWordAnchors = MutableStateFlow<List<WordAnchor>>(emptyList())
    val selectedWordAnchors: StateFlow<List<WordAnchor>> = _selectedWordAnchors.asStateFlow()

    private var noteSelectionScrollPermille: Int = 0

    private val _composingNote = MutableStateFlow<ComposingNoteState?>(null)
    val composingNote: StateFlow<ComposingNoteState?> = _composingNote.asStateFlow()

    private val _lookupEnabled = MutableStateFlow(false)
    val lookupEnabled: StateFlow<Boolean> = _lookupEnabled.asStateFlow()

    private val _isLookingUp = MutableStateFlow(false)
    val isLookingUp: StateFlow<Boolean> = _isLookingUp.asStateFlow()

    private val _lookupResult = MutableStateFlow<WordLookupResult?>(null)
    val lookupResult: StateFlow<WordLookupResult?> = _lookupResult.asStateFlow()

    private val recentScrollPositions = mutableMapOf<Int, Pair<Int, Long>>()
    private var liveScrollPermille: Int = 0
    private var hasInitialized = false
    private var lastRenderedPageIndex = -1
    private var lastRenderedRotation = -1
    private var renderJob: Job? = null
    private var tileJob: Job? = null
    private var bookmarkJob: Job? = null
    private var notesJob: Job? = null
    private var extractionJob: Job? = null
    private var extractionCancelled = AtomicBoolean(false)
    private var lookupJob: Job? = null
    private var scrollSaveJob: Job? = null

    override fun onScreenShow(screen: SimpleLightScreen<Unit>) {
        super.onScreenShow(screen)
        viewModelScope.launch {
            _progressMode.value = repository.getProgressModeSync()
            _fontSize.value = repository.getFontSizeSync()
            _pageMode.value = repository.getPageModeSync()
            _lookupEnabled.value = repository.isAnyDictionaryReadySync()
        }
        if (!hasInitialized) {
            hasInitialized = true
            viewModelScope.launch {
                val autoInvert = repository.getPdfDarkInvertSync()
                _invertColors.value = autoInvert && LightThemeController.isDarkTheme
                _rotationDegrees.value = repository.getPdfRotationSync(bookId)
                val savedProg = repository.getProgressSync(bookId)
                val savedPage = savedProg?.currentPosition ?: 0
                val startPage = explicitStartPage ?: savedPage
                val startPermille = when {
                    explicitStartScrollPermille != null -> explicitStartScrollPermille.coerceIn(0, 1000)
                    explicitStartPage == null && savedProg != null -> savedProg.scrollIndex.coerceIn(0, 1000)
                    else -> 0
                }

                val savedMode = repository.getPdfExtractionModeSync(bookId)
                _extractionMode.value = savedMode
                val wantTextMode = preferTextMode ||
                    repository.getPdfTextModeSync(bookId) ||
                    (explicitStartScrollPermille != null && explicitStartScrollPermille > 0)
                if (wantTextMode) {
                    val cached = repository.getCachedPdfTextSync(bookId, savedMode)
                    if (cached != null) {
                        _extractedBook.value = cached
                        _isTextMode.value = true
                        _isLoading.value = false
                        openTextPage(
                            index = startPage,
                            book = cached,
                            explicitScrollPermille = startPermille,
                            rememberOutgoing = false,
                        )
                        return@launch
                    } else {
                        _pageIndex.value = startPage
                        liveScrollPermille = startPermille
                        _targetScrollPermille.value = startPermille
                        _isLoading.value = false
                        enterTextMode(
                            forceReextract = true,
                            mode = savedMode,
                            targetScrollPermilleOverride = startPermille,
                        )
                        return@launch
                    }
                }
                openPage(startPage)
            }
        }
    }

    fun onTextScrollChanged(scrollPermille: Int) {
        val clamped = scrollPermille.coerceIn(0, 1000)
        liveScrollPermille = clamped
        scrollSaveJob?.cancel()
        scrollSaveJob = viewModelScope.launch {
            delay(900L)
            repository.updateScrollProgress(bookId, clamped)
        }
    }

    private fun observeBookmarksAndSaveProgress(safeIndex: Int, totalPages: Int, scrollPermille: Int) {
        viewModelScope.launch {
            repository.saveProgress(
                bookId = bookId,
                title = bookTitle,
                author = "$totalPages pages",
                format = BookFormat.PDF,
                positionIndex = safeIndex,
                totalPositions = totalPages,
                scrollIndex = scrollPermille.coerceIn(0, 1000),
            )
        }
        bookmarkJob?.cancel()
        bookmarkJob = viewModelScope.launch {
            repository.getBookmarksForPosition(bookId, safeIndex).collect { saved ->
                _pageBookmarks.value = saved
            }
        }
        notesJob?.cancel()
        notesJob = viewModelScope.launch {
            repository.getNotesForPosition(bookId, safeIndex).collect { saved ->
                _pageNotes.value = saved
            }
        }
    }

    private fun openTextPage(
        index: Int,
        book: CachedPdfTextBook,
        explicitScrollPermille: Int? = null,
        rememberOutgoing: Boolean = true,
    ) {
        val total = book.pageCount.coerceAtLeast(1)
        val safeIndex = index.coerceIn(0, total - 1)
        val now = System.currentTimeMillis()
        if (rememberOutgoing) {
            val prevIdx = _pageIndex.value
            if (prevIdx != safeIndex) {
                recentScrollPositions[prevIdx] = liveScrollPermille to now
            }
        }

        val resolvedPermille = when {
            explicitScrollPermille != null -> explicitScrollPermille.coerceIn(0, 1000)
            else -> {
                val recent = recentScrollPositions[safeIndex]
                if (recent != null && (now - recent.second) <= PDF_BRIEF_SCROLL_MEMORY_MS) {
                    recent.first.coerceIn(0, 1000)
                } else {
                    0
                }
            }
        }

        _pageIndex.value = safeIndex
        _pageCount.value = total
        liveScrollPermille = resolvedPermille
        _targetScrollPermille.value = resolvedPermille
        _scrollRequestToken.value = _scrollRequestToken.value + 1L
        _errorText.value = null
        dismissLookup()
        cancelNoteWordSelection()
        observeBookmarksAndSaveProgress(safeIndex, total, resolvedPermille)
    }

    fun openPage(
        index: Int,
        rotation: Int = _rotationDegrees.value,
        explicitScrollPermille: Int? = null,
    ) {
        val cached = _extractedBook.value
        if (_isTextMode.value && cached != null) {
            openTextPage(index, cached, explicitScrollPermille = explicitScrollPermille)
            return
        }

        renderJob?.cancel()
        tileJob?.cancel()
        _crispTile.value = null

        renderJob = viewModelScope.launch {
            _isLoading.value = true
            _errorText.value = null

            val result = repository.renderPdfPage(
                bookId = bookId,
                pageIndex = index,
                targetWidthPx = 1440,
                rotationDegrees = rotation,
            )
            _isLoading.value = false

            result.onSuccess { page ->
                val safeIndex = index.coerceIn(0, (page.pageCount - 1).coerceAtLeast(0))
                lastRenderedPageIndex = safeIndex
                lastRenderedRotation = page.rotationDegrees
                _renderedPage.value = page
                _pageIndex.value = safeIndex
                _pageCount.value = page.pageCount
                liveScrollPermille = 0
                _targetScrollPermille.value = 0
                observeBookmarksAndSaveProgress(safeIndex, page.pageCount, 0)
            }.onFailure { err ->
                _errorText.value = err.message ?: "Unable to render PDF page."
            }
        }
    }

    fun enterTextMode(
        forceReextract: Boolean = false,
        mode: PdfExtractionMode = _extractionMode.value,
        targetScrollPermilleOverride: Int? = null,
    ) {
        _textNotice.value = null
        _extractionMode.value = mode
        val alreadyExtracted = _extractedBook.value
        val desiredPermille = targetScrollPermilleOverride ?: liveScrollPermille
        if (!forceReextract && alreadyExtracted != null && alreadyExtracted.modeName == mode.name) {
            _isTextMode.value = true
            viewModelScope.launch { repository.setPdfTextMode(bookId, true) }
            openTextPage(
                index = _pageIndex.value,
                book = alreadyExtracted,
                explicitScrollPermille = desiredPermille,
                rememberOutgoing = false,
            )
            return
        }

        if (_isExtractingText.value) return
        extractionJob?.cancel()
        val cancelFlag = AtomicBoolean(false)
        extractionCancelled = cancelFlag

        extractionJob = viewModelScope.launch {
            repository.setPdfExtractionMode(bookId, mode)
            _isExtractingText.value = true
            _extractionProgressText.value = "Reading PDF (${mode.shortLabel})..."

            val result = repository.extractOrLoadPdfText(
                bookId = bookId,
                mode = mode,
                forceReextract = forceReextract,
                onProgress = { cur, total ->
                    _extractionProgressText.value = "Page $cur / $total (${mode.shortLabel})"
                },
                isCancelled = { cancelFlag.get() },
            )

            _isExtractingText.value = false
            _extractionProgressText.value = null

            if (cancelFlag.get()) return@launch

            when (result) {
                is PdfTextExtractionResult.Success -> {
                    _extractedBook.value = result.book
                    _isTextMode.value = true
                    repository.setPdfTextMode(bookId, true)
                    openTextPage(
                        index = _pageIndex.value,
                        book = result.book,
                        explicitScrollPermille = desiredPermille,
                        rememberOutgoing = false,
                    )
                }
                PdfTextExtractionResult.Encrypted -> {
                    _textNotice.value =
                        "This PDF is encrypted, so its text cannot be extracted. You can still read it in Page view."
                    if (_renderedPage.value == null) openPage(_pageIndex.value)
                }
                PdfTextExtractionResult.NoTextFound -> {
                    _textNotice.value =
                        "No embedded text found (this looks like a scanned image PDF). You can still read it in Page view."
                    if (_renderedPage.value == null) openPage(_pageIndex.value)
                }
                is PdfTextExtractionResult.Error -> {
                    _textNotice.value = result.message
                    if (_renderedPage.value == null) openPage(_pageIndex.value)
                }
            }
        }
    }

    fun reextractWithMode(mode: PdfExtractionMode) {
        dismissLookup()
        enterTextMode(forceReextract = true, mode = mode, targetScrollPermilleOverride = 0)
    }

    fun cancelTextExtraction() {
        extractionCancelled.set(true)
        extractionJob?.cancel()
        _isExtractingText.value = false
        _extractionProgressText.value = null
        if (_renderedPage.value == null && !_isTextMode.value) {
            openPage(_pageIndex.value)
        }
    }

    fun exitTextMode() {
        dismissLookup()
        _isFullScreen.value = false
        _isTextMode.value = false
        viewModelScope.launch { repository.setPdfTextMode(bookId, false) }
        val targetPage = _pageIndex.value
        val needsRender = _renderedPage.value == null ||
            lastRenderedPageIndex != targetPage ||
            lastRenderedRotation != _rotationDegrees.value
        if (needsRender) {
            openPage(targetPage)
        }
    }

    fun dismissTextNotice() {
        _textNotice.value = null
    }

    fun rotateClockwise() {
        val next = (_rotationDegrees.value + 90) % 360
        _rotationDegrees.value = next
        viewModelScope.launch {
            repository.setPdfRotation(bookId, next)
        }
        if (!_isTextMode.value && _pageCount.value > 0) {
            openPage(_pageIndex.value, rotation = next)
        }
    }

    fun requestCrispTile(
        pageIndex: Int,
        rotationDegrees: Int,
        viewportWidthPx: Int,
        viewportHeightPx: Int,
        totalScale: Float,
        translateX: Float,
        translateY: Float,
        scaleKey: Float,
        offsetXKey: Float,
        offsetYKey: Float,
    ) {
        tileJob?.cancel()
        tileJob = viewModelScope.launch {
            delay(140L)
            val result = repository.renderPdfViewportTile(
                bookId = bookId,
                pageIndex = pageIndex,
                viewportWidthPx = viewportWidthPx,
                viewportHeightPx = viewportHeightPx,
                totalScale = totalScale,
                translateX = translateX,
                translateY = translateY,
                rotationDegrees = rotationDegrees,
            )
            result.onSuccess { bmp ->
                if (_pageIndex.value == pageIndex && _rotationDegrees.value == rotationDegrees && !_isTextMode.value) {
                    _crispTile.value = CrispPdfTile(
                        bitmap = bmp,
                        pageIndex = pageIndex,
                        rotationDegrees = rotationDegrees,
                        scale = scaleKey,
                        offsetX = offsetXKey,
                        offsetY = offsetYKey,
                    )
                }
            }
        }
    }

    fun clearCrispTile() {
        tileJob?.cancel()
        _crispTile.value = null
    }

    fun nextPage(landOnStart: Boolean = false) {
        val total = _pageCount.value
        if (total > 0 && _pageIndex.value < total - 1) {
            openPage(
                index = _pageIndex.value + 1,
                explicitScrollPermille = if (landOnStart) 0 else null,
            )
        }
    }

    fun previousPage(landOnEnd: Boolean = false) {
        if (_pageIndex.value > 0) {
            openPage(
                index = _pageIndex.value - 1,
                explicitScrollPermille = if (landOnEnd) 1000 else null,
            )
        }
    }

    fun jumpRelative(delta: Int) {
        val total = _pageCount.value
        if (total <= 0) return
        val target = (_pageIndex.value + delta).coerceIn(0, total - 1)
        openPage(target, explicitScrollPermille = 0)
    }

    fun toggleInvert() {
        _invertColors.value = !_invertColors.value
    }

    fun cycleFontSize(preservePermille: Int = liveScrollPermille) {
        val clamped = preservePermille.coerceIn(0, 1000)
        val next = _fontSize.value.next()
        _fontSize.value = next
        liveScrollPermille = clamped
        _targetScrollPermille.value = clamped
        _scrollRequestToken.value = _scrollRequestToken.value + 1L
        viewModelScope.launch {
            repository.setFontSize(next)
        }
    }

    fun togglePageMode(preservePermille: Int = liveScrollPermille) {
        val clamped = preservePermille.coerceIn(0, 1000)
        val next = _pageMode.value.toggle()
        _pageMode.value = next
        liveScrollPermille = clamped
        _targetScrollPermille.value = clamped
        _scrollRequestToken.value = _scrollRequestToken.value + 1L
        viewModelScope.launch {
            repository.setPageMode(next)
        }
    }

    fun toggleFullScreen(preservePermille: Int = liveScrollPermille) {
        val clamped = preservePermille.coerceIn(0, 1000)
        _isFullScreen.value = !_isFullScreen.value
        liveScrollPermille = clamped
        _targetScrollPermille.value = clamped
        _scrollRequestToken.value = _scrollRequestToken.value + 1L
    }

    fun toggleProgressVisibility() {
        val next = if (_progressMode.value == ReaderProgressMode.HIDDEN) {
            ReaderProgressMode.BOOK
        } else {
            ReaderProgressMode.HIDDEN
        }
        _progressMode.value = next
        viewModelScope.launch {
            repository.setProgressMode(next)
        }
    }

    fun findBookmarkNear(scrollPermille: Int): BookBookmarkEntity? =
        _pageBookmarks.value.minByOrNull { abs(it.scrollPermille - scrollPermille) }
            ?.takeIf { abs(it.scrollPermille - scrollPermille) <= BOOKMARK_PROXIMITY_PERMILLE }

    fun toggleBookmarkAt(scrollPermille: Int, visibleSnippet: String) {
        val total = _pageCount.value
        if (total <= 0) return
        val page = _pageIndex.value
        val effectivePermille = if (_isTextMode.value) scrollPermille.coerceIn(0, 1000) else 0
        val fallbackSnippet = if (_isTextMode.value) {
            _extractedBook.value?.pages?.getOrNull(page)?.blocks?.firstOrNull { !it.isHeading }?.text
                ?: _extractedBook.value?.pages?.getOrNull(page)?.blocks?.firstOrNull()?.text
                ?: "PDF Page ${page + 1}"
        } else {
            "PDF Page ${page + 1}"
        }
        val snippet = visibleSnippet.ifBlank { fallbackSnippet }
        val matching = findBookmarkNear(effectivePermille)

        viewModelScope.launch {
            repository.toggleScrollBookmark(
                bookId = bookId,
                bookTitle = bookTitle,
                format = BookFormat.PDF,
                positionIndex = page,
                chapterTitle = "Page ${page + 1} of $total",
                scrollPermille = effectivePermille,
                snippet = snippet,
                matchingBookmark = matching,
            )
        }
    }

    fun onWordLongPressed(
        paragraphIndex: Int,
        fullParagraphText: String,
        charIndexInParagraph: Int,
        scrollPermille: Int,
    ) {
        val anchor = WordNoteUtils.extractWordAnchor(fullParagraphText, paragraphIndex, charIndexInParagraph)
            ?: return
        val wordText = WordNoteUtils.extractWordText(fullParagraphText, anchor)
        if (wordText.isBlank()) return

        val existing = WordNoteUtils.findNotesForAnchor(_pageNotes.value, anchor)
        _heldWord.value = HeldWordContext(
            anchor = anchor,
            wordText = wordText,
            scrollPermille = scrollPermille.coerceIn(0, 1000),
            existingNotes = existing,
        )

        lookupJob?.cancel()
        if (_lookupEnabled.value) {
            lookupJob = viewModelScope.launch {
                _isLookingUp.value = true
                _lookupResult.value = null
                val res = repository.lookupWord(fullParagraphText, charIndexInParagraph)
                _isLookingUp.value = false
                _lookupResult.value = res
            }
        } else {
            _isLookingUp.value = false
            _lookupResult.value = null
        }
    }

    fun dismissLookup() {
        lookupJob?.cancel()
        _isLookingUp.value = false
        _lookupResult.value = null
        _heldWord.value = null
    }

    fun startNoteWordSelection() {
        val held = _heldWord.value ?: return
        dismissLookup()
        noteSelectionScrollPermille = held.scrollPermille
        _selectedWordAnchors.value = listOf(held.anchor)
        _isSelectingNoteWords.value = true
    }

    fun toggleNoteWordAt(paragraphIndex: Int, fullParagraphText: String, charIndexInParagraph: Int): Boolean {
        val anchor = WordNoteUtils.extractWordAnchor(fullParagraphText, paragraphIndex, charIndexInParagraph)
            ?: return false
        _selectedWordAnchors.value = WordNoteUtils.toggleWordAnchor(_selectedWordAnchors.value, anchor)
        return true
    }

    fun cancelNoteWordSelection() {
        _isSelectingNoteWords.value = false
        _selectedWordAnchors.value = emptyList()
    }

    fun finishNoteWordSelection() {
        val anchors = _selectedWordAnchors.value
        val textPage = _extractedBook.value?.pages?.getOrNull(_pageIndex.value) ?: return
        if (anchors.isEmpty()) return
        val quoted = WordNoteUtils.buildQuotedExcerpt(
            anchors = anchors,
            paragraphLookup = { idx -> textPage.blocks.getOrNull(idx)?.text },
        )
        _isSelectingNoteWords.value = false
        _selectedWordAnchors.value = emptyList()
        _composingNote.value = ComposingNoteState(
            existingNote = null,
            anchors = anchors,
            quotedExcerpt = quoted,
            scrollPermille = noteSelectionScrollPermille,
        )
    }

    fun openExistingNoteEditor(note: BookNoteEntity) {
        dismissLookup()
        _composingNote.value = ComposingNoteState(
            existingNote = note,
            anchors = WordNoteUtils.decodeAnchors(note.anchorsEncoded),
            quotedExcerpt = note.quotedText,
            scrollPermille = note.scrollPermille,
        )
    }

    fun saveComposingNote(rawText: String) {
        val composing = _composingNote.value ?: return
        val idx = _pageIndex.value
        val trimmed = rawText.trim()
        val now = System.currentTimeMillis()
        _composingNote.value = null

        viewModelScope.launch {
            val existing = composing.existingNote
            if (trimmed.isEmpty()) {
                if (existing != null) repository.deleteNote(existing)
                return@launch
            }
            if (existing != null) {
                repository.saveNote(
                    existing.copy(
                        noteText = trimmed,
                        updatedAt = now,
                    )
                )
            } else {
                repository.saveNote(
                    BookNoteEntity(
                        bookId = bookId,
                        bookTitle = bookTitle,
                        format = BookFormat.PDF.name,
                        positionIndex = idx,
                        chapterTitle = "Page ${idx + 1}",
                        anchorsEncoded = WordNoteUtils.encodeAnchors(composing.anchors),
                        quotedText = composing.quotedExcerpt,
                        noteText = trimmed,
                        scrollPermille = composing.scrollPermille.coerceIn(0, 1000),
                        createdAt = now,
                        updatedAt = now,
                    )
                )
            }
        }
    }

    fun deleteComposingNote() {
        val existing = _composingNote.value?.existingNote
        _composingNote.value = null
        if (existing != null) {
            viewModelScope.launch { repository.deleteNote(existing) }
        }
    }

    fun dismissNoteComposer() {
        _composingNote.value = null
    }
}

class PdfReaderScreen(
    sealedActivity: SealedLightActivity,
    private val bookId: String,
    private val bookTitle: String,
    private val initialPage: Int? = null,
    private val initialScrollPermille: Int? = null,
    private val preferTextMode: Boolean = false,
) : LightScreen<Unit, PdfReaderViewModel>(sealedActivity) {

    private val vibrator = sealedActivity.vibrator
    private val isForegroundFlow = MutableStateFlow(true)

    override fun willShow() {
        super.willShow()
        isForegroundFlow.value = true
    }

    override fun willHide() {
        isForegroundFlow.value = false
        super.willHide()
    }

    override fun onAppPause() {
        isForegroundFlow.value = false
        super.onAppPause()
    }

    private val repository = ReaderRepository.getInstance(
        dbProvider = {
            lightContext.buildDatabase(
                ReaderDatabase::class.java,
                ReaderRepository.DATABASE_NAME,
            ) {
                addMigrations(
                    ReaderDatabase.MIGRATION_2_3,
                    ReaderDatabase.MIGRATION_3_4,
                )
                fallbackToDestructiveMigration(true)
            }
        },
        filesDir = lightContext.filesDir,
    )

    override val viewModelClass: Class<PdfReaderViewModel>
        get() = PdfReaderViewModel::class.java

    override fun createViewModel() = PdfReaderViewModel(
        bookId = bookId,
        bookTitle = bookTitle,
        explicitStartPage = initialPage,
        explicitStartScrollPermille = initialScrollPermille,
        preferTextMode = preferTextMode,
        repository = repository,
    )

    @Composable
    override fun Content() {
        val themeColors by LightThemeController.colors.collectAsState()
        val isForeground by isForegroundFlow.collectAsState()
        val pageIndex by viewModel.pageIndex.collectAsState()
        val pageCount by viewModel.pageCount.collectAsState()
        val targetScrollPermille by viewModel.targetScrollPermille.collectAsState()
        val scrollRequestToken by viewModel.scrollRequestToken.collectAsState()
        val renderedPage by viewModel.renderedPage.collectAsState()
        val crispTile by viewModel.crispTile.collectAsState()
        val isLoading by viewModel.isLoading.collectAsState()
        val errorText by viewModel.errorText.collectAsState()
        val pageBookmarks by viewModel.pageBookmarks.collectAsState()
        val pageNotes by viewModel.pageNotes.collectAsState()
        val heldWord by viewModel.heldWord.collectAsState()
        val isSelectingNoteWords by viewModel.isSelectingNoteWords.collectAsState()
        val selectedWordAnchors by viewModel.selectedWordAnchors.collectAsState()
        val composingNote by viewModel.composingNote.collectAsState()
        val invertColors by viewModel.invertColors.collectAsState()
        val rotationDegrees by viewModel.rotationDegrees.collectAsState()
        val progressMode by viewModel.progressMode.collectAsState()

        val isTextMode by viewModel.isTextMode.collectAsState()
        val isExtractingText by viewModel.isExtractingText.collectAsState()
        val extractionProgressText by viewModel.extractionProgressText.collectAsState()
        val textNotice by viewModel.textNotice.collectAsState()
        val extractedBook by viewModel.extractedBook.collectAsState()
        val extractionMode by viewModel.extractionMode.collectAsState()
        val fontSize by viewModel.fontSize.collectAsState()
        val pageMode by viewModel.pageMode.collectAsState()
        val isFullScreen by viewModel.isFullScreen.collectAsState()
        val lookupEnabled by viewModel.lookupEnabled.collectAsState()
        val isLookingUp by viewModel.isLookingUp.collectAsState()
        val lookupResult by viewModel.lookupResult.collectAsState()
        val keyboardOptionsFlow = rememberKeyboardOptions()

        val savedNoteAnchors = remember(pageNotes) {
            pageNotes.flatMap { WordNoteUtils.decodeAnchors(it.anchorsEncoded) }
        }

        val density = LocalDensity.current
        val haptics = LocalHapticFeedback.current
        val edgeBackThresholdPx = with(density) { 44.dp.toPx() }
        var showJumpBar by remember { mutableStateOf(false) }
        var showExtractPanel by remember { mutableStateOf(false) }
        var boxSize by remember { mutableStateOf(IntSize.Zero) }

        val textPage = extractedBook?.pages?.getOrNull(pageIndex)
        val textScrollState = rememberScrollState()
        val blockOffsets = remember(pageIndex, fontSize, isTextMode) { mutableStateMapOf<Int, Int>() }

        val textVariant = when (fontSize) {
            ReaderFontSize.COMPACT -> LightTextVariant.Detail
            ReaderFontSize.REGULAR -> LightTextVariant.Paragraph
            ReaderFontSize.LARGE -> LightTextVariant.Copy
        }

        val textMeasurer = rememberTextMeasurer()
        val bodyStyle = scaledReaderStyle(textVariant)
        val headingStyle = scaledReaderStyle(LightTextVariant.Subheading)
        val horizontalPadDp = 1.5f.gridUnitsAsDp()
        val horizontalPadPx = with(density) { horizontalPadDp.roundToPx() }
        val verticalPagePadPx = with(density) { (if (isFullScreen) 24.dp else 12.dp).roundToPx() }
        var textViewportSize by remember { mutableStateOf(IntSize.Zero) }

        val textBlocks = remember(textPage) {
            textPage?.blocks?.map { TextBlock(text = it.text, isHeading = it.isHeading, isQuote = false) }.orEmpty()
        }

        val pagedPages = remember(
            textBlocks,
            isTextMode,
            pageMode,
            bodyStyle,
            headingStyle,
            textViewportSize,
            horizontalPadPx,
            verticalPagePadPx,
        ) {
            if (!isTextMode || pageMode != ReaderPageMode.PAGED || textBlocks.isEmpty() ||
                textViewportSize.width <= 0 || textViewportSize.height <= 0
            ) {
                emptyList()
            } else {
                EReaderPaginator.paginateBlocks(
                    blocks = textBlocks,
                    textMeasurer = textMeasurer,
                    bodyStyle = bodyStyle,
                    headingStyle = headingStyle,
                    viewportWidthPx = (textViewportSize.width - horizontalPadPx * 2).coerceAtLeast(100),
                    viewportHeightPx = (textViewportSize.height - verticalPagePadPx).coerceAtLeast(100),
                    density = density,
                )
            }
        }

        var currentSubPageIndex by remember(pageIndex) { mutableIntStateOf(0) }
        val safeSubPageIndex = if (pagedPages.isEmpty()) 0 else currentSubPageIndex.coerceIn(0, pagedPages.lastIndex)

        // Read scroll permille lazily so scrolling long PDF text pages never recomposes the full screen
        fun readScrollModePermille(): Int {
            val max = textScrollState.maxValue
            return if (max <= 0) 0 else ((textScrollState.value.toFloat() / max.toFloat()) * 1000f).roundToInt().coerceIn(0, 1000)
        }

        fun readActiveTextPermille(): Int = when {
            !isTextMode -> 0
            pageMode == ReaderPageMode.PAGED -> pagedPages.getOrNull(safeSubPageIndex)?.permilleStart ?: targetScrollPermille
            else -> readScrollModePermille()
        }

        val isNearSavedBookmark by remember(textScrollState, pageBookmarks, isTextMode, pageMode, safeSubPageIndex, pagedPages, targetScrollPermille) {
            derivedStateOf {
                val target = if (isTextMode) readActiveTextPermille() else 0
                pageBookmarks.any { abs(it.scrollPermille - target) <= BOOKMARK_PROXIMITY_PERMILLE }
            }
        }

        // Restore exact scroll target in PDF Text SCROLL mode
        LaunchedEffect(pageIndex, scrollRequestToken, isTextMode, pageMode, textPage != null) {
            if (!isTextMode || pageMode != ReaderPageMode.SCROLL || textPage == null) return@LaunchedEffect
            val target = targetScrollPermille
            if (target <= 0) {
                textScrollState.scrollTo(0)
            } else {
                val maxVal = if (textScrollState.maxValue > 0) {
                    textScrollState.maxValue
                } else {
                    withTimeoutOrNull(800L) {
                        snapshotFlow { textScrollState.maxValue }.first { it > 0 }
                    } ?: textScrollState.maxValue
                }
                val targetPx = ((target / 1000f) * maxVal).roundToInt().coerceIn(0, maxVal)
                textScrollState.scrollTo(targetPx)
            }
        }

        // Restore exact sub-page in PDF Text PAGED mode
        LaunchedEffect(pageIndex, scrollRequestToken, isTextMode, pageMode, pagedPages) {
            if (!isTextMode || pageMode != ReaderPageMode.PAGED || pagedPages.isEmpty()) return@LaunchedEffect
            currentSubPageIndex = EReaderPaginator.pageIndexForPermille(pagedPages, targetScrollPermille)
        }

        // Persist scroll position in PDF Text SCROLL mode
        LaunchedEffect(pageIndex, isTextMode, pageMode, textScrollState) {
            if (!isTextMode || pageMode != ReaderPageMode.SCROLL) return@LaunchedEffect
            snapshotFlow { readScrollModePermille() }
                .distinctUntilChanged()
                .collect { permille ->
                    viewModel.onTextScrollChanged(permille)
                }
        }

        // Persist sub-page position in PDF Text PAGED mode
        LaunchedEffect(pageIndex, isTextMode, pageMode, safeSubPageIndex, pagedPages) {
            if (!isTextMode || pageMode != ReaderPageMode.PAGED || pagedPages.isEmpty()) return@LaunchedEffect
            val permille = pagedPages.getOrNull(safeSubPageIndex)?.permilleStart ?: 0
            viewModel.onTextScrollChanged(permille)
        }

        fun findVisibleTextSnippet(): String {
            if (!isTextMode) return "PDF Page ${pageIndex + 1}"
            if (pageMode == ReaderPageMode.PAGED) {
                return pagedPages.getOrNull(safeSubPageIndex)?.previewSnippet.orEmpty()
            }
            val blocks = textPage?.blocks.orEmpty()
            if (blocks.isEmpty()) return ""
            val scrollY = textScrollState.value
            val bestIdx = blockOffsets.entries
                .filter { it.value <= scrollY + 48 }
                .maxByOrNull { it.value }
                ?.key
                ?: blockOffsets.entries.minByOrNull { abs(it.value - scrollY) }?.key
                ?: ((readScrollModePermille() / 1000f) * blocks.lastIndex).roundToInt()
                    .coerceIn(0, blocks.lastIndex)
            return blocks.getOrNull(bestIdx)?.text.orEmpty()
        }

        val hasPrev = pageIndex > 0
        val hasNext = pageCount > 0 && pageIndex < pageCount - 1

        fun turnNextInTextMode() {
            if (pageMode == ReaderPageMode.PAGED) {
                if (safeSubPageIndex < pagedPages.lastIndex) {
                    currentSubPageIndex = safeSubPageIndex + 1
                } else if (hasNext) {
                    viewModel.nextPage(landOnStart = true)
                }
            } else if (hasNext) {
                viewModel.nextPage()
            }
        }

        fun turnPrevInTextMode() {
            if (pageMode == ReaderPageMode.PAGED) {
                if (safeSubPageIndex > 0) {
                    currentSubPageIndex = safeSubPageIndex - 1
                } else if (hasPrev) {
                    viewModel.previousPage(landOnEnd = true)
                } else {
                    goBack()
                }
            } else if (hasPrev) {
                viewModel.previousPage()
            } else {
                goBack()
            }
        }

        var scale by remember { mutableFloatStateOf(1f) }
        var offsetX by remember { mutableFloatStateOf(0f) }
        var offsetY by remember { mutableFloatStateOf(0f) }
        var isGesturing by remember { mutableStateOf(false) }
        var initializedKey by remember { mutableStateOf("") }

        val boxW = boxSize.width.toFloat()
        val boxH = boxSize.height.toFloat()
        val pageW = (renderedPage?.pageWidthPt ?: 1).toFloat().coerceAtLeast(1f)
        val pageH = (renderedPage?.pageHeightPt ?: 1).toFloat().coerceAtLeast(1f)

        val baseScale = if (boxW > 0f && boxH > 0f) min(boxW / pageW, boxH / pageH) else 1f
        val baseW = pageW * baseScale
        val baseH = pageH * baseScale

        val fitWidthScale = if (baseW > 0f && boxW > 0f) {
            (boxW / baseW).coerceIn(MIN_PDF_SCALE, 4.0f)
        } else {
            1.0f
        }

        fun maxPanXFor(s: Float): Float = (((baseW * s) - boxW) / 2f).coerceAtLeast(0f)
        fun maxPanYFor(s: Float): Float = (((baseH * s) - boxH) / 2f).coerceAtLeast(0f)
        fun clampX(x: Float, s: Float): Float = x.coerceIn(-maxPanXFor(s), maxPanXFor(s))
        fun clampY(y: Float, s: Float): Float = y.coerceIn(-maxPanYFor(s), maxPanYFor(s))

        fun applyCenteredZoom(targetScale: Float, focalPoint: Offset? = null) {
            val clampedScale = targetScale.coerceIn(MIN_PDF_SCALE, MAX_PDF_SCALE)
            val oldScale = scale.coerceAtLeast(0.01f)
            val ratio = clampedScale / oldScale
            val relX = if (focalPoint != null) focalPoint.x - boxW / 2f else 0f
            val relY = if (focalPoint != null) focalPoint.y - boxH / 2f else 0f
            val nextX = clampX((offsetX - relX) * ratio + relX, clampedScale)
            val nextY = clampY((offsetY - relY) * ratio + relY, clampedScale)
            scale = clampedScale
            offsetX = nextX
            offsetY = nextY
        }

        fun resetToFitWidthTop() {
            scale = fitWidthScale
            offsetX = 0f
            offsetY = maxPanYFor(fitWidthScale)
        }

        fun resetToFitPage() {
            scale = MIN_PDF_SCALE
            offsetX = 0f
            offsetY = 0f
        }

        val currentInitKey = renderedPage?.let { "$pageIndex|${it.rotationDegrees}|$isTextMode" }.orEmpty()
        LaunchedEffect(currentInitKey, boxSize, isTextMode) {
            if (!isTextMode && renderedPage != null && boxSize.width > 0 && boxSize.height > 0 && initializedKey != currentInitKey) {
                initializedKey = currentInitKey
                resetToFitWidthTop()
            }
        }

        LaunchedEffect(pageIndex, renderedPage, scale, offsetX, offsetY, isGesturing, boxSize, isTextMode) {
            val page = renderedPage
            if (isTextMode || isGesturing || page == null || boxSize.width <= 0 || boxSize.height <= 0 || scale <= 1.35f) {
                viewModel.clearCrispTile()
                return@LaunchedEffect
            }
            val totalScale = baseScale * scale
            val tx = (boxW - baseW * scale) / 2f + offsetX
            val ty = (boxH - baseH * scale) / 2f + offsetY
            viewModel.requestCrispTile(
                pageIndex = pageIndex,
                rotationDegrees = page.rotationDegrees,
                viewportWidthPx = boxSize.width,
                viewportHeightPx = boxSize.height,
                totalScale = totalScale,
                translateX = tx,
                translateY = ty,
                scaleKey = scale,
                offsetXKey = offsetX,
                offsetYKey = offsetY,
            )
        }

        val liveScale = rememberUpdatedState(scale)
        val liveOffsetX = rememberUpdatedState(offsetX)
        val liveOffsetY = rememberUpdatedState(offsetY)
        val liveBoxW = rememberUpdatedState(boxW)
        val liveBoxH = rememberUpdatedState(boxH)
        val liveBaseW = rememberUpdatedState(baseW)
        val liveBaseH = rememberUpdatedState(baseH)
        val liveFitWidthScale = rememberUpdatedState(fitWidthScale)
        val livePageIndex = rememberUpdatedState(pageIndex)
        val livePageCount = rememberUpdatedState(pageCount)

        val contentColor = LightThemeTokens.colors.content
        val bookmarkPainter = rememberVectorPainter(
            remember(isNearSavedBookmark, contentColor) {
                if (isNearSavedBookmark) createBookmarkFilledVector(contentColor)
                else createBookmarkOutlineVector(contentColor)
            }
        )

        val isAtFitPage = abs(scale - MIN_PDF_SCALE) < 0.06f
        val isAtFitWidth = fitWidthScale > 1.08f && abs(scale - fitWidthScale) < 0.08f
        val zoomBadgeLabel = when {
            isAtFitWidth -> "Fit W"
            isAtFitPage -> "Fit"
            else -> String.format(Locale.US, "%.1fx", scale)
        }

        val isOverlayOpen = heldWord != null || isLookingUp || lookupResult != null
        val hideChrome = isTextMode && isFullScreen && !isExtractingText && !isSelectingNoteWords

        LightTheme(colors = themeColors) {
            val activeComposing = composingNote
            if (activeComposing != null) {
                val existingText = activeComposing.existingNote?.noteText.orEmpty()
                val noteState = remember(activeComposing) {
                    TextFieldState(
                        initialText = existingText,
                        initialSelection = TextRange(existingText.length),
                    )
                }
                ReaderNoteComposer(
                    title = "Page ${pageIndex + 1}",
                    quotedExcerpt = activeComposing.quotedExcerpt,
                    state = noteState,
                    keyboardOptionsFlow = keyboardOptionsFlow,
                    vibrator = vibrator,
                    onSave = { viewModel.saveComposingNote(it) },
                    onBack = { viewModel.dismissNoteComposer() },
                    onDelete = if (activeComposing.existingNote != null) {
                        { viewModel.deleteComposingNote() }
                    } else {
                        null
                    },
                    editorKey = activeComposing.existingNote?.id ?: activeComposing.quotedExcerpt,
                    isForeground = isForeground,
                )
                return@LightTheme
            }

            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(LightThemeTokens.colors.background),
            ) {
                Column(modifier = Modifier.fillMaxSize()) {
                    if (isSelectingNoteWords) {
                        val selectedCount = selectedWordAnchors.size
                        LightTopBar(
                            leftButton = LightBarButton.Text(
                                text = "Cancel",
                                onClick = { viewModel.cancelNoteWordSelection() },
                            ),
                            center = LightTopBarCenter.Text(
                                text = if (selectedCount > 0) "Where? ($selectedCount)" else "Where?",
                            ),
                            rightButton = LightBarButton.Text(
                                text = "Done",
                                onClick = {
                                    if (selectedWordAnchors.isNotEmpty()) {
                                        viewModel.finishNoteWordSelection()
                                    }
                                },
                            ),
                            modifier = Modifier.padding(bottom = 0.25f.gridUnitsAsDp()),
                        )
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = horizontalPadDp, vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            LightText(
                                text = if (selectedCount > 0) {
                                    "Tap words to underline or remove, then tap Done"
                                } else {
                                    "Tap at least one word to underline"
                                },
                                variant = LightTextVariant.Detail,
                                lighten = true,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        HorizontalDivider(
                            color = LightThemeTokens.colors.content.copy(alpha = 0.2f),
                            thickness = 1.dp,
                        )
                    } else if (!hideChrome) {
                        BatteryTopBar(
                            leftButton = LightBarButton.LightIcon(
                                icon = LightIcons.BACK,
                                onClick = { goBack() },
                            ),
                            center = LightTopBarCenter.Text(bookTitle),
                            rightButton = LightBarButton.Icon(
                                painter = bookmarkPainter,
                                contentDescription = if (isNearSavedBookmark) "Bookmarked here" else "Bookmark this spot",
                                onClick = {
                                    viewModel.toggleBookmarkAt(
                                        scrollPermille = readActiveTextPermille(),
                                        visibleSnippet = findVisibleTextSnippet(),
                                    )
                                },
                            ),
                            isForeground = isForeground,
                            modifier = Modifier.padding(bottom = 0.25f.gridUnitsAsDp()),
                        )

                        // Top PDF Control Strip
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 1.0f.gridUnitsAsDp(), vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            val pageLabel = if (pageCount > 0) "P. ${pageIndex + 1}/$pageCount" else "Loading..."
                            Box(
                                modifier = Modifier
                                    .border(
                                        width = 1.dp,
                                        color = LightThemeTokens.colors.content.copy(alpha = 0.45f),
                                        shape = RoundedCornerShape(4.dp),
                                    )
                                    .lightClickable { showJumpBar = !showJumpBar }
                                    .padding(horizontal = 6.dp, vertical = 3.dp),
                            ) {
                                LightText(text = pageLabel, variant = LightTextVariant.Detail)
                            }

                            Spacer(modifier = Modifier.weight(1f))

                            if (isTextMode) {
                                PdfControlChip(
                                    label = "Page",
                                    highlighted = false,
                                    onClick = {
                                        showExtractPanel = false
                                        viewModel.exitTextMode()
                                    },
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                                PdfControlChip(
                                    label = pageMode.chipLabel,
                                    highlighted = pageMode == ReaderPageMode.PAGED,
                                    onClick = { viewModel.togglePageMode(readActiveTextPermille()) },
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                                PdfControlChip(
                                    label = "Fix",
                                    highlighted = showExtractPanel,
                                    onClick = {
                                        showExtractPanel = !showExtractPanel
                                        if (showExtractPanel) showJumpBar = false
                                    },
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                                PdfControlChip(
                                    label = fontSize.displayLabel,
                                    highlighted = false,
                                    onClick = { viewModel.cycleFontSize(readActiveTextPermille()) },
                                )
                            } else {
                                PdfControlChip(
                                    label = "Text",
                                    highlighted = isExtractingText,
                                    onClick = { viewModel.enterTextMode() },
                                )
                                Spacer(modifier = Modifier.width(3.dp))
                                PdfControlChip(
                                    label = "−",
                                    highlighted = false,
                                    onClick = { applyCenteredZoom(scale / 1.35f) },
                                )
                                Spacer(modifier = Modifier.width(3.dp))
                                PdfControlChip(
                                    label = zoomBadgeLabel,
                                    highlighted = !isAtFitPage && !isAtFitWidth,
                                    onClick = {
                                        if (isAtFitWidth) resetToFitPage() else resetToFitWidthTop()
                                    },
                                )
                                Spacer(modifier = Modifier.width(3.dp))
                                PdfControlChip(
                                    label = "+",
                                    highlighted = false,
                                    onClick = { applyCenteredZoom(scale * 1.35f) },
                                )
                                Spacer(modifier = Modifier.width(3.dp))
                                PdfControlChip(
                                    label = if (rotationDegrees == 0) "↻" else "${rotationDegrees}°",
                                    highlighted = rotationDegrees != 0,
                                    onClick = { viewModel.rotateClockwise() },
                                )
                                Spacer(modifier = Modifier.width(3.dp))
                                PdfControlChip(
                                    label = "Inv",
                                    highlighted = invertColors,
                                    onClick = { viewModel.toggleInvert() },
                                )
                            }
                        }

                        // Text Extraction Retry & Layout Mode Panel (for boxes, sidebars & proofreading)
                        if (isTextMode && showExtractPanel && !isExtractingText) {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 1.0f.gridUnitsAsDp(), vertical = 4.dp)
                                    .border(
                                        width = 1.dp,
                                        color = LightThemeTokens.colors.content.copy(alpha = 0.45f),
                                        shape = RoundedCornerShape(6.dp),
                                    )
                                    .padding(horizontal = 10.dp, vertical = 8.dp),
                                verticalArrangement = Arrangement.spacedBy(6.dp),
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                ) {
                                    LightText(
                                        text = "Text Layout: ${extractionMode.displayName}",
                                        variant = LightTextVariant.Detail,
                                    )
                                    LightText(
                                        text = "✕",
                                        variant = LightTextVariant.Detail,
                                        modifier = Modifier
                                            .lightClickable { showExtractPanel = false }
                                            .padding(horizontal = 4.dp),
                                    )
                                }
                                LightText(
                                    text = extractionMode.description,
                                    variant = LightTextVariant.Detail,
                                    lighten = true,
                                )
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                                ) {
                                    PdfExtractionMode.entries.forEach { mode ->
                                        PdfControlChip(
                                            label = mode.shortLabel,
                                            highlighted = mode == extractionMode,
                                            onClick = {
                                                showExtractPanel = false
                                                viewModel.reextractWithMode(mode)
                                            },
                                        )
                                    }
                                }
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                                ) {
                                    PdfControlChip(
                                        label = "Retry Extract",
                                        highlighted = false,
                                        onClick = {
                                            showExtractPanel = false
                                            viewModel.reextractWithMode(extractionMode)
                                        },
                                    )
                                    PdfControlChip(
                                        label = "Compare PDF Page",
                                        highlighted = false,
                                        onClick = {
                                            showExtractPanel = false
                                            viewModel.exitTextMode()
                                        },
                                    )
                                }
                            }
                        }

                        // Optional Quick Page Jump Bar
                        if (showJumpBar && pageCount > 1) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 1.0f.gridUnitsAsDp(), vertical = 4.dp),
                                horizontalArrangement = Arrangement.spacedBy(5.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                PdfControlChip("First", false) { viewModel.openPage(0, explicitScrollPermille = 0) }
                                PdfControlChip("-10", false) { viewModel.jumpRelative(-10) }
                                PdfControlChip("-1", false) { viewModel.jumpRelative(-1) }
                                PdfControlChip("+1", false) { viewModel.jumpRelative(1) }
                                PdfControlChip("+10", false) { viewModel.jumpRelative(10) }
                                PdfControlChip("Last", false) { viewModel.openPage(pageCount - 1, explicitScrollPermille = 0) }
                            }
                        }

                        // Friendly dismissible notice if PDF has no selectable text or is encrypted
                        if (textNotice != null) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 1.0f.gridUnitsAsDp(), vertical = 4.dp)
                                    .border(
                                        width = 1.dp,
                                        color = LightThemeTokens.colors.content.copy(alpha = 0.5f),
                                        shape = RoundedCornerShape(6.dp),
                                    )
                                    .padding(horizontal = 10.dp, vertical = 7.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                LightText(
                                    text = textNotice.orEmpty(),
                                    variant = LightTextVariant.Detail,
                                    modifier = Modifier.weight(1f),
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                LightText(
                                    text = "OK",
                                    variant = LightTextVariant.Detail,
                                    modifier = Modifier
                                        .border(
                                            width = 1.dp,
                                            color = LightThemeTokens.colors.content.copy(alpha = 0.55f),
                                            shape = RoundedCornerShape(4.dp),
                                        )
                                        .lightClickable { viewModel.dismissTextNotice() }
                                        .padding(horizontal = 8.dp, vertical = 3.dp),
                                )
                            }
                        }
                    }

                    // Main Reading Surface: Extraction Loader OR Book Text Mode OR Interactive PDF Page Mode
                    when {
                        isExtractingText -> {
                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .fillMaxWidth()
                                    .padding(2f.gridUnitsAsDp()),
                                contentAlignment = Alignment.Center,
                            ) {
                                Column(
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                    verticalArrangement = Arrangement.spacedBy(12.dp),
                                ) {
                                    LightText(
                                        text = "Extracting book text...",
                                        variant = LightTextVariant.Subheading,
                                    )
                                    extractionProgressText?.let { progress ->
                                        LightText(
                                            text = progress,
                                            variant = LightTextVariant.Detail,
                                            lighten = true,
                                        )
                                    }
                                    Spacer(modifier = Modifier.height(6.dp))
                                    PdfControlChip(
                                        label = "Cancel",
                                        highlighted = false,
                                        onClick = { viewModel.cancelTextExtraction() },
                                    )
                                }
                            }
                        }

                        isTextMode -> {
                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .fillMaxWidth()
                                    .onSizeChanged { textViewportSize = it }
                                    .readerHorizontalSwipes(
                                        enabled = !isOverlayOpen && !isSelectingNoteWords,
                                        onEdgeSwipeBack = { goBack() },
                                        onSwipeRight = { turnPrevInTextMode() },
                                        onSwipeLeft = { turnNextInTextMode() },
                                    )
                                    .pointerInput(isOverlayOpen, isSelectingNoteWords) {
                                        if (isOverlayOpen || isSelectingNoteWords) return@pointerInput
                                        detectTapGestures(
                                            onDoubleTap = {
                                                viewModel.toggleFullScreen(readActiveTextPermille())
                                            },
                                        )
                                    },
                            ) {
                                if (textPage == null || textPage.blocks.isEmpty()) {
                                    Box(
                                        modifier = Modifier
                                            .fillMaxSize()
                                            .padding(2f.gridUnitsAsDp()),
                                        contentAlignment = Alignment.Center,
                                    ) {
                                        Column(
                                            horizontalAlignment = Alignment.CenterHorizontally,
                                            verticalArrangement = Arrangement.spacedBy(12.dp),
                                        ) {
                                            LightText(
                                                text = "No text on page ${pageIndex + 1}",
                                                variant = LightTextVariant.Subheading,
                                            )
                                            LightText(
                                                text = "This page may be a cover, diagram, or blank page.",
                                                variant = LightTextVariant.Detail,
                                                lighten = true,
                                            )
                                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                                PdfControlChip(
                                                    label = "View PDF Page",
                                                    highlighted = false,
                                                    onClick = { viewModel.exitTextMode() },
                                                )
                                                PdfControlChip(
                                                    label = "Retry Text",
                                                    highlighted = false,
                                                    onClick = { viewModel.reextractWithMode(extractionMode) },
                                                )
                                            }
                                        }
                                    }
                                } else if (pageMode == ReaderPageMode.PAGED) {
                                    val currentSubPage = pagedPages.getOrNull(safeSubPageIndex)
                                    Column(
                                        modifier = Modifier
                                            .fillMaxSize()
                                            .padding(
                                                horizontal = horizontalPadDp,
                                                vertical = if (isFullScreen && !isSelectingNoteWords) 12.dp else 6.dp,
                                            ),
                                    ) {
                                        currentSubPage?.slices?.forEach { slice ->
                                            val fullBlockText = textPage.blocks.getOrNull(slice.sourceIndex)?.text ?: slice.text
                                            if (slice.isHeading) {
                                                LightText(
                                                    text = slice.text,
                                                    variant = LightTextVariant.Subheading,
                                                    modifier = Modifier
                                                        .fillMaxWidth()
                                                        .padding(
                                                            top = if (slice.isContinuation) 2.dp else 10.dp,
                                                            bottom = 6.dp,
                                                        ),
                                                )
                                            } else {
                                                LookupableReaderText(
                                                    text = slice.text,
                                                    paragraphIndex = slice.sourceIndex,
                                                    sliceOffsetInParagraph = slice.charOffsetInBlock,
                                                    savedAnchors = savedNoteAnchors,
                                                    selectedAnchors = selectedWordAnchors,
                                                    isSelectingWords = isSelectingNoteWords,
                                                    variant = textVariant,
                                                    lighten = false,
                                                    onTapChar = { offset ->
                                                        if (viewModel.toggleNoteWordAt(
                                                                paragraphIndex = slice.sourceIndex,
                                                                fullParagraphText = fullBlockText,
                                                                charIndexInParagraph = slice.charOffsetInBlock + offset,
                                                            )
                                                        ) {
                                                            runCatching { vibrator.click() }
                                                        }
                                                    },
                                                    onLongPressChar = { offset ->
                                                        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                                        viewModel.onWordLongPressed(
                                                            paragraphIndex = slice.sourceIndex,
                                                            fullParagraphText = fullBlockText,
                                                            charIndexInParagraph = slice.charOffsetInBlock + offset,
                                                            scrollPermille = readActiveTextPermille(),
                                                        )
                                                    },
                                                    onDoubleTap = {
                                                        viewModel.toggleFullScreen(readActiveTextPermille())
                                                    },
                                                    modifier = Modifier
                                                        .fillMaxWidth()
                                                        .padding(
                                                            top = if (slice.isContinuation) 2.dp else 4.dp,
                                                            bottom = 10.dp,
                                                        ),
                                                )
                                            }
                                        }
                                    }
                                } else {
                                    LightScrollView(
                                        scrollState = textScrollState,
                                        modifier = Modifier
                                            .fillMaxSize()
                                            .padding(
                                                start = horizontalPadDp,
                                                top = if (isFullScreen && !isSelectingNoteWords) 10.dp else 0.dp,
                                                bottom = if (isFullScreen && !isSelectingNoteWords) 10.dp else 0.dp,
                                            ),
                                    ) {
                                        Spacer(modifier = Modifier.height(10.dp))

                                        textPage.blocks.forEachIndexed { idx, block ->
                                            val trackOffsetModifier = Modifier.onGloballyPositioned { coords ->
                                                blockOffsets[idx] = coords.positionInParent().y.roundToInt()
                                            }
                                            if (block.isHeading) {
                                                LightText(
                                                    text = block.text,
                                                    variant = LightTextVariant.Subheading,
                                                    modifier = trackOffsetModifier
                                                        .fillMaxWidth()
                                                        .padding(end = horizontalPadDp, top = 12.dp, bottom = 8.dp),
                                                )
                                            } else {
                                                LookupableReaderText(
                                                    text = block.text,
                                                    paragraphIndex = idx,
                                                    sliceOffsetInParagraph = 0,
                                                    savedAnchors = savedNoteAnchors,
                                                    selectedAnchors = selectedWordAnchors,
                                                    isSelectingWords = isSelectingNoteWords,
                                                    variant = textVariant,
                                                    lighten = false,
                                                    onTapChar = { offset ->
                                                        if (viewModel.toggleNoteWordAt(
                                                                paragraphIndex = idx,
                                                                fullParagraphText = block.text,
                                                                charIndexInParagraph = offset,
                                                            )
                                                        ) {
                                                            runCatching { vibrator.click() }
                                                        }
                                                    },
                                                    onLongPressChar = { offset ->
                                                        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                                        viewModel.onWordLongPressed(
                                                            paragraphIndex = idx,
                                                            fullParagraphText = block.text,
                                                            charIndexInParagraph = offset,
                                                            scrollPermille = readActiveTextPermille(),
                                                        )
                                                    },
                                                    onDoubleTap = {
                                                        viewModel.toggleFullScreen(readActiveTextPermille())
                                                    },
                                                    modifier = trackOffsetModifier
                                                        .fillMaxWidth()
                                                        .padding(end = horizontalPadDp, top = 4.dp, bottom = 10.dp),
                                                )
                                            }
                                        }

                                        Spacer(modifier = Modifier.height(20.dp))
                                    }
                                }
                            }
                        }

                        else -> {
                            // Interactive PDF Visual Page Canvas (Pinch 1x-8x, Double-Tap Zoom, Rotate, Crisp Vector Tile)
                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .fillMaxWidth()
                                    .clipToBounds()
                                    .background(LightThemeTokens.colors.background)
                                    .onSizeChanged { boxSize = it }
                                    .pointerInput(pageIndex) {
                                        detectTapGestures(
                                            onDoubleTap = { tapOffset ->
                                                val curScale = liveScale.value
                                                val fw = liveFitWidthScale.value
                                                val bw = liveBoxW.value
                                                val bh = liveBoxH.value
                                                val bW = liveBaseW.value
                                                val bH = liveBaseH.value
                                                if (bw <= 0f || bh <= 0f) return@detectTapGestures

                                                val zoomInThreshold = max(fw * 1.35f, 2.2f)
                                                val targetScale = if (curScale < zoomInThreshold) {
                                                    max(fw * 2.2f, 2.8f).coerceAtMost(4.5f)
                                                } else {
                                                    fw
                                                }

                                                val ratio = targetScale / curScale.coerceAtLeast(0.01f)
                                                val relX = tapOffset.x - bw / 2f
                                                val relY = tapOffset.y - bh / 2f
                                                val maxX = (((bW * targetScale) - bw) / 2f).coerceAtLeast(0f)
                                                val maxY = (((bH * targetScale) - bh) / 2f).coerceAtLeast(0f)
                                                val nextX = ((liveOffsetX.value - relX) * ratio + relX).coerceIn(-maxX, maxX)
                                                val nextY = ((liveOffsetY.value - relY) * ratio + relY).coerceIn(-maxY, maxY)
                                                scale = targetScale
                                                offsetX = nextX
                                                offsetY = nextY
                                            },
                                        )
                                    }
                                    .pointerInput(pageIndex) {
                                        awaitEachGesture {
                                            val firstDown = awaitFirstDown(requireUnconsumed = false)
                                            val startX = firstDown.position.x
                                            var isMultiTouch = false
                                            var pastSlop = false
                                            var totalDx = 0f
                                            var totalDy = 0f

                                            try {
                                                while (true) {
                                                    val event = awaitPointerEvent()
                                                    val pressed = event.changes.filter { it.pressed }
                                                    if (pressed.isEmpty()) break

                                                    val bw = liveBoxW.value
                                                    val bh = liveBoxH.value
                                                    val bW = liveBaseW.value
                                                    val bH = liveBaseH.value

                                                    if (pressed.size >= 2) {
                                                        isMultiTouch = true
                                                        isGesturing = true
                                                        val zoomChange = event.calculateZoom()
                                                        val panChange = event.calculatePan()
                                                        val centroid = event.calculateCentroid(useCurrent = true)

                                                        if (bw > 0f && bh > 0f && centroid != Offset.Unspecified) {
                                                            val curScale = liveScale.value
                                                            val nextScale = (curScale * zoomChange).coerceIn(MIN_PDF_SCALE, MAX_PDF_SCALE)
                                                            val ratio = nextScale / curScale.coerceAtLeast(0.01f)
                                                            val relX = centroid.x - bw / 2f
                                                            val relY = centroid.y - bh / 2f
                                                            val maxX = (((bW * nextScale) - bw) / 2f).coerceAtLeast(0f)
                                                            val maxY = (((bH * nextScale) - bh) / 2f).coerceAtLeast(0f)
                                                            val nextX = ((liveOffsetX.value - relX) * ratio + relX + panChange.x)
                                                                .coerceIn(-maxX, maxX)
                                                            val nextY = ((liveOffsetY.value - relY) * ratio + relY + panChange.y)
                                                                .coerceIn(-maxY, maxY)

                                                            scale = nextScale
                                                            offsetX = nextX
                                                            offsetY = nextY
                                                        }
                                                        pressed.forEach { it.consume() }
                                                    } else if (!isMultiTouch) {
                                                        val change = pressed.first()
                                                        val delta = change.positionChange()
                                                        totalDx += delta.x
                                                        totalDy += delta.y

                                                        if (!pastSlop && hypot(totalDx, totalDy) > viewConfiguration.touchSlop) {
                                                            pastSlop = true
                                                            isGesturing = true
                                                        }

                                                        if (pastSlop && bw > 0f && bh > 0f) {
                                                            val curScale = liveScale.value
                                                            val maxX = (((bW * curScale) - bw) / 2f).coerceAtLeast(0f)
                                                            val maxY = (((bH * curScale) - bh) / 2f).coerceAtLeast(0f)
                                                            if (maxX > 1f || maxY > 1f) {
                                                                offsetX = (liveOffsetX.value + delta.x).coerceIn(-maxX, maxX)
                                                                offsetY = (liveOffsetY.value + delta.y).coerceIn(-maxY, maxY)
                                                            }
                                                            change.consume()
                                                        }
                                                    }
                                                }
                                            } finally {
                                                isGesturing = false
                                            }

                                            if (!isMultiTouch && pastSlop && liveBoxW.value > 0f) {
                                                val curScale = liveScale.value
                                                val curMaxX = (((liveBaseW.value * curScale) - liveBoxW.value) / 2f).coerceAtLeast(0f)
                                                val atLeftEdge = curMaxX <= 2f || liveOffsetX.value >= curMaxX - 2f
                                                val isClearHorizontal = abs(totalDx) > 100f && abs(totalDx) > abs(totalDy) * 1.4f
                                                if (isClearHorizontal && totalDx > 0f && startX <= edgeBackThresholdPx && atLeftEdge) {
                                                    goBack()
                                                } else if (curMaxX <= 2f && isClearHorizontal) {
                                                    if (totalDx > 0f) {
                                                        if (livePageIndex.value == 0) {
                                                            goBack()
                                                        } else {
                                                            viewModel.previousPage()
                                                        }
                                                    } else if (livePageIndex.value < livePageCount.value - 1) {
                                                        viewModel.nextPage()
                                                    }
                                                }
                                            }
                                        }
                                    },
                                contentAlignment = Alignment.Center,
                            ) {
                                when {
                                    errorText != null -> {
                                        LightText(
                                            text = errorText ?: "Error rendering PDF",
                                            variant = LightTextVariant.Paragraph,
                                            modifier = Modifier.padding(2f.gridUnitsAsDp()),
                                        )
                                    }
                                    renderedPage != null && boxSize.width > 0 && boxSize.height > 0 -> {
                                        val page = renderedPage!!
                                        val baseImageBitmap = remember(page.bitmap) { page.bitmap.asImageBitmap() }
                                        val baseWidthDp = with(density) { baseW.toDp() }
                                        val baseHeightDp = with(density) { baseH.toDp() }

                                        Image(
                                            bitmap = baseImageBitmap,
                                            contentDescription = "PDF Page ${pageIndex + 1}",
                                            contentScale = ContentScale.FillBounds,
                                            colorFilter = if (invertColors) INVERT_COLOR_FILTER else null,
                                            modifier = Modifier
                                                .size(width = baseWidthDp, height = baseHeightDp)
                                                .graphicsLayer(
                                                    scaleX = scale,
                                                    scaleY = scale,
                                                    translationX = offsetX,
                                                    translationY = offsetY,
                                                ),
                                        )

                                        val activeTile = crispTile?.takeIf { tile ->
                                            !isGesturing &&
                                                tile.pageIndex == pageIndex &&
                                                tile.rotationDegrees == page.rotationDegrees &&
                                                abs(tile.scale - scale) < 0.001f &&
                                                abs(tile.offsetX - offsetX) < 0.5f &&
                                                abs(tile.offsetY - offsetY) < 0.5f
                                        }
                                        if (activeTile != null) {
                                            val tileImageBitmap = remember(activeTile.bitmap) { activeTile.bitmap.asImageBitmap() }
                                            Image(
                                                bitmap = tileImageBitmap,
                                                contentDescription = null,
                                                contentScale = ContentScale.FillBounds,
                                                colorFilter = if (invertColors) INVERT_COLOR_FILTER else null,
                                                modifier = Modifier.fillMaxSize(),
                                            )
                                        }
                                    }
                                    isLoading -> {
                                        LightText(text = "Rendering page...", variant = LightTextVariant.Subheading)
                                    }
                                }
                            }
                        }
                    }

                    // Bottom Page Navigation Bar (hidden in Full Screen)
                    if (!hideChrome) {
                        val isTextPaged = isTextMode && pageMode == ReaderPageMode.PAGED && pagedPages.isNotEmpty()
                        val canGoBack = if (isTextPaged) safeSubPageIndex > 0 || hasPrev else hasPrev
                        val canGoForward = if (isTextPaged) safeSubPageIndex < pagedPages.lastIndex || hasNext else hasNext

                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = horizontalPadDp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            if (canGoBack) {
                                LightText(
                                    text = "← Prev",
                                    variant = LightTextVariant.Detail,
                                    modifier = Modifier
                                        .weight(1f)
                                        .lightClickable {
                                            if (isTextPaged) turnPrevInTextMode() else viewModel.previousPage()
                                        },
                                )
                            } else {
                                Spacer(modifier = Modifier.weight(1f))
                            }

                            if (isTextPaged || pageCount > 0) {
                                PdfFooterProgressText(
                                    isTextPaged = isTextPaged,
                                    isTextMode = isTextMode,
                                    pageIndex = pageIndex,
                                    pageCount = pageCount,
                                    safeSubPageIndex = safeSubPageIndex,
                                    pagedPagesSize = pagedPages.size,
                                    progressMode = progressMode,
                                    readPermille = { readActiveTextPermille() },
                                    onToggleVisibility = { viewModel.toggleProgressVisibility() },
                                )
                            }

                            Box(
                                modifier = Modifier.weight(1f),
                                contentAlignment = Alignment.CenterEnd,
                            ) {
                                if (canGoForward) {
                                    LightText(
                                        text = "Next →",
                                        variant = LightTextVariant.Detail,
                                        modifier = Modifier.lightClickable {
                                            if (isTextPaged) turnNextInTextMode() else viewModel.nextPage()
                                        },
                                    )
                                }
                            }
                        }
                    }
                }

                // Press-and-Hold Dictionary + Add Note Overlay Card (active in PDF Text Mode)
                if (isOverlayOpen) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(LightThemeTokens.colors.background.copy(alpha = 0.6f))
                            .pointerInput(Unit) {
                                detectTapGestures { viewModel.dismissLookup() }
                            },
                    ) {
                        DictionaryOverlayCard(
                            heldWord = heldWord,
                            lookupEnabled = lookupEnabled,
                            isLookingUp = isLookingUp,
                            result = lookupResult,
                            onAddNote = { viewModel.startNoteWordSelection() },
                            onEditNote = { note -> viewModel.openExistingNoteEditor(note) },
                            onDismiss = { viewModel.dismissLookup() },
                            modifier = Modifier
                                .align(Alignment.BottomCenter)
                                .fillMaxWidth()
                                .padding(horizontal = 12.dp, vertical = 14.dp)
                                .pointerInput(Unit) { detectTapGestures {} },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun PdfFooterProgressText(
    isTextPaged: Boolean,
    isTextMode: Boolean,
    pageIndex: Int,
    pageCount: Int,
    safeSubPageIndex: Int,
    pagedPagesSize: Int,
    progressMode: ReaderProgressMode,
    readPermille: () -> Int,
    onToggleVisibility: () -> Unit,
) {
    val centerText by remember(
        isTextPaged,
        isTextMode,
        pageIndex,
        pageCount,
        safeSubPageIndex,
        pagedPagesSize,
        progressMode,
    ) {
        derivedStateOf {
            if (isTextPaged) {
                val pct = if (pageCount > 0) {
                    (((pageIndex.toFloat() + (readPermille() / 1000f)) / pageCount) * 100f)
                        .roundToInt().coerceIn(0, 100)
                } else {
                    0
                }
                if (progressMode == ReaderProgressMode.HIDDEN) {
                    "${safeSubPageIndex + 1}/$pagedPagesSize"
                } else {
                    "${safeSubPageIndex + 1}/$pagedPagesSize • $pct%"
                }
            } else {
                val pct = if (isTextMode) {
                    (((pageIndex.toFloat() + (readPermille() / 1000f)) / pageCount) * 100f)
                        .roundToInt().coerceIn(0, 100)
                } else {
                    (((pageIndex + 1).toFloat() / pageCount) * 100f).toInt().coerceIn(1, 100)
                }
                if (progressMode == ReaderProgressMode.HIDDEN) "•••" else "$pct%"
            }
        }
    }
    LightText(
        text = centerText,
        variant = LightTextVariant.Detail,
        lighten = true,
        modifier = Modifier
            .lightClickable(onClick = onToggleVisibility)
            .padding(horizontal = 10.dp, vertical = 2.dp),
    )
}

@Composable
private fun PdfControlChip(
    label: String,
    highlighted: Boolean,
    onClick: () -> Unit,
) {
    val content = LightThemeTokens.colors.content
    val bg = if (highlighted) content else LightThemeTokens.colors.background
    val fg = if (highlighted) LightThemeTokens.colors.background else content
    Box(
        modifier = Modifier
            .background(bg, RoundedCornerShape(4.dp))
            .border(
                width = 1.dp,
                color = content.copy(alpha = 0.55f),
                shape = RoundedCornerShape(4.dp),
            )
            .lightClickable(onClick = onClick)
            .padding(horizontal = 5.dp, vertical = 3.dp),
        contentAlignment = Alignment.Center,
    ) {
        LightText(
            text = label,
            variant = LightTextVariant.Detail,
            color = fg,
        )
    }
}
