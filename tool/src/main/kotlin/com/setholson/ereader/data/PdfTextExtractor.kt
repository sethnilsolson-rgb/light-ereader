package com.setholson.ereader.data

import kotlinx.serialization.Serializable
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.Inflater
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

@Serializable
enum class PdfExtractionMode(
    val shortLabel: String,
    val displayName: String,
    val description: String,
) {
    SMART_LAYOUT(
        shortLabel = "Smart",
        displayName = "Smart Layout",
        description = "Separates boxes, sidebars & columns in reading order",
    ),
    SINGLE_COLUMN(
        shortLabel = "Top-Down",
        displayName = "Top-to-Bottom",
        description = "Reads strictly top to bottom across full page width",
    ),
    STREAM_ORDER(
        shortLabel = "Original",
        displayName = "PDF Stream Order",
        description = "Follows the PDF's internal text order (fallback)",
    );

    companion object {
        fun fromName(name: String?): PdfExtractionMode =
            entries.firstOrNull { it.name == name } ?: SMART_LAYOUT
    }
}

@Serializable
data class CachedPdfTextBlock(
    val text: String,
    val isHeading: Boolean = false,
)

@Serializable
data class CachedPdfTextPage(
    val pageIndex: Int,
    val blocks: List<CachedPdfTextBlock>,
)

@Serializable
data class CachedPdfTextBook(
    val pageCount: Int,
    val pages: List<CachedPdfTextPage>,
    val extractorVersion: Int = 1,
    val modeName: String = PdfExtractionMode.SMART_LAYOUT.name,
)

sealed class PdfTextExtractionResult {
    data class Success(val book: CachedPdfTextBook) : PdfTextExtractionResult()
    data object Encrypted : PdfTextExtractionResult()
    data object NoTextFound : PdfTextExtractionResult()
    data class Error(val message: String) : PdfTextExtractionResult()
}

/**
 * Pure-Kotlin, zero-dependency PDF text extractor designed for LightOS.
 *
 * Supports:
 * - PDF 1.0–2.0 indirect objects, incremental updates, and PDF 1.5+ compressed `/ObjStm` object streams
 * - `/FlateDecode` (zlib + raw deflate fallback, plus PNG predictors), `/ASCIIHexDecode`, `/ASCII85Decode`
 * - `/Catalog` -> `/Pages` tree traversal with `/Resources` & `/Rotate` inheritance
 * - `/ToUnicode` CMap streams (`codespacerange`, `bfchar`, `bfrange`, 1-byte & 2-byte CID, UTF-16BE, ligatures)
 * - `/WinAnsiEncoding`, `/MacRomanEncoding`, `/StandardEncoding`, and `/Differences` glyph tables
 * - Full 2D affine matrix tracking (`cm`, `q`, `Q`, `Tm`, `Td`, `TD`, `T*`, `TL`, `Tc`, `Tw`, `Tz`, `Ts`,
 *   `Tj`, `TJ`, `'`, `"`, and Form XObject `/Do` with `/Matrix`), including flipped-Y & rotated pages
 * - Smart recursive XY-cut layout segmentation for callout boxes, sidebars & multi-column books,
 *   plus Top-to-Bottom and PDF Stream Order fallback modes
 */
object PdfTextExtractor {

    const val EXTRACTOR_VERSION = 2

    private const val MAX_PDF_BYTES = 120 * 1024 * 1024 // 120 MB safety guard
    private const val MAX_DECOMPRESSED_STREAM_BYTES = 32 * 1024 * 1024

    fun extract(
        file: File,
        mode: PdfExtractionMode = PdfExtractionMode.SMART_LAYOUT,
        onProgress: (currentPage: Int, totalPages: Int) -> Unit = { _, _ -> },
        isCancelled: () -> Boolean = { false },
    ): PdfTextExtractionResult {
        if (!file.isFile) return PdfTextExtractionResult.Error("PDF file not found.")
        if (file.length() > MAX_PDF_BYTES) {
            return PdfTextExtractionResult.Error("PDF is too large for on-device text extraction.")
        }
        return extractBytes(file.readBytes(), mode, onProgress, isCancelled)
    }

    fun extractBytes(
        bytes: ByteArray,
        mode: PdfExtractionMode = PdfExtractionMode.SMART_LAYOUT,
        onProgress: (currentPage: Int, totalPages: Int) -> Unit = { _, _ -> },
        isCancelled: () -> Boolean = { false },
    ): PdfTextExtractionResult {
        return try {
            val doc = parseDocument(bytes)
            if (doc.isEncrypted) {
                return PdfTextExtractionResult.Encrypted
            }

            val pageNodes = doc.collectPages()
            if (pageNodes.isEmpty()) {
                return PdfTextExtractionResult.NoTextFound
            }

            val total = pageNodes.size
            val extractedPages = ArrayList<CachedPdfTextPage>(total)
            var totalAlphaNumChars = 0

            val fontCache = HashMap<Int, PdfFontDecoder>()

            for ((idx, pageNode) in pageNodes.withIndex()) {
                if (isCancelled()) {
                    return PdfTextExtractionResult.Error("Cancelled")
                }
                if (idx == 0 || idx == total - 1 || idx % 3 == 0) {
                    onProgress(idx + 1, total)
                }

                val blocks = extractPageBlocks(doc, pageNode, fontCache, mode)
                for (b in blocks) {
                    totalAlphaNumChars += b.text.count { it.isLetterOrDigit() }
                }
                extractedPages.add(CachedPdfTextPage(pageIndex = idx, blocks = blocks))
            }

            val minAlphaNum = min(total * 2, 12).coerceAtLeast(3)
            if (totalAlphaNumChars < minAlphaNum) {
                return PdfTextExtractionResult.NoTextFound
            }

            PdfTextExtractionResult.Success(
                CachedPdfTextBook(
                    pageCount = total,
                    pages = extractedPages,
                    extractorVersion = EXTRACTOR_VERSION,
                    modeName = mode.name,
                )
            )
        } catch (t: Throwable) {
            PdfTextExtractionResult.Error(t.message ?: "Unable to extract text from this PDF.")
        }
    }

    // -------------------------------------------------------------------------
    // PDF Object Model & Parser
    // -------------------------------------------------------------------------

    private sealed class PdfVal {
        data object Null : PdfVal()
        data class Bool(val value: Boolean) : PdfVal()
        data class Num(val value: Double) : PdfVal() {
            val asInt: Int get() = value.toInt()
            val asFloat: Float get() = value.toFloat()
        }
        data class Str(val bytes: ByteArray) : PdfVal()
        data class Name(val name: String) : PdfVal()
        data class Arr(val items: List<PdfVal>) : PdfVal()
        data class Dict(val map: Map<String, PdfVal>) : PdfVal() {
            operator fun get(key: String): PdfVal? = map[key]
        }
        data class Ref(val objNum: Int, val gen: Int) : PdfVal()
        data class Stream(val dict: Dict, val rawBytes: ByteArray) : PdfVal()
    }

    private data class PageNode(
        val dict: PdfVal.Dict,
        val inheritedResources: PdfVal.Dict?,
        val rotationDegrees: Int = 0,
    )

    private class PdfDocument(
        val objects: MutableMap<Int, PdfVal>,
        val trailerDicts: List<PdfVal.Dict>,
        val isEncrypted: Boolean,
    ) {
        fun resolve(value: PdfVal?, depth: Int = 0): PdfVal? {
            if (value == null || depth > 12) return value
            return if (value is PdfVal.Ref) resolve(objects[value.objNum], depth + 1) else value
        }

        fun resolveDict(value: PdfVal?): PdfVal.Dict? = when (val r = resolve(value)) {
            is PdfVal.Dict -> r
            is PdfVal.Stream -> r.dict
            else -> null
        }

        fun resolveInt(value: PdfVal?): Int? = (resolve(value) as? PdfVal.Num)?.asInt

        fun resolveStreamBytes(value: PdfVal?): ByteArray? {
            val stream = resolve(value) as? PdfVal.Stream ?: return null
            return decodeStream(stream.rawBytes, stream.dict, this)
        }

        fun collectPages(): List<PageNode> {
            // Find Catalog from trailer /Root first, then fall back to scanning objects for /Type /Catalog
            var rootObj: PdfVal.Dict? = null
            for (tr in trailerDicts.asReversed()) {
                val root = resolveDict(tr["Root"])
                if (root != null) {
                    rootObj = root
                    break
                }
            }
            if (rootObj == null) {
                rootObj = objects.values.firstOrNull { v ->
                    val d = when (v) {
                        is PdfVal.Dict -> v
                        is PdfVal.Stream -> v.dict
                        else -> null
                    }
                    (d?.get("Type") as? PdfVal.Name)?.name == "Catalog"
                }?.let { resolveDict(it) }
            }

            val pagesRoot = resolveDict(rootObj?.get("Pages"))
            val result = ArrayList<PageNode>()
            val visited = HashSet<Int>()

            if (pagesRoot != null) {
                walkPagesTree(pagesRoot, null, 0, result, visited)
            }

            // Fallback if catalog/tree is unusual: collect all /Type /Page objects in object-number order
            if (result.isEmpty()) {
                for ((_, v) in objects.entries.sortedBy { it.key }) {
                    val d = v as? PdfVal.Dict ?: continue
                    if ((d["Type"] as? PdfVal.Name)?.name == "Page") {
                        val rot = ((resolveInt(d["Rotate"]) ?: 0) % 360 + 360) % 360
                        result.add(PageNode(d, null, rot))
                    }
                }
            }
            return result
        }

        private fun walkPagesTree(
            node: PdfVal.Dict,
            inheritedResources: PdfVal.Dict?,
            inheritedRotation: Int,
            out: MutableList<PageNode>,
            visited: MutableSet<Int>,
        ) {
            val res = resolveDict(node["Resources"]) ?: inheritedResources
            val rot = resolveInt(node["Rotate"])?.let { ((it % 360) + 360) % 360 } ?: inheritedRotation
            val typeName = (node["Type"] as? PdfVal.Name)?.name
            val kids = resolve(node["Kids"]) as? PdfVal.Arr

            if (typeName == "Page" || (kids == null && node["Contents"] != null)) {
                out.add(PageNode(node, res, rot))
                return
            }
            if (kids == null) return

            for (kidRef in kids.items) {
                if (kidRef is PdfVal.Ref) {
                    if (!visited.add(kidRef.objNum)) continue
                }
                val kidDict = resolveDict(kidRef) ?: continue
                walkPagesTree(kidDict, res, rot, out, visited)
            }
        }
    }

    private fun parseDocument(data: ByteArray): PdfDocument {
        val objects = HashMap<Int, PdfVal>()
        val trailers = ArrayList<PdfVal.Dict>()
        var encrypted = false

        val scanner = PdfByteScanner(data)
        val n = data.size

        // Pass 1: Scan top-level indirect objects & trailers cleanly (skipping stream payloads!)
        var pos = 0
        while (pos < n) {
            // Skip whitespace & comments
            pos = scanner.skipWsAndComments(pos)
            if (pos >= n) break

            // Check for "trailer" keyword
            if (scanner.matchKeyword(pos, "trailer")) {
                val afterTrailer = scanner.skipWsAndComments(pos + 7)
                if (afterTrailer + 1 < n && data[afterTrailer] == '<'.code.toByte() && data[afterTrailer + 1] == '<'.code.toByte()) {
                    val (valObj, nextPos) = scanner.parseValue(afterTrailer)
                    if (valObj is PdfVal.Dict) {
                        trailers.add(valObj)
                        if (valObj["Encrypt"] != null) encrypted = true
                    }
                    pos = max(nextPos, afterTrailer + 2)
                    continue
                }
            }

            // Try to match "<int> <int> obj" at start of token
            if (data[pos] in '0'.code.toByte()..'9'.code.toByte()) {
                val objHeader = scanner.tryParseObjHeader(pos)
                if (objHeader != null) {
                    val (objNum, bodyStart) = objHeader
                    val (parsedVal, afterVal) = scanner.parseValue(bodyStart)
                    var cur = scanner.skipWsAndComments(afterVal)

                    if (parsedVal is PdfVal.Dict && scanner.matchKeyword(cur, "stream")) {
                        // Stream payload starts immediately after \r\n or \n
                        var streamStart = cur + 6
                        if (streamStart < n && data[streamStart] == '\r'.code.toByte()) streamStart++
                        if (streamStart < n && data[streamStart] == '\n'.code.toByte()) streamStart++

                        val declaredLen = (parsedVal["Length"] as? PdfVal.Num)?.asInt
                        var streamEnd = -1
                        if (declaredLen != null && declaredLen >= 0 && streamStart + declaredLen <= n) {
                            val checkPos = scanner.skipLineBreaks(streamStart + declaredLen)
                            if (scanner.matchKeyword(checkPos, "endstream")) {
                                streamEnd = streamStart + declaredLen
                                cur = checkPos + 9
                            }
                        }
                        if (streamEnd < 0) {
                            val found = scanner.findKeyword(streamStart, "endstream")
                            if (found >= 0) {
                                streamEnd = found
                                // Trim single trailing CRLF or LF before endstream
                                if (streamEnd > streamStart && data[streamEnd - 1] == '\n'.code.toByte()) {
                                    streamEnd--
                                    if (streamEnd > streamStart && data[streamEnd - 1] == '\r'.code.toByte()) {
                                        streamEnd--
                                    }
                                }
                                cur = found + 9
                            } else {
                                streamEnd = n
                                cur = n
                            }
                        }

                        val rawBytes = if (streamEnd >= streamStart) {
                            data.copyOfRange(streamStart, streamEnd)
                        } else {
                            ByteArray(0)
                        }
                        val streamObj = PdfVal.Stream(parsedVal, rawBytes)
                        objects[objNum] = streamObj

                        // XRef streams also carry /Root and /Encrypt
                        if ((parsedVal["Type"] as? PdfVal.Name)?.name == "XRef") {
                            trailers.add(parsedVal)
                            if (parsedVal["Encrypt"] != null) encrypted = true
                        }
                    } else {
                        objects[objNum] = parsedVal
                    }

                    // Advance past endobj if right ahead
                    val afterObj = scanner.skipWsAndComments(cur)
                    pos = if (scanner.matchKeyword(afterObj, "endobj")) afterObj + 6 else max(cur, pos + 1)
                    continue
                }
            }

            // Advance to next token/line boundary
            pos = scanner.skipToNextToken(pos)
        }

        val doc = PdfDocument(objects, trailers, encrypted)
        if (encrypted) return doc

        // Pass 2: Unpack PDF 1.5+ compressed Object Streams (/Type /ObjStm)
        val objStms = objects.values.filterIsInstance<PdfVal.Stream>().filter {
            (it.dict["Type"] as? PdfVal.Name)?.name == "ObjStm"
        }
        for (stm in objStms) {
            val count = doc.resolveInt(stm.dict["N"]) ?: continue
            val firstOffset = doc.resolveInt(stm.dict["First"]) ?: continue
            val decoded = decodeStream(stm.rawBytes, stm.dict, doc) ?: continue
            if (firstOffset < 0 || firstOffset > decoded.size) continue

            val subScanner = PdfByteScanner(decoded)
            val entries = ArrayList<Pair<Int, Int>>(count)
            var hPos = 0
            for (i in 0 until count) {
                val (numVal, p1) = subScanner.parseValue(hPos)
                val (offVal, p2) = subScanner.parseValue(p1)
                if (numVal is PdfVal.Num && offVal is PdfVal.Num) {
                    entries.add(numVal.asInt to offVal.asInt)
                }
                hPos = p2
            }
            for ((embeddedNum, relOff) in entries) {
                val absOff = firstOffset + relOff
                if (absOff in 0 until decoded.size) {
                    val (embeddedVal, _) = subScanner.parseValue(absOff)
                    // Only fill if not overridden by a newer top-level object
                    objects.putIfAbsent(embeddedNum, embeddedVal)
                }
            }
        }

        return doc
    }

    // -------------------------------------------------------------------------
    // Low-Level Byte Scanner for PDF Syntax
    // -------------------------------------------------------------------------

    private class PdfByteScanner(val data: ByteArray) {
        val len: Int = data.size

        fun isWhitespace(b: Byte): Boolean = when (b.toInt() and 0xFF) {
            0, 9, 10, 12, 13, 32 -> true
            else -> false
        }

        fun isDelimiter(b: Byte): Boolean = when (b.toInt() and 0xFF) {
            '('.code, ')'.code, '<'.code, '>'.code, '['.code, ']'.code,
            '{'.code, '}'.code, '/'.code, '%'.code -> true
            else -> false
        }

        fun skipWsAndComments(start: Int): Int {
            var p = start
            while (p < len) {
                val b = data[p]
                if (isWhitespace(b)) {
                    p++
                } else if (b == '%'.code.toByte()) {
                    while (p < len && data[p] != '\n'.code.toByte() && data[p] != '\r'.code.toByte()) p++
                } else {
                    break
                }
            }
            return p
        }

        fun skipLineBreaks(start: Int): Int {
            var p = start
            while (p < len && (data[p] == '\r'.code.toByte() || data[p] == '\n'.code.toByte() || data[p] == ' '.code.toByte())) {
                p++
            }
            return p
        }

        fun skipToNextToken(start: Int): Int {
            var p = start
            if (p >= len) return len
            if (isDelimiter(data[p])) return p + 1
            while (p < len && !isWhitespace(data[p]) && !isDelimiter(data[p])) p++
            return p
        }

        fun matchKeyword(pos: Int, kw: String): Boolean {
            if (pos < 0 || pos + kw.length > len) return false
            for (i in kw.indices) {
                if ((data[pos + i].toInt() and 0xFF) != kw[i].code) return false
            }
            val end = pos + kw.length
            return end == len || isWhitespace(data[end]) || isDelimiter(data[end])
        }

        fun findKeyword(start: Int, kw: String): Int {
            val first = kw[0].code.toByte()
            val limit = len - kw.length
            var p = start
            while (p <= limit) {
                if (data[p] == first) {
                    var ok = true
                    for (i in 1 until kw.length) {
                        if (data[p + i] != kw[i].code.toByte()) {
                            ok = false
                            break
                        }
                    }
                    if (ok) {
                        val beforeOk = p == 0 || isWhitespace(data[p - 1]) || isDelimiter(data[p - 1])
                        val afterPos = p + kw.length
                        val afterOk = afterPos == len || isWhitespace(data[afterPos]) || isDelimiter(data[afterPos])
                        if (beforeOk && afterOk) return p
                    }
                }
                p++
            }
            return -1
        }

        fun tryParseObjHeader(start: Int): Pair<Int, Int>? {
            var p = start
            val numStart = p
            while (p < len && data[p] in '0'.code.toByte()..'9'.code.toByte()) p++
            if (p == numStart || p >= len || !isWhitespace(data[p])) return null
            val objNum = String(data, numStart, p - numStart, Charsets.ISO_8859_1).toIntOrNull() ?: return null

            p = skipWsAndComments(p)
            val genStart = p
            while (p < len && data[p] in '0'.code.toByte()..'9'.code.toByte()) p++
            if (p == genStart || p >= len || !isWhitespace(data[p])) return null

            p = skipWsAndComments(p)
            if (!matchKeyword(p, "obj")) return null
            return objNum to (p + 3)
        }

        fun parseValue(start: Int, depth: Int = 0): Pair<PdfVal, Int> {
            if (depth > 32) return PdfVal.Null to min(start + 1, len)
            val p = skipWsAndComments(start)
            if (p >= len) return PdfVal.Null to len

            val b = data[p].toInt() and 0xFF
            return when {
                // Dictionary << ... >>
                b == '<'.code && p + 1 < len && data[p + 1] == '<'.code.toByte() -> parseDict(p + 2, depth + 1)
                // Hex string < ... >
                b == '<'.code -> parseHexString(p + 1)
                // Literal string ( ... )
                b == '('.code -> parseLiteralString(p + 1)
                // Array [ ... ]
                b == '['.code -> parseArray(p + 1, depth + 1)
                // Name /...
                b == '/'.code -> parseName(p + 1)
                // Number or indirect Ref (N G R)
                b in '0'.code..'9'.code || b == '+'.code || b == '-'.code || b == '.'.code -> parseNumberOrRef(p)
                // Keywords: true, false, null
                matchKeyword(p, "true") -> PdfVal.Bool(true) to (p + 4)
                matchKeyword(p, "false") -> PdfVal.Bool(false) to (p + 5)
                matchKeyword(p, "null") -> PdfVal.Null to (p + 4)
                else -> {
                    val end = skipToNextToken(p)
                    val tok = String(data, p, end - p, Charsets.ISO_8859_1)
                    PdfVal.Name(tok) to end
                }
            }
        }

        private fun parseDict(start: Int, depth: Int): Pair<PdfVal.Dict, Int> {
            val map = LinkedHashMap<String, PdfVal>()
            var p = start
            while (p < len) {
                p = skipWsAndComments(p)
                if (p >= len) break
                if (data[p] == '>'.code.toByte() && p + 1 < len && data[p + 1] == '>'.code.toByte()) {
                    return PdfVal.Dict(map) to (p + 2)
                }
                if (data[p] != '/'.code.toByte()) {
                    p = skipToNextToken(p)
                    continue
                }
                val (keyVal, afterKey) = parseName(p + 1)
                val (valObj, afterVal) = parseValue(afterKey, depth + 1)
                map[keyVal.name] = valObj
                p = max(afterVal, afterKey)
            }
            return PdfVal.Dict(map) to p
        }

        private fun parseArray(start: Int, depth: Int): Pair<PdfVal.Arr, Int> {
            val items = ArrayList<PdfVal>()
            var p = start
            while (p < len) {
                p = skipWsAndComments(p)
                if (p >= len) break
                if (data[p] == ']'.code.toByte()) {
                    return PdfVal.Arr(items) to (p + 1)
                }
                val (item, nextP) = parseValue(p, depth + 1)
                items.add(item)
                p = max(nextP, p + 1)
            }
            return PdfVal.Arr(items) to p
        }

        private fun parseName(start: Int): Pair<PdfVal.Name, Int> {
            var p = start
            val sb = StringBuilder()
            while (p < len && !isWhitespace(data[p]) && !isDelimiter(data[p])) {
                if (data[p] == '#'.code.toByte() && p + 2 < len) {
                    val h1 = hexDigit(data[p + 1])
                    val h2 = hexDigit(data[p + 2])
                    if (h1 >= 0 && h2 >= 0) {
                        sb.append(((h1 shl 4) or h2).toChar())
                        p += 3
                        continue
                    }
                }
                sb.append((data[p].toInt() and 0xFF).toChar())
                p++
            }
            return PdfVal.Name(sb.toString()) to p
        }

        fun parseLiteralString(start: Int): Pair<PdfVal.Str, Int> {
            val out = ByteArrayOutputStream()
            var p = start
            var parenDepth = 1
            while (p < len && parenDepth > 0) {
                val c = data[p].toInt() and 0xFF
                when (c) {
                    '('.code -> {
                        parenDepth++
                        out.write(c)
                        p++
                    }
                    ')'.code -> {
                        parenDepth--
                        if (parenDepth > 0) out.write(c)
                        p++
                    }
                    '\\'.code -> {
                        p++
                        if (p >= len) break
                        val esc = data[p].toInt() and 0xFF
                        when (esc) {
                            'n'.code -> { out.write('\n'.code); p++ }
                            'r'.code -> { out.write('\r'.code); p++ }
                            't'.code -> { out.write('\t'.code); p++ }
                            'b'.code -> { out.write('\b'.code); p++ }
                            'f'.code -> { out.write(12); p++ }
                            '\r'.code -> {
                                p++
                                if (p < len && data[p] == '\n'.code.toByte()) p++
                            }
                            '\n'.code -> { p++ }
                            in '0'.code..'7'.code -> {
                                var oct = esc - '0'.code
                                p++
                                var digits = 1
                                while (digits < 3 && p < len) {
                                    val d = data[p].toInt() and 0xFF
                                    if (d in '0'.code..'7'.code) {
                                        oct = (oct shl 3) or (d - '0'.code)
                                        p++
                                        digits++
                                    } else break
                                }
                                out.write(oct and 0xFF)
                            }
                            else -> { out.write(esc); p++ }
                        }
                    }
                    '\r'.code -> {
                        out.write('\n'.code)
                        p++
                        if (p < len && data[p] == '\n'.code.toByte()) p++
                    }
                    else -> {
                        out.write(c)
                        p++
                    }
                }
            }
            return PdfVal.Str(out.toByteArray()) to p
        }

        fun parseHexString(start: Int): Pair<PdfVal.Str, Int> {
            val out = ByteArrayOutputStream()
            var p = start
            var hi = -1
            while (p < len) {
                val b = data[p]
                p++
                if (b == '>'.code.toByte()) break
                val d = hexDigit(b)
                if (d < 0) continue
                if (hi < 0) {
                    hi = d
                } else {
                    out.write((hi shl 4) or d)
                    hi = -1
                }
            }
            if (hi >= 0) out.write(hi shl 4)
            return PdfVal.Str(out.toByteArray()) to p
        }

        private fun parseNumberOrRef(start: Int): Pair<PdfVal, Int> {
            val (num1, p1) = readNumber(start)
            // Check if followed by "<int> R"
            if (num1 == num1.toInt().toDouble() && num1 >= 0) {
                val q1 = skipWsAndComments(p1)
                if (q1 < len && data[q1] in '0'.code.toByte()..'9'.code.toByte()) {
                    val (num2, p2) = readNumber(q1)
                    if (num2 == num2.toInt().toDouble() && num2 >= 0) {
                        val q2 = skipWsAndComments(p2)
                        if (matchKeyword(q2, "R")) {
                            return PdfVal.Ref(num1.toInt(), num2.toInt()) to (q2 + 1)
                        }
                    }
                }
            }
            return PdfVal.Num(num1) to p1
        }

        private fun readNumber(start: Int): Pair<Double, Int> {
            var p = start
            while (p < len && !isWhitespace(data[p]) && !isDelimiter(data[p])) p++
            val s = String(data, start, p - start, Charsets.ISO_8859_1)
            return (s.toDoubleOrNull() ?: 0.0) to p
        }
    }

    private fun hexDigit(b: Byte): Int = when (val c = b.toInt() and 0xFF) {
        in '0'.code..'9'.code -> c - '0'.code
        in 'a'.code..'f'.code -> c - 'a'.code + 10
        in 'A'.code..'F'.code -> c - 'A'.code + 10
        else -> -1
    }

    // -------------------------------------------------------------------------
    // Stream Filters (FlateDecode, PNG Predictor, ASCIIHex, ASCII85)
    // -------------------------------------------------------------------------

    private fun decodeStream(raw: ByteArray, dict: PdfVal.Dict, doc: PdfDocument): ByteArray? {
        val filterVal = doc.resolve(dict["Filter"])
        val filters = when (filterVal) {
            is PdfVal.Name -> listOf(filterVal.name)
            is PdfVal.Arr -> filterVal.items.mapNotNull { (doc.resolve(it) as? PdfVal.Name)?.name }
            else -> emptyList()
        }
        val parmsVal = doc.resolve(dict["DecodeParms"])
        val parmsList: List<PdfVal.Dict?> = when (parmsVal) {
            is PdfVal.Dict -> listOf(parmsVal)
            is PdfVal.Arr -> parmsVal.items.map { doc.resolveDict(it) }
            else -> emptyList()
        }

        var current = raw
        for ((idx, filter) in filters.withIndex()) {
            val parms = parmsList.getOrNull(idx)
            current = when (filter) {
                "FlateDecode", "Fl" -> {
                    val inflated = inflateBytes(current) ?: return null
                    applyPredictorIfNeeded(inflated, parms, doc)
                }
                "ASCIIHexDecode", "AHx" -> decodeAsciiHex(current)
                "ASCII85Decode", "A85" -> decodeAscii85(current)
                else -> return null // Unsupported image/binary filter
            }
        }
        return current
    }

    private fun inflateBytes(input: ByteArray): ByteArray? {
        return tryInflate(input, nowrap = false) ?: tryInflate(input, nowrap = true)
    }

    private fun tryInflate(input: ByteArray, nowrap: Boolean): ByteArray? {
        val inflater = Inflater(nowrap)
        return try {
            inflater.setInput(input)
            val out = ByteArrayOutputStream(min(input.size * 3, 65536))
            val buf = ByteArray(8192)
            var total = 0
            while (!inflater.finished()) {
                val count = inflater.inflate(buf)
                if (count > 0) {
                    total += count
                    if (total > MAX_DECOMPRESSED_STREAM_BYTES) return null
                    out.write(buf, 0, count)
                } else {
                    if (inflater.needsInput() || inflater.needsDictionary()) break
                }
            }
            if (total == 0 && input.isNotEmpty()) null else out.toByteArray()
        } catch (_: Throwable) {
            null
        } finally {
            inflater.end()
        }
    }

    private fun applyPredictorIfNeeded(data: ByteArray, parms: PdfVal.Dict?, doc: PdfDocument): ByteArray {
        if (parms == null) return data
        val predictor = doc.resolveInt(parms["Predictor"]) ?: 1
        if (predictor < 10) return data // Only PNG predictors (10..15) apply to XRef/ObjStm

        val columns = (doc.resolveInt(parms["Columns"]) ?: 1).coerceAtLeast(1)
        val colors = (doc.resolveInt(parms["Colors"]) ?: 1).coerceAtLeast(1)
        val bpc = (doc.resolveInt(parms["BitsPerComponent"]) ?: 8).coerceAtLeast(1)
        val bpp = max(1, (colors * bpc + 7) / 8)
        val rowBytes = (columns * colors * bpc + 7) / 8
        val stride = rowBytes + 1
        if (rowBytes <= 0 || data.size < stride) return data

        val rows = data.size / stride
        val out = ByteArray(rows * rowBytes)
        var prevRow = ByteArray(rowBytes)

        for (r in 0 until rows) {
            val srcOff = r * stride
            val filterType = data[srcOff].toInt() and 0xFF
            val curRow = ByteArray(rowBytes)
            for (i in 0 until rowBytes) {
                val raw = data[srcOff + 1 + i].toInt() and 0xFF
                val left = if (i >= bpp) curRow[i - bpp].toInt() and 0xFF else 0
                val up = prevRow[i].toInt() and 0xFF
                val upLeft = if (i >= bpp) prevRow[i - bpp].toInt() and 0xFF else 0
                val decoded = when (filterType) {
                    0 -> raw
                    1 -> (raw + left) and 0xFF
                    2 -> (raw + up) and 0xFF
                    3 -> (raw + ((left + up) ushr 1)) and 0xFF
                    4 -> (raw + paethPredictor(left, up, upLeft)) and 0xFF
                    else -> raw
                }
                curRow[i] = decoded.toByte()
            }
            System.arraycopy(curRow, 0, out, r * rowBytes, rowBytes)
            prevRow = curRow
        }
        return out
    }

    private fun paethPredictor(a: Int, b: Int, c: Int): Int {
        val p = a + b - c
        val pa = abs(p - a)
        val pb = abs(p - b)
        val pc = abs(p - c)
        return when {
            pa <= pb && pa <= pc -> a
            pb <= pc -> b
            else -> c
        }
    }

    private fun decodeAsciiHex(input: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        var hi = -1
        for (b in input) {
            if (b == '>'.code.toByte()) break
            val d = hexDigit(b)
            if (d < 0) continue
            if (hi < 0) hi = d else {
                out.write((hi shl 4) or d)
                hi = -1
            }
        }
        if (hi >= 0) out.write(hi shl 4)
        return out.toByteArray()
    }

    private fun decodeAscii85(input: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        val group = IntArray(5)
        var count = 0
        var i = 0
        while (i < input.size) {
            val c = input[i].toInt() and 0xFF
            i++
            if (c <= 32) continue
            if (c == '~'.code) break
            if (c == 'z'.code && count == 0) {
                out.write(0); out.write(0); out.write(0); out.write(0)
                continue
            }
            if (c in 33..117) {
                group[count++] = c - 33
                if (count == 5) {
                    var v = 0L
                    for (k in 0 until 5) v = v * 85L + group[k]
                    out.write(((v shr 24) and 0xFF).toInt())
                    out.write(((v shr 16) and 0xFF).toInt())
                    out.write(((v shr 8) and 0xFF).toInt())
                    out.write((v and 0xFF).toInt())
                    count = 0
                }
            }
        }
        if (count > 1) {
            for (k in count until 5) group[k] = 84
            var v = 0L
            for (k in 0 until 5) v = v * 85L + group[k]
            for (b in 0 until count - 1) {
                out.write(((v shr (24 - b * 8)) and 0xFF).toInt())
            }
        }
        return out.toByteArray()
    }

    // -------------------------------------------------------------------------
    // Font Unicode Decoding (/ToUnicode CMap + /Encoding + /Differences)
    // -------------------------------------------------------------------------

    private class PdfFontDecoder(
        private val cmap1Byte: Map<Int, String>?,
        private val cmap2Byte: Map<Int, String>?,
        private val prefers2Byte: Boolean,
        private val singleByteTable: Array<String>,
    ) {
        fun decode(raw: ByteArray): String {
            if (raw.isEmpty()) return ""
            val sb = StringBuilder(raw.size)

            if (prefers2Byte && cmap2Byte != null && raw.size >= 2) {
                var i = 0
                while (i + 1 < raw.size) {
                    val code2 = ((raw[i].toInt() and 0xFF) shl 8) or (raw[i + 1].toInt() and 0xFF)
                    val mapped = cmap2Byte[code2]
                    if (mapped != null) {
                        sb.append(mapped)
                        i += 2
                    } else {
                        val mapped1 = cmap1Byte?.get(raw[i].toInt() and 0xFF)
                        if (mapped1 != null) {
                            sb.append(mapped1)
                            i++
                        } else {
                            if (code2 in 0x20..0x7E) sb.append(code2.toChar())
                            i += 2
                        }
                    }
                }
                if (i < raw.size) {
                    val b = raw[i].toInt() and 0xFF
                    sb.append(cmap1Byte?.get(b) ?: singleByteTable[b])
                }
                return normalizeLigatures(sb.toString())
            }

            // Try 2-byte lookup first if CMap has 2-byte entries and byte length is even
            if (cmap2Byte != null && cmap2Byte.isNotEmpty() && cmap1Byte.isNullOrEmpty() && raw.size % 2 == 0) {
                var i = 0
                while (i + 1 < raw.size) {
                    val code2 = ((raw[i].toInt() and 0xFF) shl 8) or (raw[i + 1].toInt() and 0xFF)
                    val mapped = cmap2Byte[code2]
                    if (mapped != null) {
                        sb.append(mapped)
                    } else if (code2 in 0x20..0x7E) {
                        sb.append(code2.toChar())
                    }
                    i += 2
                }
                return normalizeLigatures(sb.toString())
            }

            // Single-byte decoding (ToUnicode 1-byte -> Encoding + Differences -> WinAnsi fallback)
            for (byte in raw) {
                val code = byte.toInt() and 0xFF
                val mapped = cmap1Byte?.get(code)
                if (mapped != null) {
                    sb.append(mapped)
                } else {
                    sb.append(singleByteTable[code])
                }
            }
            return normalizeLigatures(sb.toString())
        }
    }

    private fun buildFontDecoder(doc: PdfDocument, fontRefOrDict: PdfVal?, cache: MutableMap<Int, PdfFontDecoder>): PdfFontDecoder {
        if (fontRefOrDict is PdfVal.Ref) {
            cache[fontRefOrDict.objNum]?.let { return it }
        }
        val fontDict = doc.resolveDict(fontRefOrDict)
            ?: return PdfFontDecoder(null, null, false, WIN_ANSI_TABLE)

        val subtype = (fontDict["Subtype"] as? PdfVal.Name)?.name
        val encodingVal = doc.resolve(fontDict["Encoding"])
        val isIdentityCid = subtype == "Type0" ||
            (encodingVal as? PdfVal.Name)?.name?.startsWith("Identity") == true

        // 1. Parse /ToUnicode CMap if present
        var cmap1: MutableMap<Int, String>? = null
        var cmap2: MutableMap<Int, String>? = null
        var prefers2Byte = isIdentityCid

        val toUnicodeBytes = doc.resolveStreamBytes(fontDict["ToUnicode"])
        if (toUnicodeBytes != null && toUnicodeBytes.isNotEmpty()) {
            val parsedCmap = parseToUnicodeCMap(toUnicodeBytes)
            cmap1 = parsedCmap.map1Byte
            cmap2 = parsedCmap.map2Byte
            if (parsedCmap.has2ByteRange || (cmap2.isNotEmpty() && cmap1.isEmpty())) {
                prefers2Byte = true
            } else if (cmap1.isNotEmpty() && cmap2.isEmpty()) {
                prefers2Byte = false
            }
        }

        // 2. Build 1-byte encoding table + /Differences
        val baseTable = when (encodingVal) {
            is PdfVal.Name -> when (encodingVal.name) {
                "MacRomanEncoding" -> MAC_ROMAN_TABLE.copyOf()
                else -> WIN_ANSI_TABLE.copyOf()
            }
            is PdfVal.Dict -> {
                val baseName = (encodingVal["BaseEncoding"] as? PdfVal.Name)?.name
                val tbl = if (baseName == "MacRomanEncoding") MAC_ROMAN_TABLE.copyOf() else WIN_ANSI_TABLE.copyOf()
                val diffs = doc.resolve(encodingVal["Differences"]) as? PdfVal.Arr
                if (diffs != null) {
                    var code = 0
                    for (item in diffs.items) {
                        when (item) {
                            is PdfVal.Num -> code = item.asInt
                            is PdfVal.Name -> {
                                if (code in 0..255) {
                                    glyphNameToUnicode(item.name)?.let { tbl[code] = it }
                                }
                                code++
                            }
                            else -> Unit
                        }
                    }
                }
                tbl
            }
            else -> WIN_ANSI_TABLE.copyOf()
        }

        val decoder = PdfFontDecoder(
            cmap1Byte = cmap1?.takeIf { it.isNotEmpty() },
            cmap2Byte = cmap2?.takeIf { it.isNotEmpty() },
            prefers2Byte = prefers2Byte,
            singleByteTable = baseTable,
        )
        if (fontRefOrDict is PdfVal.Ref) {
            cache[fontRefOrDict.objNum] = decoder
        }
        return decoder
    }

    private data class ParsedCMap(
        val map1Byte: MutableMap<Int, String>,
        val map2Byte: MutableMap<Int, String>,
        val has2ByteRange: Boolean,
    )

    private fun parseToUnicodeCMap(cmapBytes: ByteArray): ParsedCMap {
        val map1 = HashMap<Int, String>()
        val map2 = HashMap<Int, String>()
        var has2ByteRange = false

        val text = String(cmapBytes, Charsets.ISO_8859_1)

        // Check codespacerange for 2-byte codes (e.g. <0000> <FFFF>)
        val csRegex = Regex("""begincodespacerange([\s\S]*?)endcodespacerange""")
        for (match in csRegex.findAll(text)) {
            val hexes = Regex("""<([0-9A-Fa-f]+)>""").findAll(match.groupValues[1]).map { it.groupValues[1] }.toList()
            if (hexes.any { it.length >= 4 }) has2ByteRange = true
        }

        // Parse beginbfchar ... endbfchar
        val bfCharRegex = Regex("""beginbfchar([\s\S]*?)endbfchar""")
        for (match in bfCharRegex.findAll(text)) {
            val hexes = Regex("""<([0-9A-Fa-f]+)>""").findAll(match.groupValues[1]).map { it.groupValues[1] }.toList()
            var i = 0
            while (i + 1 < hexes.size) {
                val srcHex = hexes[i]
                val dstHex = hexes[i + 1]
                val srcCode = srcHex.toIntOrNull(16)
                val unicode = decodeUtf16BeHex(dstHex)
                if (srcCode != null && unicode.isNotEmpty()) {
                    if (srcHex.length <= 2) map1[srcCode] = unicode else map2[srcCode] = unicode
                }
                i += 2
            }
        }

        // Parse beginbfrange ... endbfrange
        val bfRangeRegex = Regex("""beginbfrange([\s\S]*?)endbfrange""")
        val lineTokenRegex = Regex("""<([0-9A-Fa-f]+)>\s*<([0-9A-Fa-f]+)>\s*(?:<([0-9A-Fa-f]+)>|\[([\s\S]*?)])""")
        for (match in bfRangeRegex.findAll(text)) {
            for (entry in lineTokenRegex.findAll(match.groupValues[1])) {
                val startHex = entry.groupValues[1]
                val endHex = entry.groupValues[2]
                val scalarDstHex = entry.groupValues[3]
                val arrayDstBody = entry.groupValues[4]

                val startCode = startHex.toIntOrNull(16) ?: continue
                val endCode = endHex.toIntOrNull(16) ?: continue
                if (endCode < startCode || endCode - startCode > 4096) continue
                val isTwoByte = startHex.length > 2 || endHex.length > 2

                if (scalarDstHex.isNotEmpty()) {
                    val baseUni = decodeUtf16BeHex(scalarDstHex)
                    if (baseUni.isEmpty()) continue
                    val targetMap = if (isTwoByte) map2 else map1
                    // Increment last code unit across the range
                    val prefix = baseUni.dropLast(1)
                    val lastCp = baseUni.last().code
                    for (code in startCode..endCode) {
                        val mappedChar = (lastCp + (code - startCode))
                        if (mappedChar in 1..0xFFFF) {
                            targetMap[code] = prefix + mappedChar.toChar()
                        }
                    }
                } else if (arrayDstBody.isNotEmpty()) {
                    val dstHexes = Regex("""<([0-9A-Fa-f]+)>""").findAll(arrayDstBody).map { it.groupValues[1] }.toList()
                    val targetMap = if (isTwoByte) map2 else map1
                    for ((offset, dstHex) in dstHexes.withIndex()) {
                        val code = startCode + offset
                        if (code > endCode) break
                        val uni = decodeUtf16BeHex(dstHex)
                        if (uni.isNotEmpty()) targetMap[code] = uni
                    }
                }
            }
        }

        return ParsedCMap(map1, map2, has2ByteRange)
    }

    private fun decodeUtf16BeHex(hex: String): String {
        val clean = if (hex.length % 2 == 1) "0$hex" else hex
        if (clean.length == 2) {
            val v = clean.toIntOrNull(16) ?: return ""
            return if (v > 0) v.toChar().toString() else ""
        }
        val sb = StringBuilder()
        var i = 0
        while (i + 3 < clean.length) {
            val u16 = clean.substring(i, i + 4).toIntOrNull(16) ?: 0
            if (u16 > 0) sb.append(u16.toChar())
            i += 4
        }
        return sb.toString()
    }

    // -------------------------------------------------------------------------
    // 2D Affine Matrix, Content Stream Interpreter & Layout Reflow Engine
    // -------------------------------------------------------------------------

    /**
     * Standard PDF 2D affine transformation matrix in row-vector convention:
     *   [ a  b  0 ]
     *   [ c  d  0 ]
     *   [ e  f  1 ]
     * Correctly handles translation, scaling, rotation, skew, flipped Y-axes,
     * and nested Form XObject `/Matrix` transforms.
     */
    private data class Matrix2D(
        val a: Float,
        val b: Float,
        val c: Float,
        val d: Float,
        val e: Float,
        val f: Float,
    ) {
        fun concat(other: Matrix2D): Matrix2D = Matrix2D(
            a = a * other.a + b * other.c,
            b = a * other.b + b * other.d,
            c = c * other.a + d * other.c,
            d = c * other.b + d * other.d,
            e = e * other.a + f * other.c + other.e,
            f = e * other.b + f * other.d + other.f,
        )

        fun transformPoint(x: Float, y: Float): Pair<Float, Float> =
            (a * x + c * y + e) to (b * x + d * y + f)

        fun scaleY(): Float {
            val sy = hypot(c, d)
            return if (sy > 0.001f) sy else hypot(a, b).coerceAtLeast(0.001f)
        }

        companion object {
            val IDENTITY = Matrix2D(1f, 0f, 0f, 1f, 0f, 0f)
            fun translate(tx: Float, ty: Float) = Matrix2D(1f, 0f, 0f, 1f, tx, ty)
        }
    }

    private data class TextRun(
        val text: String,
        val x: Float,
        val endX: Float,
        val y: Float,
        val fontSize: Float,
        val streamIndex: Int,
    )

    private fun extractPageBlocks(
        doc: PdfDocument,
        pageNode: PageNode,
        fontCache: MutableMap<Int, PdfFontDecoder>,
        mode: PdfExtractionMode,
    ): List<CachedPdfTextBlock> {
        val resources = doc.resolveDict(pageNode.dict["Resources"]) ?: pageNode.inheritedResources
        val contentStreams = collectContentStreams(doc, pageNode.dict["Contents"])
        if (contentStreams.isEmpty()) return emptyList()

        // Per ISO 32000-1 §7.7.3.3, an array of /Contents streams forms one continuous
        // logical content stream where graphics state & fonts carry across chunks.
        val combinedStream = if (contentStreams.size == 1) {
            contentStreams[0]
        } else {
            val out = ByteArrayOutputStream(contentStreams.sumOf { it.size + 1 })
            for ((idx, chunk) in contentStreams.withIndex()) {
                if (idx > 0) out.write('\n'.code)
                out.write(chunk)
            }
            out.toByteArray()
        }

        val runs = ArrayList<TextRun>()
        val seqCounter = IntArray(1)
        interpretContentStream(
            streamBytes = combinedStream,
            resources = resources,
            doc = doc,
            fontCache = fontCache,
            initialCtm = Matrix2D.IDENTITY,
            pageRotation = pageNode.rotationDegrees,
            outRuns = runs,
            seqCounter = seqCounter,
            formDepth = 0,
        )

        return reflowRunsToBlocks(runs, mode)
    }

    private fun collectContentStreams(doc: PdfDocument, contentsVal: PdfVal?): List<ByteArray> {
        return when (val resolved = doc.resolve(contentsVal)) {
            is PdfVal.Stream -> listOfNotNull(decodeStream(resolved.rawBytes, resolved.dict, doc))
            is PdfVal.Arr -> resolved.items.mapNotNull { doc.resolveStreamBytes(it) }
            else -> emptyList()
        }
    }

    private fun rotateToVisualCoords(x: Float, y: Float, rotationDegrees: Int): Pair<Float, Float> =
        when (rotationDegrees) {
            90 -> y to -x
            180 -> -x to -y
            270 -> -y to x
            else -> x to y
        }

    private fun estimateTextWidth(
        text: String,
        fontSize: Float,
        charSpacing: Float,
        wordSpacing: Float,
        horizScale: Float,
    ): Float {
        if (text.isEmpty()) return 0f
        var width = 0f
        for (ch in text) {
            val rel = when (ch) {
                ' ' -> 0.28f
                'i', 'l', 'I', '1', '.', ',', ':', ';', '\'', '!', '|' -> 0.28f
                'f', 'j', 'r', 't', '(', ')', '[', ']', '-', '"' -> 0.36f
                'm', 'w', 'M', 'W', '—', '–' -> 0.72f
                in 'A'..'Z' -> 0.58f
                else -> 0.48f
            }
            var advance = fontSize * rel + charSpacing
            if (ch == ' ') advance += wordSpacing
            width += advance * horizScale
        }
        return width
    }

    private fun sanitizeExtractedText(raw: String): String {
        if (raw.isEmpty()) return ""
        val sb = StringBuilder(raw.length)
        for (ch in raw) {
            when {
                ch == '\u0000' || ch == '\uFFFD' -> Unit
                ch == '\t' || ch == '\n' || ch == '\r' || ch == '\u00A0' -> sb.append(' ')
                ch.code < 0x20 -> Unit
                else -> sb.append(ch)
            }
        }
        return sb.toString()
    }

    private fun interpretContentStream(
        streamBytes: ByteArray,
        resources: PdfVal.Dict?,
        doc: PdfDocument,
        fontCache: MutableMap<Int, PdfFontDecoder>,
        initialCtm: Matrix2D,
        pageRotation: Int,
        outRuns: MutableList<TextRun>,
        seqCounter: IntArray,
        formDepth: Int,
    ) {
        if (formDepth > 6 || streamBytes.isEmpty()) return

        val fontDict = doc.resolveDict(resources?.get("Font"))
        val xObjectDict = doc.resolveDict(resources?.get("XObject"))
        val pageFonts = HashMap<String, PdfFontDecoder>()

        fun fontFor(name: String): PdfFontDecoder {
            return pageFonts.getOrPut(name) {
                buildFontDecoder(doc, fontDict?.get(name), fontCache)
            }
        }

        val scanner = PdfByteScanner(streamBytes)
        val operands = ArrayList<PdfVal>(16)

        data class GState(
            val ctm: Matrix2D,
            val font: PdfFontDecoder,
            val fontSize: Float,
            val textLeading: Float,
            val horizScale: Float,
            val charSpacing: Float,
            val wordSpacing: Float,
            val textRise: Float,
        )

        var inText = false
        var ctm = initialCtm
        var currentFont = PdfFontDecoder(null, null, false, WIN_ANSI_TABLE)
        var fontSize = 11f
        var textLeading = 14f
        var horizScale = 1f
        var charSpacing = 0f
        var wordSpacing = 0f
        var textRise = 0f

        var tm = Matrix2D.IDENTITY
        var lm = Matrix2D.IDENTITY
        val gStack = ArrayDeque<GState>()

        fun emitRunAt(str: String, startTm: Matrix2D, textAdvance: Float) {
            val cleaned = sanitizeExtractedText(str)
            if (cleaned.isBlank()) return
            val trm = startTm.concat(ctm)
            val (rawSx, rawSy) = trm.transformPoint(0f, textRise)
            val (rawEx, rawEy) = trm.transformPoint(textAdvance, textRise)
            val (visSx, visSy) = rotateToVisualCoords(rawSx, rawSy, pageRotation)
            val (visEx, _) = rotateToVisualCoords(rawEx, rawEy, pageRotation)
            val effSize = abs(fontSize * trm.scaleY()).coerceIn(4f, 96f)
            val xMin = min(visSx, visEx)
            val xMax = max(visSx, visEx).coerceAtLeast(xMin + effSize * 0.25f)
            outRuns.add(
                TextRun(
                    text = cleaned,
                    x = xMin,
                    endX = xMax,
                    y = visSy,
                    fontSize = effSize,
                    streamIndex = seqCounter[0]++,
                )
            )
        }

        fun emitText(str: String) {
            if (str.isEmpty()) return
            val adv = estimateTextWidth(str, fontSize, charSpacing, wordSpacing, horizScale)
            emitRunAt(str, tm, adv)
            tm = Matrix2D.translate(adv, 0f).concat(tm)
        }

        var pos = 0
        while (pos < scanner.len) {
            pos = scanner.skipWsAndComments(pos)
            if (pos >= scanner.len) break

            val b = streamBytes[pos].toInt() and 0xFF
            when {
                b == '('.code -> {
                    val (s, next) = scanner.parseLiteralString(pos + 1)
                    operands.add(s)
                    pos = next
                }
                b == '<'.code && pos + 1 < scanner.len && streamBytes[pos + 1] == '<'.code.toByte() -> {
                    val (d, next) = scanner.parseValue(pos)
                    operands.add(d)
                    pos = next
                }
                b == '<'.code -> {
                    val (s, next) = scanner.parseHexString(pos + 1)
                    operands.add(s)
                    pos = next
                }
                b == '['.code -> {
                    val (arr, next) = scanner.parseValue(pos)
                    operands.add(arr)
                    pos = next
                }
                b == '/'.code -> {
                    val (nm, next) = scanner.parseValue(pos)
                    operands.add(nm)
                    pos = next
                }
                b in '0'.code..'9'.code || b == '+'.code || b == '-'.code || b == '.'.code -> {
                    val end = scanner.skipToNextToken(pos)
                    val num = String(streamBytes, pos, end - pos, Charsets.ISO_8859_1).toDoubleOrNull() ?: 0.0
                    operands.add(PdfVal.Num(num))
                    pos = end
                }
                else -> {
                    val end = scanner.skipToNextToken(pos)
                    val op = String(streamBytes, pos, end - pos, Charsets.ISO_8859_1)
                    pos = end

                    when (op) {
                        "q" -> gStack.addLast(
                            GState(ctm, currentFont, fontSize, textLeading, horizScale, charSpacing, wordSpacing, textRise)
                        )
                        "Q" -> if (gStack.isNotEmpty()) {
                            val prev = gStack.removeLast()
                            ctm = prev.ctm
                            currentFont = prev.font
                            fontSize = prev.fontSize
                            textLeading = prev.textLeading
                            horizScale = prev.horizScale
                            charSpacing = prev.charSpacing
                            wordSpacing = prev.wordSpacing
                            textRise = prev.textRise
                        }
                        "cm" -> if (operands.size >= 6) {
                            val a = (operands[operands.size - 6] as? PdfVal.Num)?.asFloat ?: 1f
                            val bM = (operands[operands.size - 5] as? PdfVal.Num)?.asFloat ?: 0f
                            val cM = (operands[operands.size - 4] as? PdfVal.Num)?.asFloat ?: 0f
                            val d = (operands[operands.size - 3] as? PdfVal.Num)?.asFloat ?: 1f
                            val e = (operands[operands.size - 2] as? PdfVal.Num)?.asFloat ?: 0f
                            val f = (operands[operands.size - 1] as? PdfVal.Num)?.asFloat ?: 0f
                            ctm = Matrix2D(a, bM, cM, d, e, f).concat(ctm)
                        }
                        "BT" -> {
                            inText = true
                            tm = Matrix2D.IDENTITY
                            lm = Matrix2D.IDENTITY
                        }
                        "ET" -> inText = false
                        "Tf" -> if (operands.size >= 2) {
                            val fName = (operands[operands.size - 2] as? PdfVal.Name)?.name
                            val fSize = (operands[operands.size - 1] as? PdfVal.Num)?.asFloat
                            if (fName != null) currentFont = fontFor(fName)
                            if (fSize != null && abs(fSize) > 0.01f) fontSize = abs(fSize)
                        }
                        "TL" -> if (operands.isNotEmpty()) {
                            textLeading = (operands.last() as? PdfVal.Num)?.asFloat ?: textLeading
                        }
                        "Tz" -> if (operands.isNotEmpty()) {
                            val pct = (operands.last() as? PdfVal.Num)?.asFloat ?: 100f
                            if (abs(pct) > 1f) horizScale = (pct / 100f).coerceIn(0.1f, 10f)
                        }
                        "Tc" -> if (operands.isNotEmpty()) {
                            charSpacing = (operands.last() as? PdfVal.Num)?.asFloat ?: 0f
                        }
                        "Tw" -> if (operands.isNotEmpty()) {
                            wordSpacing = (operands.last() as? PdfVal.Num)?.asFloat ?: 0f
                        }
                        "Ts" -> if (operands.isNotEmpty()) {
                            textRise = (operands.last() as? PdfVal.Num)?.asFloat ?: 0f
                        }
                        "Tm" -> if (operands.size >= 6) {
                            val a = (operands[operands.size - 6] as? PdfVal.Num)?.asFloat ?: 1f
                            val bM = (operands[operands.size - 5] as? PdfVal.Num)?.asFloat ?: 0f
                            val cM = (operands[operands.size - 4] as? PdfVal.Num)?.asFloat ?: 0f
                            val d = (operands[operands.size - 3] as? PdfVal.Num)?.asFloat ?: 1f
                            val e = (operands[operands.size - 2] as? PdfVal.Num)?.asFloat ?: 0f
                            val f = (operands[operands.size - 1] as? PdfVal.Num)?.asFloat ?: 0f
                            tm = Matrix2D(a, bM, cM, d, e, f)
                            lm = tm
                        }
                        "Td" -> if (operands.size >= 2) {
                            val tx = (operands[operands.size - 2] as? PdfVal.Num)?.asFloat ?: 0f
                            val ty = (operands[operands.size - 1] as? PdfVal.Num)?.asFloat ?: 0f
                            lm = Matrix2D.translate(tx, ty).concat(lm)
                            tm = lm
                        }
                        "TD" -> if (operands.size >= 2) {
                            val tx = (operands[operands.size - 2] as? PdfVal.Num)?.asFloat ?: 0f
                            val ty = (operands[operands.size - 1] as? PdfVal.Num)?.asFloat ?: 0f
                            textLeading = -ty
                            lm = Matrix2D.translate(tx, ty).concat(lm)
                            tm = lm
                        }
                        "T*" -> {
                            lm = Matrix2D.translate(0f, -textLeading).concat(lm)
                            tm = lm
                        }
                        "Tj" -> if (inText && operands.isNotEmpty()) {
                            val s = operands.last() as? PdfVal.Str
                            if (s != null) emitText(currentFont.decode(s.bytes))
                        }
                        "'" -> if (inText && operands.isNotEmpty()) {
                            lm = Matrix2D.translate(0f, -textLeading).concat(lm)
                            tm = lm
                            val s = operands.last() as? PdfVal.Str
                            if (s != null) emitText(currentFont.decode(s.bytes))
                        }
                        "\"" -> if (inText && operands.isNotEmpty()) {
                            if (operands.size >= 3) {
                                wordSpacing = (operands[operands.size - 3] as? PdfVal.Num)?.asFloat ?: wordSpacing
                                charSpacing = (operands[operands.size - 2] as? PdfVal.Num)?.asFloat ?: charSpacing
                            }
                            lm = Matrix2D.translate(0f, -textLeading).concat(lm)
                            tm = lm
                            val s = operands.last() as? PdfVal.Str
                            if (s != null) emitText(currentFont.decode(s.bytes))
                        }
                        "TJ" -> if (inText && operands.isNotEmpty()) {
                            val arr = operands.last() as? PdfVal.Arr
                            if (arr != null) {
                                val sb = StringBuilder()
                                var segStartTm = tm
                                var segAdvance = 0f

                                fun flushSegment() {
                                    if (sb.isNotEmpty()) {
                                        emitRunAt(sb.toString(), segStartTm, segAdvance)
                                        sb.clear()
                                    }
                                    tm = Matrix2D.translate(segAdvance, 0f).concat(segStartTm)
                                    segStartTm = tm
                                    segAdvance = 0f
                                }

                                for (item in arr.items) {
                                    when (item) {
                                        is PdfVal.Str -> {
                                            val decoded = currentFont.decode(item.bytes)
                                            if (decoded.isNotEmpty()) {
                                                sb.append(decoded)
                                                segAdvance += estimateTextWidth(
                                                    decoded, fontSize, charSpacing, wordSpacing, horizScale
                                                )
                                            }
                                        }
                                        is PdfVal.Num -> {
                                            val disp = (-item.value.toFloat() / 1000f) * fontSize * horizScale
                                            // Large horizontal jump (> 1.4 ems) indicates a column gutter,
                                            // table cell, or reposition — split into separate positioned runs
                                            if (abs(item.value) > 1400.0) {
                                                flushSegment()
                                                tm = Matrix2D.translate(disp, 0f).concat(tm)
                                                segStartTm = tm
                                            } else {
                                                if (item.value < -180.0 && sb.isNotEmpty() && !sb.last().isWhitespace()) {
                                                    sb.append(' ')
                                                }
                                                segAdvance += disp
                                            }
                                        }
                                        else -> Unit
                                    }
                                }
                                flushSegment()
                            }
                        }
                        "Do" -> if (operands.isNotEmpty()) {
                            val xName = (operands.last() as? PdfVal.Name)?.name
                            if (xName != null && xObjectDict != null) {
                                val xObj = doc.resolve(xObjectDict[xName]) as? PdfVal.Stream
                                if (xObj != null && (xObj.dict["Subtype"] as? PdfVal.Name)?.name == "Form") {
                                    val formBytes = decodeStream(xObj.rawBytes, xObj.dict, doc)
                                    val formRes = doc.resolveDict(xObj.dict["Resources"]) ?: resources
                                    val formMatrix = parseMatrixArray(doc, xObj.dict["Matrix"])
                                    val formCtm = formMatrix.concat(ctm)
                                    if (formBytes != null) {
                                        interpretContentStream(
                                            streamBytes = formBytes,
                                            resources = formRes,
                                            doc = doc,
                                            fontCache = fontCache,
                                            initialCtm = formCtm,
                                            pageRotation = pageRotation,
                                            outRuns = outRuns,
                                            seqCounter = seqCounter,
                                            formDepth = formDepth + 1,
                                        )
                                    }
                                }
                            }
                        }
                    }
                    operands.clear()
                }
            }
        }
    }

    private fun parseMatrixArray(doc: PdfDocument, value: PdfVal?): Matrix2D {
        val arr = doc.resolve(value) as? PdfVal.Arr ?: return Matrix2D.IDENTITY
        if (arr.items.size < 6) return Matrix2D.IDENTITY
        val nums = arr.items.take(6).map { (doc.resolve(it) as? PdfVal.Num)?.asFloat ?: return Matrix2D.IDENTITY }
        return Matrix2D(nums[0], nums[1], nums[2], nums[3], nums[4], nums[5])
    }

    private data class VisualLine(
        val y: Float,
        val minX: Float,
        val maxX: Float,
        val fontSize: Float,
        val text: String,
    )

    private fun deduplicateFauxBoldRuns(runs: List<TextRun>): List<TextRun> {
        if (runs.size <= 1) return runs
        val out = ArrayList<TextRun>(runs.size)
        for (run in runs) {
            val trimmed = run.text.trim()
            if (trimmed.isEmpty()) continue
            val isDuplicate = out.asReversed().take(6).any { prev ->
                prev.text.trim() == trimmed &&
                    abs(prev.x - run.x) <= 1.2f &&
                    abs(prev.y - run.y) <= 1.2f
            }
            if (!isDuplicate) out.add(run)
        }
        return out
    }

    private fun reflowRunsToBlocks(
        rawRuns: List<TextRun>,
        mode: PdfExtractionMode,
    ): List<CachedPdfTextBlock> {
        val runs = deduplicateFauxBoldRuns(rawRuns)
        if (runs.isEmpty()) return emptyList()

        val sortedSizes = runs.map { it.fontSize }.sorted()
        val pageBodyFontSize = sortedSizes[sortedSizes.size / 2]

        return when (mode) {
            PdfExtractionMode.STREAM_ORDER -> reflowStreamOrder(runs, pageBodyFontSize)
            PdfExtractionMode.SINGLE_COLUMN -> reflowSingleColumn(runs, pageBodyFontSize)
            PdfExtractionMode.SMART_LAYOUT -> reflowSmartLayout(runs, pageBodyFontSize)
        }
    }

    private fun joinRunsToLineText(bucket: List<TextRun>): String {
        val sb = StringBuilder()
        var prevEndX = Float.NaN
        var prevSize = bucket.first().fontSize
        for (r in bucket) {
            val t = r.text
            if (sb.isNotEmpty() && !prevEndX.isNaN()) {
                val gap = r.x - prevEndX
                val needsSpace = gap > prevSize * 0.16f &&
                    !sb.last().isWhitespace() &&
                    !t.first().isWhitespace()
                if (needsSpace) sb.append(' ')
            }
            sb.append(t)
            prevEndX = max(prevEndX.takeIf { !it.isNaN() } ?: r.endX, r.endX)
            prevSize = r.fontSize
        }
        return sb.toString().replace(Regex("[ \\t\\u00A0]+"), " ").trim()
    }

    private fun reflowStreamOrder(
        runs: List<TextRun>,
        pageBodyFontSize: Float,
    ): List<CachedPdfTextBlock> {
        val ordered = runs.sortedBy { it.streamIndex }
        val lines = ArrayList<VisualLine>()
        val curBucket = ArrayList<TextRun>()

        fun flushBucket() {
            if (curBucket.isEmpty()) return
            val txt = joinRunsToLineText(curBucket)
            if (txt.isNotEmpty()) {
                val medianSize = curBucket.map { it.fontSize }.sorted()[curBucket.size / 2]
                lines.add(
                    VisualLine(
                        y = curBucket.first().y,
                        minX = curBucket.minOf { it.x },
                        maxX = curBucket.maxOf { it.endX },
                        fontSize = medianSize,
                        text = txt,
                    )
                )
            }
            curBucket.clear()
        }

        for (run in ordered) {
            if (curBucket.isEmpty()) {
                curBucket.add(run)
                continue
            }
            val first = curBucket.first()
            val last = curBucket.last()
            val tolY = max(2.5f, min(first.fontSize, run.fontSize) * 0.45f)
            val sameLine = abs(first.y - run.y) <= tolY && run.x >= last.x - run.fontSize * 0.5f
            if (sameLine) {
                curBucket.add(run)
            } else {
                flushBucket()
                curBucket.add(run)
            }
        }
        flushBucket()
        return mergeLinesToBlocks(lines, pageBodyFontSize, checkVerticalGaps = true)
    }

    private fun reflowSingleColumn(
        runs: List<TextRun>,
        pageBodyFontSize: Float,
    ): List<CachedPdfTextBlock> {
        val sortedByY = runs.sortedWith(
            compareByDescending<TextRun> { it.y }
                .thenBy { it.x }
                .thenBy { it.streamIndex }
        )
        val lineBuckets = ArrayList<MutableList<TextRun>>()
        for (run in sortedByY) {
            val existing = lineBuckets.firstOrNull { bucket ->
                val refY = bucket.first().y
                val tol = max(2.5f, min(bucket.first().fontSize, run.fontSize) * 0.45f)
                abs(refY - run.y) <= tol
            }
            if (existing != null) existing.add(run) else lineBuckets.add(mutableListOf(run))
        }

        val lines = lineBuckets.mapNotNull { bucket ->
            bucket.sortWith(compareBy<TextRun> { it.x }.thenBy { it.streamIndex })
            val txt = joinRunsToLineText(bucket)
            if (txt.isEmpty()) null else VisualLine(
                y = bucket.first().y,
                minX = bucket.first().x,
                maxX = bucket.maxOf { it.endX },
                fontSize = bucket.map { it.fontSize }.sorted()[bucket.size / 2],
                text = txt,
            )
        }.sortedByDescending { it.y }

        return mergeLinesToBlocks(lines, pageBodyFontSize, checkVerticalGaps = true)
    }

    private fun reflowSmartLayout(
        runs: List<TextRun>,
        pageBodyFontSize: Float,
    ): List<CachedPdfTextBlock> {
        // 1. Group runs by Y coordinate, then split wide horizontal gaps into separate LineSegments
        //    so side-by-side columns, callout boxes, and sidebars stay completely un-interleaved.
        val sortedByY = runs.sortedWith(
            compareByDescending<TextRun> { it.y }
                .thenBy { it.x }
                .thenBy { it.streamIndex }
        )
        val yBuckets = ArrayList<MutableList<TextRun>>()
        for (run in sortedByY) {
            val existing = yBuckets.firstOrNull { bucket ->
                val refY = bucket.first().y
                val tol = max(2.5f, min(bucket.first().fontSize, run.fontSize) * 0.45f)
                abs(refY - run.y) <= tol
            }
            if (existing != null) existing.add(run) else yBuckets.add(mutableListOf(run))
        }

        val segments = ArrayList<VisualLine>()
        for (bucket in yBuckets) {
            bucket.sortWith(compareBy<TextRun> { it.x }.thenBy { it.streamIndex })
            val cur = ArrayList<TextRun>()

            fun flushSeg() {
                if (cur.isEmpty()) return
                val txt = joinRunsToLineText(cur)
                if (txt.isNotEmpty()) {
                    segments.add(
                        VisualLine(
                            y = cur.first().y,
                            minX = cur.minOf { it.x },
                            maxX = cur.maxOf { it.endX },
                            fontSize = cur.map { it.fontSize }.sorted()[cur.size / 2],
                            text = txt,
                        )
                    )
                }
                cur.clear()
            }

            var prevEndX = Float.NaN
            var prevSize = bucket.first().fontSize
            for (r in bucket) {
                if (cur.isNotEmpty() && !prevEndX.isNaN()) {
                    val gap = r.x - prevEndX
                    val splitThreshold = max(prevSize * 2.4f, 22f)
                    if (gap > splitThreshold) {
                        flushSeg()
                    }
                }
                cur.add(r)
                prevEndX = max(prevEndX.takeIf { !it.isNaN() } ?: r.endX, r.endX)
                prevSize = r.fontSize
            }
            flushSeg()
        }

        if (segments.isEmpty()) return emptyList()

        // 2. Recursively segment into reading-order regions (XY-Cut: columns, callout boxes, bands)
        val regions = segmentRegions(segments, pageBodyFontSize, depth = 0)

        // 3. Reflow each region independently so adjacent boxes/columns never merge mid-paragraph
        val allBlocks = ArrayList<CachedPdfTextBlock>()
        for (region in regions) {
            val mergedLines = mergeSameYSegments(region)
            allBlocks.addAll(mergeLinesToBlocks(mergedLines, pageBodyFontSize, checkVerticalGaps = true))
        }
        return allBlocks
    }

    private fun mergeSameYSegments(region: List<VisualLine>): List<VisualLine> {
        if (region.size <= 1) return region
        val sorted = region.sortedWith(compareByDescending<VisualLine> { it.y }.thenBy { it.minX })
        val out = ArrayList<VisualLine>()
        var current = sorted.first()
        for (i in 1 until sorted.size) {
            val next = sorted[i]
            val tolY = max(2.5f, min(current.fontSize, next.fontSize) * 0.45f)
            if (abs(current.y - next.y) <= tolY) {
                current = VisualLine(
                    y = current.y,
                    minX = min(current.minX, next.minX),
                    maxX = max(current.maxX, next.maxX),
                    fontSize = max(current.fontSize, next.fontSize),
                    text = "${current.text} ${next.text}".replace(Regex("\\s+"), " ").trim(),
                )
            } else {
                out.add(current)
                current = next
            }
        }
        out.add(current)
        return out
    }

    /**
     * Recursive XY-Cut layout analyzer:
     * 1. Tries a clean full-height vertical gutter split (e.g. 2 columns or body + sidebar box).
     * 2. Otherwise tries a clean horizontal whitespace band split (isolating headers, footers, or stacked boxes).
     * 3. If a single full-width heading/footer spans across columns without a huge vertical gap,
     *    peels off the spanning line(s) at the top or bottom and retries the vertical column split!
     */
    private fun segmentRegions(
        segments: List<VisualLine>,
        bodyFontSize: Float,
        depth: Int,
    ): List<List<VisualLine>> {
        if (segments.size <= 3 || depth >= 6) return listOf(segments)

        // 1. Clean full-height vertical gutter cut (left region completely before right region)
        val xCut = findBestVerticalGutter(segments, bodyFontSize)
        if (xCut != null) {
            val left = segments.filter { it.maxX <= xCut + 1f }
            val right = segments.filter { it.minX >= xCut - 1f }
            if (left.isNotEmpty() && right.isNotEmpty() && left.size + right.size == segments.size) {
                return segmentRegions(left, bodyFontSize, depth + 1) +
                    segmentRegions(right, bodyFontSize, depth + 1)
            }
        }

        // 2. Clean horizontal band cut on large whitespace gaps (separates full-width titles/boxes)
        val byY = segments.sortedWith(compareByDescending<VisualLine> { it.y }.thenBy { it.minX })
        val yBandThreshold = max(bodyFontSize * 2.4f, 26f)
        var bestYCutIdx = -1
        var bestYGap = 0f
        for (i in 1 until byY.size) {
            val gap = byY[i - 1].y - byY[i].y
            if (gap > yBandThreshold && gap > bestYGap) {
                bestYGap = gap
                bestYCutIdx = i
            }
        }
        if (bestYCutIdx > 0) {
            val top = byY.subList(0, bestYCutIdx)
            val bottom = byY.subList(bestYCutIdx, byY.size)
            // Only split horizontally if at least one half gains a column/box split or it cleanly separates bands
            val topHasCols = findBestVerticalGutter(top, bodyFontSize) != null
            val bottomHasCols = findBestVerticalGutter(bottom, bodyFontSize) != null
            if (topHasCols || bottomHasCols) {
                return segmentRegions(top, bodyFontSize, depth + 1) +
                    segmentRegions(bottom, bodyFontSize, depth + 1)
            }
        }

        // 3. Peel off wide spanning lines at top/bottom (e.g. tight title directly above 2 columns)
        val regionMinX = segments.minOf { it.minX }
        val regionMaxX = segments.maxOf { it.maxX }
        val regionWidth = regionMaxX - regionMinX
        if (regionWidth > bodyFontSize * 12f) {
            val midX = (regionMinX + regionMaxX) / 2f
            val firstNonSpan = byY.indexOfFirst { !(it.minX < midX && it.maxX > midX && (it.maxX - it.minX) > regionWidth * 0.6f) }
            if (firstNonSpan in 1 until (byY.size - 3)) {
                val topSpan = byY.subList(0, firstNonSpan)
                val rest = byY.subList(firstNonSpan, byY.size)
                if (findBestVerticalGutter(rest, bodyFontSize) != null) {
                    return listOf(topSpan) + segmentRegions(rest, bodyFontSize, depth + 1)
                }
            }
            val lastNonSpan = byY.indexOfLast { !(it.minX < midX && it.maxX > midX && (it.maxX - it.minX) > regionWidth * 0.6f) }
            if (lastNonSpan in 3 until (byY.size - 1)) {
                val mainPart = byY.subList(0, lastNonSpan + 1)
                val bottomSpan = byY.subList(lastNonSpan + 1, byY.size)
                if (findBestVerticalGutter(mainPart, bodyFontSize) != null) {
                    return segmentRegions(mainPart, bodyFontSize, depth + 1) + listOf(bottomSpan)
                }
            }
        }

        return listOf(segments)
    }

    private fun findBestVerticalGutter(
        segments: List<VisualLine>,
        bodyFontSize: Float,
    ): Float? {
        if (segments.size < 4) return null
        val minGutterWidth = max(bodyFontSize * 1.4f, 14f)

        // Sort unique candidate left boundaries by xEnd
        val sortedByEnd = segments.sortedBy { it.maxX }
        var bestMid: Float? = null
        var bestWidth = 0f

        for (i in 0 until sortedByEnd.size - 1) {
            val gapLeft = sortedByEnd[i].maxX
            // Find the leftmost segment starting strictly to the right of gapLeft
            var gapRight = Float.POSITIVE_INFINITY
            var leftCount = 0
            var rightCount = 0
            var crossed = false
            var leftMinY = Float.POSITIVE_INFINITY
            var leftMaxY = Float.NEGATIVE_INFINITY
            var rightMinY = Float.POSITIVE_INFINITY
            var rightMaxY = Float.NEGATIVE_INFINITY

            for (seg in segments) {
                when {
                    seg.maxX <= gapLeft + 0.5f -> {
                        leftCount++
                        if (seg.y < leftMinY) leftMinY = seg.y
                        if (seg.y > leftMaxY) leftMaxY = seg.y
                    }
                    seg.minX > gapLeft -> {
                        rightCount++
                        if (seg.minX < gapRight) gapRight = seg.minX
                        if (seg.y < rightMinY) rightMinY = seg.y
                        if (seg.y > rightMaxY) rightMaxY = seg.y
                    }
                    else -> {
                        crossed = true
                        break
                    }
                }
            }
            if (crossed || leftCount < 2 || rightCount < 2 || !gapRight.isFinite()) continue

            val gutterWidth = gapRight - gapLeft
            if (gutterWidth < minGutterWidth) continue

            // Ensure left and right actually overlap vertically (side-by-side columns/boxes)
            val verticalOverlap = min(leftMaxY, rightMaxY) >= max(leftMinY, rightMinY) - bodyFontSize
            if (!verticalOverlap) continue

            if (gutterWidth > bestWidth) {
                bestWidth = gutterWidth
                bestMid = (gapLeft + gapRight) / 2f
            }
        }
        return bestMid
    }

    private fun mergeLinesToBlocks(
        lines: List<VisualLine>,
        pageBodyFontSize: Float,
        checkVerticalGaps: Boolean,
    ): List<CachedPdfTextBlock> {
        if (lines.isEmpty()) return emptyList()

        val bodyLineGaps = (1 until lines.size)
            .filter { i ->
                abs(lines[i - 1].fontSize - pageBodyFontSize) <= 1.5f &&
                    abs(lines[i].fontSize - pageBodyFontSize) <= 1.5f
            }
            .map { lines[it - 1].y - lines[it].y }
            .filter { it > pageBodyFontSize * 0.75f && it < pageBodyFontSize * 2.15f }
            .sorted()
        val normalLineSpacing = if (bodyLineGaps.isNotEmpty()) {
            bodyLineGaps[bodyLineGaps.size / 3]
        } else {
            pageBodyFontSize * 1.35f
        }

        val bodyLeftMargins = lines.filter { abs(it.fontSize - pageBodyFontSize) <= 1.5f }.map { it.minX }.sorted()
        val typicalLeftMargin = if (bodyLeftMargins.isNotEmpty()) bodyLeftMargins[bodyLeftMargins.size / 4] else 0f

        val blocks = ArrayList<CachedPdfTextBlock>()
        val paraBuf = StringBuilder()
        var currentIsHeading = false

        fun flushParagraph() {
            val txt = paraBuf.toString().replace(Regex("\\s+"), " ").trim()
            if (txt.isNotEmpty()) {
                blocks.add(CachedPdfTextBlock(text = txt, isHeading = currentIsHeading))
            }
            paraBuf.clear()
            currentIsHeading = false
        }

        for ((idx, line) in lines.withIndex()) {
            val isHeadingLine = line.fontSize >= pageBodyFontSize * 1.22f && line.text.length <= 120
            if (idx == 0) {
                currentIsHeading = isHeadingLine
                paraBuf.append(line.text)
                continue
            }

            val prev = lines[idx - 1]
            val dy = prev.y - line.y
            val largeVerticalGap = checkVerticalGaps && (dy > normalLineSpacing * 1.48f || dy < -pageBodyFontSize * 0.6f)
            val isIndentedNewPara = !isHeadingLine &&
                line.minX > typicalLeftMargin + pageBodyFontSize * 0.9f &&
                (prev.text.endsWith(".") || prev.text.endsWith("?") || prev.text.endsWith("!") ||
                    prev.text.endsWith("\"") || prev.text.endsWith("”") || prev.text.endsWith("’"))
            val styleChanged = isHeadingLine != currentIsHeading
            val bulletStart = line.text.startsWith("•") || line.text.startsWith("- ") ||
                line.text.startsWith("▪") || line.text.startsWith("– ") ||
                Regex("""^\d+[.)]\s+""").containsMatchIn(line.text)

            if (largeVerticalGap || isIndentedNewPara || styleChanged || bulletStart) {
                flushParagraph()
                currentIsHeading = isHeadingLine
                paraBuf.append(line.text)
            } else {
                // Smoothly join wrapped lines (and de-hyphenate broken words like "exam-" + "ple")
                val curStr = paraBuf.toString()
                if (curStr.length >= 2 &&
                    curStr.endsWith("-") &&
                    curStr[curStr.length - 2].isLetter() &&
                    line.text.firstOrNull()?.isLowerCase() == true
                ) {
                    paraBuf.deleteCharAt(paraBuf.length - 1)
                    paraBuf.append(line.text)
                } else {
                    if (paraBuf.isNotEmpty() && !paraBuf.last().isWhitespace()) {
                        paraBuf.append(' ')
                    }
                    paraBuf.append(line.text)
                }
            }
        }
        flushParagraph()

        return blocks
    }

    private fun normalizeLigatures(s: String): String {
        if (s.none { it in '\uFB00'..'\uFB06' || it == '\u00AD' }) return s
        val sb = StringBuilder(s.length)
        for (ch in s) {
            when (ch) {
                '\uFB00' -> sb.append("ff")
                '\uFB01' -> sb.append("fi")
                '\uFB02' -> sb.append("fl")
                '\uFB03' -> sb.append("ffi")
                '\uFB04' -> sb.append("ffl")
                '\uFB05', '\uFB06' -> sb.append("st")
                '\u00AD' -> Unit // strip soft hyphen
                else -> sb.append(ch)
            }
        }
        return sb.toString()
    }

    // -------------------------------------------------------------------------
    // Standard PDF Character Encoding Tables & Adobe Glyph List Essentials
    // -------------------------------------------------------------------------

    private val WIN_ANSI_TABLE: Array<String> = Array(256) { i ->
        when (i) {
            0x80 -> "\u20AC" // Euro
            0x82 -> "\u201A"
            0x83 -> "\u0192"
            0x84 -> "\u201E"
            0x85 -> "\u2026" // ellipsis
            0x86 -> "\u2020"
            0x87 -> "\u2021"
            0x88 -> "\u02C6"
            0x89 -> "\u2030"
            0x8A -> "\u0160"
            0x8B -> "\u2039"
            0x8C -> "\u0152"
            0x8E -> "\u017D"
            0x91 -> "\u2018" // left single quote
            0x92 -> "\u2019" // right single quote
            0x93 -> "\u201C" // left double quote
            0x94 -> "\u201D" // right double quote
            0x95 -> "\u2022" // bullet
            0x96 -> "\u2013" // en-dash
            0x97 -> "\u2014" // em-dash
            0x98 -> "\u02DC"
            0x99 -> "\u2122"
            0x9A -> "\u0161"
            0x9B -> "\u203A"
            0x9C -> "\u0153"
            0x9E -> "\u017E"
            0x9F -> "\u0178"
            in 0x20..0x7E, in 0xA0..0xFF -> i.toChar().toString()
            0x09, 0x0A, 0x0D -> " "
            else -> ""
        }
    }

    private val MAC_ROMAN_TABLE: Array<String> = Array(256) { i ->
        when (i) {
            0x80 -> "Ä"; 0x81 -> "Å"; 0x82 -> "Ç"; 0x83 -> "É"; 0x84 -> "Ñ"; 0x85 -> "Ö"; 0x86 -> "Ü"
            0x87 -> "á"; 0x88 -> "à"; 0x89 -> "â"; 0x8A -> "ä"; 0x8B -> "ã"; 0x8C -> "å"; 0x8D -> "ç"
            0x8E -> "é"; 0x8F -> "è"; 0x90 -> "ê"; 0x91 -> "ë"; 0x92 -> "í"; 0x93 -> "ì"; 0x94 -> "î"
            0x95 -> "ï"; 0x96 -> "ñ"; 0x97 -> "ó"; 0x98 -> "ò"; 0x99 -> "ô"; 0x9A -> "ö"; 0x9B -> "õ"
            0x9C -> "ú"; 0x9D -> "ù"; 0x9E -> "û"; 0x9F -> "ü"
            0xA5 -> "•"; 0xC9 -> "…"
            0xD0 -> "–"; 0xD1 -> "—"; 0xD2 -> "“"; 0xD3 -> "”"; 0xD4 -> "‘"; 0xD5 -> "’"
            in 0x20..0x7E -> i.toChar().toString()
            0x09, 0x0A, 0x0D -> " "
            else -> WIN_ANSI_TABLE[i]
        }
    }

    private fun glyphNameToUnicode(name: String): String? {
        if (name.length == 1) return name
        if (name.startsWith("uni") && name.length >= 7) {
            val hex = name.substring(3)
            if (hex.all { it in '0'..'9' || it in 'A'..'F' || it in 'a'..'f' }) {
                return decodeUtf16BeHex(hex).ifEmpty { null }
            }
        }
        return GLYPH_MAP[name]
    }

    private val GLYPH_MAP: Map<String, String> = mapOf(
        "space" to " ", "nbspace" to " ", "exclam" to "!", "quotedbl" to "\"", "numbersign" to "#",
        "dollar" to "$", "percent" to "%", "ampersand" to "&", "quotesingle" to "'",
        "parenleft" to "(", "parenright" to ")", "asterisk" to "*", "plus" to "+", "comma" to ",",
        "hyphen" to "-", "minus" to "-", "period" to ".", "slash" to "/",
        "zero" to "0", "one" to "1", "two" to "2", "three" to "3", "four" to "4",
        "five" to "5", "six" to "6", "seven" to "7", "eight" to "8", "nine" to "9",
        "colon" to ":", "semicolon" to ";", "less" to "<", "equal" to "=", "greater" to ">",
        "question" to "?", "at" to "@", "bracketleft" to "[", "backslash" to "\\",
        "bracketright" to "]", "asciicircum" to "^", "underscore" to "_", "grave" to "`",
        "braceleft" to "{", "bar" to "|", "braceright" to "}", "asciitilde" to "~",
        "fi" to "fi", "fl" to "fl", "ff" to "ff", "ffi" to "ffi", "ffl" to "ffl",
        "endash" to "–", "emdash" to "—", "figuredash" to "–",
        "quoteleft" to "‘", "quoteright" to "’", "quotedblleft" to "“", "quotedblright" to "”",
        "quotesinglbase" to "‚", "quotedblbase" to "„", "guillemotleft" to "«", "guillemotright" to "»",
        "bullet" to "•", "ellipsis" to "…", "dagger" to "†", "daggerdbl" to "‡",
        "trademark" to "™", "copyright" to "©", "registered" to "®", "section" to "§",
        "paragraph" to "¶", "degree" to "°", "multiply" to "×", "divide" to "÷",
        "plusminus" to "±", "Euro" to "€", "sterling" to "£", "yen" to "¥", "cent" to "¢",
        "Agrave" to "À", "Aacute" to "Á", "Acircumflex" to "Â", "Atilde" to "Ã", "Adieresis" to "Ä",
        "Aring" to "Å", "AE" to "Æ", "Ccedilla" to "Ç", "Egrave" to "È", "Eacute" to "É",
        "Ecircumflex" to "Ê", "Edieresis" to "Ë", "Igrave" to "Ì", "Iacute" to "Í",
        "Icircumflex" to "Î", "Idieresis" to "Ï", "Ntilde" to "Ñ", "Ograve" to "Ò",
        "Oacute" to "Ó", "Ocircumflex" to "Ô", "Otilde" to "Õ", "Odieresis" to "Ö",
        "Ugrave" to "Ù", "Uacute" to "Ú", "Ucircumflex" to "Û", "Udieresis" to "Ü",
        "agrave" to "à", "aacute" to "á", "acircumflex" to "â", "atilde" to "ã", "adieresis" to "ä",
        "aring" to "å", "ae" to "æ", "ccedilla" to "ç", "egrave" to "è", "eacute" to "é",
        "ecircumflex" to "ê", "edieresis" to "ë", "igrave" to "ì", "iacute" to "í",
        "icircumflex" to "î", "idieresis" to "ï", "ntilde" to "ñ", "ograve" to "ò",
        "oacute" to "ó", "ocircumflex" to "ô", "otilde" to "õ", "odieresis" to "ö",
        "ugrave" to "ù", "uacute" to "ú", "ucircumflex" to "û", "udieresis" to "ü",
        "ydieresis" to "ÿ", "germandbls" to "ß", "OE" to "Œ", "oe" to "œ",
    )
}
