package com.setholson.ereader.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewModelScope
import com.setholson.ereader.data.ReaderRepository
import com.setholson.ereader.db.BookBookmarkEntity
import com.setholson.ereader.db.BookNoteEntity
import com.setholson.ereader.db.ReaderDatabase
import com.setholson.ereader.model.BookChapter
import com.setholson.ereader.model.BookFormat
import com.setholson.ereader.model.ParsedBook
import com.setholson.ereader.model.ReaderFontSize
import com.setholson.ereader.model.ReaderPageMode
import com.setholson.ereader.model.ReaderProgressMode
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
internal const val BOOKMARK_PROXIMITY_PERMILLE = 55  // ~5.5% of chapter/page height

data class HeldWordContext(
    val anchor: WordAnchor,
    val wordText: String,
    val scrollPermille: Int,
    val existingNotes: List<BookNoteEntity>,
)

data class ComposingNoteState(
    val existingNote: BookNoteEntity?,
    val anchors: List<WordAnchor>,
    val quotedExcerpt: String,
    val scrollPermille: Int,
)

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

    private val _pageMode = MutableStateFlow(ReaderPageMode.SCROLL)
    val pageMode: StateFlow<ReaderPageMode> = _pageMode.asStateFlow()

    private val _isFullScreen = MutableStateFlow(false)
    val isFullScreen: StateFlow<Boolean> = _isFullScreen.asStateFlow()

    private val _progressMode = MutableStateFlow(ReaderProgressMode.BOOK)
    val progressMode: StateFlow<ReaderProgressMode> = _progressMode.asStateFlow()

    private val _chapterNotes = MutableStateFlow<List<BookNoteEntity>>(emptyList())
    val chapterNotes: StateFlow<List<BookNoteEntity>> = _chapterNotes.asStateFlow()

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

    // In-memory brief scroll cache: chapterIndex -> (scrollPermille, savedAtMs)
    private val recentScrollPositions = mutableMapOf<Int, Pair<Int, Long>>()
    private var liveScrollPermille: Int = 0
    private var hasInitialized = false
    private var bookmarkJob: Job? = null
    private var notesJob: Job? = null
    private var lookupJob: Job? = null
    private var scrollSaveJob: Job? = null

    override fun onScreenShow(screen: SimpleLightScreen<Unit>) {
        super.onScreenShow(screen)
        viewModelScope.launch {
            _fontSize.value = repository.getFontSizeSync()
            _pageMode.value = repository.getPageModeSync()
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
        cancelNoteWordSelection()

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

        notesJob?.cancel()
        notesJob = viewModelScope.launch {
            repository.getNotesForPosition(bookId, safeIdx).collect { saved ->
                _chapterNotes.value = saved
            }
        }
    }

    fun nextChapter(landOnStart: Boolean = false) {
        val parsed = _book.value ?: return
        if (_chapterIndex.value < parsed.chapters.lastIndex) {
            openChapter(
                index = _chapterIndex.value + 1,
                explicitScrollPermille = if (landOnStart) 0 else null,
            )
        }
    }

    fun previousChapter(landOnEnd: Boolean = false) {
        if (_chapterIndex.value > 0) {
            openChapter(
                index = _chapterIndex.value - 1,
                explicitScrollPermille = if (landOnEnd) 1000 else null,
            )
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

    fun cycleProgressMode() {
        val next = _progressMode.value.next()
        _progressMode.value = next
        viewModelScope.launch {
            repository.setProgressMode(next)
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

        val existing = WordNoteUtils.findNotesForAnchor(_chapterNotes.value, anchor)
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
        val parsed = _book.value ?: return
        val chapter = parsed.chapters.getOrNull(_chapterIndex.value) ?: return
        if (anchors.isEmpty()) return
        val quoted = WordNoteUtils.buildQuotedExcerpt(
            anchors = anchors,
            paragraphLookup = { idx -> chapter.blocks.getOrNull(idx)?.text },
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
        val parsed = _book.value ?: return
        val idx = _chapterIndex.value
        val chapter = parsed.chapters.getOrNull(idx) ?: return
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
                        bookTitle = parsed.title,
                        format = bookFormat.name,
                        positionIndex = idx,
                        chapterTitle = chapter.title.ifBlank { "Chapter ${idx + 1}" },
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

class TextReaderScreen(
    sealedActivity: SealedLightActivity,
    private val bookId: String,
    private val bookFormat: BookFormat,
    private val initialChapter: Int? = null,
    private val initialScrollPermille: Int? = null,
) : LightScreen<Unit, TextReaderViewModel>(sealedActivity) {

    private val vibrator = sealedActivity.vibrator

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
        val chapterNotes by viewModel.chapterNotes.collectAsState()
        val heldWord by viewModel.heldWord.collectAsState()
        val isSelectingNoteWords by viewModel.isSelectingNoteWords.collectAsState()
        val selectedWordAnchors by viewModel.selectedWordAnchors.collectAsState()
        val composingNote by viewModel.composingNote.collectAsState()
        val fontSize by viewModel.fontSize.collectAsState()
        val pageMode by viewModel.pageMode.collectAsState()
        val isFullScreen by viewModel.isFullScreen.collectAsState()
        val progressMode by viewModel.progressMode.collectAsState()
        val lookupEnabled by viewModel.lookupEnabled.collectAsState()
        val isLookingUp by viewModel.isLookingUp.collectAsState()
        val lookupResult by viewModel.lookupResult.collectAsState()
        val keyboardOptionsFlow = rememberKeyboardOptions()

        val savedNoteAnchors = remember(chapterNotes) {
            chapterNotes.flatMap { WordNoteUtils.decodeAnchors(it.anchorsEncoded) }
        }

        val currentChapter: BookChapter? = book?.chapters?.getOrNull(chapterIndex)
        val totalChapters = book?.chapters?.size ?: 0
        val hasPrev = chapterIndex > 0
        val hasNext = totalChapters > 0 && chapterIndex < totalChapters - 1

        val scrollState = rememberScrollState()
        val blockOffsets = remember(chapterIndex, fontSize) { mutableStateMapOf<Int, Int>() }

        val textVariant = when (fontSize) {
            ReaderFontSize.COMPACT -> LightTextVariant.Detail
            ReaderFontSize.REGULAR -> LightTextVariant.Paragraph
            ReaderFontSize.LARGE -> LightTextVariant.Copy
        }

        val density = LocalDensity.current
        val textMeasurer = rememberTextMeasurer()
        val bodyStyle = scaledReaderStyle(textVariant)
        val headingStyle = scaledReaderStyle(LightTextVariant.Subheading)
        val horizontalPadDp = 1.5f.gridUnitsAsDp()
        val horizontalPadPx = with(density) { horizontalPadDp.roundToPx() }
        val verticalPagePadPx = with(density) { (if (isFullScreen) 24.dp else 12.dp).roundToPx() }
        var viewportSize by remember { mutableStateOf(IntSize.Zero) }

        val pages = remember(
            currentChapter,
            pageMode,
            bodyStyle,
            headingStyle,
            viewportSize,
            horizontalPadPx,
            verticalPagePadPx,
        ) {
            if (pageMode != ReaderPageMode.PAGED || currentChapter == null || viewportSize.width <= 0 || viewportSize.height <= 0) {
                emptyList()
            } else {
                EReaderPaginator.paginateBlocks(
                    blocks = currentChapter.blocks,
                    textMeasurer = textMeasurer,
                    bodyStyle = bodyStyle,
                    headingStyle = headingStyle,
                    viewportWidthPx = (viewportSize.width - horizontalPadPx * 2).coerceAtLeast(100),
                    viewportHeightPx = (viewportSize.height - verticalPagePadPx).coerceAtLeast(100),
                    density = density,
                )
            }
        }

        var currentPageIndex by remember(chapterIndex) { mutableIntStateOf(0) }
        val safePageIndex = if (pages.isEmpty()) 0 else currentPageIndex.coerceIn(0, pages.lastIndex)

        // Compute current scroll position as 0..1000 permille (Scroll mode)
        val scrollModePermille by remember(scrollState) {
            derivedStateOf {
                val max = scrollState.maxValue
                if (max <= 0) 0 else ((scrollState.value.toFloat() / max.toFloat()) * 1000f).roundToInt().coerceIn(0, 1000)
            }
        }

        val activeScrollPermille = if (pageMode == ReaderPageMode.PAGED) {
            pages.getOrNull(safePageIndex)?.permilleStart ?: targetScrollPermille
        } else {
            scrollModePermille
        }

        // Filled bookmark icon whenever current viewport/page is right at a saved bookmark in this chapter
        val isNearSavedBookmark = remember(chapterBookmarks, activeScrollPermille) {
            chapterBookmarks.any { abs(it.scrollPermille - activeScrollPermille) <= BOOKMARK_PROXIMITY_PERMILLE }
        }

        // Restore exact scroll target in SCROLL mode
        LaunchedEffect(chapterIndex, scrollRequestToken, pageMode, currentChapter != null) {
            if (pageMode != ReaderPageMode.SCROLL || currentChapter == null) return@LaunchedEffect
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

        // Restore exact page index in PAGED mode (on chapter load, Full-Screen toggle, font size change, or Scroll -> Pages)
        LaunchedEffect(chapterIndex, scrollRequestToken, pageMode, pages) {
            if (pageMode != ReaderPageMode.PAGED || pages.isEmpty()) return@LaunchedEffect
            currentPageIndex = EReaderPaginator.pageIndexForPermille(pages, targetScrollPermille)
        }

        // Report scroll updates in SCROLL mode
        LaunchedEffect(chapterIndex, pageMode, scrollState) {
            if (pageMode != ReaderPageMode.SCROLL) return@LaunchedEffect
            snapshotFlow { scrollModePermille }
                .distinctUntilChanged()
                .collect { permille ->
                    viewModel.onScrollChanged(permille)
                }
        }

        // Report page progress in PAGED mode
        LaunchedEffect(chapterIndex, pageMode, safePageIndex, pages) {
            if (pageMode != ReaderPageMode.PAGED || pages.isEmpty()) return@LaunchedEffect
            val permille = pages.getOrNull(safePageIndex)?.permilleStart ?: 0
            viewModel.onScrollChanged(permille)
        }

        fun findVisibleSnippet(): String {
            if (pageMode == ReaderPageMode.PAGED) {
                return pages.getOrNull(safePageIndex)?.previewSnippet.orEmpty()
            }
            val chapter = currentChapter ?: return ""
            if (chapter.blocks.isEmpty()) return ""
            val scrollY = scrollState.value
            val bestIdx = blockOffsets.entries
                .filter { it.value <= scrollY + 48 }
                .maxByOrNull { it.value }
                ?.key
                ?: blockOffsets.entries.minByOrNull { abs(it.value - scrollY) }?.key
                ?: ((scrollModePermille / 1000f) * chapter.blocks.lastIndex).roundToInt()
                    .coerceIn(0, chapter.blocks.lastIndex)
            return chapter.blocks.getOrNull(bestIdx)?.text.orEmpty()
        }

        fun turnNextPageOrChapter() {
            if (pageMode == ReaderPageMode.PAGED) {
                if (safePageIndex < pages.lastIndex) {
                    currentPageIndex = safePageIndex + 1
                } else if (hasNext) {
                    viewModel.nextChapter(landOnStart = true)
                }
            } else if (hasNext) {
                viewModel.nextChapter()
            }
        }

        fun turnPrevPageOrChapter() {
            if (pageMode == ReaderPageMode.PAGED) {
                if (safePageIndex > 0) {
                    currentPageIndex = safePageIndex - 1
                } else if (hasPrev) {
                    viewModel.previousChapter(landOnEnd = true)
                } else {
                    goBack()
                }
            } else if (hasPrev) {
                viewModel.previousChapter()
            } else {
                goBack()
            }
        }

        val contentColor = LightThemeTokens.colors.content
        val bookmarkPainter = rememberVectorPainter(
            remember(isNearSavedBookmark, contentColor) {
                if (isNearSavedBookmark) createBookmarkFilledVector(contentColor)
                else createBookmarkOutlineVector(contentColor)
            }
        )

        val haptics = LocalHapticFeedback.current
        val isOverlayOpen = heldWord != null || isLookingUp || lookupResult != null
        val liveActivePermille by rememberUpdatedState(activeScrollPermille)

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
                    title = currentChapter?.title ?: book?.title ?: "Note",
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
                )
                return@LightTheme
            }

            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(LightThemeTokens.colors.background)
                    .readerHorizontalSwipes(
                        enabled = !isOverlayOpen && !isSelectingNoteWords,
                        onEdgeSwipeBack = { goBack() },
                        onSwipeRight = { turnPrevPageOrChapter() },
                        onSwipeLeft = { turnNextPageOrChapter() },
                    ),
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
                            modifier = Modifier.padding(bottom = 0.5f.gridUnitsAsDp()),
                        )
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(
                                    start = horizontalPadDp,
                                    end = horizontalPadDp,
                                    top = 2.dp,
                                    bottom = 6.dp,
                                ),
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
                    } else if (!isFullScreen) {
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
                                        scrollPermille = activeScrollPermille,
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
                                            ((chapterIndex.toFloat() + (activeScrollPermille / 1000f)) / totalChapters.toFloat()) * 100f
                                            ).roundToInt().coerceIn(0, 100)
                                        if (totalChapters > 1) {
                                            "Ch. ${chapterIndex + 1}/$totalChapters • $bookPct%"
                                        } else {
                                            "$bookPct% book"
                                        }
                                    }
                                    ReaderProgressMode.CHAPTER -> {
                                        val scrollPct = (activeScrollPermille / 10).coerceIn(0, 100)
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
                                        .padding(start = 6.dp)
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
                                        .padding(horizontal = 6.dp, vertical = 2.dp),
                                ) {
                                    LightText(
                                        text = "Contents",
                                        variant = LightTextVariant.Detail,
                                    )
                                }
                            }

                            val pagedActive = pageMode == ReaderPageMode.PAGED
                            Box(
                                modifier = Modifier
                                    .padding(start = 6.dp)
                                    .background(
                                        if (pagedActive) LightThemeTokens.colors.content else LightThemeTokens.colors.background,
                                        RoundedCornerShape(4.dp),
                                    )
                                    .border(
                                        width = 1.dp,
                                        color = LightThemeTokens.colors.content.copy(alpha = 0.55f),
                                        shape = RoundedCornerShape(4.dp),
                                    )
                                    .lightClickable { viewModel.togglePageMode(activeScrollPermille) }
                                    .padding(horizontal = 6.dp, vertical = 2.dp),
                            ) {
                                LightText(
                                    text = pageMode.chipLabel,
                                    variant = LightTextVariant.Detail,
                                    color = if (pagedActive) LightThemeTokens.colors.background else LightThemeTokens.colors.content,
                                )
                            }

                            LightText(
                                text = fontSize.displayLabel,
                                variant = LightTextVariant.Detail,
                                lighten = true,
                                modifier = Modifier
                                    .padding(start = 8.dp)
                                    .lightClickable { viewModel.cycleFontSize(activeScrollPermille) },
                            )
                        }
                    }

                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth()
                            .onSizeChanged { viewportSize = it }
                            .pointerInput(isOverlayOpen, isSelectingNoteWords) {
                                if (isOverlayOpen || isSelectingNoteWords) return@pointerInput
                                detectTapGestures(
                                    onDoubleTap = {
                                        viewModel.toggleFullScreen(liveActivePermille)
                                    },
                                )
                            },
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
                            currentChapter != null && pageMode == ReaderPageMode.PAGED -> {
                                val currentPage = pages.getOrNull(safePageIndex)
                                Column(
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .padding(
                                            horizontal = horizontalPadDp,
                                            vertical = if (isFullScreen && !isSelectingNoteWords) 12.dp else 6.dp,
                                        ),
                                ) {
                                    currentPage?.slices?.forEach { slice ->
                                        val fullBlockText = currentChapter.blocks.getOrNull(slice.sourceIndex)?.text ?: slice.text
                                        when {
                                            slice.isHeading -> {
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
                                            }
                                            slice.isQuote -> {
                                                Row(
                                                    modifier = Modifier
                                                        .fillMaxWidth()
                                                        .padding(
                                                            top = if (slice.isContinuation) 2.dp else 4.dp,
                                                            bottom = 10.dp,
                                                        ),
                                                ) {
                                                    Box(
                                                        modifier = Modifier
                                                            .width(2.dp)
                                                            .heightIn(min = 24.dp)
                                                            .background(LightThemeTokens.colors.content.copy(alpha = 0.45f)),
                                                    )
                                                    Spacer(modifier = Modifier.width(10.dp))
                                                    LookupableReaderText(
                                                        text = slice.text,
                                                        paragraphIndex = slice.sourceIndex,
                                                        sliceOffsetInParagraph = slice.charOffsetInBlock,
                                                        savedAnchors = savedNoteAnchors,
                                                        selectedAnchors = selectedWordAnchors,
                                                        isSelectingWords = isSelectingNoteWords,
                                                        variant = textVariant,
                                                        lighten = true,
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
                                                                scrollPermille = liveActivePermille,
                                                            )
                                                        },
                                                        onDoubleTap = {
                                                            viewModel.toggleFullScreen(liveActivePermille)
                                                        },
                                                        modifier = Modifier.weight(1f),
                                                    )
                                                }
                                            }
                                            else -> {
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
                                                            scrollPermille = liveActivePermille,
                                                        )
                                                    },
                                                    onDoubleTap = {
                                                        viewModel.toggleFullScreen(liveActivePermille)
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
                                }
                            }
                            currentChapter != null -> {
                                LightScrollView(
                                    scrollState = scrollState,
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .padding(
                                            start = horizontalPadDp,
                                            top = if (isFullScreen && !isSelectingNoteWords) 10.dp else 0.dp,
                                            bottom = if (isFullScreen && !isSelectingNoteWords) 10.dp else 0.dp,
                                        ),
                                ) {
                                    Spacer(modifier = Modifier.height(10.dp))

                                    currentChapter.blocks.forEachIndexed { idx, block ->
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
                                                        .padding(end = horizontalPadDp, top = 12.dp, bottom = 8.dp),
                                                )
                                            }
                                            block.isQuote -> {
                                                Row(
                                                    modifier = trackOffsetModifier
                                                        .fillMaxWidth()
                                                        .padding(end = horizontalPadDp, top = 6.dp, bottom = 10.dp),
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
                                                        paragraphIndex = idx,
                                                        sliceOffsetInParagraph = 0,
                                                        savedAnchors = savedNoteAnchors,
                                                        selectedAnchors = selectedWordAnchors,
                                                        isSelectingWords = isSelectingNoteWords,
                                                        variant = textVariant,
                                                        lighten = true,
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
                                                                scrollPermille = liveActivePermille,
                                                            )
                                                        },
                                                        onDoubleTap = {
                                                            viewModel.toggleFullScreen(liveActivePermille)
                                                        },
                                                        modifier = Modifier.weight(1f),
                                                    )
                                                }
                                            }
                                            else -> {
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
                                                            scrollPermille = liveActivePermille,
                                                        )
                                                    },
                                                    onDoubleTap = {
                                                        viewModel.toggleFullScreen(liveActivePermille)
                                                    },
                                                    modifier = trackOffsetModifier
                                                        .fillMaxWidth()
                                                        .padding(end = horizontalPadDp, top = 4.dp, bottom = 10.dp),
                                                )
                                            }
                                        }
                                    }

                                    if (!isFullScreen && !isSelectingNoteWords) {
                                        Spacer(modifier = Modifier.height(18.dp))
                                        Row(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .padding(end = horizontalPadDp, top = 8.dp, bottom = 18.dp),
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
                                    } else {
                                        Spacer(modifier = Modifier.height(14.dp))
                                    }
                                }
                            }
                        }
                    }

                    // Quiet bottom page indicator & tap controls in PAGED mode (hidden in Full Screen)
                    if (!isFullScreen && !isSelectingNoteWords && pageMode == ReaderPageMode.PAGED && currentChapter != null && pages.isNotEmpty()) {
                        val canGoBack = safePageIndex > 0 || hasPrev
                        val canGoForward = safePageIndex < pages.lastIndex || hasNext
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
                                    lighten = true,
                                    modifier = Modifier
                                        .weight(1f)
                                        .lightClickable { turnPrevPageOrChapter() },
                                )
                            } else {
                                Spacer(modifier = Modifier.weight(1f))
                            }

                            LightText(
                                text = "${safePageIndex + 1} / ${pages.size}",
                                variant = LightTextVariant.Detail,
                                lighten = true,
                            )

                            Box(
                                modifier = Modifier.weight(1f),
                                contentAlignment = Alignment.CenterEnd,
                            ) {
                                if (canGoForward) {
                                    LightText(
                                        text = "Next →",
                                        variant = LightTextVariant.Detail,
                                        lighten = true,
                                        modifier = Modifier.lightClickable { turnNextPageOrChapter() },
                                    )
                                }
                            }
                        }
                    }
                }

                // Multi-Dictionary Press-and-Hold + Add Note Overlay Card
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
internal fun LookupableReaderText(
    text: String,
    paragraphIndex: Int,
    sliceOffsetInParagraph: Int,
    savedAnchors: List<WordAnchor>,
    selectedAnchors: List<WordAnchor>,
    isSelectingWords: Boolean,
    variant: LightTextVariant,
    lighten: Boolean,
    onTapChar: (Int) -> Unit,
    onLongPressChar: (Int) -> Unit,
    onDoubleTap: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val style = scaledReaderStyle(variant)
    val colors = LightThemeTokens.colors
    val color = if (lighten) colors.contentSecondary else colors.content
    var layoutResult by remember(text, variant) { mutableStateOf<TextLayoutResult?>(null) }
    val currentOnDoubleTap by rememberUpdatedState(onDoubleTap)
    val currentOnTap by rememberUpdatedState(onTapChar)
    val currentOnLongPress by rememberUpdatedState(onLongPressChar)

    val annotatedText = remember(text, paragraphIndex, sliceOffsetInParagraph, savedAnchors, selectedAnchors) {
        WordNoteUtils.buildUnderlinedText(
            text = text,
            paragraphIndex = paragraphIndex,
            sliceOffsetInParagraph = sliceOffsetInParagraph,
            savedAnchors = savedAnchors,
            selectedAnchors = selectedAnchors,
        )
    }

    val mod = modifier.pointerInput(text, isSelectingWords) {
        if (isSelectingWords) {
            detectTapGestures(
                onTap = { pos ->
                    layoutResult?.let { layout ->
                        currentOnTap(layout.getOffsetForPosition(pos))
                    }
                },
            )
        } else {
            detectTapGestures(
                onDoubleTap = { currentOnDoubleTap() },
                onLongPress = { pos ->
                    layoutResult?.let { layout ->
                        currentOnLongPress(layout.getOffsetForPosition(pos))
                    }
                },
            )
        }
    }

    Text(
        text = annotatedText,
        color = color,
        style = style,
        onTextLayout = { layoutResult = it },
        modifier = mod,
    )
}

@Composable
internal fun scaledReaderStyle(variant: LightTextVariant): TextStyle {
    val t = LightThemeTokens.typography
    val base = when (variant) {
        LightTextVariant.Detail -> t.detail
        LightTextVariant.Copy -> t.copy
        LightTextVariant.Subheading -> t.subheading
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
    heldWord: HeldWordContext?,
    lookupEnabled: Boolean,
    isLookingUp: Boolean,
    result: WordLookupResult?,
    onAddNote: () -> Unit,
    onEditNote: (BookNoteEntity) -> Unit,
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
            .heightIn(max = 370.dp),
    ) {
        val hits = result?.hits.orEmpty()
        val activeHit = hits.getOrNull(selectedHitIdx)
        val displayHeadword = activeHit?.headword ?: result?.headword ?: heldWord?.wordText ?: "Looking up..."
        val displaySource = when {
            activeHit != null -> activeHit.sourceLabel
            result != null -> result.sourceLabel
            isLookingUp -> "Looking up definition..."
            !lookupEnabled -> "Offline dictionary not downloaded"
            else -> ""
        }
        val displayDef = activeHit?.definition ?: result?.definition

        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                LightText(text = displayHeadword, variant = LightTextVariant.Subheading)
                if (displaySource.isNotBlank()) {
                    LightText(text = displaySource, variant = LightTextVariant.Detail, lighten = true)
                }
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
                .weight(1f, fill = false)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState()),
        ) {
            when {
                isLookingUp -> {
                    LightText(text = "Looking up...", variant = LightTextVariant.Detail, lighten = true)
                }
                displayDef != null -> {
                    LightText(text = displayDef, variant = LightTextVariant.Paragraph)
                }
                !lookupEnabled -> {
                    LightText(
                        text = "Download an offline dictionary in Settings to see definitions here.",
                        variant = LightTextVariant.Detail,
                        lighten = true,
                    )
                }
            }

            val existingNotes = heldWord?.existingNotes.orEmpty()
            if (existingNotes.isNotEmpty()) {
                HorizontalDivider(
                    color = colors.content.copy(alpha = 0.2f),
                    thickness = 1.dp,
                    modifier = Modifier.padding(vertical = 8.dp),
                )
                existingNotes.take(2).forEach { note ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp)
                            .lightClickable { onEditNote(note) },
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            LightText(
                                text = "NOTE",
                                variant = LightTextVariant.Detail,
                                lighten = true,
                            )
                            LightText(
                                text = note.noteText,
                                variant = LightTextVariant.Paragraph,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.padding(top = 2.dp),
                            )
                        }
                        LightText(
                            text = "Edit",
                            variant = LightTextVariant.Detail,
                            modifier = Modifier.padding(start = 10.dp),
                        )
                    }
                }
            }
        }

        // Action bar right below definition: Add Note
        if (heldWord != null) {
            HorizontalDivider(
                color = colors.content.copy(alpha = 0.25f),
                thickness = 1.dp,
                modifier = Modifier.padding(top = 10.dp, bottom = 8.dp),
            )
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .border(
                        width = 1.dp,
                        color = colors.content,
                        shape = RoundedCornerShape(4.dp),
                    )
                    .lightClickable { onAddNote() }
                    .padding(vertical = 8.dp),
                contentAlignment = Alignment.Center,
            ) {
                LightText(
                    text = "Add Note",
                    variant = LightTextVariant.Paragraph,
                )
            }
        }
    }
}
