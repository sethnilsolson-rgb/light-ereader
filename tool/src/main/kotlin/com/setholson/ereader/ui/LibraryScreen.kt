package com.setholson.ereader.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewModelScope
import com.setholson.ereader.data.ReaderEntryPoint
import com.setholson.ereader.data.ReaderRepository
import com.setholson.ereader.db.BookProgressEntity
import com.setholson.ereader.db.ReaderDatabase
import com.setholson.ereader.model.BookFormat
import com.setholson.ereader.model.LibraryBook
import com.thelightphone.sdk.InitialScreen
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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

class LibraryViewModel(
    private val repository: ReaderRepository,
) : LightViewModel<Unit>() {

    private val _books = MutableStateFlow<List<LibraryBook>>(emptyList())
    val books: StateFlow<List<LibraryBook>> = _books.asStateFlow()

    private val _continueBook = MutableStateFlow<LibraryBook?>(null)
    val continueBook: StateFlow<LibraryBook?> = _continueBook.asStateFlow()

    private val _bookmarkCount = MutableStateFlow(0)
    val bookmarkCount: StateFlow<Int> = _bookmarkCount.asStateFlow()

    private val _noteCount = MutableStateFlow(0)
    val noteCount: StateFlow<Int> = _noteCount.asStateFlow()

    private val _isScanning = MutableStateFlow(false)
    val isScanning: StateFlow<Boolean> = _isScanning.asStateFlow()

    private val manualRefreshTrigger = MutableStateFlow(0L)
    private var scanJob: Job? = null
    private var bookmarkJob: Job? = null
    private var noteJob: Job? = null

    override fun onScreenShow(screen: SimpleLightScreen<Unit>) {
        super.onScreenShow(screen)

        bookmarkJob?.cancel()
        bookmarkJob = viewModelScope.launch {
            repository.bookmarkCount.collect { count ->
                _bookmarkCount.value = count
            }
        }

        noteJob?.cancel()
        noteJob = viewModelScope.launch {
            repository.noteCount.collect { count ->
                _noteCount.value = count
            }
        }

        scanJob?.cancel()
        scanJob = viewModelScope.launch {
            combine(
                repository.allProgress,
                ReaderEntryPoint.libraryRefreshSignal,
                manualRefreshTrigger,
            ) { progress, _, _ -> progress }.collect { progress ->
                rescan(progress)
            }
        }
    }

    fun refreshLibrary() {
        manualRefreshTrigger.value = System.currentTimeMillis()
    }

    private suspend fun rescan(progress: List<BookProgressEntity>) {
        _isScanning.value = true
        val scanned = repository.scanLibrary(progress)
        _books.value = scanned
        _continueBook.value = scanned
            .filter { it.lastReadAt != null }
            .maxByOrNull { it.lastReadAt ?: 0L }
        _isScanning.value = false
    }
}

@InitialScreen
class LibraryScreen(
    sealedActivity: SealedLightActivity,
) : LightScreen<Unit, LibraryViewModel>(sealedActivity) {

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

    override val viewModelClass: Class<LibraryViewModel>
        get() = LibraryViewModel::class.java

    override fun createViewModel() = LibraryViewModel(repository)

    private fun openBook(book: LibraryBook, startPosition: Int? = null) {
        if (book.format == BookFormat.PDF) {
            navigateTo(screenFactory = { act ->
                PdfReaderScreen(
                    sealedActivity = act,
                    bookId = book.id,
                    bookTitle = book.title,
                    initialPage = startPosition,
                )
            })
        } else {
            navigateTo(screenFactory = { act ->
                TextReaderScreen(
                    sealedActivity = act,
                    bookId = book.id,
                    bookFormat = book.format,
                    initialChapter = startPosition,
                )
            })
        }
    }

    private fun openAddBooks(tab: AddBooksTab) {
        navigateTo(screenFactory = { act ->
            AddBooksScreen(act, initialTab = tab)
        })
    }

    @Composable
    override fun Content() {
        val themeColors by LightThemeController.colors.collectAsState()
        val books by viewModel.books.collectAsState()
        val continueBook by viewModel.continueBook.collectAsState()
        val bookmarkCount by viewModel.bookmarkCount.collectAsState()
        val noteCount by viewModel.noteCount.collectAsState()
        val isScanning by viewModel.isScanning.collectAsState()

        val userBooks = books.filter { !it.isBuiltIn }
        val guideBook = books.firstOrNull { it.isBuiltIn }

        LightTheme(colors = themeColors) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(LightThemeTokens.colors.background)
            ) {
                Column(modifier = Modifier.fillMaxSize()) {
                    LightTopBar(
                        leftButton = LightBarButton.LightIcon(
                            icon = LightIcons.ADD,
                            contentDescription = "Add Books",
                            onClick = { openAddBooks(AddBooksTab.CLASSICS) },
                        ),
                        center = LightTopBarCenter.Text("E-Reader"),
                        rightButton = LightBarButton.LightIcon(
                            icon = LightIcons.SETTINGS,
                            contentDescription = "Settings",
                            onClick = {
                                navigateTo(screenFactory = { act ->
                                    SettingsScreen(act)
                                })
                            },
                        ),
                        modifier = Modifier.padding(bottom = 0.5f.gridUnitsAsDp()),
                    )

                    LightScrollView(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth()
                            .padding(start = 1.5f.gridUnitsAsDp()),
                    ) {
                        val rightPad = 1.5f.gridUnitsAsDp()
                        Spacer(modifier = Modifier.height(8.dp))

                        // 1. Continue Reading
                        continueBook?.let { recent ->
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(end = rightPad, top = 4.dp, bottom = 14.dp)
                                    .border(
                                        width = 1.dp,
                                        color = LightThemeTokens.colors.content.copy(alpha = 0.45f),
                                        shape = RoundedCornerShape(4.dp),
                                    )
                                    .lightClickable { openBook(recent) }
                                    .padding(horizontal = 12.dp, vertical = 10.dp),
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    LightText(
                                        text = "CONTINUE READING",
                                        variant = LightTextVariant.Detail,
                                        lighten = true,
                                        modifier = Modifier.weight(1f),
                                    )
                                    FormatBadge(recent.format.badge)
                                }
                                Spacer(modifier = Modifier.height(4.dp))
                                LightText(
                                    text = recent.title,
                                    variant = LightTextVariant.Subheading,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                LightText(
                                    text = formatBookProgress(recent),
                                    variant = LightTextVariant.Detail,
                                    lighten = true,
                                    modifier = Modifier.padding(top = 3.dp),
                                )
                            }
                        }

                        // 2. Quick Add Buttons (Free Classics & Wi-Fi Upload)
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(end = rightPad, bottom = 14.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            QuickActionTile(
                                title = "Free Classics",
                                subtitle = "19 free books",
                                onClick = { openAddBooks(AddBooksTab.CLASSICS) },
                                modifier = Modifier.weight(1f),
                            )
                            QuickActionTile(
                                title = "Wi-Fi Upload",
                                subtitle = "Send from PC/Mac",
                                onClick = { openAddBooks(AddBooksTab.WIFI) },
                                modifier = Modifier.weight(1f),
                            )
                        }

                        // 3. Bookmarks & Notes Quick Entry
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(end = rightPad, top = 2.dp, bottom = 14.dp)
                                .lightClickable {
                                    navigateTo(screenFactory = { act ->
                                        BookmarksScreen(act)
                                    })
                                },
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                LightText(
                                    text = "Bookmarks & Notes",
                                    variant = LightTextVariant.Subheading,
                                )
                                val bmLabel = if (bookmarkCount == 1) "1 bookmark" else "$bookmarkCount bookmarks"
                                val noteLabel = if (noteCount == 1) "1 note" else "$noteCount notes"
                                LightText(
                                    text = "$noteLabel • $bmLabel",
                                    variant = LightTextVariant.Detail,
                                    lighten = true,
                                    modifier = Modifier.padding(top = 2.dp),
                                )
                            }
                        }

                        // 4. User Library Section Header
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(end = rightPad, bottom = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            LightText(
                                text = "LIBRARY (${userBooks.size})",
                                variant = LightTextVariant.Detail,
                                lighten = true,
                                modifier = Modifier.weight(1f),
                            )
                            LightText(
                                text = if (isScanning) "Scanning..." else "↻ Refresh",
                                variant = LightTextVariant.Detail,
                                lighten = true,
                                modifier = Modifier.lightClickable { viewModel.refreshLibrary() },
                            )
                        }

                        if (userBooks.isEmpty()) {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(end = rightPad, bottom = 16.dp)
                                    .border(
                                        width = 1.dp,
                                        color = LightThemeTokens.colors.content.copy(alpha = 0.35f),
                                        shape = RoundedCornerShape(4.dp),
                                    )
                                    .lightClickable { openAddBooks(AddBooksTab.CLASSICS) }
                                    .padding(horizontal = 12.dp, vertical = 12.dp),
                            ) {
                                LightText(
                                    text = "No Books Yet",
                                    variant = LightTextVariant.Subheading,
                                )
                                LightText(
                                    text = "Tap Free Classics above to download 19 classic books right on your phone, or tap Wi-Fi Upload to drag-and-drop your own .epub, .pdf, .txt, or .md files from any computer.",
                                    variant = LightTextVariant.Detail,
                                    lighten = true,
                                    modifier = Modifier.padding(top = 4.dp),
                                )
                            }
                        } else {
                            userBooks.forEach { book ->
                                BookRowItem(
                                    book = book,
                                    onClick = { openBook(book) },
                                    modifier = Modifier.padding(end = rightPad),
                                )
                            }
                            Spacer(modifier = Modifier.height(10.dp))
                        }

                        // 5. Built-in Quick Guide
                        guideBook?.let { guide ->
                            Spacer(modifier = Modifier.height(6.dp))
                            LightText(
                                text = "GETTING STARTED",
                                variant = LightTextVariant.Detail,
                                lighten = true,
                                modifier = Modifier.padding(bottom = 6.dp),
                            )
                            BookRowItem(
                                book = guide,
                                onClick = { openBook(guide) },
                                modifier = Modifier.padding(end = rightPad),
                            )
                        }

                        Spacer(modifier = Modifier.height(18.dp))
                    }
                }
            }
        }
    }
}

@Composable
private fun QuickActionTile(
    title: String,
    subtitle: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .border(
                width = 1.dp,
                color = LightThemeTokens.colors.content.copy(alpha = 0.45f),
                shape = RoundedCornerShape(4.dp),
            )
            .lightClickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 9.dp),
    ) {
        LightText(
            text = title,
            variant = LightTextVariant.Subheading,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        LightText(
            text = subtitle,
            variant = LightTextVariant.Detail,
            lighten = true,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 2.dp),
        )
    }
}

@Composable
private fun BookRowItem(
    book: LibraryBook,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 9.dp)
            .lightClickable(onClick = onClick),
        verticalAlignment = Alignment.Top,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            LightText(
                text = book.title,
                variant = LightTextVariant.Subheading,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            val detailParts = buildList {
                if (book.author.isNotBlank()) add(book.author)
                if (book.progressPercent > 0) add("${book.progressPercent}%")
                val sz = formatFileSize(book.fileSizeBytes)
                if (sz.isNotBlank()) add(sz)
            }
            if (detailParts.isNotEmpty()) {
                LightText(
                    text = detailParts.joinToString(" • "),
                    variant = LightTextVariant.Detail,
                    lighten = true,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
        }
        Spacer(modifier = Modifier.width(8.dp))
        FormatBadge(book.format.badge)
    }
}

@Composable
private fun FormatBadge(label: String) {
    Box(
        modifier = Modifier
            .border(
                width = 1.dp,
                color = LightThemeTokens.colors.content.copy(alpha = 0.5f),
                shape = RoundedCornerShape(3.dp),
            )
            .padding(horizontal = 6.dp, vertical = 2.dp),
        contentAlignment = Alignment.Center,
    ) {
        LightText(
            text = label,
            variant = LightTextVariant.Detail,
            lighten = true,
        )
    }
}

private fun formatBookProgress(book: LibraryBook): String {
    val unit = if (book.format == BookFormat.PDF) "Page" else "Ch."
    val current = book.currentPosition + 1
    val total = book.totalPositions.coerceAtLeast(1)
    return buildString {
        if (book.author.isNotBlank()) {
            append(book.author)
            append(" • ")
        }
        append("$unit $current of $total")
        if (book.progressPercent > 0) {
            append(" (${book.progressPercent}%)")
        }
    }
}
