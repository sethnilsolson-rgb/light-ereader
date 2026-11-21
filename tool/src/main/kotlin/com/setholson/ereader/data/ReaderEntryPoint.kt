package com.setholson.ereader.data

import com.thelightphone.sdk.EntryPoint
import com.thelightphone.sdk.LightEntryPoint
import com.thelightphone.toolmanager.ClientLeafNode
import com.thelightphone.toolmanager.ClientToolManifest
import com.thelightphone.toolmanager.FileBrowserSpec
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Registers E-Reader with Light's browser-based Tool Manager so users can drag and drop
 * DRM-free .epub, .pdf, .txt, and .md books directly from their computer over Wi-Fi.
 */
@EntryPoint
object ReaderEntryPoint : LightEntryPoint {

    const val BOOKS_RELATIVE_DIR = "books"

    private val booksSpec = FileBrowserSpec(
        label = "Books (EPUB, PDF, TXT)",
        path = BOOKS_RELATIVE_DIR,
        headerText = "Upload DRM-free .epub, .pdf, .txt, or .md files here. They will appear automatically in your E-Reader library.",
    )

    private val _libraryRefreshSignal = MutableStateFlow(0L)
    val libraryRefreshSignal: StateFlow<Long> = _libraryRefreshSignal.asStateFlow()

    override fun getToolManagerManifest(): ClientToolManifest {
        return ClientToolManifest(
            title = "E-Reader",
            roots = listOf(
                ClientLeafNode(booksSpec, canBeBackedUp = true)
            ),
        )
    }

    override suspend fun onToolManagerDataUpdate() {
        super.onToolManagerDataUpdate()
        _libraryRefreshSignal.value = System.currentTimeMillis()
    }
}
