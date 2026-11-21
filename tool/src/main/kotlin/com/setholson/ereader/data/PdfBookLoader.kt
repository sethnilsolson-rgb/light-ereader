package com.setholson.ereader.data

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import com.setholson.ereader.model.BookMetadata
import java.io.File
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

data class RenderedPdfPage(
    val bitmap: Bitmap,
    val pageCount: Int,
    val pageWidthPt: Int,
    val pageHeightPt: Int,
)

/**
 * Thread-safe native Android [PdfRenderer] wrapper for local PDF files.
 * Strictly preserves exact page aspect ratios and supports crisp vector-rendered
 * viewport tiles at high zoom levels (up to 8x).
 */
object PdfBookLoader {

    private const val MAX_BASE_SIDE_PX = 2400
    private const val MAX_BASE_PIXELS = 6_000_000L
    private const val MAX_TILE_SIDE_PX = 1440

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
     * Renders [pageIndex] (0-based) to a crisp white-backed [Bitmap] using a single uniform
     * scale factor so the exact PDF page aspect ratio is NEVER distorted.
     */
    fun renderPage(
        file: File,
        pageIndex: Int,
        targetWidthPx: Int = 2160,
    ): Result<RenderedPdfPage> = runCatching {
        require(file.isFile) { "PDF file not found" }
        ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { pfd ->
            PdfRenderer(pfd).use { renderer ->
                val count = renderer.pageCount
                require(count > 0) { "PDF has no pages" }
                val safeIndex = pageIndex.coerceIn(0, count - 1)
                renderer.openPage(safeIndex).use { page ->
                    val pageW = page.width.coerceAtLeast(1)
                    val pageH = page.height.coerceAtLeast(1)

                    val scaleByWidth = targetWidthPx.coerceIn(600, MAX_BASE_SIDE_PX).toFloat() / pageW.toFloat()
                    val scaleByMaxSide = MAX_BASE_SIDE_PX.toFloat() / max(pageW, pageH).toFloat()
                    val scaleByPixels = sqrt(MAX_BASE_PIXELS.toDouble() / (pageW.toDouble() * pageH.toDouble())).toFloat()
                    val uniformScale = min(scaleByWidth, min(scaleByMaxSide, scaleByPixels)).coerceAtLeast(0.25f)

                    val bmpW = (pageW * uniformScale).roundToInt().coerceAtLeast(1)
                    val bmpH = (pageH * uniformScale).roundToInt().coerceAtLeast(1)

                    val bitmap = Bitmap.createBitmap(bmpW, bmpH, Bitmap.Config.ARGB_8888)
                    val canvas = Canvas(bitmap)
                    canvas.drawColor(Color.WHITE)

                    val matrix = Matrix().apply {
                        postScale(bmpW.toFloat() / pageW.toFloat(), bmpH.toFloat() / pageH.toFloat())
                    }
                    page.render(bitmap, null, matrix, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)

                    RenderedPdfPage(
                        bitmap = bitmap,
                        pageCount = count,
                        pageWidthPt = pageW,
                        pageHeightPt = pageH,
                    )
                }
            }
        }
    }

    /**
     * Renders ONLY the currently visible zoomed viewport directly from PDF vector data
     * using the exact screen transform ([totalScale], [translateX], [translateY]).
     * Keeps memory tiny (~3-4 MB) while keeping text razor-sharp up to 8x zoom.
     */
    fun renderViewportTile(
        file: File,
        pageIndex: Int,
        viewportWidthPx: Int,
        viewportHeightPx: Int,
        totalScale: Float,
        translateX: Float,
        translateY: Float,
    ): Result<Bitmap> = runCatching {
        require(file.isFile) { "PDF file not found" }
        require(viewportWidthPx > 0 && viewportHeightPx > 0) { "Invalid viewport" }
        ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { pfd ->
            PdfRenderer(pfd).use { renderer ->
                val count = renderer.pageCount
                require(count > 0) { "PDF has no pages" }
                val safeIndex = pageIndex.coerceIn(0, count - 1)
                renderer.openPage(safeIndex).use { page ->
                    val maxDim = max(viewportWidthPx, viewportHeightPx)
                    val tileRatio = min(1f, MAX_TILE_SIDE_PX.toFloat() / maxDim.toFloat())
                    val tileW = (viewportWidthPx * tileRatio).roundToInt().coerceAtLeast(1)
                    val tileH = (viewportHeightPx * tileRatio).roundToInt().coerceAtLeast(1)

                    val bitmap = Bitmap.createBitmap(tileW, tileH, Bitmap.Config.ARGB_8888)
                    val canvas = Canvas(bitmap)
                    canvas.drawColor(Color.TRANSPARENT)

                    val matrix = Matrix().apply {
                        postScale(totalScale * tileRatio, totalScale * tileRatio)
                        postTranslate(translateX * tileRatio, translateY * tileRatio)
                    }

                    // Fill only the transformed page bounds with white so margins stay transparent
                    val pageBounds = RectF(0f, 0f, page.width.toFloat(), page.height.toFloat())
                    matrix.mapRect(pageBounds)
                    val whitePaint = Paint().apply {
                        color = Color.WHITE
                        style = Paint.Style.FILL
                    }
                    canvas.drawRect(pageBounds, whitePaint)

                    page.render(bitmap, null, matrix, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                    bitmap
                }
            }
        }
    }
}
