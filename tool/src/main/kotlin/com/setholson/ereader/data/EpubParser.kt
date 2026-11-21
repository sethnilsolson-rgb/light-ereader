package com.setholson.ereader.data

import com.setholson.ereader.model.BookChapter
import com.setholson.ereader.model.BookMetadata
import com.setholson.ereader.model.ParsedBook
import com.setholson.ereader.model.TextBlock
import java.io.File
import java.net.URLDecoder
import java.util.zip.ZipEntry
import java.util.zip.ZipFile

/**
 * Native, zero-dependency EPUB 2 & EPUB 3 parser built on [ZipFile].
 * Extracts title, author, reading order (<spine>), Table of Contents (NCX + EPUB 3 nav),
 * and converts each XHTML chapter into structured [TextBlock]s (headings, blockquotes, paragraphs).
 */
object EpubParser {

    fun peekMetadata(file: File): BookMetadata {
        val fallbackTitle = cleanFilenameTitle(file.nameWithoutExtension)
        return runCatching {
            ZipFile(file).use { zip ->
                val opfPath = findOpfPath(zip) ?: return@use BookMetadata(fallbackTitle, "")
                val opfXml = readEntryText(zip, opfPath) ?: return@use BookMetadata(fallbackTitle, "")
                val title = extractXmlTagText(opfXml, "dc:title")
                    ?: extractXmlTagText(opfXml, "title")
                    ?: fallbackTitle
                val author = extractXmlTagText(opfXml, "dc:creator")
                    ?: extractXmlTagText(opfXml, "creator")
                    ?: ""
                val spineCount = SPINE_ITEMREF_REGEX.findAll(opfXml).count()
                BookMetadata(
                    title = decodeHtmlEntities(title).trim().ifBlank { fallbackTitle },
                    author = decodeHtmlEntities(author).trim(),
                    totalPositions = spineCount.coerceAtLeast(1),
                )
            }
        }.getOrElse {
            BookMetadata(fallbackTitle, "")
        }
    }

    fun parse(file: File): Result<ParsedBook> = runCatching {
        val fallbackTitle = cleanFilenameTitle(file.nameWithoutExtension)
        ZipFile(file).use { zip ->
            val opfPath = findOpfPath(zip) ?: error("Invalid EPUB: package (.opf) file not found")
            val opfDir = parentDir(opfPath)
            val opfXml = readEntryText(zip, opfPath) ?: error("Unable to read OPF package")

            val title = decodeHtmlEntities(
                extractXmlTagText(opfXml, "dc:title")
                    ?: extractXmlTagText(opfXml, "title")
                    ?: fallbackTitle
            ).trim().ifBlank { fallbackTitle }

            val author = decodeHtmlEntities(
                extractXmlTagText(opfXml, "dc:creator")
                    ?: extractXmlTagText(opfXml, "creator")
                    ?: ""
            ).trim()

            // Parse <manifest> items: id -> ManifestItem(href, mediaType, properties)
            val manifest = parseManifest(opfXml)
            val spineIds = parseSpineIds(opfXml)

            // Build TOC title map: normalized zip path -> chapter title
            val tocMap = buildTocTitleMap(zip, opfDir, manifest, opfXml)

            // Resolve ordered spine hrefs (fallback to all XHTML items if spine is empty)
            val spineHrefs: List<String> = if (spineIds.isNotEmpty()) {
                spineIds.mapNotNull { manifest[it]?.href }
            } else {
                manifest.values
                    .filter { it.mediaType.contains("html", ignoreCase = true) || it.href.endsWith(".xhtml", true) || it.href.endsWith(".html", true) }
                    .map { it.href }
            }

            val chapters = mutableListOf<BookChapter>()
            spineHrefs.forEach { rawHref ->
                val resolvedPath = resolveRelativePath(opfDir, rawHref)
                val html = readEntryText(zip, resolvedPath) ?: return@forEach
                val blocks = parseXhtmlBlocks(html)
                if (blocks.isEmpty()) return@forEach

                val normalizedKey = normalizeZipPath(resolvedPath)
                val tocTitle = tocMap[normalizedKey]
                val headingFallback = blocks.firstOrNull { it.isHeading }?.text
                    ?: extractXmlTagText(html, "title")?.let { decodeHtmlEntities(it).trim() }
                val chapterTitle = (tocTitle ?: headingFallback)
                    ?.takeIf { it.isNotBlank() && !it.equals(title, ignoreCase = true) }
                    ?.take(90)
                    ?: "Chapter ${chapters.size + 1}"

                chapters.add(
                    BookChapter(
                        index = chapters.size,
                        title = chapterTitle,
                        blocks = blocks,
                    )
                )
            }

            if (chapters.isEmpty()) {
                error("No readable text chapters found in this EPUB (it may be DRM-protected or image-only).")
            }

            ParsedBook(
                title = title,
                author = author,
                chapters = chapters,
            )
        }
    }

    private data class ManifestItem(
        val id: String,
        val href: String,
        val mediaType: String,
        val properties: String,
    )

    private fun findOpfPath(zip: ZipFile): String? {
        val containerXml = readEntryText(zip, "META-INF/container.xml")
        if (containerXml != null) {
            val match = FULL_PATH_REGEX.find(containerXml)
            val path = match?.groupValues?.getOrNull(1)?.trim()
            if (!path.isNullOrBlank()) return normalizeZipPath(decodeUrlPath(path))
        }
        val entries = zip.entries()
        while (entries.hasMoreElements()) {
            val entry = entries.nextElement()
            if (!entry.isDirectory && entry.name.endsWith(".opf", ignoreCase = true)) {
                return entry.name
            }
        }
        return null
    }

    private fun parseManifest(opfXml: String): Map<String, ManifestItem> {
        val map = LinkedHashMap<String, ManifestItem>()
        ITEM_TAG_REGEX.findAll(opfXml).forEach { match ->
            val attrs = match.groupValues[1]
            val id = extractAttr(attrs, "id") ?: return@forEach
            val href = extractAttr(attrs, "href") ?: return@forEach
            val mediaType = extractAttr(attrs, "media-type").orEmpty()
            val properties = extractAttr(attrs, "properties").orEmpty()
            map[id] = ManifestItem(id, href, mediaType, properties)
        }
        return map
    }

    private fun parseSpineIds(opfXml: String): List<String> {
        val spineSection = SPINE_BLOCK_REGEX.find(opfXml)?.groupValues?.getOrNull(1) ?: opfXml
        return SPINE_ITEMREF_REGEX.findAll(spineSection).mapNotNull { match ->
            val attrs = match.groupValues[1]
            val linear = extractAttr(attrs, "linear")
            if (linear.equals("no", ignoreCase = true)) null else extractAttr(attrs, "idref")
        }.toList()
    }

    private fun buildTocTitleMap(
        zip: ZipFile,
        opfDir: String,
        manifest: Map<String, ManifestItem>,
        opfXml: String,
    ): Map<String, String> {
        val map = LinkedHashMap<String, String>()

        // 1. EPUB 3 nav document (properties="nav")
        val navItem = manifest.values.firstOrNull {
            it.properties.split(Regex("\\s+")).any { p -> p.equals("nav", ignoreCase = true) }
        }
        if (navItem != null) {
            val navPath = resolveRelativePath(opfDir, navItem.href)
            val navDir = parentDir(navPath)
            val navHtml = readEntryText(zip, navPath)
            if (navHtml != null) {
                val tocNavBlock = NAV_TOC_REGEX.find(navHtml)?.groupValues?.getOrNull(1) ?: navHtml
                ANCHOR_REGEX.findAll(tocNavBlock).forEach { m ->
                    val href = m.groupValues[1].substringBefore('#').trim()
                    val label = cleanInlineHtml(m.groupValues[2])
                    if (href.isNotBlank() && label.isNotBlank()) {
                        val key = normalizeZipPath(resolveRelativePath(navDir, href))
                        map.putIfAbsent(key, label)
                    }
                }
            }
        }

        // 2. EPUB 2 NCX document
        val tocId = SPINE_TOC_ATTR_REGEX.find(opfXml)?.groupValues?.getOrNull(1)
        val ncxItem = (tocId?.let { manifest[it] })
            ?: manifest.values.firstOrNull {
                it.mediaType.contains("dtbncx", ignoreCase = true) || it.href.endsWith(".ncx", ignoreCase = true)
            }
        if (ncxItem != null) {
            val ncxPath = resolveRelativePath(opfDir, ncxItem.href)
            val ncxDir = parentDir(ncxPath)
            val ncxXml = readEntryText(zip, ncxPath)
            if (ncxXml != null) {
                NAV_POINT_REGEX.findAll(ncxXml).forEach { m ->
                    val block = m.groupValues[1]
                    val label = extractXmlTagText(block, "text")?.let { decodeHtmlEntities(it).trim() }.orEmpty()
                    val src = CONTENT_SRC_REGEX.find(block)?.groupValues?.getOrNull(1)
                        ?.substringBefore('#')?.trim().orEmpty()
                    if (label.isNotBlank() && src.isNotBlank()) {
                        val key = normalizeZipPath(resolveRelativePath(ncxDir, src))
                        map.putIfAbsent(key, label)
                    }
                }
            }
        }

        return map
    }

    internal fun parseXhtmlBlocks(xhtml: String): List<TextBlock> {
        val bodyContent = BODY_REGEX.find(xhtml)?.groupValues?.getOrNull(1) ?: xhtml
        val stripped = bodyContent
            .replace(SCRIPT_STYLE_SVG_REGEX, "")
            .replace(HTML_COMMENT_REGEX, "")

        val blocks = mutableListOf<TextBlock>()
        BLOCK_ELEMENT_REGEX.findAll(stripped).forEach { match ->
            val tag = match.groupValues[1].lowercase()
            val inner = match.groupValues[2]

            // Skip wrapper <div> or <blockquote> if it contains child block tags that will be parsed individually
            if ((tag == "div" || tag == "section") && CONTAINS_INNER_BLOCK_REGEX.containsMatchIn(inner)) {
                return@forEach
            }

            val text = cleanInlineHtml(inner)
            if (text.isBlank()) return@forEach

            val isHeading = tag in setOf("h1", "h2", "h3", "h4", "h5", "h6")
            val isQuote = tag == "blockquote"
            val formattedText = if (tag == "li") "• $text" else text

            // Avoid immediate duplicate lines caused by nested structures
            if (blocks.lastOrNull()?.text != formattedText) {
                blocks.add(
                    TextBlock(
                        text = formattedText,
                        isHeading = isHeading,
                        isQuote = isQuote,
                    )
                )
            }
        }

        // Fallback if chapter had bare text without <p>/<div> tags
        if (blocks.isEmpty()) {
            val plain = cleanInlineHtml(stripped)
            if (plain.isNotBlank()) {
                plain.split(Regex("\\n{2,}"))
                    .map { it.trim() }
                    .filter { it.isNotBlank() }
                    .forEach { blocks.add(TextBlock(it)) }
            }
        }

        return blocks
    }

    internal fun cleanInlineHtml(raw: String): String {
        val withBreaks = raw
            .replace(Regex("""<br\s*/?>""", RegexOption.IGNORE_CASE), "\n")
            .replace(Regex("""<[^>]+>"""), "")
        return decodeHtmlEntities(withBreaks)
            .replace('\u00A0', ' ')
            .lines()
            .joinToString("\n") { it.replace(Regex("""[ \t]+"""), " ").trim() }
            .replace(Regex("""\n{3,}"""), "\n\n")
            .trim()
    }

    internal fun decodeHtmlEntities(input: String): String {
        if ('&' !in input) return input
        val named = input
            .replace("&nbsp;", " ")
            .replace("&amp;", "&")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&quot;", "\"")
            .replace("&apos;", "'")
            .replace("&ldquo;", "“")
            .replace("&rdquo;", "”")
            .replace("&lsquo;", "‘")
            .replace("&rsquo;", "’")
            .replace("&mdash;", "—")
            .replace("&ndash;", "–")
            .replace("&hellip;", "…")
            .replace("&copy;", "©")
            .replace("&eacute;", "é")
            .replace("&egrave;", "è")
            .replace("&aacute;", "á")
            .replace("&iacute;", "í")
            .replace("&oacute;", "ó")
            .replace("&uacute;", "ú")
            .replace("&ntilde;", "ñ")
            .replace("&uuml;", "ü")
            .replace("&ouml;", "ö")
            .replace("&auml;", "ä")

        return NUMERIC_ENTITY_REGEX.replace(named) { m ->
            val hex = m.groupValues[1]
            val dec = m.groupValues[2]
            val codePoint = if (hex.isNotEmpty()) hex.toIntOrNull(16) else dec.toIntOrNull(10)
            if (codePoint != null && codePoint in 1..0x10FFFF) {
                runCatching { String(Character.toChars(codePoint)) }.getOrDefault(m.value)
            } else {
                m.value
            }
        }
    }

    private fun readEntryText(zip: ZipFile, path: String): String? {
        val normalized = normalizeZipPath(decodeUrlPath(path))
        val entry: ZipEntry = zip.getEntry(normalized)
            ?: zip.getEntry(path)
            ?: findEntryCaseInsensitive(zip, normalized)
            ?: return null
        return zip.getInputStream(entry).bufferedReader(Charsets.UTF_8).use { it.readText() }
    }

    private fun findEntryCaseInsensitive(zip: ZipFile, normalized: String): ZipEntry? {
        val entries = zip.entries()
        while (entries.hasMoreElements()) {
            val e = entries.nextElement()
            if (normalizeZipPath(e.name).equals(normalized, ignoreCase = true)) return e
        }
        return null
    }

    private fun parentDir(path: String): String {
        val idx = path.lastIndexOf('/')
        return if (idx == -1) "" else path.substring(0, idx)
    }

    private fun resolveRelativePath(baseDir: String, relativeHref: String): String {
        val cleanHref = decodeUrlPath(relativeHref.substringBefore('#').trim())
        if (cleanHref.startsWith("/")) return normalizeZipPath(cleanHref.removePrefix("/"))
        val combined = if (baseDir.isBlank()) cleanHref else "$baseDir/$cleanHref"
        return normalizeZipPath(combined)
    }

    private fun normalizeZipPath(path: String): String {
        val parts = ArrayDeque<String>()
        path.replace('\\', '/').split('/').forEach { segment ->
            when {
                segment.isEmpty() || segment == "." -> Unit
                segment == ".." -> if (parts.isNotEmpty()) parts.removeLast()
                else -> parts.addLast(segment)
            }
        }
        return parts.joinToString("/")
    }

    private fun decodeUrlPath(path: String): String =
        runCatching { URLDecoder.decode(path, "UTF-8") }.getOrDefault(path)

    private fun extractXmlTagText(xml: String, tagName: String): String? {
        val regex = Regex(
            """<${Regex.escape(tagName)}(?:\s[^>]*)?>(.*?)</${Regex.escape(tagName)}>""",
            setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE),
        )
        val raw = regex.find(xml)?.groupValues?.getOrNull(1) ?: return null
        return raw.replace(Regex("""<[^>]+>"""), "").trim()
    }

    private fun extractAttr(attrString: String, attrName: String): String? {
        val regex = Regex("""\b${Regex.escape(attrName)}\s*=\s*["']([^"']*)["']""", RegexOption.IGNORE_CASE)
        return regex.find(attrString)?.groupValues?.getOrNull(1)
    }

    internal fun cleanFilenameTitle(nameWithoutExt: String): String =
        nameWithoutExt
            .replace('_', ' ')
            .replace('-', ' ')
            .replace(Regex("""\s+"""), " ")
            .trim()
            .ifBlank { "Untitled Book" }

    private val FULL_PATH_REGEX = Regex("""full-path\s*=\s*["']([^"']+)["']""", RegexOption.IGNORE_CASE)
    private val ITEM_TAG_REGEX = Regex("""<item\b([^>]+)/?>""", setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE))
    private val SPINE_BLOCK_REGEX = Regex("""<spine\b[^>]*>(.*?)</spine>""", setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE))
    private val SPINE_TOC_ATTR_REGEX = Regex("""<spine\b[^>]*\btoc\s*=\s*["']([^"']+)["']""", RegexOption.IGNORE_CASE)
    private val SPINE_ITEMREF_REGEX = Regex("""<itemref\b([^>]+)/?>""", setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE))
    private val NAV_TOC_REGEX = Regex("""<nav\b[^>]*epub:type\s*=\s*["'][^"']*toc[^"']*["'][^>]*>(.*?)</nav>""", setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE))
    private val ANCHOR_REGEX = Regex("""<a\b[^>]*href\s*=\s*["']([^"']+)["'][^>]*>(.*?)</a>""", setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE))
    private val NAV_POINT_REGEX = Regex("""<navPoint\b[^>]*>(.*?)</navPoint>""", setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE))
    private val CONTENT_SRC_REGEX = Regex("""<content\b[^>]*src\s*=\s*["']([^"']+)["']""", RegexOption.IGNORE_CASE)
    private val BODY_REGEX = Regex("""<body\b[^>]*>(.*?)</body>""", setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE))
    private val SCRIPT_STYLE_SVG_REGEX = Regex("""<(script|style|svg|head)\b[^>]*>.*?</\1>""", setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE))
    private val HTML_COMMENT_REGEX = Regex("""<!--.*?-->""", setOf(RegexOption.DOT_MATCHES_ALL))
    private val BLOCK_ELEMENT_REGEX = Regex(
        """<(h[1-6]|p|blockquote|li|div|section)\b[^>]*>(.*?)</\1>""",
        setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE),
    )
    private val CONTAINS_INNER_BLOCK_REGEX = Regex("""<(h[1-6]|p|blockquote|li|div|section)\b""", RegexOption.IGNORE_CASE)
    private val NUMERIC_ENTITY_REGEX = Regex("""&#(?:x([0-9a-fA-F]+)|([0-9]+));""")
}
