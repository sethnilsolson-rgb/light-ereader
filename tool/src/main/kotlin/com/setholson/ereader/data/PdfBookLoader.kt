package com.setholson.ereader.data

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import com.setholson.ereader.model.BookMetadata
import java.io.File
import kotlin.math.roundToInt

/**
 * Thread-safe native Android [PdfRenderer] wrapper for local PDF files.
 * Always closes descriptors cleanly to prevent leaks.
 */
object PdfBookLoader {

    fun peekMetadata(file: File): BookMetadata {
        val title = EpubParser.cleanFilenameTitle(file.nameWithoutExtension)
        val count = getPageCount(file)
        return BookMetadata(
            title = title,
            author = if (count > 0) "$count pages" else "PDF Document",
            totalPositions = count.coerceAtLeast(1),
        )
    }

    fun getPageCount(file: File): Int {
        if (!file.isFile) return 0
        return runCatching {
            ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { pfd ->
                PdfRenderer(pfd).use { renderer ->
                    renderer.pageCount
                }
            }
        }.getOrDefault(0)
    }

    /**
     * Renders [pageIndex] (0-based) to a crisp white-backed [Bitmap] scaled to [targetWidthPx].
     */
    fun renderPage(
        file: File,
        pageIndex: Int,
        targetWidthPx: Int = 1080,
    ): Result<Pair<Bitmap, Int>> = runCatching {
        require(file.isFile) { "PDF file not found" }
        ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { pfd ->
            PdfRenderer(pfd).use { renderer ->
                val count = renderer.pageCount
                require(count > 0) { "PDF has no pages" }
                val safeIndex = pageIndex.coerceIn(0, count - 1)
                renderer.openPage(safeIndex).use { page ->
                    val clampedWidth = targetWidthPx.coerceIn(540, 1800)
                    val aspect = page.height.toFloat() / page.width.coerceAtLeast(1).toFloat()
                    val targetHeight = (clampedWidth * aspect).roundToInt().coerceIn(300, 3200)

                    val bitmap = Bitmap.createBitmap(clampedWidth, targetHeight, Bitmap.Config.ARGB_8888)
                    val canvas = Canvas(bitmap)
                    canvas.drawColor(Color.WHITE)
                    page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                    bitmap to count
                }
            }
        }
    }
}
