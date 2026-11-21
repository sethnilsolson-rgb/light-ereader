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
    val rotationDegrees: Int = 0,
)

/**
 * Thread-safe native Android [PdfRenderer] wrapper for local PDF files.
 * Strictly preserves exact page aspect ratios, supports 0/90/180/270-degree rotation,
 * and renders razor-sharp vector viewport tiles at high zoom levels (up to 8x).
 */
object PdfBookLoader {

    private const val MAX_BASE_SIDE_PX = 2400
    private const val MAX_BASE_PIXELS = 6_000_000L
    private const val MAX_TILE_SIDE_PX = 1440

    private fun normalizeRotation(degrees: Int): Int = ((degrees % 360) + 360) % 360

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
     * scale factor so the exact PDF page aspect ratio is NEVER distorted, then rotates by
     * [rotationDegrees] (0, 90, 180, or 270 clockwise).
     */
    fun renderPage(
        file: File,
        pageIndex: Int,
        targetWidthPx: Int = 2160,
        rotationDegrees: Int = 0,
    ): Result<RenderedPdfPage> = runCatching {
        require(file.isFile) { "PDF file not found" }
        val rot = normalizeRotation(rotationDegrees)
        ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { pfd ->
            PdfRenderer(pfd).use { renderer ->
                val count = renderer.pageCount
                require(count > 0) { "PDF has no pages" }
                val safeIndex = pageIndex.coerceIn(0, count - 1)
                renderer.openPage(safeIndex).use { page ->
                    val rawW = page.width.coerceAtLeast(1)
                    val rawH = page.height.coerceAtLeast(1)
                    val isSideways = rot == 90 || rot == 270
                    val effW = if (isSideways) rawH else rawW
                    val effH = if (isSideways) rawW else rawH

                    val scaleByWidth = targetWidthPx.coerceIn(600, MAX_BASE_SIDE_PX).toFloat() / effW.toFloat()
                    val scaleByMaxSide = MAX_BASE_SIDE_PX.toFloat() / max(rawW, rawH).toFloat()
                    val scaleByPixels = sqrt(MAX_BASE_PIXELS.toDouble() / (rawW.toDouble() * rawH.toDouble())).toFloat()
                    val uniformScale = min(scaleByWidth, min(scaleByMaxSide, scaleByPixels)).coerceAtLeast(0.25f)

                    val bmpW = (rawW * uniformScale).roundToInt().coerceAtLeast(1)
                    val bmpH = (rawH * uniformScale).roundToInt().coerceAtLeast(1)

                    val rawBitmap = Bitmap.createBitmap(bmpW, bmpH, Bitmap.Config.ARGB_8888)
                    val canvas = Canvas(rawBitmap)
                    canvas.drawColor(Color.WHITE)

                    val matrix = Matrix().apply {
                        postScale(bmpW.toFloat() / rawW.toFloat(), bmpH.toFloat() / rawH.toFloat())
                    }
                    page.render(rawBitmap, null, matrix, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)

                    val finalBitmap = if (rot == 0) {
                        rawBitmap
                    } else {
                        val rotMatrix = Matrix().apply { postRotate(rot.toFloat()) }
                        Bitmap.createBitmap(rawBitmap, 0, 0, rawBitmap.width, rawBitmap.height, rotMatrix, true).also {
                            if (it !== rawBitmap) rawBitmap.recycle()
                        }
                    }

                    RenderedPdfPage(
                        bitmap = finalBitmap,
                        pageCount = count,
                        pageWidthPt = effW,
                        pageHeightPt = effH,
                        rotationDegrees = rot,
                    )
                }
            }
        }
    }

    /**
     * Renders ONLY the currently visible zoomed viewport directly from PDF vector data
     * using the exact screen transform ([totalScale], [translateX], [translateY]) and
     * [rotationDegrees]. Uses strictly positive scale in [PdfRenderer] and rotates the
     * small tile bitmap afterwards for 100% reliability across all Android builds.
     */
    fun renderViewportTile(
        file: File,
        pageIndex: Int,
        viewportWidthPx: Int,
        viewportHeightPx: Int,
        totalScale: Float,
        translateX: Float,
        translateY: Float,
        rotationDegrees: Int = 0,
    ): Result<Bitmap> = runCatching {
        require(file.isFile) { "PDF file not found" }
        require(viewportWidthPx > 0 && viewportHeightPx > 0) { "Invalid viewport" }
        val rot = normalizeRotation(rotationDegrees)
        ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { pfd ->
            PdfRenderer(pfd).use { renderer ->
                val count = renderer.pageCount
                require(count > 0) { "PDF has no pages" }
                val safeIndex = pageIndex.coerceIn(0, count - 1)
                renderer.openPage(safeIndex).use { page ->
                    val rawW = page.width.toFloat().coerceAtLeast(1f)
                    val rawH = page.height.toFloat().coerceAtLeast(1f)

                    val maxDim = max(viewportWidthPx, viewportHeightPx)
                    val tileRatio = min(1f, MAX_TILE_SIDE_PX.toFloat() / maxDim.toFloat())
                    val tileW = (viewportWidthPx * tileRatio).roundToInt().coerceAtLeast(1)
                    val tileH = (viewportHeightPx * tileRatio).roundToInt().coerceAtLeast(1)

                    val s = totalScale * tileRatio
                    val tx = translateX * tileRatio
                    val ty = translateY * tileRatio

                    val unrotW: Int
                    val unrotH: Int
                    val ux: Float
                    val uy: Float

                    when (rot) {
                        90 -> {
                            unrotW = tileH
                            unrotH = tileW
                            ux = ty
                            uy = tileW - tx - rawH * s
                        }
                        180 -> {
                            unrotW = tileW
                            unrotH = tileH
                            ux = tileW - tx - rawW * s
                            uy = tileH - ty - rawH * s
                        }
                        270 -> {
                            unrotW = tileH
                            unrotH = tileW
                            ux = tileH - ty - rawW * s
                            uy = tx
                        }
                        else -> {
                            unrotW = tileW
                            unrotH = tileH
                            ux = tx
                            uy = ty
                        }
                    }

                    val rawTile = Bitmap.createBitmap(unrotW, unrotH, Bitmap.Config.ARGB_8888)
                    val canvas = Canvas(rawTile)
                    canvas.drawColor(Color.TRANSPARENT)

                    val matrix = Matrix().apply {
                        postScale(s, s)
                        postTranslate(ux, uy)
                    }

                    // Fill only the transformed page bounds with white so outer margins stay transparent
                    val pageBounds = RectF(0f, 0f, rawW, rawH)
                    matrix.mapRect(pageBounds)
                    val whitePaint = Paint().apply {
                        color = Color.WHITE
                        style = Paint.Style.FILL
                    }
                    canvas.drawRect(pageBounds, whitePaint)

                    page.render(rawTile, null, matrix, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)

                    if (rot == 0) {
                        rawTile
                    } else {
                        val rotMatrix = Matrix().apply { postRotate(rot.toFloat()) }
                        Bitmap.createBitmap(rawTile, 0, 0, rawTile.width, rawTile.height, rotMatrix, true).also {
                            if (it !== rawTile) rawTile.recycle()
                        }
                    }
                }
            }
        }
    }
}
