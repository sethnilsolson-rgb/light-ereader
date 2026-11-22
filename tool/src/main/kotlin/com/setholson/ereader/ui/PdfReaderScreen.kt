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
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
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
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewModelScope
import com.setholson.ereader.data.CachedPdfTextBook
import com.setholson.ereader.data.PdfTextExtractionResult
import com.setholson.ereader.data.ReaderRepository
import com.setholson.ereader.data.RenderedPdfPage
import com.setholson.ereader.db.ReaderDatabase
import com.setholson.ereader.model.BookFormat
import com.setholson.ereader.model.ReaderFontSize
import com.setholson.ereader.model.ReaderProgressMode
import com.setholson.ereader.model.WordLookupResult
import com.thelightphone.sdk.LightScreen
import com.thelightphone.sdk.LightViewModel
import com.thelightphone.sdk.SealedLightActivity
import com.thelightphone.sdk.SimpleLightScreen
import com.thelightphone.sdk.buildDatabase
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
import kotlinx.coroutines.launch
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

private const val MIN_PDF_SCALE = 1.0f
private const val MAX_PDF_SCALE = 8.0f

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
    private val repository: ReaderRepository,
) : LightViewModel<Unit>() {

    private val _pageIndex = MutableStateFlow(0)
    val pageIndex: StateFlow<Int> = _pageIndex.asStateFlow()

    private val _pageCount = MutableStateFlow(0)
    val pageCount: StateFlow<Int> = _pageCount.asStateFlow()

    private val _renderedPage = MutableStateFlow<RenderedPdfPage?>(null)
    val renderedPage: StateFlow<RenderedPdfPage?> = _renderedPage.asStateFlow()

    private val _crispTile = MutableStateFlow<CrispPdfTile?>(null)
    val crispTile: StateFlow<CrispPdfTile?> = _crispTile.asStateFlow()

    private val _isLoading = MutableStateFlow(true)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _errorText = MutableStateFlow<String?>(null)
    val errorText: StateFlow<String?> = _errorText.asStateFlow()

    private val _isBookmarked = MutableStateFlow(false)
    val isBookmarked: StateFlow<Boolean> = _isBookmarked.asStateFlow()

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

    private val _fontSize = MutableStateFlow(ReaderFontSize.REGULAR)
    val fontSize: StateFlow<ReaderFontSize> = _fontSize.asStateFlow()

    private val _lookupEnabled = MutableStateFlow(false)
    val lookupEnabled: StateFlow<Boolean> = _lookupEnabled.asStateFlow()

    private val _isLookingUp = MutableStateFlow(false)
    val isLookingUp: StateFlow<Boolean> = _isLookingUp.asStateFlow()

    private val _lookupResult = MutableStateFlow<WordLookupResult?>(null)
    val lookupResult: StateFlow<WordLookupResult?> = _lookupResult.asStateFlow()

    private var hasInitialized = false
    private var lastRenderedPageIndex = -1
    private var lastRenderedRotation = -1
    private var renderJob: Job? = null
    private var tileJob: Job? = null
    private var bookmarkJob: Job? = null
    private var extractionJob: Job? = null
    private var extractionCancelled = AtomicBoolean(false)
    private var lookupJob: Job? = null

    override fun onScreenShow(screen: SimpleLightScreen<Unit>) {
        super.onScreenShow(screen)
        viewModelScope.launch {
            _progressMode.value = repository.getProgressModeSync()
            _fontSize.value = repository.getFontSizeSync()
            _lookupEnabled.value = repository.isAnyDictionaryReadySync()
        }
        if (!hasInitialized) {
            hasInitialized = true
            viewModelScope.launch {
                val autoInvert = repository.getPdfDarkInvertSync()
                _invertColors.value = autoInvert && LightThemeController.isDarkTheme
                _rotationDegrees.value = repository.getPdfRotationSync(bookId)
                val savedPage = repository.getProgressSync(bookId)?.currentPosition ?: 0
                val startPage = explicitStartPage ?: savedPage

                val wantTextMode = repository.getPdfTextModeSync(bookId)
                if (wantTextMode) {
                    val cached = repository.getCachedPdfTextSync(bookId)
                    if (cached != null) {
                        _extractedBook.value = cached
                        _isTextMode.value = true
                        _isLoading.value = false
                        openTextPage(startPage, cached)
                        return@launch
                    }
                }
                openPage(startPage)
            }
        }
    }

    private fun observeBookmarkAndSaveProgress(safeIndex: Int, totalPages: Int) {
        viewModelScope.launch {
            repository.saveProgress(
                bookId = bookId,
                title = bookTitle,
                author = "$totalPages pages",
                format = BookFormat.PDF,
                positionIndex = safeIndex,
                totalPositions = totalPages,
            )
        }
        bookmarkJob?.cancel()
        bookmarkJob = viewModelScope.launch {
            repository.isPositionBookmarked(bookId, safeIndex).collect { saved ->
                _isBookmarked.value = saved
            }
        }
    }

    private fun openTextPage(index: Int, book: CachedPdfTextBook) {
        val total = book.pageCount.coerceAtLeast(1)
        val safeIndex = index.coerceIn(0, total - 1)
        _pageIndex.value = safeIndex
        _pageCount.value = total
        _errorText.value = null
        observeBookmarkAndSaveProgress(safeIndex, total)
    }

    fun openPage(index: Int, rotation: Int = _rotationDegrees.value) {
        val cached = _extractedBook.value
        if (_isTextMode.value && cached != null) {
            openTextPage(index, cached)
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
                observeBookmarkAndSaveProgress(safeIndex, page.pageCount)
            }.onFailure { err ->
                _errorText.value = err.message ?: "Unable to render PDF page."
            }
        }
    }

    fun enterTextMode() {
        _textNotice.value = null
        val alreadyExtracted = _extractedBook.value
        if (alreadyExtracted != null) {
            _isTextMode.value = true
            viewModelScope.launch { repository.setPdfTextMode(bookId, true) }
            openTextPage(_pageIndex.value, alreadyExtracted)
            return
        }

        if (_isExtractingText.value) return
        extractionJob?.cancel()
        val cancelFlag = AtomicBoolean(false)
        extractionCancelled = cancelFlag

        extractionJob = viewModelScope.launch {
            _isExtractingText.value = true
            _extractionProgressText.value = "Reading PDF..."

            val result = repository.extractOrLoadPdfText(
                bookId = bookId,
                onProgress = { cur, total ->
                    _extractionProgressText.value = "Page $cur / $total"
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
                    openTextPage(_pageIndex.value, result.book)
                }
                PdfTextExtractionResult.Encrypted -> {
                    _textNotice.value =
                        "This PDF is encrypted, so its text cannot be extracted. You can still read it in Page view."
                }
                PdfTextExtractionResult.NoTextFound -> {
                    _textNotice.value =
                        "No embedded text found (this looks like a scanned image PDF). You can still read it in Page view."
                }
                is PdfTextExtractionResult.Error -> {
                    _textNotice.value = result.message
                }
            }
        }
    }

    fun cancelTextExtraction() {
        extractionCancelled.set(true)
        extractionJob?.cancel()
        _isExtractingText.value = false
        _extractionProgressText.value = null
    }

    fun exitTextMode() {
        dismissLookup()
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

    fun nextPage() {
        val total = _pageCount.value
        if (total > 0 && _pageIndex.value < total - 1) {
            openPage(_pageIndex.value + 1)
        }
    }

    fun previousPage() {
        if (_pageIndex.value > 0) {
            openPage(_pageIndex.value - 1)
        }
    }

    fun jumpRelative(delta: Int) {
        val total = _pageCount.value
        if (total <= 0) return
        val target = (_pageIndex.value + delta).coerceIn(0, total - 1)
        openPage(target)
    }

    fun toggleInvert() {
        _invertColors.value = !_invertColors.value
    }

    fun cycleFontSize() {
        val next = _fontSize.value.next()
        _fontSize.value = next
        viewModelScope.launch {
            repository.setFontSize(next)
        }
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

    fun toggleBookmark() {
        val total = _pageCount.value
        if (total <= 0) return
        val page = _pageIndex.value
        val snippet = if (_isTextMode.value) {
            _extractedBook.value?.pages?.getOrNull(page)?.blocks?.firstOrNull()?.text?.take(100)
                ?: "PDF Bookmark"
        } else {
            "PDF Bookmark"
        }
        viewModelScope.launch {
            repository.toggleBookmark(
                bookId = bookId,
                bookTitle = bookTitle,
                format = BookFormat.PDF,
                positionIndex = page,
                positionLabel = "Page ${page + 1} of $total",
                snippet = snippet,
                currentlyBookmarked = _isBookmarked.value,
            )
        }
    }

    fun lookupWordAt(text: String, charIndex: Int) {
        if (!_lookupEnabled.value) return
        lookupJob?.cancel()
        lookupJob = viewModelScope.launch {
            _isLookingUp.value = true
            _lookupResult.value = null
            val res = repository.lookupWord(text, charIndex)
            _isLookingUp.value = false
            _lookupResult.value = res
        }
    }

    fun dismissLookup() {
        lookupJob?.cancel()
        _isLookingUp.value = false
        _lookupResult.value = null
    }
}

class PdfReaderScreen(
    sealedActivity: SealedLightActivity,
    private val bookId: String,
    private val bookTitle: String,
    private val initialPage: Int? = null,
) : LightScreen<Unit, PdfReaderViewModel>(sealedActivity) {

    private val repository = ReaderRepository.getInstance(
        dbProvider = {
            lightContext.buildDatabase(
                ReaderDatabase::class.java,
                ReaderRepository.DATABASE_NAME,
            ) {
                addMigrations(ReaderDatabase.MIGRATION_2_3)
                fallbackToDestructiveMigration(true)
            }
        },
        filesDir = lightContext.filesDir,
    )

    override val viewModelClass: Class<PdfReaderViewModel>
        get() = PdfReaderViewModel::class.java

    override fun createViewModel() = PdfReaderViewModel(bookId, bookTitle, initialPage, repository)

    @Composable
    override fun Content() {
        val themeColors by LightThemeController.colors.collectAsState()
        val pageIndex by viewModel.pageIndex.collectAsState()
        val pageCount by viewModel.pageCount.collectAsState()
        val renderedPage by viewModel.renderedPage.collectAsState()
        val crispTile by viewModel.crispTile.collectAsState()
        val isLoading by viewModel.isLoading.collectAsState()
        val errorText by viewModel.errorText.collectAsState()
        val isBookmarked by viewModel.isBookmarked.collectAsState()
        val invertColors by viewModel.invertColors.collectAsState()
        val rotationDegrees by viewModel.rotationDegrees.collectAsState()
        val progressMode by viewModel.progressMode.collectAsState()

        val isTextMode by viewModel.isTextMode.collectAsState()
        val isExtractingText by viewModel.isExtractingText.collectAsState()
        val extractionProgressText by viewModel.extractionProgressText.collectAsState()
        val textNotice by viewModel.textNotice.collectAsState()
        val extractedBook by viewModel.extractedBook.collectAsState()
        val fontSize by viewModel.fontSize.collectAsState()
        val lookupEnabled by viewModel.lookupEnabled.collectAsState()
        val isLookingUp by viewModel.isLookingUp.collectAsState()
        val lookupResult by viewModel.lookupResult.collectAsState()

        val density = LocalDensity.current
        val haptics = LocalHapticFeedback.current
        var showJumpBar by remember { mutableStateOf(false) }
        var boxSize by remember { mutableStateOf(IntSize.Zero) }

        val textScrollState = rememberScrollState()
        LaunchedEffect(pageIndex, isTextMode) {
            if (isTextMode) {
                textScrollState.scrollTo(0)
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
            remember(isBookmarked, contentColor) {
                if (isBookmarked) createBookmarkFilledVector(contentColor)
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

        val textVariant = when (fontSize) {
            ReaderFontSize.COMPACT -> LightTextVariant.Detail
            ReaderFontSize.REGULAR -> LightTextVariant.Paragraph
            ReaderFontSize.LARGE -> LightTextVariant.Copy
        }

        val isOverlayOpen = isLookingUp || lookupResult != null
        val hasPrev = pageIndex > 0
        val hasNext = pageCount > 0 && pageIndex < pageCount - 1

        LightTheme(colors = themeColors) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(LightThemeTokens.colors.background),
            ) {
                Column(modifier = Modifier.fillMaxSize()) {
                    LightTopBar(
                        leftButton = LightBarButton.LightIcon(
                            icon = LightIcons.BACK,
                            onClick = { goBack() },
                        ),
                        center = LightTopBarCenter.Text(bookTitle),
                        rightButton = LightBarButton.Icon(
                            painter = bookmarkPainter,
                            contentDescription = if (isBookmarked) "Bookmarked" else "Bookmark",
                            onClick = { viewModel.toggleBookmark() },
                        ),
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
                                onClick = { viewModel.exitTextMode() },
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            PdfControlChip(
                                label = fontSize.displayLabel,
                                highlighted = false,
                                onClick = { viewModel.cycleFontSize() },
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

                    // Optional Quick Page Jump Bar
                    if (showJumpBar && pageCount > 1) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 1.0f.gridUnitsAsDp(), vertical = 4.dp),
                            horizontalArrangement = Arrangement.spacedBy(5.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            PdfControlChip("First", false) { viewModel.openPage(0) }
                            PdfControlChip("-10", false) { viewModel.jumpRelative(-10) }
                            PdfControlChip("-1", false) { viewModel.jumpRelative(-1) }
                            PdfControlChip("+1", false) { viewModel.jumpRelative(1) }
                            PdfControlChip("+10", false) { viewModel.jumpRelative(10) }
                            PdfControlChip("Last", false) { viewModel.openPage(pageCount - 1) }
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
                            var dragAccumulator by remember(pageIndex) { mutableFloatStateOf(0f) }
                            var dragStartX by remember(pageIndex) { mutableFloatStateOf(0f) }
                            val textPage = extractedBook?.pages?.getOrNull(pageIndex)

                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .fillMaxWidth()
                                    .pointerInput(pageIndex, isOverlayOpen, hasPrev, hasNext) {
                                        if (isOverlayOpen) return@pointerInput
                                        detectHorizontalDragGestures(
                                            onDragStart = { offset ->
                                                dragStartX = offset.x
                                                dragAccumulator = 0f
                                            },
                                            onDragEnd = {
                                                if (dragAccumulator > 70f) {
                                                    if (dragStartX <= 260f || !hasPrev) {
                                                        goBack()
                                                    } else {
                                                        viewModel.previousPage()
                                                    }
                                                } else if (dragAccumulator < -80f && hasNext) {
                                                    viewModel.nextPage()
                                                }
                                                dragAccumulator = 0f
                                            },
                                            onDragCancel = { dragAccumulator = 0f },
                                            onHorizontalDrag = { _, dragAmount ->
                                                dragAccumulator += dragAmount
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
                                            PdfControlChip(
                                                label = "View PDF Page",
                                                highlighted = false,
                                                onClick = { viewModel.exitTextMode() },
                                            )
                                        }
                                    }
                                } else {
                                    val rightPad = 1.5f.gridUnitsAsDp()
                                    LightScrollView(
                                        scrollState = textScrollState,
                                        modifier = Modifier
                                            .fillMaxSize()
                                            .padding(start = 1.5f.gridUnitsAsDp()),
                                    ) {
                                        Spacer(modifier = Modifier.height(10.dp))

                                        textPage.blocks.forEach { block ->
                                            if (block.isHeading) {
                                                LightText(
                                                    text = block.text,
                                                    variant = LightTextVariant.Subheading,
                                                    modifier = Modifier
                                                        .fillMaxWidth()
                                                        .padding(end = rightPad, top = 12.dp, bottom = 8.dp),
                                                )
                                            } else {
                                                LookupableReaderText(
                                                    text = block.text,
                                                    variant = textVariant,
                                                    lighten = false,
                                                    lookupEnabled = lookupEnabled,
                                                    onLongPressChar = { offset ->
                                                        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                                        viewModel.lookupWordAt(block.text, offset)
                                                    },
                                                    modifier = Modifier
                                                        .fillMaxWidth()
                                                        .padding(end = rightPad, top = 4.dp, bottom = 10.dp),
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
                                                if (curMaxX <= 2f && abs(totalDx) > 110f && abs(totalDx) > abs(totalDy) * 1.6f) {
                                                    if (totalDx > 0f) {
                                                        if (startX <= 220f || livePageIndex.value == 0) {
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

                    // Bottom Page Navigation Bar (tap % to toggle Book % / Off)
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 1.5f.gridUnitsAsDp(), vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        if (hasPrev) {
                            LightText(
                                text = "← Prev",
                                variant = LightTextVariant.Detail,
                                modifier = Modifier
                                    .weight(1f)
                                    .lightClickable { viewModel.previousPage() },
                            )
                        } else {
                            Spacer(modifier = Modifier.weight(1f))
                        }

                        if (pageCount > 0) {
                            val pct = (((pageIndex + 1).toFloat() / pageCount) * 100f).toInt().coerceIn(1, 100)
                            val centerText = if (progressMode == ReaderProgressMode.HIDDEN) "•••" else "$pct%"
                            LightText(
                                text = centerText,
                                variant = LightTextVariant.Detail,
                                lighten = true,
                                modifier = Modifier
                                    .lightClickable { viewModel.toggleProgressVisibility() }
                                    .padding(horizontal = 10.dp, vertical = 2.dp),
                            )
                        }

                        Box(
                            modifier = Modifier.weight(1f),
                            contentAlignment = Alignment.CenterEnd,
                        ) {
                            if (hasNext) {
                                LightText(
                                    text = "Next →",
                                    variant = LightTextVariant.Detail,
                                    modifier = Modifier.lightClickable { viewModel.nextPage() },
                                )
                            }
                        }
                    }
                }

                // Press-and-Hold Dictionary Overlay Card (active in PDF Text Mode)
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
                            isLookingUp = isLookingUp,
                            result = lookupResult,
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
