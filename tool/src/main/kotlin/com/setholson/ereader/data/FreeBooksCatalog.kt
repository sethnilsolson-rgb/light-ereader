package com.setholson.ereader.data

import com.setholson.ereader.model.BookFormat
import com.setholson.ereader.model.FreeCatalogBook
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URI
import java.net.URLDecoder

/**
 * Curated catalog of 19 classic public-domain EPUB 3 books from Standard Ebooks
 * (cleanly typeset, proofread, DRM-free) plus a direct URL book downloader.
 */
object FreeBooksCatalog {

    val CATEGORIES = listOf("All", "Fiction", "Adventure", "Philosophy", "Mystery & Gothic")

    private fun seBook(
        slug: String,
        title: String,
        author: String,
        category: String,
        year: String,
        description: String,
    ): FreeCatalogBook {
        val flat = slug.replace('/', '_')
        val shortId = slug.substringAfter('/').substringBefore('/')
        return FreeCatalogBook(
            id = shortId,
            title = title,
            author = author,
            category = category,
            year = year,
            description = description,
            downloadUrl = "https://standardebooks.org/ebooks/$slug/downloads/$flat.epub?source=download",
            fileName = "classic_$shortId.epub",
        )
    }

    val BOOKS: List<FreeCatalogBook> = listOf(
        seBook(
            slug = "jane-austen/pride-and-prejudice",
            title = "Pride and Prejudice",
            author = "Jane Austen",
            category = "Fiction",
            year = "1813",
            description = "Wit, romance, and family pride in Regency England.",
        ),
        seBook(
            slug = "f-scott-fitzgerald/the-great-gatsby",
            title = "The Great Gatsby",
            author = "F. Scott Fitzgerald",
            category = "Fiction",
            year = "1925",
            description = "Jazz Age ambition, wealth, and illusion on Long Island.",
        ),
        seBook(
            slug = "alexandre-dumas/the-count-of-monte-cristo/chapman-and-hall",
            title = "The Count of Monte Cristo",
            author = "Alexandre Dumas",
            category = "Adventure",
            year = "1844",
            description = "Betrayal, daring escape, hidden treasure, and vengeance.",
        ),
        seBook(
            slug = "marcus-aurelius/meditations/george-long",
            title = "Meditations",
            author = "Marcus Aurelius",
            category = "Philosophy",
            year = "180 AD",
            description = "Private Stoic reflections of a Roman Emperor.",
        ),
        seBook(
            slug = "fyodor-dostoevsky/crime-and-punishment/constance-garnett",
            title = "Crime and Punishment",
            author = "Fyodor Dostoevsky",
            category = "Fiction",
            year = "1866",
            description = "A gripping psychological drama of guilt and redemption.",
        ),
        seBook(
            slug = "mary-shelley/frankenstein",
            title = "Frankenstein",
            author = "Mary Shelley",
            category = "Mystery & Gothic",
            year = "1818",
            description = "A scientist's creation forces a reckoning with ambition.",
        ),
        seBook(
            slug = "herman-melville/moby-dick",
            title = "Moby-Dick",
            author = "Herman Melville",
            category = "Adventure",
            year = "1851",
            description = "Captain Ahab's obsessive sea voyage after the white whale.",
        ),
        seBook(
            slug = "arthur-conan-doyle/the-adventures-of-sherlock-holmes",
            title = "The Adventures of Sherlock Holmes",
            author = "Arthur Conan Doyle",
            category = "Mystery & Gothic",
            year = "1892",
            description = "Twelve classic mysteries solved by Baker Street's detective.",
        ),
        seBook(
            slug = "bram-stoker/dracula",
            title = "Dracula",
            author = "Bram Stoker",
            category = "Mystery & Gothic",
            year = "1897",
            description = "The iconic Gothic vampire novel told in letters and diaries.",
        ),
        seBook(
            slug = "robert-louis-stevenson/treasure-island",
            title = "Treasure Island",
            author = "Robert Louis Stevenson",
            category = "Adventure",
            year = "1883",
            description = "Pirates, a secret map, and high-seas adventure.",
        ),
        seBook(
            slug = "sun-tzu/the-art-of-war/lionel-giles",
            title = "The Art of War",
            author = "Sun Tzu",
            category = "Philosophy",
            year = "5th c. BC",
            description = "Timeless treatise on strategy, leadership, and discipline.",
        ),
        seBook(
            slug = "charlotte-bronte/jane-eyre",
            title = "Jane Eyre",
            author = "Charlotte Brontë",
            category = "Fiction",
            year = "1847",
            description = "An independent governess uncovers secrets at Thornfield Hall.",
        ),
        seBook(
            slug = "charles-dickens/a-tale-of-two-cities",
            title = "A Tale of Two Cities",
            author = "Charles Dickens",
            category = "Fiction",
            year = "1859",
            description = "Love and sacrifice across London and Paris during the Revolution.",
        ),
        seBook(
            slug = "mark-twain/the-adventures-of-huckleberry-finn",
            title = "Huckleberry Finn",
            author = "Mark Twain",
            category = "Adventure",
            year = "1884",
            description = "A raft journey down the Mississippi River toward freedom.",
        ),
        seBook(
            slug = "oscar-wilde/the-picture-of-dorian-gray",
            title = "The Picture of Dorian Gray",
            author = "Oscar Wilde",
            category = "Mystery & Gothic",
            year = "1890",
            description = "A portrait ages while its subject remains forever young.",
        ),
        seBook(
            slug = "victor-hugo/les-miserables/isabel-f-hapgood",
            title = "Les Misérables",
            author = "Victor Hugo",
            category = "Fiction",
            year = "1862",
            description = "Jean Valjean's sweeping journey of grace, justice, and hope.",
        ),
        seBook(
            slug = "homer/the-odyssey/william-cullen-bryant",
            title = "The Odyssey",
            author = "Homer",
            category = "Adventure",
            year = "8th c. BC",
            description = "Odysseus's ten-year voyage home across mythic seas.",
        ),
        seBook(
            slug = "louisa-may-alcott/little-women",
            title = "Little Women",
            author = "Louisa May Alcott",
            category = "Fiction",
            year = "1868",
            description = "The four March sisters coming of age in New England.",
        ),
        seBook(
            slug = "leo-tolstoy/anna-karenina/constance-garnett",
            title = "Anna Karenina",
            author = "Leo Tolstoy",
            category = "Fiction",
            year = "1878",
            description = "Tolstoy's panoramic masterpiece of love and society in Russia.",
        ),
    )

    suspend fun downloadBookToDir(
        urlString: String,
        targetDir: File,
        suggestedFileName: String? = null,
        onProgress: (String) -> Unit = {},
    ): Result<File> = withContext(Dispatchers.IO) {
        runCatching {
            val normalizedUrl = normalizeUrl(urlString)
            require(normalizedUrl.startsWith("http://") || normalizedUrl.startsWith("https://")) {
                "Please enter a valid http:// or https:// link."
            }

            if (!targetDir.exists()) targetDir.mkdirs()

            onProgress("Connecting...")
            val conn = openFollowingRedirects(normalizedUrl)
            try {
                val code = conn.responseCode
                check(code in 200..299) { "Server returned HTTP $code." }

                val contentType = conn.contentType.orEmpty().lowercase()
                val rawName = suggestedFileName
                    ?: extractFileNameFromHeader(conn.getHeaderField("Content-Disposition"))
                    ?: extractFileNameFromUrl(conn.url.toString())
                    ?: guessFallbackName(contentType)

                val safeName = sanitizeBookFileName(rawName, contentType)
                require(BookFormat.fromExtension(safeName.substringAfterLast('.', "")) != null) {
                    "Unsupported file type. Link must point to an .epub, .pdf, .txt, or .md file."
                }

                val targetFile = File(targetDir, safeName)
                val tempFile = File(targetDir, ".$safeName.part")
                val totalBytes = conn.contentLengthLong

                try {
                    conn.inputStream.use { input ->
                        FileOutputStream(tempFile).use { output ->
                            val buf = ByteArray(16 * 1024)
                            var downloaded = 0L
                            var lastReportedPct = -1
                            var read = input.read(buf)
                            while (read != -1) {
                                output.write(buf, 0, read)
                                downloaded += read
                                if (totalBytes > 0) {
                                    val pct = ((downloaded * 100L) / totalBytes).toInt().coerceIn(0, 100)
                                    if (pct != lastReportedPct && pct % 10 == 0) {
                                        lastReportedPct = pct
                                        onProgress("Downloading $pct%...")
                                    }
                                } else if (downloaded % (128 * 1024) < buf.size) {
                                    onProgress("Downloading ${downloaded / 1024} KB...")
                                }
                                read = input.read(buf)
                            }
                        }
                    }

                    check(tempFile.length() > 0L) { "Downloaded file was empty." }
                    if (targetFile.exists()) targetFile.delete()
                    check(tempFile.renameTo(targetFile)) { "Could not save downloaded book." }
                    onProgress("Saved ${targetFile.name}")
                    targetFile
                } catch (t: Throwable) {
                    tempFile.delete()
                    throw t
                }
            } finally {
                conn.disconnect()
            }
        }
    }

    private fun openFollowingRedirects(startUrl: String, maxRedirects: Int = 5): HttpURLConnection {
        var current = startUrl
        repeat(maxRedirects) {
            val conn = (URI(current).toURL().openConnection() as HttpURLConnection).apply {
                connectTimeout = 12000
                readTimeout = 45000
                instanceFollowRedirects = false
                requestMethod = "GET"
                setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 14) LightPhoneEReader/1.1")
            }
            val code = conn.responseCode
            if (code in listOf(301, 302, 303, 307, 308)) {
                val loc = conn.getHeaderField("Location")
                conn.disconnect()
                if (loc.isNullOrBlank()) throw IllegalStateException("Empty redirect location")
                current = URI(current).resolve(loc).toString()
            } else {
                return conn
            }
        }
        throw IllegalStateException("Too many redirects")
    }

    internal fun normalizeUrl(raw: String): String {
        val trimmed = raw.trim()
        return if (!trimmed.contains("://") && trimmed.contains('.')) {
            "https://$trimmed"
        } else {
            trimmed
        }
    }

    internal fun sanitizeBookFileName(rawName: String, contentType: String = ""): String {
        val decoded = runCatching { URLDecoder.decode(rawName, "UTF-8") }.getOrDefault(rawName)
        val base = decoded
            .substringAfterLast('/')
            .substringAfterLast('\\')
            .substringBefore('?')
            .replace(Regex("""[^\w.\- ()]"""), "_")
            .trim()
            .trimStart('.')
            .ifBlank { "book" }

        val existingExt = base.substringAfterLast('.', "").lowercase()
        if (BookFormat.fromExtension(existingExt) != null) return base

        val inferredExt = when {
            contentType.contains("epub") -> "epub"
            contentType.contains("pdf") -> "pdf"
            contentType.contains("markdown") -> "md"
            contentType.contains("text/plain") -> "txt"
            else -> ""
        }
        return if (inferredExt.isNotBlank()) "$base.$inferredExt" else base
    }

    private fun extractFileNameFromHeader(header: String?): String? {
        if (header.isNullOrBlank()) return null
        val match = Regex("""filename\*?=(?:UTF-8'')?"?([^";\r\n]+)"?""", RegexOption.IGNORE_CASE)
            .find(header)
        return match?.groupValues?.get(1)?.trim()
    }

    private fun extractFileNameFromUrl(url: String): String? {
        val path = runCatching { URI(url).path }.getOrNull().orEmpty()
        val last = path.substringAfterLast('/').trim()
        return last.takeIf { it.isNotBlank() && it.contains('.') }
    }

    private fun guessFallbackName(contentType: String): String = when {
        contentType.contains("epub") -> "downloaded_book.epub"
        contentType.contains("pdf") -> "downloaded_document.pdf"
        contentType.contains("text/plain") -> "downloaded_book.txt"
        else -> "downloaded_book"
    }
}
