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
import com.setholson.ereader.db.ReaderDatabase
import com.setholson.ereader.model.DictionaryPack
import com.setholson.ereader.model.LibraryBook
import com.setholson.ereader.model.ReaderPageMode
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
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import java.text.NumberFormat
import java.util.Locale

class SettingsViewModel(
    private val repository: ReaderRepository,
) : LightViewModel<Unit>() {

    private val _pdfDarkInvert = MutableStateFlow(true)
    val pdfDarkInvert: StateFlow<Boolean> = _pdfDarkInvert.asStateFlow()

    private val _pageMode = MutableStateFlow(ReaderPageMode.SCROLL)
    val pageMode: StateFlow<ReaderPageMode> = _pageMode.asStateFlow()

    private val _dictCounts = MutableStateFlow<Map<String, Int>>(emptyMap())
    val dictCounts: StateFlow<Map<String, Int>> = _dictCounts.asStateFlow()

    private val _primaryDictId = MutableStateFlow<String?>(null)
    val primaryDictId: StateFlow<String?> = _primaryDictId.asStateFlow()

    private val _onlineDictEnabled = MutableStateFlow(false)
    val onlineDictEnabled: StateFlow<Boolean> = _onlineDictEnabled.asStateFlow()

    private val _downloadingPackId = MutableStateFlow<String?>(null)
    val downloadingPackId: StateFlow<String?> = _downloadingPackId.asStateFlow()

    private val _dictProgress = MutableStateFlow<String?>(null)
    val dictProgress: StateFlow<String?> = _dictProgress.asStateFlow()

    private val _errorText = MutableStateFlow<String?>(null)
    val errorText: StateFlow<String?> = _errorText.asStateFlow()

    private val _userBooks = MutableStateFlow<List<LibraryBook>>(emptyList())
    val userBooks: StateFlow<List<LibraryBook>> = _userBooks.asStateFlow()

    private var settingsJob: Job? = null
    private var countsJob: Job? = null
    private var booksJob: Job? = null

    override fun onScreenShow(screen: SimpleLightScreen<Unit>) {
        super.onScreenShow(screen)

        settingsJob?.cancel()
        settingsJob = viewModelScope.launch {
            combine(
                repository.pdfDarkInvertFlow,
                repository.primaryDictIdFlow,
                repository.onlineDictEnabledFlow,
                repository.pageModeFlow,
            ) { pdfInv, primaryId, onlineOn, mode ->
                SettingsUiSnapshot(pdfInv, primaryId, onlineOn, mode)
            }.collect { snap ->
                _pdfDarkInvert.value = snap.pdfDarkInvert
                _primaryDictId.value = snap.primaryDictId
                _onlineDictEnabled.value = snap.onlineDictEnabled
                _pageMode.value = snap.pageMode
            }
        }

        countsJob?.cancel()
        countsJob = viewModelScope.launch {
            repository.dictCountsFlow.collect { _dictCounts.value = it }
        }

        booksJob?.cancel()
        booksJob = viewModelScope.launch {
            combine(
                repository.allProgress,
                ReaderEntryPoint.libraryRefreshSignal,
            ) { progress, _ -> progress }.collect { progress ->
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

    fun togglePageMode() {
        val next = _pageMode.value.toggle()
        _pageMode.value = next
        viewModelScope.launch {
            repository.setPageMode(next)
        }
    }

    fun downloadPack(pack: DictionaryPack) {
        if (_downloadingPackId.value != null) return
        viewModelScope.launch {
            _downloadingPackId.value = pack.id
            _errorText.value = null
            _dictProgress.value = "Connecting..."
            val result = repository.downloadDictionaryPack(pack) { msg ->
                _dictProgress.value = msg
            }
            result.onFailure {
                _errorText.value = "Couldn't download ${pack.displayName}. Check Wi-Fi and try again."
            }
            _downloadingPackId.value = null
            _dictProgress.value = null
        }
    }

    fun removePack(pack: DictionaryPack) {
        if (_downloadingPackId.value != null) return
        viewModelScope.launch {
            _errorText.value = null
            repository.removeDictionaryPack(pack)
        }
    }

    fun setPrimary(pack: DictionaryPack) {
        viewModelScope.launch {
            repository.setPrimaryDictionary(pack)
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
                addMigrations(ReaderDatabase.MIGRATION_2_3)
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
        val pageMode by viewModel.pageMode.collectAsState()
        val dictCounts by viewModel.dictCounts.collectAsState()
        val primaryDictId by viewModel.primaryDictId.collectAsState()
        val onlineDictEnabled by viewModel.onlineDictEnabled.collectAsState()
        val downloadingPackId by viewModel.downloadingPackId.collectAsState()
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
                                .padding(end = rightPad, bottom = 10.dp)
                                .border(
                                    width = 1.dp,
                                    color = LightThemeTokens.colors.content.copy(alpha = 0.4f),
                                    shape = RoundedCornerShape(4.dp),
                                )
                                .lightClickable { viewModel.togglePageMode() }
                                .padding(horizontal = 12.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                LightText(
                                    text = "Reading Format: ${pageMode.label}",
                                    variant = LightTextVariant.Subheading,
                                )
                                LightText(
                                    text = if (pageMode == ReaderPageMode.PAGED) {
                                        "Swipe on words to turn pages like a book. Double-tap for full screen."
                                    } else {
                                        "Continuous scroll through chapters. Double-tap for full screen."
                                    },
                                    variant = LightTextVariant.Detail,
                                    lighten = true,
                                    modifier = Modifier.padding(top = 2.dp),
                                )
                            }
                            Spacer(modifier = Modifier.width(8.dp))
                            SettingsPill(
                                label = pageMode.chipLabel,
                                highlighted = pageMode == ReaderPageMode.PAGED,
                                onClick = { viewModel.togglePageMode() },
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

                        // 2. PRESS-AND-HOLD DICTIONARIES (Multiple Downloadable Packs)
                        LightText(
                            text = "PRESS-AND-HOLD DICTIONARIES",
                            variant = LightTextVariant.Detail,
                            lighten = true,
                            modifier = Modifier.padding(bottom = 4.dp),
                        )
                        LightText(
                            text = "Download one or more dictionaries. Press and hold any word while reading EPUB/TXT books to look it up.",
                            variant = LightTextVariant.Detail,
                            lighten = true,
                            modifier = Modifier.padding(end = rightPad, bottom = 10.dp),
                        )

                        DictionaryPack.entries.forEach { pack ->
                            val count = dictCounts[pack.id] ?: 0
                            val isInstalled = if (pack.isOnlineAutoCache) onlineDictEnabled else count > 0
                            val isPrimary = isInstalled && primaryDictId == pack.id
                            val isDownloading = downloadingPackId == pack.id
                            val busyOther = downloadingPackId != null && !isDownloading

                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(end = rightPad, bottom = 10.dp)
                                    .border(
                                        width = if (isPrimary) 1.5.dp else 1.dp,
                                        color = LightThemeTokens.colors.content.copy(alpha = if (isPrimary) 0.9f else 0.35f),
                                        shape = RoundedCornerShape(4.dp),
                                    )
                                    .padding(horizontal = 12.dp, vertical = 10.dp),
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        val titleText = if (isPrimary) "${pack.displayName} ★" else pack.displayName
                                        LightText(
                                            text = titleText,
                                            variant = LightTextVariant.Subheading,
                                            maxLines = 2,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                        val statusText = when {
                                            isDownloading -> dictProgress ?: "Downloading..."
                                            pack.isOnlineAutoCache && isInstalled -> {
                                                val cachedStr = NumberFormat.getNumberInstance(Locale.US).format(count)
                                                "Enabled • $cachedStr words cached offline"
                                            }
                                            isInstalled -> {
                                                val formatted = NumberFormat.getNumberInstance(Locale.US).format(count)
                                                "Installed • $formatted entries" + if (isPrimary) " • Default" else ""
                                            }
                                            else -> "${pack.sizeHint} • ${pack.description}"
                                        }
                                        LightText(
                                            text = statusText,
                                            variant = LightTextVariant.Detail,
                                            lighten = !isDownloading,
                                            modifier = Modifier.padding(top = 3.dp),
                                        )
                                    }
                                }

                                Spacer(modifier = Modifier.height(8.dp))
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    when {
                                        isDownloading -> {
                                            SettingsPill("Downloading...", highlighted = false, onClick = {})
                                        }
                                        isInstalled -> {
                                            if (!isPrimary) {
                                                SettingsPill("Set Default", highlighted = true) {
                                                    viewModel.setPrimary(pack)
                                                }
                                            } else {
                                                SettingsPill("Default", highlighted = true, onClick = {})
                                            }
                                            SettingsPill(
                                                label = if (pack.isOnlineAutoCache) "Turn Off" else "Remove",
                                                highlighted = false,
                                            ) { viewModel.removePack(pack) }
                                        }
                                        busyOther -> {
                                            SettingsPill("Please wait...", highlighted = false, onClick = {})
                                        }
                                        else -> {
                                            SettingsPill(
                                                label = if (pack.isOnlineAutoCache) "Enable" else "Download",
                                                highlighted = pack == DictionaryPack.MODERN_WORDSET,
                                            ) { viewModel.downloadPack(pack) }
                                        }
                                    }
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(8.dp))

                        // 3. ADDING BOOKS
                        LightText(
                            text = "ADDING BOOKS",
                            variant = LightTextVariant.Detail,
                            lighten = true,
                            modifier = Modifier.padding(bottom = 6.dp),
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
                                .lightClickable {
                                    navigateTo(screenFactory = { act ->
                                        AddBooksScreen(act, initialTab = AddBooksTab.WIFI)
                                    })
                                }
                                .padding(horizontal = 12.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                LightText(
                                    text = "Add Books & Wi-Fi Transfer",
                                    variant = LightTextVariant.Subheading,
                                )
                                LightText(
                                    text = "Download 19 free classics, drag-and-drop over Wi-Fi, or paste a link.",
                                    variant = LightTextVariant.Detail,
                                    lighten = true,
                                    modifier = Modifier.padding(top = 2.dp),
                                )
                            }
                            Spacer(modifier = Modifier.width(8.dp))
                            SettingsPill("Open", highlighted = false) {
                                navigateTo(screenFactory = { act ->
                                    AddBooksScreen(act, initialTab = AddBooksTab.WIFI)
                                })
                            }
                        }

                        // 4. MANAGE BOOKS
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

private data class SettingsUiSnapshot(
    val pdfDarkInvert: Boolean,
    val primaryDictId: String?,
    val onlineDictEnabled: Boolean,
    val pageMode: ReaderPageMode,
)

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
