package com.setholson.ereader.data

import com.setholson.ereader.db.DictDao
import com.setholson.ereader.db.DictEntryEntity
import com.setholson.ereader.model.WordLookupResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URI

/**
 * Optional downloadable offline English dictionary (Noah Webster's 1828 Dictionary)
 * for press-and-hold word definitions while reading EPUB and TXT books.
 */
object OfflineDictionaryService {

    private const val WEBSTER_1828_CSV_URL =
        "https://raw.githubusercontent.com/akaitsurugi/webster1828/main/webster1828.csv"

    suspend fun downloadDictionary(
        dao: DictDao,
        onProgress: (String) -> Unit,
    ): Result<Int> = withContext(Dispatchers.IO) {
        runCatching {
            onProgress("Downloading Webster's 1828 Dictionary...")
            dao.clearAll()
            var connection: HttpURLConnection? = null
            var inserted = 0
            val batch = ArrayList<DictEntryEntity>(1000)

            try {
                val conn = openConnection(WEBSTER_1828_CSV_URL)
                connection = conn
                BufferedReader(InputStreamReader(conn.inputStream, Charsets.UTF_8)).use { reader ->
                    var line: String? = reader.readLine()
                    var rowBuffer = StringBuilder()
                    var inQuotes = false
                    var isHeader = true

                    while (line != null) {
                        if (rowBuffer.isNotEmpty()) rowBuffer.append('\n')
                        rowBuffer.append(line)
                        for (c in line) {
                            if (c == '"') inQuotes = !inQuotes
                        }
                        if (!inQuotes) {
                            val fullRow = rowBuffer.toString()
                            rowBuffer = StringBuilder()
                            if (isHeader) {
                                isHeader = false
                            } else {
                                parseCsvRow(fullRow)?.let { entry ->
                                    batch.add(entry)
                                    if (batch.size >= 1000) {
                                        dao.insertBatch(batch)
                                        inserted += batch.size
                                        batch.clear()
                                        if (inserted % 10000 == 0) {
                                            onProgress("Indexing dictionary... $inserted words")
                                        }
                                    }
                                }
                            }
                        }
                        line = reader.readLine()
                    }
                }
                if (batch.isNotEmpty()) {
                    dao.insertBatch(batch)
                    inserted += batch.size
                    batch.clear()
                }
            } finally {
                connection?.disconnect()
            }
            onProgress("Dictionary ready ($inserted words)")
            inserted
        }
    }

    internal fun parseCsvRow(row: String): DictEntryEntity? {
        if (row.isBlank()) return null
        val fields = mutableListOf<String>()
        val current = StringBuilder()
        var inQuotes = false
        var i = 0
        while (i < row.length) {
            val c = row[i]
            when {
                c == '"' -> {
                    if (inQuotes && i + 1 < row.length && row[i + 1] == '"') {
                        current.append('"')
                        i++
                    } else {
                        inQuotes = !inQuotes
                    }
                }
                c == ',' && !inQuotes -> {
                    fields.add(current.toString())
                    current.clear()
                }
                else -> current.append(c)
            }
            i++
        }
        fields.add(current.toString())
        if (fields.size < 2) return null

        val word = fields[0].trim()
        val def = fields[1].trim()
            .replace(Regex("""\s+"""), " ")
            .take(700)
        if (word.isBlank() || def.isBlank()) return null

        return DictEntryEntity(
            headword = word.lowercase(),
            definition = def,
        )
    }

    suspend fun lookupAtOffset(
        text: String,
        charIndex: Int,
        dao: DictDao,
    ): WordLookupResult? = withContext(Dispatchers.IO) {
        val extracted = extractWordAt(text, charIndex) ?: return@withContext null
        val normalized = extracted.lowercase()

        for (candidate in buildEnglishCandidates(normalized)) {
            val rows = dao.lookupExact(candidate)
            if (rows.isNotEmpty()) {
                val combined = rows.map { it.definition }.distinct().take(2).joinToString("\n\n")
                return@withContext WordLookupResult(
                    query = extracted,
                    headword = rows.first().headword,
                    definition = combined,
                    sourceLabel = "Webster's 1828 Dictionary",
                )
            }
        }

        WordLookupResult(
            query = extracted,
            headword = extracted,
            definition = "No offline definition found for \"$extracted\".",
            sourceLabel = "Webster's 1828 Dictionary",
            notFound = true,
        )
    }

    internal fun extractWordAt(text: String, charIndex: Int): String? {
        if (text.isEmpty()) return null
        var idx = charIndex.coerceIn(0, text.lastIndex)
        if (!isWordChar(text[idx])) {
            if (idx > 0 && isWordChar(text[idx - 1])) idx--
            else if (idx + 1 < text.length && isWordChar(text[idx + 1])) idx++
            else return null
        }
        var start = idx
        while (start > 0 && isWordChar(text[start - 1])) start--
        var end = idx
        while (end + 1 < text.length && isWordChar(text[end + 1])) end++
        return text.substring(start, end + 1).trim('\'', '-', '’').takeIf { it.isNotBlank() }
    }

    internal fun buildEnglishCandidates(normalized: String): List<String> {
        val list = LinkedHashSet<String>()
        list.add(normalized)
        if (normalized.endsWith("ieth") && normalized.length > 5) list.add(normalized.dropLast(4) + "y")
        if (normalized.endsWith("eth") && normalized.length > 4) {
            list.add(normalized.dropLast(3))
            list.add(normalized.dropLast(3) + "e")
        }
        if (normalized.endsWith("est") && normalized.length > 4) {
            list.add(normalized.dropLast(3))
            list.add(normalized.dropLast(3) + "e")
        }
        if (normalized.endsWith("ies") && normalized.length > 4) list.add(normalized.dropLast(3) + "y")
        if (normalized.endsWith("ied") && normalized.length > 4) list.add(normalized.dropLast(3) + "y")
        if (normalized.endsWith("ing") && normalized.length > 5) {
            val stem = normalized.dropLast(3)
            list.add(stem)
            list.add(stem + "e")
            if (stem.length >= 2 && stem.last() == stem[stem.lastIndex - 1]) list.add(stem.dropLast(1))
        }
        if (normalized.endsWith("ed") && normalized.length > 4) {
            val stem = normalized.dropLast(2)
            list.add(stem)
            list.add(stem + "e")
            if (stem.length >= 2 && stem.last() == stem[stem.lastIndex - 1]) list.add(stem.dropLast(1))
        }
        if (normalized.endsWith("es") && normalized.length > 3) list.add(normalized.dropLast(2))
        if (normalized.endsWith("s") && normalized.length > 3 && !normalized.endsWith("ss")) {
            list.add(normalized.dropLast(1))
        }
        return list.toList()
    }

    private fun isWordChar(c: Char): Boolean = c.isLetter() || c == '\'' || c == '’' || c == '-'

    private fun openConnection(urlString: String): HttpURLConnection {
        val url = URI(urlString).toURL()
        return (url.openConnection() as HttpURLConnection).apply {
            connectTimeout = 8000
            readTimeout = 30000
            instanceFollowRedirects = true
            requestMethod = "GET"
            setRequestProperty("User-Agent", "LightPhoneEReader/1.0")
        }
    }
}
