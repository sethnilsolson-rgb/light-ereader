package com.setholson.ereader.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewModelScope
import com.setholson.ereader.data.ReaderRepository
import com.setholson.ereader.db.BookBookmarkEntity
import com.setholson.ereader.db.ReaderDatabase
import com.setholson.ereader.model.BookFormat
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
import kotlinx.coroutines.launch

class BookmarksViewModel(
    private val repository: ReaderRepository,
) : LightViewModel<Unit>() {

    private val _bookmarks = MutableStateFlow<List<BookBookmarkEntity>>(emptyList())
    val bookmarks: StateFlow<List<BookBookmarkEntity>> = _bookmarks.asStateFlow()

    private var collectJob: Job? = null

    override fun onScreenShow(screen: SimpleLightScreen<Unit>) {
        super.onScreenShow(screen)
        collectJob?.cancel()
        collectJob = viewModelScope.launch {
            repository.bookmarks.collect { list ->
                _bookmarks.value = list
            }
        }
    }

    fun deleteBookmark(bookmark: BookBookmarkEntity) {
        viewModelScope.launch {
            repository.deleteBookmark(bookmark)
        }
    }
}

class BookmarksScreen(
    sealedActivity: SealedLightActivity,
) : LightScreen<Unit, BookmarksViewModel>(sealedActivity) {

    private val repository = ReaderRepository.getInstance(
        dbProvider = {
            lightContext.buildDatabase(
                ReaderDatabase::class.java,
                ReaderRepository.DATABASE_NAME,
            ) {
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
                )
            })
        } else {
            navigateTo(screenFactory = { act ->
                TextReaderScreen(
                    sealedActivity = act,
                    bookId = bookmark.bookId,
                    bookFormat = format,
                    initialChapter = bookmark.positionIndex,
                )
            })
        }
    }

    @Composable
    override fun Content() {
        val themeColors by LightThemeController.colors.collectAsState()
        val bookmarks by viewModel.bookmarks.collectAsState()

        LightTheme(colors = themeColors) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(LightThemeTokens.colors.background)
                    .swipeToBack { goBack() },
            ) {
                Column(modifier = Modifier.fillMaxSize()) {
                    LightTopBar(
                        leftButton = LightBarButton.LightIcon(
                            icon = LightIcons.BACK,
                            onClick = { goBack() },
                        ),
                        center = LightTopBarCenter.Text("Bookmarks"),
                        modifier = Modifier.padding(bottom = 0.5f.gridUnitsAsDp()),
                    )

                    if (bookmarks.isEmpty()) {
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxWidth()
                                .padding(2f.gridUnitsAsDp()),
                            contentAlignment = Alignment.Center,
                        ) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                LightText(
                                    text = "No Bookmarks Yet",
                                    variant = LightTextVariant.Subheading,
                                    modifier = Modifier.padding(bottom = 8.dp),
                                )
                                LightText(
                                    text = "Tap the bookmark icon while reading any EPUB chapter, TXT section, or PDF page to save it here.",
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
                                .padding(start = 1.5f.gridUnitsAsDp()),
                        ) {
                            Spacer(modifier = Modifier.height(10.dp))

                            bookmarks.forEach { bookmark ->
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(end = 1.5f.gridUnitsAsDp(), top = 10.dp, bottom = 10.dp)
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
                    }
                }
            }
        }
    }
}
