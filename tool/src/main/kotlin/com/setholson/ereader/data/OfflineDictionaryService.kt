package com.setholson.ereader.data

import com.setholson.ereader.db.DictDao
import com.setholson.ereader.db.DictEntryEntity
import com.setholson.ereader.model.DictionaryHit
import com.setholson.ereader.model.DictionaryPack
import com.setholson.ereader.model.WordLookupResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URI
import java.net.URLEncoder
import java.util.zip.GZIPInputStream

/**
 * Multi-dictionary downloader & lookup service supporting:
 *  - Modern English (Wordset)
 *  - Classic Literary English (GCIDE)
 *  - Webster's 1828 (Classic & Scripture)
 *  - Live Modern Dictionary (Free Dictionary API with automatic offline caching)
 *  - Spanish -> English (FreeDict)
 *  - French -> English (FreeDict)
 *  - Chinese -> English (CC-CEDICT with Pinyin & CJK segmentation)
 */
object OfflineDictionaryService {

    private const val WORDSET_BASE_URL =
        "https://raw.githubusercontent.com/wordset/wordset-dictionary/master/data/"
    private const val GCIDE_CSV_URL =
        "https://raw.githubusercontent.com/benjihillard/English-Dictionary-Database/main/english%20Dictionary.csv"
    private const val WEBSTER_1828_CSV_URL =
        "https://raw.githubusercontent.com/akaitsurugi/webster1828/main/webster1828.csv"
    private const val CC_CEDICT_GZ_URL =
        "https://www.mdbg.net/chinese/export/cedict/cedict_1_0_ts_utf-8_mdbg.txt.gz"
    private const val FREEDICT_BASE_URL =
        "https://raw.githubusercontent.com/freedict/fd-dictionaries/master/"

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    suspend fun downloadPack(
        pack: DictionaryPack,
        dao: DictDao,
        onProgress: (String) -> Unit,
    ): Result<Int> = withContext(Dispatchers.IO) {
        runCatching {
            when (pack) {
                DictionaryPack.MODERN_WORDSET -> downloadWordset(dao, onProgress)
                DictionaryPack.CONCISE_GCIDE -> downloadGcide(dao, onProgress)
                DictionaryPack.WEBSTER_1828 -> downloadWebster1828(dao, onProgress)
                DictionaryPack.ONLINE_MODERN -> {
                    onProgress("Live Modern Dictionary enabled (auto-caches offline)")
                    dao.countForDict(pack.id)
                }
                DictionaryPack.SPANISH_ENG -> downloadFreeDictTei(pack, "spa-eng", dao, onProgress)
                DictionaryPack.FRENCH_ENG -> downloadFreeDictTei(pack, "fra-eng", dao, onProgress)
                DictionaryPack.CHINESE_ENG -> downloadCcCedict(dao, onProgress)
            }
        }
    }

    // -------------------------------------------------------------------------
    // 1. Modern English (Wordset) - A..Z + misc JSON files
    // -------------------------------------------------------------------------
    private suspend fun downloadWordset(
        dao: DictDao,
        onProgress: (String) -> Unit,
    ): Int {
        val shards = ('a'..'z').map { "$it.json" } + "misc.json"
        dao.deleteByDict(DictionaryPack.MODERN_WORDSET.id)
        var totalInserted = 0
        val batch = ArrayList<DictEntryEntity>(1500)

        for ((idx, shard) in shards.withIndex()) {
            val label = shard.removeSuffix(".json").uppercase()
            onProgress("Modern English ($label • ${idx + 1}/${shards.size})...")
            var conn: HttpURLConnection? = null
            try {
                conn = openConnection("$WORDSET_BASE_URL$shard", readTimeoutMs = 30000)
                if (conn.responseCode != HttpURLConnection.HTTP_OK) continue
                val body = BufferedReader(InputStreamReader(conn.inputStream, Charsets.UTF_8), 65536).use {
                    it.readText()
                }
                val parsed = parseWordsetJson(body)
                for (entry in parsed) {
                    batch.add(entry)
                    if (batch.size >= 1500) {
                        dao.insertBatch(batch)
                        totalInserted += batch.size
                        batch.clear()
                    }
                }
            } finally {
                conn?.disconnect()
            }
        }

        if (batch.isNotEmpty()) {
            dao.insertBatch(batch)
            totalInserted += batch.size
            batch.clear()
        }
        onProgress("Modern English ready ($totalInserted words)")
        return totalInserted
    }

    internal fun parseWordsetJson(rawJson: String): List<DictEntryEntity> {
        val root = runCatching { json.parseToJsonElement(rawJson).jsonObject }.getOrNull() ?: return emptyList()
        val result = ArrayList<DictEntryEntity>(root.size)

        for ((key, value) in root) {
            val obj = runCatching { value.jsonObject }.getOrNull() ?: continue
            val word = obj["word"]?.jsonPrimitive?.content?.trim()?.ifBlank { key } ?: key
            val meanings = obj["meanings"]?.let { runCatching { it.jsonArray }.getOrNull() } ?: continue
            if (meanings.isEmpty()) continue

            val parts = mutableListOf<String>()
            for (mElem in meanings.take(3)) {
                val mObj = runCatching { mElem.jsonObject }.getOrNull() ?: continue
                val def = mObj["def"]?.jsonPrimitive?.content?.trim().orEmpty()
                if (def.isBlank()) continue
                val pos = mObj["speech_part"]?.jsonPrimitive?.content?.trim().orEmpty()
                val example = mObj["example"]?.jsonPrimitive?.content?.trim().orEmpty()
                val line = buildString {
                    if (pos.isNotBlank()) append("($pos) ")
                    append(def)
                    if (example.isNotBlank() && parts.isEmpty()) {
                        append("\n  \"$example\"")
                    }
                }
                parts.add(line)
            }
            if (parts.isEmpty()) continue
            result.add(
                DictEntryEntity(
                    dictId = DictionaryPack.MODERN_WORDSET.id,
                    headword = word.lowercase(),
                    definition = parts.joinToString("\n").take(750),
                )
            )
        }
        return result
    }

    // -------------------------------------------------------------------------
    // 2. Classic Literary English (GCIDE CSV: word,pos,definition)
    // -------------------------------------------------------------------------
    private suspend fun downloadGcide(
        dao: DictDao,
        onProgress: (String) -> Unit,
    ): Int {
        onProgress("Downloading Classic Literary English...")
        dao.deleteByDict(DictionaryPack.CONCISE_GCIDE.id)
        var conn: HttpURLConnection? = null
        var inserted = 0
        val batch = ArrayList<DictEntryEntity>(1500)
        // Group consecutive senses of the same word into one clean multi-sense entry
        var currentWord: String? = null
        val currentSenses = mutableListOf<String>()

        suspend fun flushCurrentWord() {
            val w = currentWord ?: return
            if (currentSenses.isNotEmpty()) {
                batch.add(
                    DictEntryEntity(
                        dictId = DictionaryPack.CONCISE_GCIDE.id,
                        headword = w,
                        definition = currentSenses.take(3).joinToString("\n").take(750),
                    )
                )
                if (batch.size >= 1500) {
                    dao.insertBatch(batch)
                    inserted += batch.size
                    batch.clear()
                    if (inserted % 9000 == 0) {
                        onProgress("Indexing Literary English... $inserted words")
                    }
                }
            }
            currentWord = null
            currentSenses.clear()
        }

        try {
            conn = openConnection(GCIDE_CSV_URL, readTimeoutMs = 45000)
            BufferedReader(InputStreamReader(conn.inputStream, Charsets.UTF_8), 65536).use { reader ->
                var isHeader = true
                readCsvRecords(reader) { record ->
                    if (isHeader) {
                        isHeader = false
                    } else {
                        val parsed = parseGcideCsvRow(record)
                        if (parsed != null) {
                            val (hw, formattedSense) = parsed
                            if (currentWord != null && currentWord != hw) {
                                flushCurrentWord()
                            }
                            currentWord = hw
                            if (currentSenses.size < 3 && formattedSense !in currentSenses) {
                                currentSenses.add(formattedSense)
                            }
                        }
                    }
                }
            }
            flushCurrentWord()
            if (batch.isNotEmpty()) {
                dao.insertBatch(batch)
                inserted += batch.size
                batch.clear()
            }
        } finally {
            conn?.disconnect()
        }
        onProgress("Literary English ready ($inserted words)")
        return inserted
    }

    internal fun parseGcideCsvRow(row: String): Pair<String, String>? {
        val fields = splitCsvFields(row)
        if (fields.size < 3) return null
        val word = fields[0].trim()
        val pos = fields[1].trim()
        val def = fields[2].trim().replace(Regex("""\s+"""), " ")
        if (word.isBlank() || def.isBlank()) return null
        val formatted = if (pos.isNotBlank()) "($pos) $def" else def
        return word.lowercase() to formatted.take(280)
    }

    // -------------------------------------------------------------------------
    // 3. Webster's 1828 Dictionary CSV
    // -------------------------------------------------------------------------
    private suspend fun downloadWebster1828(
        dao: DictDao,
        onProgress: (String) -> Unit,
    ): Int {
        onProgress("Downloading Webster's 1828 Dictionary...")
        dao.deleteByDict(DictionaryPack.WEBSTER_1828.id)
        var conn: HttpURLConnection? = null
        var inserted = 0
        val batch = ArrayList<DictEntryEntity>(1500)

        try {
            conn = openConnection(WEBSTER_1828_CSV_URL, readTimeoutMs = 45000)
            BufferedReader(InputStreamReader(conn.inputStream, Charsets.UTF_8), 65536).use { reader ->
                var isHeader = true
                readCsvRecords(reader) { record ->
                    if (isHeader) {
                        isHeader = false
                    } else {
                        parseCsvRow(record)?.let { entry ->
                            batch.add(entry)
                            if (batch.size >= 1500) {
                                dao.insertBatch(batch)
                                inserted += batch.size
                                batch.clear()
                                if (inserted % 9000 == 0) {
                                    onProgress("Indexing Webster's 1828... $inserted words")
                                }
                            }
                        }
                    }
                }
            }
            if (batch.isNotEmpty()) {
                dao.insertBatch(batch)
                inserted += batch.size
                batch.clear()
            }
        } finally {
            conn?.disconnect()
        }
        onProgress("Webster's 1828 ready ($inserted words)")
        return inserted
    }

    internal fun parseCsvRow(row: String): DictEntryEntity? {
        val fields = splitCsvFields(row)
        if (fields.size < 2) return null
        val word = fields[0].trim()
        val def = fields[1].trim().replace(Regex("""\s+"""), " ").take(700)
        if (word.isBlank() || def.isBlank()) return null
        return DictEntryEntity(
            dictId = DictionaryPack.WEBSTER_1828.id,
            headword = word.lowercase(),
            definition = def,
        )
    }

    // -------------------------------------------------------------------------
    // 4. FreeDict TEI XML (Spanish -> English, French -> English)
    // -------------------------------------------------------------------------
    private suspend fun downloadFreeDictTei(
        pack: DictionaryPack,
        pair: String,
        dao: DictDao,
        onProgress: (String) -> Unit,
    ): Int {
        onProgress("Downloading ${pack.displayName}...")
        dao.deleteByDict(pack.id)
        var conn: HttpURLConnection? = null
        var inserted = 0
        val batch = ArrayList<DictEntryEntity>(1500)
        val orthRegex = Regex("""<orth[^>]*>([^<]+)</orth>""")
        val quoteRegex = Regex("""<quote[^>]*>([^<]+)</quote>""")

        try {
            conn = openConnection("$FREEDICT_BASE_URL$pair/$pair.tei", readTimeoutMs = 30000)
            BufferedReader(InputStreamReader(conn.inputStream, Charsets.UTF_8), 65536).use { reader ->
                val entryBuf = StringBuilder()
                var insideEntry = false
                var line = reader.readLine()
                while (line != null) {
                    if (line.contains("<entry")) {
                        insideEntry = true
                        entryBuf.clear()
                    }
                    if (insideEntry) {
                        entryBuf.append(line).append(' ')
                        if (line.contains("</entry>")) {
                            insideEntry = false
                            val xml = entryBuf.toString()
                            val headword = orthRegex.find(xml)?.groupValues?.get(1)?.trim()
                            val glosses = quoteRegex.findAll(xml)
                                .map { it.groupValues[1].trim() }
                                .filter { it.isNotBlank() }
                                .distinct()
                                .take(5)
                                .toList()
                            if (!headword.isNullOrBlank() && glosses.isNotEmpty()) {
                                batch.add(
                                    DictEntryEntity(
                                        dictId = pack.id,
                                        headword = headword.lowercase(),
                                        definition = glosses.joinToString("; ").take(600),
                                    )
                                )
                                if (batch.size >= 1500) {
                                    dao.insertBatch(batch)
                                    inserted += batch.size
                                    batch.clear()
                                    if (inserted % 6000 == 0) {
                                        onProgress("Indexing ${pack.shortName}... $inserted words")
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
            conn?.disconnect()
        }
        onProgress("${pack.displayName} ready ($inserted words)")
        return inserted
    }

    // -------------------------------------------------------------------------
    // 5. CC-CEDICT (Chinese -> English with Pinyin)
    // -------------------------------------------------------------------------
    private suspend fun downloadCcCedict(
        dao: DictDao,
        onProgress: (String) -> Unit,
    ): Int {
        onProgress("Downloading Chinese CC-CEDICT...")
        dao.deleteByDict(DictionaryPack.CHINESE_ENG.id)
        var conn: HttpURLConnection? = null
        var inserted = 0
        val batch = ArrayList<DictEntryEntity>(2000)
        val lineRegex = Regex("""^(\S+)\s+(\S+)\s+\[([^\]]+)]\s+/(.+)/\s*$""")

        try {
            conn = openConnection(CC_CEDICT_GZ_URL, readTimeoutMs = 45000)
            BufferedReader(InputStreamReader(GZIPInputStream(conn.inputStream), Charsets.UTF_8), 65536).use { reader ->
                var line = reader.readLine()
                while (line != null) {
                    if (!line.startsWith("#") && line.isNotBlank()) {
                        val m = lineRegex.matchEntire(line.trim())
                        if (m != null) {
                            val trad = m.groupValues[1]
                            val simp = m.groupValues[2]
                            val pinyin = m.groupValues[3]
                            val meanings = m.groupValues[4].split('/').filter { it.isNotBlank() }.take(4).joinToString("; ")
                            val formatted = "[$pinyin] $meanings".take(650)
                            batch.add(DictEntryEntity(dictId = DictionaryPack.CHINESE_ENG.id, headword = simp, definition = formatted))
                            if (trad != simp) {
                                batch.add(DictEntryEntity(dictId = DictionaryPack.CHINESE_ENG.id, headword = trad, definition = formatted))
                            }
                            if (batch.size >= 2000) {
                                dao.insertBatch(batch)
                                inserted += batch.size
                                batch.clear()
                                if (inserted % 20000 == 0) {
                                    onProgress("Indexing Chinese... $inserted entries")
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
            conn?.disconnect()
        }
        onProgress("Chinese dictionary ready ($inserted entries)")
        return inserted
    }

    // -------------------------------------------------------------------------
    // Lookup Across Installed Dictionaries (+ Optional Live Auto-Cache)
    // -------------------------------------------------------------------------
    suspend fun lookupAtOffset(
        text: String,
        charIndex: Int,
        dao: DictDao,
        primaryDictId: String?,
        onlineEnabled: Boolean,
    ): WordLookupResult? = withContext(Dispatchers.IO) {
        if (text.isEmpty()) return@withContext null
        val safeIdx = charIndex.coerceIn(0, text.lastIndex)

        // Check CJK first if user long-pressed a Chinese character
        if (isCjkChar(text[safeIdx])) {
            val cjkHits = lookupCjk(text, safeIdx, dao)
            if (cjkHits.isNotEmpty()) {
                val best = cjkHits.first()
                return@withContext WordLookupResult(
                    query = best.headword,
                    headword = best.headword,
                    definition = best.definition,
                    sourceLabel = best.sourceLabel,
                    hits = cjkHits,
                )
            }
        }

        val extracted = extractWordAt(text, safeIdx) ?: return@withContext null
        val normalized = extracted.lowercase()
        val candidates = buildWordCandidates(normalized)

        // Gather offline hits across all installed dictionaries
        val hitsByDict = LinkedHashMap<String, DictionaryHit>()
        for (candidate in candidates) {
            val rows = dao.lookupAcrossAll(candidate)
            for (row in rows) {
                if (row.dictId in hitsByDict) continue
                val pack = DictionaryPack.fromId(row.dictId) ?: continue
                hitsByDict[row.dictId] = DictionaryHit(
                    dictId = pack.id,
                    shortLabel = pack.shortName,
                    sourceLabel = pack.displayName,
                    headword = row.headword,
                    definition = row.definition,
                )
            }
            if (hitsByDict.isNotEmpty() && primaryDictId != null && primaryDictId in hitsByDict) {
                // We already found an exact/closest match in the preferred dictionary;
                // still keep any other dictionaries that matched on this candidate
                break
            }
        }

        // Fetch live online modern definition if enabled and either:
        // (a) Online is the user's primary dictionary and not yet cached, OR
        // (b) No offline dictionary matched this word
        val needOnlineFetch = onlineEnabled &&
            (DictionaryPack.ONLINE_MODERN.id !in hitsByDict) &&
            (hitsByDict.isEmpty() || primaryDictId == DictionaryPack.ONLINE_MODERN.id)

        if (needOnlineFetch) {
            fetchOnlineModernAndCache(normalized, dao)?.let { hit ->
                hitsByDict[hit.dictId] = hit
            }
        }

        // Order hits: primary dictionary first, then Modern -> Literary -> 1828 -> Online -> Bilingual
        val orderedHits = hitsByDict.values.sortedWith(
            compareBy<DictionaryHit> { if (it.dictId == primaryDictId) 0 else 1 }
                .thenBy { DictionaryPack.fromId(it.dictId)?.ordinal ?: 99 }
        )

        if (orderedHits.isNotEmpty()) {
            val primary = orderedHits.first()
            return@withContext WordLookupResult(
                query = extracted,
                headword = primary.headword,
                definition = primary.definition,
                sourceLabel = primary.sourceLabel,
                hits = orderedHits,
            )
        }

        val fallbackSource = DictionaryPack.fromId(primaryDictId)?.displayName ?: "Offline Dictionary"
        WordLookupResult(
            query = extracted,
            headword = extracted,
            definition = "No definition found for \"$extracted\".",
            sourceLabel = fallbackSource,
            hits = emptyList(),
            notFound = true,
        )
    }

    private suspend fun lookupCjk(text: String, charIndex: Int, dao: DictDao): List<DictionaryHit> {
        val pack = DictionaryPack.CHINESE_ENG
        // Try longest phrase match up to 4 characters starting at or just before charIndex
        for (len in 4 downTo 1) {
            val startMin = (charIndex - len + 1).coerceAtLeast(0)
            val startMax = charIndex.coerceAtMost(text.length - len)
            for (s in startMax downTo startMin) {
                val sub = text.substring(s, s + len)
                if (!sub.all { isCjkChar(it) }) continue
                val rows = dao.lookupInDict(pack.id, sub)
                if (rows.isNotEmpty()) {
                    val combined = rows.map { it.definition }.distinct().take(2).joinToString("\n\n")
                    return listOf(
                        DictionaryHit(
                            dictId = pack.id,
                            shortLabel = pack.shortName,
                            sourceLabel = pack.displayName,
                            headword = sub,
                            definition = combined,
                        )
                    )
                }
            }
        }
        return emptyList()
    }

    private suspend fun fetchOnlineModernAndCache(
        word: String,
        dao: DictDao,
    ): DictionaryHit? {
        val formatted = withTimeoutOrNull(5000L) {
            runCatching {
                var conn: HttpURLConnection? = null
                try {
                    val encoded = URLEncoder.encode(word, "UTF-8")
                    conn = openConnection("https://api.dictionaryapi.dev/api/v2/entries/en/$encoded", readTimeoutMs = 4500)
                    if (conn.responseCode != HttpURLConnection.HTTP_OK) return@runCatching null
                    val body = BufferedReader(InputStreamReader(conn.inputStream, Charsets.UTF_8)).use { it.readText() }
                    parseFreeDictionaryApiJson(body)
                } finally {
                    conn?.disconnect()
                }
            }.getOrNull()
        } ?: return null

        val (headword, def) = formatted
        dao.insertBatch(
            listOf(
                DictEntryEntity(
                    dictId = DictionaryPack.ONLINE_MODERN.id,
                    headword = word,
                    definition = def,
                )
            )
        )
        return DictionaryHit(
            dictId = DictionaryPack.ONLINE_MODERN.id,
            shortLabel = DictionaryPack.ONLINE_MODERN.shortName,
            sourceLabel = DictionaryPack.ONLINE_MODERN.displayName,
            headword = headword.ifBlank { word },
            definition = def,
        )
    }

    internal fun parseFreeDictionaryApiJson(rawJson: String): Pair<String, String>? {
        val arr = runCatching { json.parseToJsonElement(rawJson).jsonArray }.getOrNull() ?: return null
        val first = arr.firstOrNull()?.let { runCatching { it.jsonObject }.getOrNull() } ?: return null
        val word = first["word"]?.jsonPrimitive?.content?.trim().orEmpty()
        val phonetic = first["phonetic"]?.jsonPrimitive?.content?.trim().orEmpty()
        val meanings = first["meanings"]?.let { runCatching { it.jsonArray }.getOrNull() } ?: return null

        val lines = mutableListOf<String>()
        if (phonetic.isNotBlank()) lines.add(phonetic)

        for (mElem in meanings.take(3)) {
            val mObj = runCatching { mElem.jsonObject }.getOrNull() ?: continue
            val pos = mObj["partOfSpeech"]?.jsonPrimitive?.content?.trim().orEmpty()
            val defs = mObj["definitions"]?.let { runCatching { it.jsonArray }.getOrNull() } ?: continue
            val firstDefObj = defs.firstOrNull()?.let { runCatching { it.jsonObject }.getOrNull() } ?: continue
            val defText = firstDefObj["definition"]?.jsonPrimitive?.content?.trim().orEmpty()
            if (defText.isBlank()) continue
            val example = firstDefObj["example"]?.jsonPrimitive?.content?.trim().orEmpty()
            lines.add(
                buildString {
                    if (pos.isNotBlank()) append("($pos) ")
                    append(defText)
                    if (example.isNotBlank() && lines.size <= 2) append("\n  \"$example\"")
                }
            )
        }
        if (lines.isEmpty()) return null
        return word to lines.joinToString("\n").take(750)
    }

    // -------------------------------------------------------------------------
    // Helpers: CSV reader, word extractor, stemmer, HTTP
    // -------------------------------------------------------------------------
    private suspend fun readCsvRecords(
        reader: BufferedReader,
        onRecord: suspend (String) -> Unit,
    ) {
        var line: String? = reader.readLine()
        val rowBuffer = StringBuilder()
        var inQuotes = false

        while (line != null) {
            if (rowBuffer.isNotEmpty()) rowBuffer.append('\n')
            rowBuffer.append(line)
            var i = 0
            while (i < line.length) {
                if (line[i] == '"') {
                    if (i + 1 < line.length && line[i + 1] == '"') {
                        i++
                    } else {
                        inQuotes = !inQuotes
                    }
                }
                i++
            }
            if (!inQuotes) {
                onRecord(rowBuffer.toString())
                rowBuffer.clear()
            }
            line = reader.readLine()
        }
        if (rowBuffer.isNotEmpty()) {
            onRecord(rowBuffer.toString())
        }
    }

    private fun splitCsvFields(row: String): List<String> {
        if (row.isBlank()) return emptyList()
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
        return fields
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

    internal fun buildEnglishCandidates(normalized: String): List<String> = buildWordCandidates(normalized)

    internal fun buildWordCandidates(normalized: String): List<String> {
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
        if (normalized.endsWith("ly") && normalized.length > 4) {
            list.add(normalized.dropLast(2))
        }
        if (normalized.endsWith("es") && normalized.length > 3) list.add(normalized.dropLast(2))
        if (normalized.endsWith("s") && normalized.length > 3 && !normalized.endsWith("ss")) {
            list.add(normalized.dropLast(1))
        }
        return list.toList()
    }

    private fun isWordChar(c: Char): Boolean = c.isLetter() || c == '\'' || c == '’' || c == '-'

    private fun isCjkChar(c: Char): Boolean {
        val code = c.code
        return code in 0x4E00..0x9FFF || code in 0x3400..0x4DBF
    }

    private fun openConnection(urlString: String, readTimeoutMs: Int = 30000): HttpURLConnection {
        val url = URI(urlString).toURL()
        return (url.openConnection() as HttpURLConnection).apply {
            connectTimeout = 10000
            readTimeout = readTimeoutMs
            instanceFollowRedirects = true
            requestMethod = "GET"
            setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 14) LightPhoneEReader/1.1")
        }
    }
}
