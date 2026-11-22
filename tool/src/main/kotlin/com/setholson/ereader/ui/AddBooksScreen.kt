package com.setholson.ereader.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewModelScope
import com.setholson.ereader.data.FreeBooksCatalog
import com.setholson.ereader.data.ReaderRepository
import com.setholson.ereader.data.WifiBookServer
import com.setholson.ereader.db.ReaderDatabase
import com.setholson.ereader.model.BookFormat
import com.setholson.ereader.model.FreeCatalogBook
import com.thelightphone.sdk.LightScreen
import com.thelightphone.sdk.LightViewModel
import com.thelightphone.sdk.SealedLightActivity
import com.thelightphone.sdk.SimpleLightScreen
import com.thelightphone.sdk.buildDatabase
import com.thelightphone.sdk.ui.LightBarButton
import com.thelightphone.sdk.ui.LightIcons
import com.thelightphone.sdk.ui.LightScrollView
import com.thelightphone.sdk.ui.LightText
import com.thelightphone.sdk.ui.LightTextField
import com.thelightphone.sdk.ui.LightTextInputEditor
import com.thelightphone.sdk.ui.LightTextVariant
import com.thelightphone.sdk.ui.LightTheme
import com.thelightphone.sdk.ui.LightThemeController
import com.thelightphone.sdk.ui.LightThemeTokens
import com.thelightphone.sdk.ui.LightTopBar
import com.thelightphone.sdk.ui.LightTopBarCenter
import com.thelightphone.sdk.ui.defaultKeyboardOptions
import com.thelightphone.sdk.ui.gridUnitsAsDp
import com.thelightphone.sdk.ui.lightClickable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

enum class AddBooksTab(val label: String) {
    CLASSICS("Free Classics"),
    WIFI("Wi-Fi Upload"),
    URL("From URL"),
}

class AddBooksViewModel(
    private val repository: ReaderRepository,
    initialTab: AddBooksTab,
) : LightViewModel<Unit>() {

    private val _activeTab = MutableStateFlow(initialTab)
    val activeTab: StateFlow<AddBooksTab> = _activeTab.asStateFlow()

    private val _selectedCategory = MutableStateFlow("All")
    val selectedCategory: StateFlow<String> = _selectedCategory.asStateFlow()

    private val _downloadedCatalogIds = MutableStateFlow<Set<String>>(emptySet())
    val downloadedCatalogIds: StateFlow<Set<String>> = _downloadedCatalogIds.asStateFlow()

    private val _downloadingCatalogId = MutableStateFlow<String?>(null)
    val downloadingCatalogId: StateFlow<String?> = _downloadingCatalogId.asStateFlow()

    private val _catalogProgress = MutableStateFlow<String?>(null)
    val catalogProgress: StateFlow<String?> = _catalogProgress.asStateFlow()

    // Wi-Fi Transfer state
    private val _serverUrl = MutableStateFlow<String?>(null)
    val serverUrl: StateFlow<String?> = _serverUrl.asStateFlow()

    private val _wifiEvents = MutableStateFlow<List<String>>(emptyList())
    val wifiEvents: StateFlow<List<String>> = _wifiEvents.asStateFlow()

    // Direct URL state
    private val _urlValue = MutableStateFlow("")
    val urlValue: StateFlow<String> = _urlValue.asStateFlow()

    private val _isDownloadingUrl = MutableStateFlow(false)
    val isDownloadingUrl: StateFlow<Boolean> = _isDownloadingUrl.asStateFlow()

    private val _urlStatus = MutableStateFlow<String?>(null)
    val urlStatus: StateFlow<String?> = _urlStatus.asStateFlow()

    private val _errorText = MutableStateFlow<String?>(null)
    val errorText: StateFlow<String?> = _errorText.asStateFlow()

    private var wifiServer: WifiBookServer? = null

    override fun onScreenShow(screen: SimpleLightScreen<Unit>) {
        super.onScreenShow(screen)
        refreshDownloadedCatalog()
        if (_activeTab.value == AddBooksTab.WIFI && _serverUrl.value == null) {
            startWifiServer()
        }
    }

    override fun onScreenHide(screen: SimpleLightScreen<Unit>) {
        stopWifiServer()
        super.onScreenHide(screen)
    }

    override fun onAppPause() {
        stopWifiServer()
        super.onAppPause()
    }

    fun selectTab(tab: AddBooksTab) {
        _activeTab.value = tab
        _errorText.value = null
        if (tab == AddBooksTab.WIFI && _serverUrl.value == null) {
            startWifiServer()
        } else if (tab != AddBooksTab.WIFI) {
            stopWifiServer()
        }
    }

    fun selectCategory(category: String) {
        _selectedCategory.value = category
    }

    fun refreshDownloadedCatalog() {
        viewModelScope.launch(Dispatchers.IO) {
            val present = FreeBooksCatalog.BOOKS
                .filter { repository.isCatalogBookDownloaded(it) }
                .map { it.id }
                .toSet()
            _downloadedCatalogIds.value = present
        }
    }

    fun downloadCatalogBook(book: FreeCatalogBook) {
        if (_downloadingCatalogId.value != null) return
        viewModelScope.launch {
            _downloadingCatalogId.value = book.id
            _catalogProgress.value = "Connecting..."
            _errorText.value = null
            val res = repository.downloadCatalogBook(book) { msg ->
                _catalogProgress.value = msg
            }
            res.onSuccess {
                refreshDownloadedCatalog()
            }.onFailure { err ->
                _errorText.value = "Couldn't download \"${book.title}\": ${err.message ?: "Check Wi-Fi and try again."}"
            }
            _downloadingCatalogId.value = null
            _catalogProgress.value = null
        }
    }

    fun startWifiServer() {
        viewModelScope.launch(Dispatchers.IO) {
            _errorText.value = null
            wifiServer?.stop()
            val server = WifiBookServer(
                booksDir = repository.booksRootDir,
                onBookChanged = { msg ->
                    _wifiEvents.value = (listOf(msg) + _wifiEvents.value).take(8)
                    refreshDownloadedCatalog()
                },
            )
            val result = server.start()
            result.onSuccess { url ->
                wifiServer = server
                _serverUrl.value = url
            }.onFailure { err ->
                server.stop()
                _serverUrl.value = null
                _errorText.value = err.message ?: "Connect your Light Phone to Wi-Fi and try again."
            }
        }
    }

    fun stopWifiServer() {
        wifiServer?.stop()
        wifiServer = null
        _serverUrl.value = null
    }

    fun setUrlValue(value: String) {
        _urlValue.value = value
        _errorText.value = null
        _urlStatus.value = null
    }

    fun downloadFromUrl() {
        val raw = _urlValue.value.trim()
        if (raw.isBlank() || _isDownloadingUrl.value) return
        viewModelScope.launch {
            _isDownloadingUrl.value = true
            _errorText.value = null
            _urlStatus.value = "Connecting..."
            val result = repository.downloadBookFromUrl(raw) { msg ->
                _urlStatus.value = msg
            }
            result.onSuccess { file ->
                _urlStatus.value = "Added \"${file.name}\" to your Library!"
                _urlValue.value = ""
            }.onFailure { err ->
                _urlStatus.value = null
                _errorText.value = err.message ?: "Download failed. Make sure the link points directly to an .epub, .pdf, or .txt file."
            }
            _isDownloadingUrl.value = false
        }
    }

    override fun onCleared() {
        stopWifiServer()
        super.onCleared()
    }
}

class AddBooksScreen(
    sealedActivity: SealedLightActivity,
    private val initialTab: AddBooksTab = AddBooksTab.CLASSICS,
) : LightScreen<Unit, AddBooksViewModel>(sealedActivity) {

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

    override val viewModelClass: Class<AddBooksViewModel>
        get() = AddBooksViewModel::class.java

    override fun createViewModel() = AddBooksViewModel(repository, initialTab)

    private fun openCatalogBook(book: FreeCatalogBook) {
        viewModel.stopWifiServer()
        navigateTo(screenFactory = { act ->
            TextReaderScreen(
                sealedActivity = act,
                bookId = book.fileName,
                bookFormat = BookFormat.EPUB,
            )
        })
    }

    @Composable
    override fun Content() {
        val themeColors by LightThemeController.colors.collectAsState()
        val activeTab by viewModel.activeTab.collectAsState()
        val selectedCategory by viewModel.selectedCategory.collectAsState()
        val downloadedIds by viewModel.downloadedCatalogIds.collectAsState()
        val downloadingId by viewModel.downloadingCatalogId.collectAsState()
        val catalogProgress by viewModel.catalogProgress.collectAsState()
        val serverUrl by viewModel.serverUrl.collectAsState()
        val wifiEvents by viewModel.wifiEvents.collectAsState()
        val urlValue by viewModel.urlValue.collectAsState()
        val isDownloadingUrl by viewModel.isDownloadingUrl.collectAsState()
        val urlStatus by viewModel.urlStatus.collectAsState()
        val errorText by viewModel.errorText.collectAsState()

        var editingUrl by remember { mutableStateOf(false) }

        LightTheme(colors = themeColors) {
            if (editingUrl) {
                val urlFieldState = rememberTextFieldState(initialText = urlValue)
                val keyboardOpts = remember { MutableStateFlow(defaultKeyboardOptions()) }
                LightTextInputEditor(
                    title = "Book Link (URL)",
                    state = urlFieldState,
                    keyboardOptionsFlow = keyboardOpts,
                    submitLabel = "DONE",
                    singleLine = true,
                    onSubmit = { entered ->
                        viewModel.setUrlValue(entered.toString())
                        editingUrl = false
                    },
                    onBack = { editingUrl = false },
                )
                return@LightTheme
            }

            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(LightThemeTokens.colors.background)
                    .swipeToBack {
                        viewModel.stopWifiServer()
                        goBack()
                    },
            ) {
                Column(modifier = Modifier.fillMaxSize()) {
                    LightTopBar(
                        leftButton = LightBarButton.LightIcon(
                            icon = LightIcons.BACK,
                            onClick = {
                                viewModel.stopWifiServer()
                                goBack()
                            },
                        ),
                        center = LightTopBarCenter.Text("Add Books"),
                        modifier = Modifier.padding(bottom = 0.5f.gridUnitsAsDp()),
                    )

                    // 3-Tab Switcher
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 1.5f.gridUnitsAsDp(), vertical = 6.dp),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        AddBooksTab.entries.forEach { tab ->
                            val selected = activeTab == tab
                            val content = LightThemeTokens.colors.content
                            val bg = if (selected) content else LightThemeTokens.colors.background
                            val fg = if (selected) LightThemeTokens.colors.background else content
                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .background(bg, RoundedCornerShape(4.dp))
                                    .border(
                                        width = 1.dp,
                                        color = content.copy(alpha = if (selected) 1f else 0.45f),
                                        shape = RoundedCornerShape(4.dp),
                                    )
                                    .lightClickable { viewModel.selectTab(tab) }
                                    .padding(vertical = 6.dp),
                                contentAlignment = Alignment.Center,
                            ) {
                                LightText(
                                    text = tab.label,
                                    variant = LightTextVariant.Detail,
                                    color = fg,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                    }

                    LightScrollView(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth()
                            .padding(start = 1.5f.gridUnitsAsDp()),
                    ) {
                        val rightPad = 1.5f.gridUnitsAsDp()
                        Spacer(modifier = Modifier.height(6.dp))

                        errorText?.let { err ->
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(end = rightPad, bottom = 12.dp)
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

                        when (activeTab) {
                            AddBooksTab.CLASSICS -> {
                                // Category Filter Chips
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(end = rightPad, bottom = 10.dp)
                                        .horizontalScroll(rememberScrollState()),
                                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                                ) {
                                    FreeBooksCatalog.CATEGORIES.forEach { cat ->
                                        val isSelected = selectedCategory == cat
                                        val content = LightThemeTokens.colors.content
                                        Box(
                                            modifier = Modifier
                                                .background(
                                                    if (isSelected) content else LightThemeTokens.colors.background,
                                                    RoundedCornerShape(4.dp),
                                                )
                                                .border(
                                                    width = 1.dp,
                                                    color = content.copy(alpha = if (isSelected) 1f else 0.4f),
                                                    shape = RoundedCornerShape(4.dp),
                                                )
                                                .lightClickable { viewModel.selectCategory(cat) }
                                                .padding(horizontal = 8.dp, vertical = 4.dp),
                                        ) {
                                            LightText(
                                                text = cat,
                                                variant = LightTextVariant.Detail,
                                                color = if (isSelected) LightThemeTokens.colors.background else content,
                                            )
                                        }
                                    }
                                }

                                val filtered = if (selectedCategory == "All") {
                                    FreeBooksCatalog.BOOKS
                                } else {
                                    FreeBooksCatalog.BOOKS.filter { it.category == selectedCategory }
                                }

                                LightText(
                                    text = "FREE PUBLIC DOMAIN EPUBS (${filtered.size}) • STANDARD EBOOKS",
                                    variant = LightTextVariant.Detail,
                                    lighten = true,
                                    modifier = Modifier.padding(end = rightPad, bottom = 8.dp),
                                )

                                filtered.forEach { book ->
                                    val isDownloaded = book.id in downloadedIds
                                    val isDownloading = downloadingId == book.id
                                    val busyOther = downloadingId != null && !isDownloading

                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(end = rightPad, bottom = 10.dp)
                                            .border(
                                                width = 1.dp,
                                                color = LightThemeTokens.colors.content.copy(alpha = 0.35f),
                                                shape = RoundedCornerShape(4.dp),
                                            )
                                            .padding(horizontal = 11.dp, vertical = 10.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        Column(modifier = Modifier.weight(1f)) {
                                            LightText(
                                                text = book.title,
                                                variant = LightTextVariant.Subheading,
                                                maxLines = 2,
                                                overflow = TextOverflow.Ellipsis,
                                            )
                                            LightText(
                                                text = "${book.author} • ${book.year}",
                                                variant = LightTextVariant.Detail,
                                                lighten = true,
                                                modifier = Modifier.padding(top = 2.dp),
                                            )
                                            LightText(
                                                text = if (isDownloading) {
                                                    catalogProgress ?: "Downloading..."
                                                } else {
                                                    book.description
                                                },
                                                variant = LightTextVariant.Detail,
                                                lighten = !isDownloading,
                                                maxLines = 2,
                                                overflow = TextOverflow.Ellipsis,
                                                modifier = Modifier.padding(top = 3.dp),
                                            )
                                        }
                                        Spacer(modifier = Modifier.width(8.dp))
                                        when {
                                            isDownloading -> ActionPill("...", highlighted = false, onClick = {})
                                            isDownloaded -> ActionPill("Read", highlighted = true) { openCatalogBook(book) }
                                            busyOther -> ActionPill("Wait", highlighted = false, onClick = {})
                                            else -> ActionPill("Get", highlighted = false) { viewModel.downloadCatalogBook(book) }
                                        }
                                    }
                                }
                            }

                            AddBooksTab.WIFI -> {
                                LightText(
                                    text = "SEND BOOKS FROM YOUR COMPUTER OR PHONE",
                                    variant = LightTextVariant.Detail,
                                    lighten = true,
                                    modifier = Modifier.padding(end = rightPad, bottom = 8.dp),
                                )

                                Column(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(end = rightPad, bottom = 14.dp)
                                        .border(
                                            width = 1.5.dp,
                                            color = LightThemeTokens.colors.content,
                                            shape = RoundedCornerShape(4.dp),
                                        )
                                        .padding(horizontal = 12.dp, vertical = 12.dp),
                                ) {
                                    if (serverUrl != null) {
                                        LightText(
                                            text = "WIFI TRANSFER ACTIVE",
                                            variant = LightTextVariant.Detail,
                                            lighten = true,
                                        )
                                        Spacer(modifier = Modifier.height(4.dp))
                                        LightText(
                                            text = serverUrl ?: "",
                                            variant = LightTextVariant.Heading,
                                        )
                                        Spacer(modifier = Modifier.height(8.dp))
                                        LightText(
                                            text = "1. Connect your computer or phone to the same Wi-Fi.\n2. Open the address above in Chrome, Safari, or any browser.\n3. Drag & drop .epub, .pdf, .txt, or .md files (or paste a book URL) — they appear in your Library right away.\n4. Keep this screen open while transferring.",
                                            variant = LightTextVariant.Detail,
                                            lighten = true,
                                        )
                                        Spacer(modifier = Modifier.height(10.dp))
                                        ActionPill("Stop Server", highlighted = false) { viewModel.stopWifiServer() }
                                    } else {
                                        LightText(
                                            text = "Wi-Fi Server Stopped",
                                            variant = LightTextVariant.Subheading,
                                        )
                                        LightText(
                                            text = "Start a private local upload page on your Light Phone so you can drag-and-drop EPUB, PDF, TXT, or Markdown books from any browser on your Wi-Fi network.",
                                            variant = LightTextVariant.Detail,
                                            lighten = true,
                                            modifier = Modifier.padding(top = 4.dp, bottom = 10.dp),
                                        )
                                        ActionPill("Start Wi-Fi Transfer", highlighted = true) { viewModel.startWifiServer() }
                                    }
                                }

                                if (wifiEvents.isNotEmpty()) {
                                    LightText(
                                        text = "RECENT TRANSFERS",
                                        variant = LightTextVariant.Detail,
                                        lighten = true,
                                        modifier = Modifier.padding(end = rightPad, bottom = 6.dp),
                                    )
                                    wifiEvents.forEach { evt ->
                                        LightText(
                                            text = "✓ $evt",
                                            variant = LightTextVariant.Detail,
                                            modifier = Modifier.padding(end = rightPad, bottom = 4.dp),
                                        )
                                    }
                                }
                            }

                            AddBooksTab.URL -> {
                                LightText(
                                    text = "DOWNLOAD DIRECT BOOK LINK",
                                    variant = LightTextVariant.Detail,
                                    lighten = true,
                                    modifier = Modifier.padding(end = rightPad, bottom = 8.dp),
                                )

                                LightText(
                                    text = "Enter any direct https:// link to a DRM-free .epub, .pdf, .txt, or .md file. (Tip: for long links, use the Wi-Fi Upload tab to paste the URL from your computer!)",
                                    variant = LightTextVariant.Detail,
                                    lighten = true,
                                    modifier = Modifier.padding(end = rightPad, bottom = 12.dp),
                                )

                                LightTextField(
                                    label = "Direct Book URL:",
                                    value = urlValue,
                                    placeholder = "Tap to type https://...epub",
                                    onClick = { editingUrl = true },
                                    modifier = Modifier.padding(end = rightPad, bottom = 14.dp),
                                )

                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(end = rightPad, bottom = 12.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    ActionPill(
                                        label = if (isDownloadingUrl) "Downloading..." else "Download Book",
                                        highlighted = urlValue.isNotBlank() && !isDownloadingUrl,
                                        onClick = { viewModel.downloadFromUrl() },
                                    )
                                }

                                urlStatus?.let { status ->
                                    LightText(
                                        text = status,
                                        variant = LightTextVariant.Paragraph,
                                        modifier = Modifier.padding(end = rightPad, top = 4.dp),
                                    )
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
private fun ActionPill(
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
