package com.setholson.ereader.ui

import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.setholson.ereader.model.TextBlock
import kotlin.math.ceil

data class EReaderPageSlice(
    val sourceIndex: Int,
    val text: String,
    val isHeading: Boolean,
    val isQuote: Boolean,
    val isContinuation: Boolean,
    val charOffsetInBlock: Int = 0,
)

data class EReaderPage(
    val pageIndex: Int,
    val slices: List<EReaderPageSlice>,
    val permilleStart: Int,
) {
    val previewSnippet: String
        get() = slices.firstOrNull { !it.isHeading }?.text
            ?: slices.firstOrNull()?.text.orEmpty()
}

/**
 * Exact-fit Compose [TextMeasurer] paginator shared by EPUB/TXT chapters and PDF Text Mode.
 * Splits paragraphs cleanly on visual line boundaries so zero lines are clipped, prevents
 * orphan headings at the bottom of a page, and computes character-weighted [EReaderPage.permilleStart]
 * (0..1000) so bookmarks, Scroll <-> Pages toggles, font size changes, and Full-Screen toggles
 * preserve the user's exact reading spot.
 */
object EReaderPaginator {

    fun paginateBlocks(
        blocks: List<TextBlock>,
        textMeasurer: TextMeasurer,
        bodyStyle: TextStyle,
        headingStyle: TextStyle,
        viewportWidthPx: Int,
        viewportHeightPx: Int,
        density: Density,
    ): List<EReaderPage> {
        if (blocks.isEmpty() || viewportWidthPx <= 0 || viewportHeightPx <= 0) return emptyList()

        val quoteIndentPx = with(density) { 12.dp.roundToPx() }
        val headingTopPadPx = with(density) { 10.dp.roundToPx() }
        val headingBottomPadPx = with(density) { 6.dp.roundToPx() }
        val bodyTopPadPx = with(density) { 4.dp.roundToPx() }
        val bodyBottomPadPx = with(density) { 10.dp.roundToPx() }
        val contTopPadPx = with(density) { 2.dp.roundToPx() }
        val safetyMarginPx = with(density) { 10.dp.roundToPx() }
        val orphanHeadingGuardPx = with(density) { 54.dp.roundToPx() }

        val usableHeightPx = (viewportHeightPx - safetyMarginPx).coerceAtLeast(80)
        val totalChars = blocks.sumOf { it.text.length }.coerceAtLeast(1)

        val pages = ArrayList<EReaderPage>()
        var currentSlices = ArrayList<EReaderPageSlice>()
        var currentY = 0
        var pageStartChars = 0
        var consumedChars = 0

        fun flushPage() {
            if (currentSlices.isEmpty()) return
            val permille = if (pages.isEmpty()) {
                0
            } else {
                ((pageStartChars.toLong() * 1000L) / totalChars.toLong()).toInt().coerceIn(1, 999)
            }
            pages.add(
                EReaderPage(
                    pageIndex = pages.size,
                    slices = currentSlices,
                    permilleStart = permille,
                )
            )
            currentSlices = ArrayList()
            currentY = 0
            pageStartChars = consumedChars
        }

        for ((idx, block) in blocks.withIndex()) {
            val rawText = block.text
            if (rawText.isBlank()) continue

            val style = if (block.isHeading) headingStyle else bodyStyle
            val textWidthPx = (viewportWidthPx - if (block.isQuote) quoteIndentPx else 0).coerceAtLeast(80)
            val layout = textMeasurer.measure(
                text = AnnotatedString(rawText),
                style = style,
                constraints = Constraints(maxWidth = textWidthPx),
            )
            val lineCount = layout.lineCount
            if (lineCount <= 0) {
                consumedChars += rawText.length
                continue
            }

            // Keep headings together with the start of the following paragraph when possible
            if (block.isHeading && currentSlices.isNotEmpty()) {
                val headingHeight = headingTopPadPx +
                    ceil(layout.getLineBottom(lineCount - 1) - layout.getLineTop(0)).toInt() +
                    headingBottomPadPx
                if (currentY + headingHeight + orphanHeadingGuardPx > usableHeightPx) {
                    flushPage()
                }
            }

            var startLine = 0
            while (startLine < lineCount) {
                val isFirstSlice = startLine == 0
                val topPad = when {
                    !isFirstSlice -> contTopPadPx
                    block.isHeading -> if (currentSlices.isEmpty()) 2 else headingTopPadPx
                    else -> bodyTopPadPx
                }
                val bottomPadFull = if (block.isHeading) headingBottomPadPx else bodyBottomPadPx
                val available = usableHeightPx - currentY - topPad

                var endLine = startLine - 1
                val topY = layout.getLineTop(startLine)
                for (line in startLine until lineCount) {
                    val h = ceil(layout.getLineBottom(line) - topY).toInt()
                    val needBottom = if (line == lineCount - 1) bottomPadFull else 0
                    if (h + needBottom <= available) {
                        endLine = line
                    } else {
                        break
                    }
                }

                if (endLine < startLine) {
                    if (currentSlices.isNotEmpty()) {
                        flushPage()
                        continue
                    } else {
                        // Single line taller than viewport: take at least 1 line so pagination terminates
                        endLine = startLine
                    }
                }

                // Avoid stranding a single 1-line stub at the bottom of a page for a 3+ line paragraph
                val linesTaken = endLine - startLine + 1
                if (isFirstSlice && !block.isHeading && linesTaken == 1 && lineCount >= 3 && currentSlices.isNotEmpty()) {
                    flushPage()
                    continue
                }

                val charStart = layout.getLineStart(startLine).coerceIn(0, rawText.length)
                val charEnd = layout.getLineEnd(endLine, visibleEnd = true).coerceIn(charStart, rawText.length)
                val sliceText = rawText.substring(charStart, charEnd).trimEnd()
                val sliceHeight = ceil(layout.getLineBottom(endLine) - topY).toInt()
                val isLastSlice = endLine == lineCount - 1

                if (sliceText.isNotEmpty()) {
                    currentSlices.add(
                        EReaderPageSlice(
                            sourceIndex = idx,
                            text = sliceText,
                            isHeading = block.isHeading,
                            isQuote = block.isQuote,
                            isContinuation = !isFirstSlice,
                            charOffsetInBlock = charStart,
                        )
                    )
                    currentY += topPad + sliceHeight + if (isLastSlice) bottomPadFull else 0
                }
                consumedChars += (charEnd - charStart).coerceAtLeast(0)
                startLine = endLine + 1

                if (!isLastSlice) {
                    flushPage()
                }
            }
        }

        flushPage()
        return pages
    }

    fun pageIndexForPermille(pages: List<EReaderPage>, permille: Int): Int {
        if (pages.isEmpty()) return 0
        if (permille <= 0) return 0
        if (permille >= 1000) return pages.lastIndex
        var match = 0
        for (i in pages.indices) {
            if (pages[i].permilleStart <= permille) {
                match = i
            } else {
                break
            }
        }
        return match
    }
}
