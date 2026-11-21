package com.setholson.ereader.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
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
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewModelScope
import com.setholson.ereader.data.ReaderRepository
import com.setholson.ereader.db.BookBookmarkEntity
import com.setholson.ereader.db.ReaderDatabase
import com.setholson.ereader.model.BookChapter
import com.setholson.ereader.model.BookFormat
import com.setholson.ereader.model.ParsedBook
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
import com.thelightphone.sdk.ui.designVerticalPxToSp
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
import kotlin.math.abs
import kotlin.math.roundToInt

private const val BRIEF_SCROLL_MEMORY_MS = 180_000L // 3 minutes for flip-back comparisons
private const val BOOKMARK_PROXIMITY_PERMILLE = 55  // ~5.5% of chapter height

class TextReaderViewModel(
    private val bookId: String,
    private val bookFormat: BookFormat,
    private val explicitStartChapter: Int?,
    private val explicitStartScrollPermille: Int?,
    private val repository: ReaderRepository,
) : LightViewModel<Unit>() {

    private val _book = MutableStateFlow<ParsedBook?>(null)
    val book: StateFlow<ParsedBook?> = _book.asStateFlow()

    private val _chapterIndex = MutableStateFlow(0)
    val chapterIndex: StateFlow<Int> = _chapterIndex.asStateFlow()

    private val _targetScrollPermille = MutableStateFlow(0)
    val targetScrollPermille: StateFlow<Int> = _targetScrollPermille.asStateFlow()

    private val _scrollRequestToken = MutableStateFlow(0L)
    val scrollRequestToken: StateFlow<Long> = _scrollRequestToken.asStateFlow()

    private val _isLoading = MutableStateFlow(true)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _errorText = MutableStateFlow<String?>(null)
    val errorText: StateFlow<String?> = _errorText.asStateFlow()

    private val _chapterBookmarks = MutableStateFlow<List<BookBookmarkEntity>>(emptyList())
    val chapterBookmarks: StateFlow<List<BookBookmarkEntity>> = _chapterBookmarks.asStateFlow()

    private val _fontSize = MutableStateFlow(ReaderFontSize.REGULAR)
    val fontSize: StateFlow<ReaderFontSize> = _fontSize.asStateFlow()

    private val _progressMode = MutableStateFlow(ReaderProgressMode.BOOK)
    val progressMode: StateFlow<ReaderProgressMode> = _progressMode.asStateFlow()

    private val _lookupEnabled = MutableStateFlow(false)
    val lookupEnabled: StateFlow<Boolean> = _lookupEnabled.asStateFlow()

    private val _isLookingUp = MutableStateFlow(false)
    val isLookingUp: StateFlow<Boolean> = _isLookingUp.asStateFlow()

    private val _lookupResult = MutableStateFlow<WordLookupResult?>(null)
    val lookupResult: StateFlow<WordLookupResult?> = _lookupResult.asStateFlow()

    // In-memory brief scroll cache: chapterIndex -> (scrollPermille, savedAtMs)
    private val recentScrollPositions = mutableMapOf<Int, Pair<Int, Long>>()
    private var liveScrollPermille: Int = 0
    private var hasInitialized = false
    private var bookmarkJob: Job? = null
    private var lookupJob: Job? = null
    private var scrollSaveJob: Job? = null

    override fun onScreenShow(screen: SimpleLightScreen<Unit>) {
        super.onScreenShow(screen)
        viewModelScope.launch {
            _fontSize.value = repository.getFontSizeSync()
            _progressMode.value = repository.getProgressModeSync()
            _lookupEnabled.value = repository.isAnyDictionaryReadySync()
        }

        if (!hasInitialized) {
            hasInitialized = true
            loadBook()
        }
    }

    private fun loadBook() {
        viewModelScope.launch {
            _isLoading.value = true
            _errorText.value = null
            val result = repository.loadTextBook(bookId)
            _isLoading.value = false

            result.onSuccess { parsed ->
                _book.value = parsed
                val savedProg = repository.getProgressSync(bookId)
                val savedPos = savedProg?.currentPosition ?: 0
                val startIdx = (explicitStartChapter ?: savedPos).coerceIn(0, (parsed.chapters.size - 1).coerceAtLeast(0))
                val initialPermille = when {
                    explicitStartScrollPermille != null -> explicitStartScrollPermille.coerceIn(0, 1000)
                    explicitStartChapter == null && savedProg != null -> savedProg.scrollIndex.coerceIn(0, 1000)
                    else -> 0
                }
                openChapter(index = startIdx, explicitScrollPermille = initialPermille, rememberOutgoing = false)
            }.onFailure { err ->
                _errorText.value = err.message ?: "Unable to open this book."
            }
        }
    }

    fun onScrollChanged(scrollPermille: Int) {
        val clamped = scrollPermille.coerceIn(0, 1000)
        liveScrollPermille = clamped
        scrollSaveJob?.cancel()
        scrollSaveJob = viewModelScope.launch {
            delay(500L)
            repository.updateScrollProgress(bookId, clamped)
        }
    }

    fun openChapter(
        index: Int,
        explicitScrollPermille: Int? = null,
        rememberOutgoing: Boolean = true,
    ) {
        val parsed = _book.value ?: return
        if (parsed.chapters.isEmpty()) return
        val safeIdx = index.coerceIn(0, parsed.chapters.lastIndex)

        val now = System.currentTimeMillis()
        if (rememberOutgoing) {
            val prevIdx = _chapterIndex.value
            if (prevIdx != safeIdx) {
                recentScrollPositions[prevIdx] = liveScrollPermille to now
            }
        }

        val resolvedPermille = when {
            explicitScrollPermille != null -> explicitScrollPermille.coerceIn(0, 1000)
            else -> {
                val recent = recentScrollPositions[safeIdx]
                if (recent != null && (now - recent.second) <= BRIEF_SCROLL_MEMORY_MS) {
                    recent.first.coerceIn(0, 1000)
                } else {
                    0
                }
            }
        }

        _chapterIndex.value = safeIdx
        liveScrollPermille = resolvedPermille
        _targetScrollPermille.value = resolvedPermille
        _scrollRequestToken.value = _scrollRequestToken.value + 1L
        dismissLookup()

        viewModelScope.launch {
            repository.saveProgress(
                bookId = bookId,
                title = parsed.title,
                author = parsed.author,
                format = bookFormat,
                positionIndex = safeIdx,
                totalPositions = parsed.chapters.size,
                scrollIndex = resolvedPermille,
            )
        }

        bookmarkJob?.cancel()
        bookmarkJob = viewModelScope.launch {
            repository.getBookmarksForPosition(bookId, safeIdx).collect { saved ->
                _chapterBookmarks.value = saved
            }
        }
    }

    fun nextChapter() {
        val parsed = _book.value ?: return
        if (_chapterIndex.value < parsed.chapters.lastIndex) {
            openChapter(_chapterIndex.value + 1)
        }
    }

    fun previousChapter() {
        if (_chapterIndex.value > 0) {
            openChapter(_chapterIndex.value - 1)
        }
    }

    fun findBookmarkNear(scrollPermille: Int): BookBookmarkEntity? =
        _chapterBookmarks.value.minByOrNull { abs(it.scrollPermille - scrollPermille) }
            ?.takeIf { abs(it.scrollPermille - scrollPermille) <= BOOKMARK_PROXIMITY_PERMILLE }

    fun toggleBookmarkAt(scrollPermille: Int, visibleSnippet: String) {
        val parsed = _book.value ?: return
        val idx = _chapterIndex.value
        val chapter = parsed.chapters.getOrNull(idx) ?: return
        val fallbackSnippet = chapter.blocks.firstOrNull { !it.isHeading }?.text
            ?: chapter.blocks.firstOrNull()?.text
            ?: ""
        val snippet = visibleSnippet.ifBlank { fallbackSnippet }
        val matching = findBookmarkNear(scrollPermille)

        viewModelScope.launch {
            repository.toggleScrollBookmark(
                bookId = bookId,
                bookTitle = parsed.title,
                format = bookFormat,
                positionIndex = idx,
                chapterTitle = chapter.title,
                scrollPermille = scrollPermille,
                snippet = snippet,
                matchingBookmark = matching,
            )
        }
    }

    fun cycleFontSize() {
        val next = _fontSize.value.next()
        _fontSize.value = next
        // Preserve current reading scroll fraction across font size changes
        _targetScrollPermille.value = liveScrollPermille
        _scrollRequestToken.value = _scrollRequestToken.value + 1L
        viewModelScope.launch {
            repository.setFontSize(next)
        }
    }

    fun cycleProgressMode() {
        val next = _progressMode.value.next()
        _progressMode.value = next
        viewModelScope.launch {
            repository.setProgressMode(next)
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

class TextReaderScreen(
    sealedActivity: SealedLightActivity,
    private val bookId: String,
    private val bookFormat: BookFormat,
    private val initialChapter: Int? = null,
    private val initialScrollPermille: Int? = null,
) : LightScreen<Unit, TextReaderViewModel>(sealedActivity) {

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

    override val viewModelClass: Class<TextReaderViewModel>
        get() = TextReaderViewModel::class.java

    override fun createViewModel() = TextReaderViewModel(
        bookId = bookId,
        bookFormat = bookFormat,
        explicitStartChapter = initialChapter,
        explicitStartScrollPermille = initialScrollPermille,
        repository = repository,
    )

    @Composable
    override fun Content() {
        val themeColors by LightThemeController.colors.collectAsState()
        val book by viewModel.book.collectAsState()
        val chapterIndex by viewModel.chapterIndex.collectAsState()
        val targetScrollPermille by viewModel.targetScrollPermille.collectAsState()
        val scrollRequestToken by viewModel.scrollRequestToken.collectAsState()
        val isLoading by viewModel.isLoading.collectAsState()
        val errorText by viewModel.errorText.collectAsState()
        val chapterBookmarks by viewModel.chapterBookmarks.collectAsState()
        val fontSize by viewModel.fontSize.collectAsState()
        val progressMode by viewModel.progressMode.collectAsState()
        val lookupEnabled by viewModel.lookupEnabled.collectAsState()
        val isLookingUp by viewModel.isLookingUp.collectAsState()
        val lookupResult by viewModel.lookupResult.collectAsState()

        val currentChapter: BookChapter? = book?.chapters?.getOrNull(chapterIndex)
        val totalChapters = book?.chapters?.size ?: 0
        val hasPrev = chapterIndex > 0
        val hasNext = totalChapters > 0 && chapterIndex < totalChapters - 1

        val scrollState = rememberScrollState()
        val blockOffsets = remember(chapterIndex, fontSize) { mutableStateMapOf<Int, Int>() }

        // Compute current scroll position as 0..1000 permille
        val currentScrollPermille by remember(scrollState) {
            derivedStateOf {
                val max = scrollState.maxValue
                if (max <= 0) 0 else ((scrollState.value.toFloat() / max.toFloat()) * 1000f).roundToInt().coerceIn(0, 1000)
            }
        }

        // Filled bookmark icon whenever current viewport is right at a saved bookmark in this chapter
        val isNearSavedBookmark = remember(chapterBookmarks, currentScrollPermille) {
            chapterBookmarks.any { abs(it.scrollPermille - currentScrollPermille) <= BOOKMARK_PROXIMITY_PERMILLE }
        }

        // Restore exact scroll target (0 = top for new chapters, saved permille for bookmarks/resume/flip-back)
        LaunchedEffect(chapterIndex, scrollRequestToken, currentChapter != null) {
            if (currentChapter == null) return@LaunchedEffect
            val target = targetScrollPermille
            if (target <= 0) {
                scrollState.scrollTo(0)
            } else {
                val maxVal = if (scrollState.maxValue > 0) {
                    scrollState.maxValue
                } else {
                    withTimeoutOrNull(800L) {
                        snapshotFlow { scrollState.maxValue }.first { it > 0 }
                    } ?: scrollState.maxValue
                }
                val targetPx = ((target / 1000f) * maxVal).roundToInt().coerceIn(0, maxVal)
                scrollState.scrollTo(targetPx)
            }
        }

        // Report scroll updates to ViewModel so Continue Reading & flip-back memory stay exact
        LaunchedEffect(chapterIndex, scrollState) {
            snapshotFlow { currentScrollPermille }
                .distinctUntilChanged()
                .collect { permille ->
                    viewModel.onScrollChanged(permille)
                }
        }

        fun findVisibleSnippet(): String {
            val chapter = currentChapter ?: return ""
            if (chapter.blocks.isEmpty()) return ""
            val scrollY = scrollState.value
            val bestIdx = blockOffsets.entries
                .filter { it.value <= scrollY + 48 }
                .maxByOrNull { it.value }
                ?.key
                ?: blockOffsets.entries.minByOrNull { abs(it.value - scrollY) }?.key
                ?: ((currentScrollPermille / 1000f) * chapter.blocks.lastIndex).roundToInt()
                    .coerceIn(0, chapter.blocks.lastIndex)
            return chapter.blocks.getOrNull(bestIdx)?.text.orEmpty()
        }

        val textVariant = when (fontSize) {
            ReaderFontSize.COMPACT -> LightTextVariant.Detail
            ReaderFontSize.REGULAR -> LightTextVariant.Paragraph
            ReaderFontSize.LARGE -> LightTextVariant.Copy
        }

        val contentColor = LightThemeTokens.colors.content
        val bookmarkPainter = rememberVectorPainter(
            remember(isNearSavedBookmark, contentColor) {
                if (isNearSavedBookmark) createBookmarkFilledVector(contentColor)
                else createBookmarkOutlineVector(contentColor)
            }
        )

        val haptics = LocalHapticFeedback.current
        var dragAccumulator by remember(chapterIndex) { mutableFloatStateOf(0f) }
        var dragStartX by remember(chapterIndex) { mutableFloatStateOf(0f) }
        val isOverlayOpen = isLookingUp || lookupResult != null

        LightTheme(colors = themeColors) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(LightThemeTokens.colors.background)
                    .pointerInput(chapterIndex, isOverlayOpen, hasPrev, hasNext) {
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
                                        viewModel.previousChapter()
                                    }
                                } else if (dragAccumulator < -80f && hasNext) {
                                    viewModel.nextChapter()
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
                Column(modifier = Modifier.fillMaxSize()) {
                    LightTopBar(
                        leftButton = LightBarButton.LightIcon(
                            icon = LightIcons.BACK,
                            onClick = { goBack() },
                        ),
                        center = LightTopBarCenter.Text(
                            text = currentChapter?.title ?: book?.title ?: "Reader",
                        ),
                        rightButton = LightBarButton.Icon(
                            painter = bookmarkPainter,
                            contentDescription = if (isNearSavedBookmark) "Bookmarked here" else "Bookmark this spot",
                            onClick = {
                                viewModel.toggleBookmarkAt(
                                    scrollPermille = currentScrollPermille,
                                    visibleSnippet = findVisibleSnippet(),
                                )
                            },
                        ),
                        modifier = Modifier.padding(bottom = 0.5f.gridUnitsAsDp()),
                    )

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(
                                start = 1.5f.gridUnitsAsDp(),
                                end = 1.5f.gridUnitsAsDp(),
                                top = 2.dp,
                                bottom = 6.dp,
                            ),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        val progressText = if (totalChapters > 0) {
                            when (progressMode) {
                                ReaderProgressMode.BOOK -> {
                                    val bookPct = (
                                        ((chapterIndex.toFloat() + (currentScrollPermille / 1000f)) / totalChapters.toFloat()) * 100f
                                        ).roundToInt().coerceIn(0, 100)
                                    if (totalChapters > 1) {
                                        "Ch. ${chapterIndex + 1}/$totalChapters • $bookPct% book"
                                    } else {
                                        "$bookPct% book"
                                    }
                                }
                                ReaderProgressMode.CHAPTER -> {
                                    val scrollPct = (currentScrollPermille / 10).coerceIn(0, 100)
                                    if (totalChapters > 1) {
                                        "Ch. ${chapterIndex + 1}/$totalChapters • $scrollPct% ch."
                                    } else {
                                        "$scrollPct% ch."
                                    }
                                }
                                ReaderProgressMode.HIDDEN -> {
                                    if (totalChapters > 1) {
                                        "Ch. ${chapterIndex + 1}/$totalChapters"
                                    } else {
                                        "•••"
                                    }
                                }
                            }
                        } else {
                            book?.title.orEmpty()
                        }
                        LightText(
                            text = progressText,
                            variant = LightTextVariant.Detail,
                            lighten = true,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier
                                .weight(1f)
                                .lightClickable { viewModel.cycleProgressMode() },
                        )

                        if (totalChapters > 1) {
                            Box(
                                modifier = Modifier
                                    .padding(start = 8.dp)
                                    .border(
                                        width = 1.dp,
                                        color = LightThemeTokens.colors.content.copy(alpha = 0.45f),
                                        shape = RoundedCornerShape(4.dp),
                                    )
                                    .lightClickable {
                                        val titles = book?.chapters?.map { it.title }.orEmpty()
                                        navigateTo(
                                            screenFactory = { act ->
                                                TocScreen(
                                                    sealedActivity = act,
                                                    bookTitle = book?.title ?: "Contents",
                                                    chapterTitles = titles,
                                                    currentChapterIndex = chapterIndex,
                                                )
                                            },
                                            resultCallback = { chosenIndex ->
                                                viewModel.openChapter(chosenIndex)
                                            },
                                        )
                                    }
                                    .padding(horizontal = 7.dp, vertical = 2.dp),
                            ) {
                                LightText(
                                    text = "Contents",
                                    variant = LightTextVariant.Detail,
                                )
                            }
                        }

                        LightText(
                            text = fontSize.displayLabel,
                            variant = LightTextVariant.Detail,
                            lighten = true,
                            modifier = Modifier
                                .padding(start = 12.dp)
                                .lightClickable { viewModel.cycleFontSize() },
                        )
                    }

                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth(),
                    ) {
                        when {
                            isLoading -> {
                                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                    LightText(text = "Opening book...", variant = LightTextVariant.Subheading)
                                }
                            }
                            errorText != null -> {
                                Box(
                                    Modifier
                                        .fillMaxSize()
                                        .padding(2f.gridUnitsAsDp()),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    LightText(
                                        text = errorText ?: "Unable to open book",
                                        variant = LightTextVariant.Paragraph,
                                    )
                                }
                            }
                            currentChapter != null -> {
                                LightScrollView(
                                    scrollState = scrollState,
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .padding(start = 1.5f.gridUnitsAsDp()),
                                ) {
                                    Spacer(modifier = Modifier.height(10.dp))

                                    currentChapter.blocks.forEachIndexed { idx, block ->
                                        val rightPad = 1.5f.gridUnitsAsDp()
                                        val trackOffsetModifier = Modifier.onGloballyPositioned { coords ->
                                            blockOffsets[idx] = coords.positionInParent().y.roundToInt()
                                        }
                                        when {
                                            block.isHeading -> {
                                                LightText(
                                                    text = block.text,
                                                    variant = LightTextVariant.Subheading,
                                                    modifier = trackOffsetModifier
                                                        .fillMaxWidth()
                                                        .padding(end = rightPad, top = 12.dp, bottom = 8.dp),
                                                )
                                            }
                                            block.isQuote -> {
                                                Row(
                                                    modifier = trackOffsetModifier
                                                        .fillMaxWidth()
                                                        .padding(end = rightPad, top = 6.dp, bottom = 10.dp),
                                                ) {
                                                    Box(
                                                        modifier = Modifier
                                                            .width(2.dp)
                                                            .heightIn(min = 24.dp)
                                                            .background(LightThemeTokens.colors.content.copy(alpha = 0.45f)),
                                                    )
                                                    Spacer(modifier = Modifier.width(10.dp))
                                                    LookupableReaderText(
                                                        text = block.text,
                                                        variant = textVariant,
                                                        lighten = true,
                                                        lookupEnabled = lookupEnabled,
                                                        onLongPressChar = { offset ->
                                                            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                                            viewModel.lookupWordAt(block.text, offset)
                                                        },
                                                        modifier = Modifier.weight(1f),
                                                    )
                                                }
                                            }
                                            else -> {
                                                LookupableReaderText(
                                                    text = block.text,
                                                    variant = textVariant,
                                                    lighten = false,
                                                    lookupEnabled = lookupEnabled,
                                                    onLongPressChar = { offset ->
                                                        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                                        viewModel.lookupWordAt(block.text, offset)
                                                    },
                                                    modifier = trackOffsetModifier
                                                        .fillMaxWidth()
                                                        .padding(end = rightPad, top = 4.dp, bottom = 10.dp),
                                                )
                                            }
                                        }
                                    }

                                    Spacer(modifier = Modifier.height(18.dp))
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(end = 1.5f.gridUnitsAsDp(), top = 8.dp, bottom = 18.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        if (hasPrev) {
                                            LightText(
                                                text = "← Previous",
                                                variant = LightTextVariant.Detail,
                                                lighten = true,
                                                modifier = Modifier
                                                    .weight(1f)
                                                    .lightClickable { viewModel.previousChapter() },
                                            )
                                        } else {
                                            Spacer(modifier = Modifier.weight(1f))
                                        }

                                        if (hasNext) {
                                            LightText(
                                                text = "Next →",
                                                variant = LightTextVariant.Detail,
                                                lighten = true,
                                                modifier = Modifier.lightClickable { viewModel.nextChapter() },
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

                // Multi-Dictionary Press-and-Hold Overlay Card
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
internal fun LookupableReaderText(
    text: String,
    variant: LightTextVariant,
    lighten: Boolean,
    lookupEnabled: Boolean,
    onLongPressChar: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val style = scaledReaderStyle(variant)
    val colors = LightThemeTokens.colors
    val color = if (lighten) colors.contentSecondary else colors.content
    var layoutResult by remember(text, variant) { mutableStateOf<TextLayoutResult?>(null) }

    val mod = if (lookupEnabled) {
        modifier.pointerInput(text, lookupEnabled) {
            detectTapGestures(
                onLongPress = { pos ->
                    layoutResult?.let { layout ->
                        onLongPressChar(layout.getOffsetForPosition(pos))
                    }
                },
            )
        }
    } else {
        modifier
    }

    Text(
        text = text,
        color = color,
        style = style,
        onTextLayout = { layoutResult = it },
        modifier = mod,
    )
}

@Composable
private fun scaledReaderStyle(variant: LightTextVariant): TextStyle {
    val t = LightThemeTokens.typography
    val base = when (variant) {
        LightTextVariant.Detail -> t.detail
        LightTextVariant.Copy -> t.copy
        else -> t.paragraph
    }
    return base.copy(
        fontSize = base.fontSize.scaledUnit(),
        lineHeight = base.lineHeight.scaledUnit(),
        letterSpacing = base.letterSpacing.scaledUnit(),
    )
}

@Composable
private fun TextUnit.scaledUnit(): TextUnit {
    if (this == TextUnit.Unspecified) return this
    return value.designVerticalPxToSp()
}

@Composable
internal fun DictionaryOverlayCard(
    isLookingUp: Boolean,
    result: WordLookupResult?,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LightThemeTokens.colors
    var selectedHitIdx by remember(result) { mutableIntStateOf(0) }

    Column(
        modifier = modifier
            .background(colors.background, RoundedCornerShape(6.dp))
            .border(width = 1.5.dp, color = colors.content, shape = RoundedCornerShape(6.dp))
            .padding(horizontal = 14.dp, vertical = 12.dp)
            .heightIn(max = 330.dp),
    ) {
        if (isLookingUp || result == null) {
            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                LightText(text = "Looking up...", variant = LightTextVariant.Subheading, modifier = Modifier.weight(1f))
                LightText(
                    text = "✕",
                    variant = LightTextVariant.Subheading,
                    modifier = Modifier.lightClickable { onDismiss() },
                )
            }
            return@Column
        }

        val hits = result.hits
        val activeHit = hits.getOrNull(selectedHitIdx)
        val displayHeadword = activeHit?.headword ?: result.headword
        val displaySource = activeHit?.sourceLabel ?: result.sourceLabel
        val displayDef = activeHit?.definition ?: result.definition

        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                LightText(text = displayHeadword, variant = LightTextVariant.Subheading)
                LightText(text = displaySource, variant = LightTextVariant.Detail, lighten = true)
            }
            LightText(
                text = "✕",
                variant = LightTextVariant.Subheading,
                modifier = Modifier
                    .padding(start = 12.dp)
                    .lightClickable { onDismiss() },
            )
        }

        if (hits.size > 1) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp)
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                hits.forEachIndexed { idx, hit ->
                    val active = idx == selectedHitIdx
                    Box(
                        modifier = Modifier
                            .background(
                                if (active) colors.content else colors.background,
                                RoundedCornerShape(4.dp),
                            )
                            .border(
                                width = 1.dp,
                                color = colors.content.copy(alpha = if (active) 1f else 0.45f),
                                shape = RoundedCornerShape(4.dp),
                            )
                            .lightClickable { selectedHitIdx = idx }
                            .padding(horizontal = 8.dp, vertical = 3.dp),
                    ) {
                        LightText(
                            text = hit.shortLabel,
                            variant = LightTextVariant.Detail,
                            color = if (active) colors.background else colors.content,
                        )
                    }
                }
            }
        }

        HorizontalDivider(
            color = colors.content.copy(alpha = 0.25f),
            thickness = 1.dp,
            modifier = Modifier.padding(vertical = 8.dp),
        )

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState()),
        ) {
            LightText(text = displayDef, variant = LightTextVariant.Paragraph)
        }
    }
}
