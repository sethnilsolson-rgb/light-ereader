package com.setholson.ereader.ui

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewModelScope
import com.setholson.ereader.data.ReaderRepository
import com.setholson.ereader.db.ReaderDatabase
import com.setholson.ereader.model.BookFormat
import com.thelightphone.sdk.LightScreen
import com.thelightphone.sdk.LightViewModel
import com.thelightphone.sdk.SealedLightActivity
import com.thelightphone.sdk.SimpleLightScreen
import com.thelightphone.sdk.buildDatabase
import com.thelightphone.sdk.ui.LightBarButton
import com.thelightphone.sdk.ui.LightIcons
import com.thelightphone.sdk.ui.LightText
import com.thelightphone.sdk.ui.LightTextVariant
import com.thelightphone.sdk.ui.LightTheme
import com.thelightphone.sdk.ui.LightThemeColors
import com.thelightphone.sdk.ui.LightThemeController
import com.thelightphone.sdk.ui.LightThemeTokens
import com.thelightphone.sdk.ui.LightTopBar
import com.thelightphone.sdk.ui.LightTopBarCenter
import com.thelightphone.sdk.ui.gridUnitsAsDp
import com.thelightphone.sdk.ui.lightClickable
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

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

    private val _bitmap = MutableStateFlow<Bitmap?>(null)
    val bitmap: StateFlow<Bitmap?> = _bitmap.asStateFlow()

    private val _isLoading = MutableStateFlow(true)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _errorText = MutableStateFlow<String?>(null)
    val errorText: StateFlow<String?> = _errorText.asStateFlow()

    private val _isBookmarked = MutableStateFlow(false)
    val isBookmarked: StateFlow<Boolean> = _isBookmarked.asStateFlow()

    private val _invertColors = MutableStateFlow(false)
    val invertColors: StateFlow<Boolean> = _invertColors.asStateFlow()

    private val _zoomLevel = MutableStateFlow(1f)
    val zoomLevel: StateFlow<Float> = _zoomLevel.asStateFlow()

    private var hasInitialized = false
    private var renderJob: Job? = null
    private var bookmarkJob: Job? = null

    override fun onScreenShow(screen: SimpleLightScreen<Unit>) {
        super.onScreenShow(screen)
        if (!hasInitialized) {
            hasInitialized = true
            viewModelScope.launch {
                val autoInvert = repository.getPdfDarkInvertSync()
                _invertColors.value = autoInvert && LightThemeController.isDarkTheme
                val savedPage = repository.getProgressSync(bookId)?.currentPosition ?: 0
                openPage(explicitStartPage ?: savedPage)
            }
        }
    }

    fun openPage(index: Int) {
        renderJob?.cancel()
        renderJob = viewModelScope.launch {
            _isLoading.value = true
            _errorText.value = null

            val targetWidth = if (_zoomLevel.value > 1.2f) 1600 else 1080
            val result = repository.renderPdfPage(bookId, index, targetWidthPx = targetWidth)
            _isLoading.value = false

            result.onSuccess { (bmp, total) ->
                val safeIndex = index.coerceIn(0, (total - 1).coerceAtLeast(0))
                _bitmap.value = bmp
                _pageIndex.value = safeIndex
                _pageCount.value = total

                repository.saveProgress(
                    bookId = bookId,
                    title = bookTitle,
                    author = "$total pages",
                    format = BookFormat.PDF,
                    positionIndex = safeIndex,
                    totalPositions = total,
                )

                bookmarkJob?.cancel()
                bookmarkJob = viewModelScope.launch {
                    repository.isPositionBookmarked(bookId, safeIndex).collect { saved ->
                        _isBookmarked.value = saved
                    }
                }
            }.onFailure { err ->
                _errorText.value = err.message ?: "Unable to render PDF page."
            }
        }
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

    fun cycleZoom() {
        val next = when {
            _zoomLevel.value < 1.25f -> 1.5f
            _zoomLevel.value < 1.75f -> 2.0f
            _zoomLevel.value < 2.25f -> 2.5f
            else -> 1.0f
        }
        _zoomLevel.value = next
        // Re-render at sharper resolution when zooming in
        if (next > 1.2f && _pageCount.value > 0) {
            openPage(_pageIndex.value)
        }
    }

    fun toggleInvert() {
        _invertColors.value = !_invertColors.value
    }

    fun toggleBookmark() {
        val total = _pageCount.value
        if (total <= 0) return
        val page = _pageIndex.value
        viewModelScope.launch {
            repository.toggleBookmark(
                bookId = bookId,
                bookTitle = bookTitle,
                format = BookFormat.PDF,
                positionIndex = page,
                positionLabel = "Page ${page + 1} of $total",
                snippet = "PDF Bookmark",
                currentlyBookmarked = _isBookmarked.value,
            )
        }
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
        val bitmap by viewModel.bitmap.collectAsState()
        val isLoading by viewModel.isLoading.collectAsState()
        val errorText by viewModel.errorText.collectAsState()
        val isBookmarked by viewModel.isBookmarked.collectAsState()
        val invertColors by viewModel.invertColors.collectAsState()
        val zoomLevel by viewModel.zoomLevel.collectAsState()

        var showJumpBar by remember { mutableStateOf(false) }
        var panX by remember(pageIndex, zoomLevel) { mutableFloatStateOf(0f) }
        var panY by remember(pageIndex, zoomLevel) { mutableFloatStateOf(0f) }
        var swipeAccum by remember(pageIndex) { mutableFloatStateOf(0f) }
        var swipeStartX by remember(pageIndex) { mutableFloatStateOf(0f) }

        val contentColor = LightThemeTokens.colors.content
        val bookmarkPainter = rememberVectorPainter(
            remember(isBookmarked, contentColor) {
                if (isBookmarked) createBookmarkFilledVector(contentColor)
                else createBookmarkOutlineVector(contentColor)
            }
        )

        val zoomLabel = when {
            zoomLevel < 1.25f -> "1x"
            zoomLevel < 1.75f -> "1.5x"
            zoomLevel < 2.25f -> "2x"
            else -> "2.5x"
        }

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

                    // Top PDF Control Strip: Page Jump + Zoom + Dark Invert
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 1.25f.gridUnitsAsDp(), vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        val pageLabel = if (pageCount > 0) "Page ${pageIndex + 1} / $pageCount" else "Loading..."
                        Box(
                            modifier = Modifier
                                .border(
                                    width = 1.dp,
                                    color = LightThemeTokens.colors.content.copy(alpha = 0.45f),
                                    shape = RoundedCornerShape(4.dp),
                                )
                                .lightClickable { showJumpBar = !showJumpBar }
                                .padding(horizontal = 8.dp, vertical = 3.dp),
                        ) {
                            LightText(text = pageLabel, variant = LightTextVariant.Detail)
                        }

                        Spacer(modifier = Modifier.weight(1f))

                        PdfControlChip(
                            label = zoomLabel,
                            highlighted = zoomLevel > 1.05f,
                            onClick = { viewModel.cycleZoom() },
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        PdfControlChip(
                            label = "Invert",
                            highlighted = invertColors,
                            onClick = { viewModel.toggleInvert() },
                        )
                    }

                    // Optional Quick Page Jump Bar
                    if (showJumpBar && pageCount > 1) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 1.25f.gridUnitsAsDp(), vertical = 4.dp),
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
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

                    // Main PDF Canvas
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth()
                            .clipToBounds()
                            .background(
                                if (invertColors || themeColors == LightThemeColors.Dark) {
                                    LightThemeTokens.colors.background
                                } else {
                                    LightThemeTokens.colors.background
                                }
                            )
                            .pointerInput(pageIndex, zoomLevel, pageCount) {
                                if (zoomLevel > 1.05f) {
                                    detectDragGestures { change, dragAmount ->
                                        change.consume()
                                        val maxPanX = (size.width * (zoomLevel - 1f)) / 2f
                                        val maxPanY = (size.height * (zoomLevel - 1f)) / 2f
                                        panX = (panX + dragAmount.x).coerceIn(-maxPanX, maxPanX)
                                        panY = (panY + dragAmount.y).coerceIn(-maxPanY, maxPanY)
                                    }
                                } else {
                                    detectHorizontalDragGestures(
                                        onDragStart = { offset ->
                                            swipeStartX = offset.x
                                            swipeAccum = 0f
                                        },
                                        onDragEnd = {
                                            if (swipeAccum > 70f) {
                                                if (swipeStartX <= 220f || pageIndex == 0) {
                                                    goBack()
                                                } else {
                                                    viewModel.previousPage()
                                                }
                                            } else if (swipeAccum < -70f) {
                                                viewModel.nextPage()
                                            }
                                            swipeAccum = 0f
                                        },
                                        onDragCancel = { swipeAccum = 0f },
                                        onHorizontalDrag = { _, dragAmount ->
                                            swipeAccum += dragAmount
                                        },
                                    )
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
                            bitmap != null -> {
                                val imageBitmap = remember(bitmap) { bitmap!!.asImageBitmap() }
                                Image(
                                    bitmap = imageBitmap,
                                    contentDescription = "PDF Page ${pageIndex + 1}",
                                    contentScale = ContentScale.Fit,
                                    colorFilter = if (invertColors) INVERT_COLOR_FILTER else null,
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .graphicsLayer(
                                            scaleX = zoomLevel,
                                            scaleY = zoomLevel,
                                            translationX = panX,
                                            translationY = panY,
                                        ),
                                )
                            }
                            isLoading -> {
                                LightText(text = "Rendering page...", variant = LightTextVariant.Subheading)
                            }
                        }
                    }

                    // Bottom Page Navigation Bar
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 1.5f.gridUnitsAsDp(), vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        if (pageIndex > 0) {
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
                            LightText(
                                text = "$pct%",
                                variant = LightTextVariant.Detail,
                                lighten = true,
                            )
                        }

                        Box(
                            modifier = Modifier.weight(1f),
                            contentAlignment = Alignment.CenterEnd,
                        ) {
                            if (pageCount > 0 && pageIndex < pageCount - 1) {
                                LightText(
                                    text = "Next →",
                                    variant = LightTextVariant.Detail,
                                    modifier = Modifier.lightClickable { viewModel.nextPage() },
                                )
                            }
                        }
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
            .padding(horizontal = 7.dp, vertical = 3.dp),
        contentAlignment = Alignment.Center,
    ) {
        LightText(
            text = label,
            variant = LightTextVariant.Detail,
            color = fg,
        )
    }
}
