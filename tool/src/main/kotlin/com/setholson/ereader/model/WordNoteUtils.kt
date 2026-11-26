package com.setholson.ereader.model

import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import com.setholson.ereader.db.BookNoteEntity

/**
 * Exact word anchor within a chapter or PDF text-mode paragraph block.
 * [startChar] is inclusive and [endChar] is exclusive within the full block text.
 */
data class WordAnchor(
    val paragraphIndex: Int,
    val startChar: Int,
    val endChar: Int,
) : Comparable<WordAnchor> {
    override fun compareTo(other: WordAnchor): Int {
        if (paragraphIndex != other.paragraphIndex) return paragraphIndex - other.paragraphIndex
        if (startChar != other.startChar) return startChar - other.startChar
        return endChar - other.endChar
    }

    fun overlaps(other: WordAnchor): Boolean =
        paragraphIndex == other.paragraphIndex && startChar < other.endChar && other.startChar < endChar
}

data class NoteChapterGroup(
    val bookId: String,
    val positionIndex: Int,
    val chapterTitle: String,
    val latestUpdatedAt: Long,
    val notes: List<BookNoteEntity>,
)

data class NoteBookGroup(
    val bookId: String,
    val bookTitle: String,
    val format: String,
    val latestUpdatedAt: Long,
    val totalNoteCount: Int,
    val chapters: List<NoteChapterGroup>,
)

object WordNoteUtils {

    private fun isCjkChar(c: Char): Boolean {
        val code = c.code
        return (code in 0x4E00..0x9FFF) ||
            (code in 0x3400..0x4DBF) ||
            (code in 0x3040..0x30FF) ||
            (code in 0xF900..0xFAFF)
    }

    private fun isWordCoreChar(c: Char): Boolean = c.isLetterOrDigit()

    private fun isWordConnector(c: Char): Boolean =
        c == '\'' || c == '\u2019' || c == '-'

    fun extractWordAnchor(text: String, paragraphIndex: Int, charIndex: Int): WordAnchor? {
        if (text.isEmpty()) return null
        val clamped = charIndex.coerceIn(0, text.lastIndex)

        var idx = clamped
        if (!isWordCoreChar(text[idx])) {
            var found = -1
            for (delta in 1..4) {
                val left = clamped - delta
                val right = clamped + delta
                if (left >= 0 && isWordCoreChar(text[left])) {
                    found = left
                    break
                }
                if (right < text.length && isWordCoreChar(text[right])) {
                    found = right
                    break
                }
            }
            if (found < 0) return null
            idx = found
        }

        if (isCjkChar(text[idx])) {
            return WordAnchor(paragraphIndex = paragraphIndex, startChar = idx, endChar = idx + 1)
        }

        var start = idx
        while (start > 0) {
            val prev = text[start - 1]
            if (isWordCoreChar(prev) && !isCjkChar(prev)) {
                start--
            } else if (isWordConnector(prev) && start >= 2 && isWordCoreChar(text[start - 2]) && !isCjkChar(text[start - 2])) {
                start--
            } else {
                break
            }
        }

        var end = idx + 1
        while (end < text.length) {
            val next = text[end]
            if (isWordCoreChar(next) && !isCjkChar(next)) {
                end++
            } else if (isWordConnector(next) && end + 1 < text.length && isWordCoreChar(text[end + 1]) && !isCjkChar(text[end + 1])) {
                end++
            } else {
                break
            }
        }

        if (start >= end) return null
        return WordAnchor(paragraphIndex = paragraphIndex, startChar = start, endChar = end)
    }

    fun extractWordText(text: String, anchor: WordAnchor): String {
        if (text.isEmpty()) return ""
        val s = anchor.startChar.coerceIn(0, text.length)
        val e = anchor.endChar.coerceIn(s, text.length)
        return text.substring(s, e)
    }

    fun toggleWordAnchor(current: List<WordAnchor>, target: WordAnchor): List<WordAnchor> {
        val hasOverlap = current.any { it.overlaps(target) }
        return if (hasOverlap) {
            current.filterNot { it.overlaps(target) }
        } else {
            (current + target).distinct().sorted()
        }
    }

    fun encodeAnchors(anchors: List<WordAnchor>): String =
        anchors.distinct().sorted().joinToString(";") { "${it.paragraphIndex}:${it.startChar}:${it.endChar}" }

    fun decodeAnchors(encoded: String): List<WordAnchor> {
        if (encoded.isBlank()) return emptyList()
        return encoded.split(";").mapNotNull { token ->
            val parts = token.split(":")
            if (parts.size != 3) return@mapNotNull null
            val p = parts[0].toIntOrNull() ?: return@mapNotNull null
            val s = parts[1].toIntOrNull() ?: return@mapNotNull null
            val e = parts[2].toIntOrNull() ?: return@mapNotNull null
            if (p >= 0 && s >= 0 && e > s) WordAnchor(p, s, e) else null
        }.distinct().sorted()
    }

    fun buildQuotedExcerpt(
        anchors: List<WordAnchor>,
        paragraphLookup: (Int) -> String?,
        maxLength: Int = 180,
    ): String {
        val sorted = anchors.distinct().sorted()
        if (sorted.isEmpty()) return ""

        val segments = ArrayList<String>()
        var idx = 0
        while (idx < sorted.size) {
            val first = sorted[idx]
            val paragraph = paragraphLookup(first.paragraphIndex)
            if (paragraph.isNullOrEmpty()) {
                idx++
                continue
            }
            val runStart = first.startChar.coerceIn(0, paragraph.length)
            var runEnd = first.endChar.coerceIn(runStart, paragraph.length)
            var j = idx + 1
            while (j < sorted.size && sorted[j].paragraphIndex == first.paragraphIndex) {
                val next = sorted[j]
                val nextStart = next.startChar.coerceIn(0, paragraph.length)
                val nextEnd = next.endChar.coerceIn(nextStart, paragraph.length)
                if (nextStart >= runEnd) {
                    val gap = paragraph.substring(runEnd, nextStart)
                    if (gap.none { isWordCoreChar(it) }) {
                        runEnd = nextEnd
                        j++
                        continue
                    }
                }
                break
            }
            val snippet = paragraph.substring(runStart, runEnd).trim()
            if (snippet.isNotEmpty()) {
                segments.add(snippet)
            }
            idx = j
        }

        val joined = segments.joinToString(" \u2026 ")
        return if (joined.length <= maxLength) {
            joined
        } else {
            joined.take(maxLength - 1).trimEnd() + "\u2026"
        }
    }

    fun findNotesForAnchor(notes: List<BookNoteEntity>, anchor: WordAnchor): List<BookNoteEntity> =
        notes.filter { note ->
            decodeAnchors(note.anchorsEncoded).any { it.overlaps(anchor) }
        }.sortedByDescending { it.updatedAt }

    fun buildUnderlinedText(
        text: String,
        paragraphIndex: Int,
        sliceOffsetInParagraph: Int,
        savedAnchors: List<WordAnchor>,
        selectedAnchors: List<WordAnchor>,
    ): AnnotatedString {
        val sliceStart = sliceOffsetInParagraph
        val sliceEnd = sliceOffsetInParagraph + text.length
        val hasSaved = savedAnchors.any {
            it.paragraphIndex == paragraphIndex && it.endChar > sliceStart && it.startChar < sliceEnd
        }
        val hasSelected = selectedAnchors.any {
            it.paragraphIndex == paragraphIndex && it.endChar > sliceStart && it.startChar < sliceEnd
        }
        if (!hasSaved && !hasSelected) {
            return AnnotatedString(text)
        }

        return buildAnnotatedString {
            append(text)
            for (anchor in savedAnchors) {
                if (anchor.paragraphIndex != paragraphIndex) continue
                val localStart = (anchor.startChar - sliceStart).coerceIn(0, text.length)
                val localEnd = (anchor.endChar - sliceStart).coerceIn(localStart, text.length)
                if (localEnd > localStart) {
                    addStyle(
                        style = SpanStyle(textDecoration = TextDecoration.Underline),
                        start = localStart,
                        end = localEnd,
                    )
                }
            }
            for (anchor in selectedAnchors) {
                if (anchor.paragraphIndex != paragraphIndex) continue
                val localStart = (anchor.startChar - sliceStart).coerceIn(0, text.length)
                val localEnd = (anchor.endChar - sliceStart).coerceIn(localStart, text.length)
                if (localEnd > localStart) {
                    addStyle(
                        style = SpanStyle(
                            textDecoration = TextDecoration.Underline,
                            fontWeight = FontWeight.Bold,
                        ),
                        start = localStart,
                        end = localEnd,
                    )
                }
            }
        }
    }

    /**
     * Groups notes into Books (containing notes) -> Chapters/Pages (containing notes) -> Notes,
     * with the most recent notes ordered highest at every level.
     */
    fun groupNotesByBookAndChapter(notes: List<BookNoteEntity>): List<NoteBookGroup> {
        if (notes.isEmpty()) return emptyList()
        return notes
            .groupBy { it.bookId }
            .map { (bookId, bookNotes) ->
                val sortedBookNotes = bookNotes.sortedByDescending { it.updatedAt }
                val latestBookNote = sortedBookNotes.first()
                val chapters = bookNotes
                    .groupBy { it.positionIndex }
                    .map { (posIndex, chapNotes) ->
                        val sortedChapNotes = chapNotes.sortedByDescending { it.updatedAt }
                        val latestChapNote = sortedChapNotes.first()
                        NoteChapterGroup(
                            bookId = bookId,
                            positionIndex = posIndex,
                            chapterTitle = latestChapNote.chapterTitle.ifBlank { "Chapter ${posIndex + 1}" },
                            latestUpdatedAt = latestChapNote.updatedAt,
                            notes = sortedChapNotes,
                        )
                    }
                    .sortedByDescending { it.latestUpdatedAt }
                NoteBookGroup(
                    bookId = bookId,
                    bookTitle = latestBookNote.bookTitle.ifBlank { bookId },
                    format = latestBookNote.format,
                    latestUpdatedAt = latestBookNote.updatedAt,
                    totalNoteCount = bookNotes.size,
                    chapters = chapters,
                )
            }
            .sortedByDescending { it.latestUpdatedAt }
    }
}
