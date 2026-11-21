package com.setholson.ereader.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import com.setholson.ereader.data.ReaderRepository
import com.setholson.ereader.db.ReaderDatabase
import com.setholson.ereader.model.LibraryBook
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
import java.text.NumberFormat
import java.util.Locale

class SettingsViewModel(
    private val repository: ReaderRepository,
) : LightViewModel<Unit>() {

    private val _pdfDarkInvert = MutableStateFlow(true)
    val pdfDarkInvert: StateFlow<Boolean> = _pdfDarkInvert.asStateFlow()

    private val _dictCount = MutableStateFlow(0)
    val dictCount: StateFlow<Int> = _dictCount.asStateFlow()

    private val _isDownloadingDict = MutableStateFlow(false)
    val isDownloadingDict: StateFlow<Boolean> = _isDownloadingDict.asStateFlow()

    private val _dictProgress = MutableStateFlow<String?>(null)
    val dictProgress: StateFlow<String?> = _dictProgress.asStateFlow()

    private val _errorText = MutableStateFlow<String?>(null)
    val errorText: StateFlow<String?> = _errorText.asStateFlow()

    private val _userBooks = MutableStateFlow<List<LibraryBook>>(emptyList())
    val userBooks: StateFlow<List<LibraryBook>> = _userBooks.asStateFlow()

    private var pdfSettingJob: Job? = null
    private var dictSettingJob: Job? = null
    private var booksJob: Job? = null

    override fun onScreenShow(screen: SimpleLightScreen<Unit>) {
        super.onScreenShow(screen)

        pdfSettingJob?.cancel()
        pdfSettingJob = viewModelScope.launch {
            repository.pdfDarkInvertFlow.collect { _pdfDarkInvert.value = it }
        }

        dictSettingJob?.cancel()
        dictSettingJob = viewModelScope.launch {
            repository.dictEntryCountFlow.collect { _dictCount.value = it }
        }

        booksJob?.cancel()
        booksJob = viewModelScope.launch {
            repository.allProgress.collect { progress ->
                _userBooks.value = repository.scanLibrary(progress).filter { !it.isBuiltIn }
            }
        }
    }

    fun togglePdfDarkInvert() {
        val next = !_pdfDarkInvert.value
        _pdfDarkInvert.value = next
        viewModelScope.launch {
            repository.setPdfDarkInvert(next)
        }
    }

    fun downloadDictionary() {
        if (_isDownloadingDict.value) return
        viewModelScope.launch {
            _isDownloadingDict.value = true
            _errorText.value = null
            _dictProgress.value = "Connecting..."
            val result = repository.downloadOfflineDictionary { msg ->
                _dictProgress.value = msg
            }
            result.onFailure {
                _errorText.value = "Couldn't download dictionary. Check Wi-Fi and try again."
            }
            _isDownloadingDict.value = false
            _dictProgress.value = null
        }
    }

    fun removeDictionary() {
        if (_isDownloadingDict.value) return
        viewModelScope.launch {
            _errorText.value = null
            repository.removeOfflineDictionary()
        }
    }

    fun deleteBook(book: LibraryBook) {
        viewModelScope.launch {
            repository.deleteBook(book.id)
            _userBooks.value = _userBooks.value.filterNot { it.id == book.id }
        }
    }
}

class SettingsScreen(
    sealedActivity: SealedLightActivity,
) : LightScreen<Unit, SettingsViewModel>(sealedActivity) {

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

    override val viewModelClass: Class<SettingsViewModel>
        get() = SettingsViewModel::class.java

    override fun createViewModel() = SettingsViewModel(repository)

    @Composable
    override fun Content() {
        val themeColors by LightThemeController.colors.collectAsState()
        val pdfDarkInvert by viewModel.pdfDarkInvert.collectAsState()
        val dictCount by viewModel.dictCount.collectAsState()
        val isDownloadingDict by viewModel.isDownloadingDict.collectAsState()
        val dictProgress by viewModel.dictProgress.collectAsState()
        val errorText by viewModel.errorText.collectAsState()
        val userBooks by viewModel.userBooks.collectAsState()

        val isDark = themeColors == LightThemeColors.Dark

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
                        center = LightTopBarCenter.Text("Settings"),
                        modifier = Modifier.padding(bottom = 0.5f.gridUnitsAsDp()),
                    )

                    LightScrollView(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth()
                            .padding(start = 1.5f.gridUnitsAsDp()),
                    ) {
                        val rightPad = 1.5f.gridUnitsAsDp()
                        Spacer(modifier = Modifier.height(10.dp))

                        errorText?.let { err ->
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(end = rightPad, bottom = 14.dp)
                                    .border(
                                        width = 1.dp,
                                        color = LightThemeTokens.colors.content,
                                        shape = RoundedCornerShape(4.dp),
                                    )
                                    .padding(10.dp),
                            ) {
                                LightText(text = err, variant = LightTextVariant.Detail)
                            }
                        }

                        // 1. APPEARANCE
                        LightText(
                            text = "APPEARANCE",
                            variant = LightTextVariant.Detail,
                            lighten = true,
                            modifier = Modifier.padding(bottom = 8.dp),
                        )

                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(end = rightPad, bottom = 10.dp)
                                .border(
                                    width = 1.dp,
                                    color = LightThemeTokens.colors.content.copy(alpha = 0.4f),
                                    shape = RoundedCornerShape(4.dp),
                                )
                                .lightClickable { LightThemeController.toggle() }
                                .padding(horizontal = 12.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                LightText(
                                    text = if (isDark) "Dark Theme" else "Light Theme",
                                    variant = LightTextVariant.Subheading,
                                )
                                LightText(
                                    text = if (isDark) "Tap to switch to Light Theme" else "Tap to switch to Dark Theme",
                                    variant = LightTextVariant.Detail,
                                    lighten = true,
                                    modifier = Modifier.padding(top = 2.dp),
                                )
                            }
                            SettingsPill(
                                label = if (isDark) "Light" else "Dark",
                                highlighted = false,
                                onClick = { LightThemeController.toggle() },
                            )
                        }

                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(end = rightPad, bottom = 18.dp)
                                .border(
                                    width = 1.dp,
                                    color = LightThemeTokens.colors.content.copy(alpha = 0.4f),
                                    shape = RoundedCornerShape(4.dp),
                                )
                                .lightClickable { viewModel.togglePdfDarkInvert() }
                                .padding(horizontal = 12.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                LightText(
                                    text = "Auto-Invert PDFs in Dark Mode",
                                    variant = LightTextVariant.Subheading,
                                )
                                LightText(
                                    text = "Turns white PDF pages into OLED black when Dark Theme is on.",
                                    variant = LightTextVariant.Detail,
                                    lighten = true,
                                    modifier = Modifier.padding(top = 2.dp),
                                )
                            }
                            Spacer(modifier = Modifier.width(8.dp))
                            SettingsPill(
                                label = if (pdfDarkInvert) "On" else "Off",
                                highlighted = pdfDarkInvert,
                                onClick = { viewModel.togglePdfDarkInvert() },
                            )
                        }

                        // 2. OFFLINE DICTIONARY
                        LightText(
                            text = "PRESS-AND-HOLD DICTIONARY",
                            variant = LightTextVariant.Detail,
                            lighten = true,
                            modifier = Modifier.padding(bottom = 8.dp),
                        )

                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(end = rightPad, bottom = 18.dp)
                                .border(
                                    width = 1.dp,
                                    color = LightThemeTokens.colors.content.copy(alpha = 0.4f),
                                    shape = RoundedCornerShape(4.dp),
                                )
                                .padding(horizontal = 12.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                LightText(
                                    text = "Webster's 1828 Dictionary",
                                    variant = LightTextVariant.Subheading,
                                )
                                val sub = when {
                                    isDownloadingDict -> dictProgress ?: "Downloading..."
                                    dictCount > 0 -> {
                                        val formatted = NumberFormat.getNumberInstance(Locale.US).format(dictCount)
                                        "Ready • $formatted words • Hold any word in EPUB/TXT"
                                    }
                                    else -> "Optional offline dictionary for press-and-hold definitions."
                                }
                                LightText(
                                    text = sub,
                                    variant = LightTextVariant.Detail,
                                    lighten = !isDownloadingDict,
                                    modifier = Modifier.padding(top = 3.dp),
                                )
                            }
                            Spacer(modifier = Modifier.width(8.dp))
                            when {
                                isDownloadingDict -> SettingsPill("...", false, onClick = {})
                                dictCount > 0 -> SettingsPill("Remove", false) { viewModel.removeDictionary() }
                                else -> SettingsPill("Download", true) { viewModel.downloadDictionary() }
                            }
                        }

                        // 3. HOW TO UPLOAD BOOKS
                        LightText(
                            text = "ADDING BOOKS",
                            variant = LightTextVariant.Detail,
                            lighten = true,
                            modifier = Modifier.padding(bottom = 6.dp),
                        )
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(end = rightPad, bottom = 18.dp)
                                .border(
                                    width = 1.dp,
                                    color = LightThemeTokens.colors.content.copy(alpha = 0.35f),
                                    shape = RoundedCornerShape(4.dp),
                                )
                                .padding(horizontal = 12.dp, vertical = 10.dp),
                        ) {
                            LightText(
                                text = "1. Open Settings → Tool Manager on your Light Phone.\n2. Open the Tool Manager URL in your computer's browser.\n3. Select E-Reader → Books and upload .epub, .pdf, .txt, or .md files.",
                                variant = LightTextVariant.Detail,
                                lighten = true,
                            )
                        }

                        // 4. MANAGE UPLOADED BOOKS
                        if (userBooks.isNotEmpty()) {
                            LightText(
                                text = "MANAGE BOOKS (${userBooks.size})",
                                variant = LightTextVariant.Detail,
                                lighten = true,
                                modifier = Modifier.padding(bottom = 8.dp),
                            )

                            userBooks.forEach { book ->
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(end = rightPad, bottom = 10.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        LightText(
                                            text = book.title,
                                            variant = LightTextVariant.Paragraph,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                        LightText(
                                            text = "${book.format.badge} • ${formatFileSize(book.fileSizeBytes)}",
                                            variant = LightTextVariant.Detail,
                                            lighten = true,
                                        )
                                    }
                                    Spacer(modifier = Modifier.width(8.dp))
                                    SettingsPill("Delete", false) { viewModel.deleteBook(book) }
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(18.dp))
                    }
                }
            }
        }
    }
}

@Composable
private fun SettingsPill(
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
                color = content.copy(alpha = 0.75f),
                shape = RoundedCornerShape(4.dp),
            )
            .lightClickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 5.dp),
        contentAlignment = Alignment.Center,
    ) {
        LightText(text = label, variant = LightTextVariant.Detail, color = fg)
    }
}
