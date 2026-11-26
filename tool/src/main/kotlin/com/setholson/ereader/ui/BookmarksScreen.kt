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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.material3.HorizontalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewModelScope
import com.setholson.ereader.data.ReaderRepository
import com.setholson.ereader.db.BookBookmarkEntity
import com.setholson.ereader.db.BookNoteEntity
import com.setholson.ereader.db.ReaderDatabase
import com.setholson.ereader.model.BookFormat
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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private sealed class BookmarksHubPage {
    data object Root : BookmarksHubPage()
    data object NotesBooks : BookmarksHubPage()
    data class NotesChapters(val bookId: String, val bookTitle: String) : BookmarksHubPage()
    data class NotesChapterDetail(
        val bookId: String,
        val bookTitle: String,
        val positionIndex: Int,
        val chapterTitle: String,
    ) : BookmarksHubPage()
}

class BookmarksViewModel(
    private val repository: ReaderRepository,
) : LightViewModel<Unit>() {

    private val _bookmarks = MutableStateFlow<List<BookBookmarkEntity>>(emptyList())
    val bookmarks: StateFlow<List<BookBookmarkEntity>> = _bookmarks.asStateFlow()

    private val _notes = MutableStateFlow<List<BookNoteEntity>>(emptyList())
    val notes: StateFlow<List<BookNoteEntity>> = _notes.asStateFlow()

    private var collectJob: Job? = null
    private var notesJob: Job? = null

    override fun onScreenShow(screen: SimpleLightScreen<Unit>) {
        super.onScreenShow(screen)
        collectJob?.cancel()
        collectJob = viewModelScope.launch {
            repository.bookmarks.collect { list ->
                _bookmarks.value = list
            }
        }
        notesJob?.cancel()
        notesJob = viewModelScope.launch {
            repository.allNotes.collect { list ->
                _notes.value = list
            }
        }
    }

    fun deleteBookmark(bookmark: BookBookmarkEntity) {
        viewModelScope.launch {
            repository.deleteBookmark(bookmark)
        }
    }

    fun updateNoteText(note: BookNoteEntity, newText: String) {
        val trimmed = newText.trim()
        viewModelScope.launch {
            if (trimmed.isEmpty()) {
                repository.deleteNote(note)
            } else {
                repository.saveNote(
                    note.copy(
                        noteText = trimmed,
                        updatedAt = System.currentTimeMillis(),
                    )
                )
            }
        }
    }

    fun deleteNote(note: BookNoteEntity) {
        viewModelScope.launch {
            repository.deleteNote(note)
        }
    }
}

class BookmarksScreen(
    sealedActivity: SealedLightActivity,
) : LightScreen<Unit, BookmarksViewModel>(sealedActivity) {

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

    override val viewModelClass: Class<BookmarksViewModel>
        get() = BookmarksViewModel::class.java

    override fun createViewModel() = BookmarksViewModel(repository)

    private fun openBookmark(bookmark: BookBookmarkEntity) {
        val format = runCatching { BookFormat.valueOf(bookmark.format) }.getOrDefault(BookFormat.EPUB)
        if (format == BookFormat.PDF) {
            navigateTo(screenFactory = { act ->
                PdfReaderScreen(
                    sealedActivity = act,
                    bookId = bookmark.bookId,
                    bookTitle = bookmark.bookTitle,
                    initialPage = bookmark.positionIndex,
                    initialScrollPermille = bookmark.scrollPermille,
                )
            })
        } else {
            navigateTo(screenFactory = { act ->
                TextReaderScreen(
                    sealedActivity = act,
                    bookId = bookmark.bookId,
                    bookFormat = format,
                    initialChapter = bookmark.positionIndex,
                    initialScrollPermille = bookmark.scrollPermille,
                )
            })
        }
    }

    private fun openNote(note: BookNoteEntity) {
        val format = runCatching { BookFormat.valueOf(note.format) }.getOrDefault(BookFormat.EPUB)
        if (format == BookFormat.PDF) {
            navigateTo(screenFactory = { act ->
                PdfReaderScreen(
                    sealedActivity = act,
                    bookId = note.bookId,
                    bookTitle = note.bookTitle,
                    initialPage = note.positionIndex,
                    initialScrollPermille = note.scrollPermille,
                    preferTextMode = true,
                )
            })
        } else {
            navigateTo(screenFactory = { act ->
                TextReaderScreen(
                    sealedActivity = act,
                    bookId = note.bookId,
                    bookFormat = format,
                    initialChapter = note.positionIndex,
                    initialScrollPermille = note.scrollPermille,
                )
            })
        }
    }

    @Composable
    override fun Content() {
        val themeColors by LightThemeController.colors.collectAsState()
        val bookmarks by viewModel.bookmarks.collectAsState()
        val notes by viewModel.notes.collectAsState()
        val keyboardOptionsFlow = rememberKeyboardOptions()

        var page by remember { mutableStateOf<BookmarksHubPage>(BookmarksHubPage.Root) }
        var editingNote by remember { mutableStateOf<BookNoteEntity?>(null) }

        val bookGroups = remember(notes) {
            WordNoteUtils.groupNotesByBookAndChapter(notes)
        }

        fun navigateUp() {
            page = when (val cur = page) {
                is BookmarksHubPage.Root -> {
                    goBack()
                    cur
                }
                is BookmarksHubPage.NotesBooks -> BookmarksHubPage.Root
                is BookmarksHubPage.NotesChapters -> BookmarksHubPage.NotesBooks
                is BookmarksHubPage.NotesChapterDetail -> BookmarksHubPage.NotesChapters(cur.bookId, cur.bookTitle)
            }
        }

        val dateFormatter = remember { SimpleDateFormat("MMM d, h:mm a", Locale.getDefault()) }

        LightTheme(colors = themeColors) {
            val activeEdit = editingNote
            if (activeEdit != null) {
                val noteFieldState = remember(activeEdit.id) {
                    TextFieldState(
                        initialText = activeEdit.noteText,
                        initialSelection = TextRange(activeEdit.noteText.length),
                    )
                }
                ReaderNoteComposer(
                    title = activeEdit.chapterTitle.ifBlank { "Edit Note" },
                    quotedExcerpt = activeEdit.quotedText,
                    state = noteFieldState,
                    keyboardOptionsFlow = keyboardOptionsFlow,
                    vibrator = vibrator,
                    onSave = { updated ->
                        viewModel.updateNoteText(activeEdit, updated)
                        editingNote = null
                    },
                    onBack = { editingNote = null },
                    onDelete = {
                        viewModel.deleteNote(activeEdit)
                        editingNote = null
                    },
                    editorKey = "edit-note-${activeEdit.id}",
                )
                return@LightTheme
            }

            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(LightThemeTokens.colors.background)
                    .swipeToBack { navigateUp() },
            ) {
                val sidePad = 1.5f.gridUnitsAsDp()
                val colors = LightThemeTokens.colors

                Column(modifier = Modifier.fillMaxSize()) {
                    val topTitle = when (val cur = page) {
                        is BookmarksHubPage.Root -> "Bookmarks & Notes"
                        is BookmarksHubPage.NotesBooks -> "Notes"
                        is BookmarksHubPage.NotesChapters -> cur.bookTitle
                        is BookmarksHubPage.NotesChapterDetail -> cur.chapterTitle
                    }

                    LightTopBar(
                        leftButton = LightBarButton.LightIcon(
                            icon = LightIcons.BACK,
                            onClick = { navigateUp() },
                        ),
                        center = LightTopBarCenter.Text(topTitle),
                        modifier = Modifier.padding(bottom = 0.5f.gridUnitsAsDp()),
                    )

                    when (val cur = page) {
                        is BookmarksHubPage.Root -> {
                            LightScrollView(
                                modifier = Modifier
                                    .weight(1f)
                                    .fillMaxWidth()
                                    .padding(start = sidePad),
                            ) {
                                Spacer(modifier = Modifier.height(8.dp))

                                // Top dedicated Notes section entry
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(end = sidePad, top = 4.dp, bottom = 10.dp)
                                        .border(
                                            width = 1.dp,
                                            color = colors.content.copy(alpha = 0.45f),
                                            shape = RoundedCornerShape(4.dp),
                                        )
                                        .lightClickable { page = BookmarksHubPage.NotesBooks }
                                        .padding(horizontal = 12.dp, vertical = 12.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        LightText(
                                            text = if (notes.isEmpty()) "Notes" else "Notes (${notes.size})",
                                            variant = LightTextVariant.Subheading,
                                        )
                                        val noteSub = when {
                                            notes.isEmpty() -> "Hold any word while reading to add a note"
                                            bookGroups.size == 1 -> "1 book • Tap to browse"
                                            else -> "${bookGroups.size} books • Tap to browse"
                                        }
                                        LightText(
                                            text = noteSub,
                                            variant = LightTextVariant.Detail,
                                            lighten = true,
                                            modifier = Modifier.padding(top = 2.dp),
                                        )
                                    }
                                    LightText(
                                        text = "→",
                                        variant = LightTextVariant.Subheading,
                                        lighten = true,
                                        modifier = Modifier.padding(start = 8.dp),
                                    )
                                }

                                HorizontalDivider(
                                    color = colors.content.copy(alpha = 0.2f),
                                    thickness = 1.dp,
                                    modifier = Modifier.padding(end = sidePad, top = 4.dp, bottom = 12.dp),
                                )

                                LightText(
                                    text = "BOOKMARKS",
                                    variant = LightTextVariant.Detail,
                                    lighten = true,
                                    modifier = Modifier.padding(bottom = 4.dp),
                                )

                                if (bookmarks.isEmpty()) {
                                    LightText(
                                        text = "No bookmarks yet. Tap the bookmark icon while reading any chapter or PDF page.",
                                        variant = LightTextVariant.Detail,
                                        lighten = true,
                                        modifier = Modifier.padding(end = sidePad, top = 8.dp, bottom = 16.dp),
                                    )
                                } else {
                                    bookmarks.forEach { bookmark ->
                                        Row(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .padding(end = sidePad, top = 10.dp, bottom = 10.dp)
                                                .lightClickable { openBookmark(bookmark) },
                                            verticalAlignment = Alignment.CenterVertically,
                                        ) {
                                            Column(modifier = Modifier.weight(1f)) {
                                                LightText(
                                                    text = bookmark.bookTitle,
                                                    variant = LightTextVariant.Subheading,
                                                    maxLines = 1,
                                                    overflow = TextOverflow.Ellipsis,
                                                )
                                                LightText(
                                                    text = bookmark.positionLabel,
                                                    variant = LightTextVariant.Detail,
                                                    lighten = true,
                                                    modifier = Modifier.padding(top = 2.dp),
                                                )
                                                if (bookmark.snippet.isNotBlank()) {
                                                    LightText(
                                                        text = bookmark.snippet,
                                                        variant = LightTextVariant.Detail,
                                                        lighten = true,
                                                        maxLines = 2,
                                                        overflow = TextOverflow.Ellipsis,
                                                        modifier = Modifier.padding(top = 2.dp),
                                                    )
                                                }
                                            }

                                            LightText(
                                                text = "Delete",
                                                variant = LightTextVariant.Detail,
                                                lighten = true,
                                                modifier = Modifier
                                                    .padding(start = 12.dp)
                                                    .lightClickable {
                                                        viewModel.deleteBookmark(bookmark)
                                                    },
                                            )
                                        }
                                    }
                                }

                                Spacer(modifier = Modifier.height(16.dp))
                            }
                        }

                        is BookmarksHubPage.NotesBooks -> {
                            if (bookGroups.isEmpty()) {
                                Box(
                                    modifier = Modifier
                                        .weight(1f)
                                        .fillMaxWidth()
                                        .padding(2f.gridUnitsAsDp()),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                        LightText(
                                            text = "No Notes Yet",
                                            variant = LightTextVariant.Subheading,
                                            modifier = Modifier.padding(bottom = 8.dp),
                                        )
                                        LightText(
                                            text = "Hold on any word while reading, tap Add Note, select words to underline, and write your thought.",
                                            variant = LightTextVariant.Detail,
                                            lighten = true,
                                        )
                                    }
                                }
                            } else {
                                LightScrollView(
                                    modifier = Modifier
                                        .weight(1f)
                                        .fillMaxWidth()
                                        .padding(start = sidePad),
                                ) {
                                    Spacer(modifier = Modifier.height(8.dp))
                                    bookGroups.forEach { book ->
                                        Row(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .padding(end = sidePad, top = 12.dp, bottom = 12.dp)
                                                .lightClickable {
                                                    page = BookmarksHubPage.NotesChapters(book.bookId, book.bookTitle)
                                                },
                                            verticalAlignment = Alignment.CenterVertically,
                                        ) {
                                            Column(modifier = Modifier.weight(1f)) {
                                                LightText(
                                                    text = book.bookTitle,
                                                    variant = LightTextVariant.Subheading,
                                                    maxLines = 2,
                                                    overflow = TextOverflow.Ellipsis,
                                                )
                                                val nLabel = if (book.totalNoteCount == 1) "1 note" else "${book.totalNoteCount} notes"
                                                val unit = if (book.format == BookFormat.PDF.name) "page" else "chapter"
                                                val cLabel = if (book.chapters.size == 1) "1 $unit" else "${book.chapters.size} ${unit}s"
                                                LightText(
                                                    text = "$nLabel • $cLabel",
                                                    variant = LightTextVariant.Detail,
                                                    lighten = true,
                                                    modifier = Modifier.padding(top = 2.dp),
                                                )
                                            }
                                            LightText(
                                                text = "→",
                                                variant = LightTextVariant.Detail,
                                                lighten = true,
                                                modifier = Modifier.padding(start = 8.dp),
                                            )
                                        }
                                    }
                                    Spacer(modifier = Modifier.height(16.dp))
                                }
                            }
                        }

                        is BookmarksHubPage.NotesChapters -> {
                            val selectedBook = bookGroups.firstOrNull { it.bookId == cur.bookId }
                            if (selectedBook == null) {
                                page = BookmarksHubPage.NotesBooks
                            } else {
                                LightScrollView(
                                    modifier = Modifier
                                        .weight(1f)
                                        .fillMaxWidth()
                                        .padding(start = sidePad),
                                ) {
                                    Spacer(modifier = Modifier.height(8.dp))
                                    selectedBook.chapters.forEach { chap ->
                                        Row(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .padding(end = sidePad, top = 12.dp, bottom = 12.dp)
                                                .lightClickable {
                                                    page = BookmarksHubPage.NotesChapterDetail(
                                                        bookId = selectedBook.bookId,
                                                        bookTitle = selectedBook.bookTitle,
                                                        positionIndex = chap.positionIndex,
                                                        chapterTitle = chap.chapterTitle,
                                                    )
                                                },
                                            verticalAlignment = Alignment.CenterVertically,
                                        ) {
                                            Column(modifier = Modifier.weight(1f)) {
                                                LightText(
                                                    text = chap.chapterTitle,
                                                    variant = LightTextVariant.Subheading,
                                                    maxLines = 2,
                                                    overflow = TextOverflow.Ellipsis,
                                                )
                                                val nLabel = if (chap.notes.size == 1) "1 note" else "${chap.notes.size} notes"
                                                LightText(
                                                    text = nLabel,
                                                    variant = LightTextVariant.Detail,
                                                    lighten = true,
                                                    modifier = Modifier.padding(top = 2.dp),
                                                )
                                            }
                                            LightText(
                                                text = "→",
                                                variant = LightTextVariant.Detail,
                                                lighten = true,
                                                modifier = Modifier.padding(start = 8.dp),
                                            )
                                        }
                                    }
                                    Spacer(modifier = Modifier.height(16.dp))
                                }
                            }
                        }

                        is BookmarksHubPage.NotesChapterDetail -> {
                            val chapterNotes = bookGroups
                                .firstOrNull { it.bookId == cur.bookId }
                                ?.chapters
                                ?.firstOrNull { it.positionIndex == cur.positionIndex }
                                ?.notes
                                .orEmpty()

                            if (chapterNotes.isEmpty()) {
                                page = BookmarksHubPage.NotesChapters(cur.bookId, cur.bookTitle)
                            } else {
                                LightScrollView(
                                    modifier = Modifier
                                        .weight(1f)
                                        .fillMaxWidth()
                                        .padding(start = sidePad),
                                ) {
                                    Spacer(modifier = Modifier.height(8.dp))
                                    chapterNotes.forEachIndexed { idx, note ->
                                        Column(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .padding(end = sidePad, top = 10.dp, bottom = 10.dp),
                                        ) {
                                            if (note.quotedText.isNotBlank()) {
                                                val quoteStyle = noteScaledTextStyle(
                                                    LightThemeTokens.typography.detail,
                                                    colors.contentSecondary,
                                                ).copy(textDecoration = TextDecoration.Underline)
                                                BasicText(
                                                    text = "\u201C${note.quotedText}\u201D",
                                                    style = quoteStyle,
                                                    maxLines = 2,
                                                    overflow = TextOverflow.Ellipsis,
                                                )
                                            }
                                            LightText(
                                                text = note.noteText,
                                                variant = LightTextVariant.Paragraph,
                                                modifier = Modifier
                                                    .fillMaxWidth()
                                                    .padding(top = 5.dp)
                                                    .lightClickable { editingNote = note },
                                            )
                                            Row(
                                                modifier = Modifier
                                                    .fillMaxWidth()
                                                    .padding(top = 8.dp),
                                                horizontalArrangement = Arrangement.SpaceBetween,
                                                verticalAlignment = Alignment.CenterVertically,
                                            ) {
                                                LightText(
                                                    text = dateFormatter.format(Date(note.updatedAt)),
                                                    variant = LightTextVariant.Detail,
                                                    lighten = true,
                                                )
                                                Row(
                                                    horizontalArrangement = Arrangement.spacedBy(14.dp),
                                                    verticalAlignment = Alignment.CenterVertically,
                                                ) {
                                                    LightText(
                                                        text = "Open",
                                                        variant = LightTextVariant.Detail,
                                                        modifier = Modifier.lightClickable {
                                                            openNote(note)
                                                        },
                                                    )
                                                    LightText(
                                                        text = "Edit",
                                                        variant = LightTextVariant.Detail,
                                                        modifier = Modifier.lightClickable {
                                                            editingNote = note
                                                        },
                                                    )
                                                    LightText(
                                                        text = "Delete",
                                                        variant = LightTextVariant.Detail,
                                                        lighten = true,
                                                        modifier = Modifier.lightClickable {
                                                            viewModel.deleteNote(note)
                                                        },
                                                    )
                                                }
                                            }
                                        }
                                        if (idx < chapterNotes.lastIndex) {
                                            HorizontalDivider(
                                                color = colors.content.copy(alpha = 0.16f),
                                                thickness = 1.dp,
                                                modifier = Modifier.padding(end = sidePad),
                                            )
                                        }
                                    }
                                    Spacer(modifier = Modifier.height(16.dp))
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
