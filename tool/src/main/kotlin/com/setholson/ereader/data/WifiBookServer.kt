package com.setholson.ereader.data

import com.setholson.ereader.model.BookFormat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream
import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import java.util.Locale

/**
 * Lightweight built-in Wi-Fi HTTP server so users can drag-and-drop EPUB, PDF, TXT, or MD
 * books directly from any computer or phone browser on the same Wi-Fi network — no Light
 * Dashboard Tool Manager required.
 */
class WifiBookServer(
    private val booksDir: File,
    private val onBookChanged: (String) -> Unit,
) {
    private var serverSocket: ServerSocket? = null
    private var scope: CoroutineScope? = null
    private var acceptJob: Job? = null

    val isRunning: Boolean
        get() = serverSocket != null && !(serverSocket?.isClosed ?: true)

    var boundPort: Int = 0
        private set

    fun start(): Result<String> = runCatching {
        stop()
        if (!booksDir.exists()) booksDir.mkdirs()

        val socket = bindFirstAvailablePort(listOf(8080, 8765, 8888, 0))
        serverSocket = socket
        boundPort = socket.localPort

        val serverScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        scope = serverScope
        acceptJob = serverScope.launch {
            while (isActive && !socket.isClosed) {
                try {
                    val client = socket.accept()
                    launch { handleClient(client) }
                } catch (_: Throwable) {
                    if (socket.isClosed) break
                }
            }
        }

        val ip = findLocalIpv4Address()
            ?: throw IllegalStateException("No Wi-Fi IP found. Connect your Light Phone to Wi-Fi and try again.")
        "http://$ip:$boundPort"
    }

    fun stop() {
        runCatching { serverSocket?.close() }
        serverSocket = null
        acceptJob?.cancel()
        acceptJob = null
        scope?.cancel()
        scope = null
        boundPort = 0
    }

    private fun bindFirstAvailablePort(ports: List<Int>): ServerSocket {
        var lastError: Throwable? = null
        for (port in ports) {
            try {
                return ServerSocket(port).apply { reuseAddress = true }
            } catch (t: Throwable) {
                lastError = t
            }
        }
        throw lastError ?: IllegalStateException("Could not open local Wi-Fi server port.")
    }

    private suspend fun handleClient(socket: Socket) {
        socket.use { client ->
            client.soTimeout = 60_000
            val input = BufferedInputStream(client.getInputStream(), 32 * 1024)
            val output = client.getOutputStream()

            val requestLine = readAsciiLine(input) ?: return
            val parts = requestLine.split(' ')
            if (parts.size < 2) return
            val method = parts[0].uppercase(Locale.US)
            val rawTarget = parts[1]
            val path = rawTarget.substringBefore('?')
            val query = rawTarget.substringAfter('?', "")

            val headers = mutableMapOf<String, String>()
            while (true) {
                val line = readAsciiLine(input) ?: break
                if (line.isEmpty()) break
                val colon = line.indexOf(':')
                if (colon > 0) {
                    headers[line.substring(0, colon).trim().lowercase(Locale.US)] =
                        line.substring(colon + 1).trim()
                }
            }

            val contentLength = headers["content-length"]?.toLongOrNull() ?: 0L

            when {
                method == "GET" && (path == "/" || path == "/index.html") -> {
                    sendResponse(output, 200, "text/html; charset=utf-8", UPLOAD_PAGE_HTML.toByteArray(Charsets.UTF_8))
                }
                method == "GET" && path == "/api/books" -> {
                    val jsonBody = buildBooksJson()
                    sendResponse(output, 200, "application/json; charset=utf-8", jsonBody.toByteArray(Charsets.UTF_8))
                }
                method == "POST" && path == "/api/upload" -> {
                    handleBinaryUpload(input, output, query, contentLength)
                }
                method == "POST" && path == "/api/url" -> {
                    handleRemoteUrlUpload(input, output, contentLength)
                }
                method == "POST" && path == "/api/delete" -> {
                    handleDelete(output, query)
                }
                else -> {
                    sendResponse(output, 404, "text/plain; charset=utf-8", "Not found".toByteArray(Charsets.UTF_8))
                }
            }
        }
    }

    private fun handleBinaryUpload(
        input: BufferedInputStream,
        output: OutputStream,
        query: String,
        contentLength: Long,
    ) {
        val rawName = parseQueryParam(query, "name").orEmpty()
        val safeName = FreeBooksCatalog.sanitizeBookFileName(rawName)
        val ext = safeName.substringAfterLast('.', "").lowercase(Locale.US)

        if (BookFormat.fromExtension(ext) == null) {
            sendJson(output, 400, """{"ok":false,"error":"Unsupported file type. Upload .epub, .pdf, .txt, or .md"}""")
            return
        }
        if (contentLength <= 0L) {
            sendJson(output, 400, """{"ok":false,"error":"Empty upload"}""")
            return
        }

        val target = File(booksDir, safeName)
        val temp = File(booksDir, ".$safeName.upload")
        try {
            FileOutputStream(temp).use { fos ->
                val buf = ByteArray(32 * 1024)
                var remaining = contentLength
                while (remaining > 0L) {
                    val toRead = minOf(buf.size.toLong(), remaining).toInt()
                    val read = input.read(buf, 0, toRead)
                    if (read == -1) break
                    fos.write(buf, 0, read)
                    remaining -= read
                }
            }
            if (target.exists()) target.delete()
            if (!temp.renameTo(target)) {
                temp.delete()
                sendJson(output, 500, """{"ok":false,"error":"Could not save file on phone"}""")
                return
            }
            ReaderEntryPoint.notifyLibraryChanged()
            onBookChanged("Received: ${target.name}")
            sendJson(output, 200, """{"ok":true,"name":${jsonString(target.name)},"size":${target.length()}}""")
        } catch (t: Throwable) {
            temp.delete()
            sendJson(output, 500, """{"ok":false,"error":${jsonString(t.message ?: "Upload failed")}}""")
        }
    }

    private suspend fun handleRemoteUrlUpload(
        input: BufferedInputStream,
        output: OutputStream,
        contentLength: Long,
    ) {
        if (contentLength <= 0L || contentLength > 4096L) {
            sendJson(output, 400, """{"ok":false,"error":"Invalid URL payload"}""")
            return
        }
        val bytes = ByteArray(contentLength.toInt())
        var offset = 0
        while (offset < bytes.size) {
            val r = input.read(bytes, offset, bytes.size - offset)
            if (r == -1) break
            offset += r
        }
        val urlText = String(bytes, 0, offset, Charsets.UTF_8).trim()
        val result = FreeBooksCatalog.downloadBookToDir(urlText, booksDir)
        result.onSuccess { file ->
            ReaderEntryPoint.notifyLibraryChanged()
            onBookChanged("Downloaded: ${file.name}")
            sendJson(output, 200, """{"ok":true,"name":${jsonString(file.name)},"size":${file.length()}}""")
        }.onFailure { err ->
            sendJson(output, 400, """{"ok":false,"error":${jsonString(err.message ?: "Download failed")}}""")
        }
    }

    private fun handleDelete(output: OutputStream, query: String) {
        val name = parseQueryParam(query, "name").orEmpty()
        val root = booksDir.canonicalFile
        val target = File(root, name).canonicalFile
        if (!target.path.startsWith(root.path) || !target.isFile) {
            sendJson(output, 404, """{"ok":false,"error":"Book not found"}""")
            return
        }
        val deleted = target.delete()
        if (deleted) {
            ReaderEntryPoint.notifyLibraryChanged()
            onBookChanged("Removed: ${target.name}")
            sendJson(output, 200, """{"ok":true}""")
        } else {
            sendJson(output, 500, """{"ok":false,"error":"Could not delete file"}""")
        }
    }

    private fun buildBooksJson(): String {
        val files = booksDir.listFiles()
            ?.filter { it.isFile && !it.name.startsWith(".") && BookFormat.fromExtension(it.extension) != null }
            ?.sortedByDescending { it.lastModified() }
            .orEmpty()
        val items = files.joinToString(",") { f ->
            """{"name":${jsonString(f.name)},"size":${f.length()}}"""
        }
        return """{"books":[$items]}"""
    }

    private fun sendJson(output: OutputStream, status: Int, body: String) {
        sendResponse(output, status, "application/json; charset=utf-8", body.toByteArray(Charsets.UTF_8))
    }

    private fun sendResponse(output: OutputStream, status: Int, contentType: String, body: ByteArray) {
        val reason = when (status) {
            200 -> "OK"
            400 -> "Bad Request"
            404 -> "Not Found"
            else -> "Internal Server Error"
        }
        val header = buildString {
            append("HTTP/1.1 $status $reason\r\n")
            append("Content-Type: $contentType\r\n")
            append("Content-Length: ${body.size}\r\n")
            append("Cache-Control: no-store\r\n")
            append("Connection: close\r\n\r\n")
        }
        output.write(header.toByteArray(Charsets.US_ASCII))
        output.write(body)
        output.flush()
    }

    private fun readAsciiLine(input: BufferedInputStream): String? {
        val baos = ByteArrayOutputStream()
        while (true) {
            val b = input.read()
            if (b == -1) return if (baos.size() == 0) null else baos.toString("ISO-8859-1")
            if (b == '\n'.code) break
            if (b != '\r'.code) baos.write(b)
            if (baos.size() > 8192) break
        }
        return baos.toString("ISO-8859-1")
    }

    private fun parseQueryParam(query: String, key: String): String? {
        if (query.isBlank()) return null
        for (pair in query.split('&')) {
            val k = pair.substringBefore('=')
            if (k == key) {
                val v = pair.substringAfter('=', "")
                return runCatching { URLDecoder.decode(v, "UTF-8") }.getOrDefault(v)
            }
        }
        return null
    }

    private fun jsonString(value: String): String {
        val escaped = value
            .replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("\n", "\\n")
            .replace("\r", "")
        return "\"$escaped\""
    }

    companion object {
        fun findLocalIpv4Address(): String? {
            val candidates = mutableListOf<Pair<Int, String>>()
            val interfaces = runCatching { NetworkInterface.getNetworkInterfaces() }.getOrNull() ?: return null
            while (interfaces.hasMoreElements()) {
                val iface = interfaces.nextElement()
                if (!iface.isUp || iface.isLoopback || iface.isVirtual) continue
                val name = iface.name.lowercase(Locale.US)
                val addrs = iface.inetAddresses
                while (addrs.hasMoreElements()) {
                    val addr = addrs.nextElement()
                    if (addr is Inet4Address && !addr.isLoopbackAddress && addr.isSiteLocalAddress) {
                        val host = addr.hostAddress ?: continue
                        val priority = when {
                            name.startsWith("wlan") || name.startsWith("en") -> 0
                            name.startsWith("eth") || name.startsWith("ap") -> 1
                            else -> 2
                        }
                        candidates.add(priority to host)
                    }
                }
            }
            return candidates.sortedBy { it.first }.firstOrNull()?.second
        }

        private val UPLOAD_PAGE_HTML = """
            <!doctype html>
            <html lang="en">
            <head>
              <meta charset="utf-8">
              <meta name="viewport" content="width=device-width,initial-scale=1">
              <title>Light Phone E-Reader — Wi-Fi Book Transfer</title>
              <style>
                * { box-sizing: border-box; }
                body { font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, sans-serif;
                       background: #0d0d0d; color: #f5f5f5; margin: 0; padding: 24px 16px; }
                .wrap { max-width: 620px; margin: 0 auto; }
                h1 { font-size: 24px; margin: 0 0 6px; font-weight: 600; letter-spacing: -0.02em; }
                .sub { color: #a3a3a3; font-size: 14px; margin: 0 0 22px; }
                .card { border: 1px solid #333; border-radius: 10px; padding: 20px; margin-bottom: 18px; background: #141414; }
                .drop { border: 2px dashed #555; border-radius: 8px; padding: 34px 16px; text-align: center; cursor: pointer; transition: border-color .15s, background .15s; }
                .drop.over { border-color: #fff; background: #1f1f1f; }
                .btn { background: #fff; color: #000; border: 0; border-radius: 6px; padding: 10px 16px; font-size: 14px; font-weight: 600; cursor: pointer; }
                .btn:disabled { opacity: 0.45; cursor: default; }
                .btn-outline { background: transparent; color: #f5f5f5; border: 1px solid #555; border-radius: 6px; padding: 6px 11px; font-size: 12px; cursor: pointer; }
                input[type=text] { width: 100%; background: #0d0d0d; color: #fff; border: 1px solid #444; border-radius: 6px; padding: 10px 12px; font-size: 14px; }
                .row { display: flex; gap: 8px; align-items: center; }
                #status { margin-top: 12px; font-size: 13px; color: #d4d4d4; min-height: 18px; }
                .book { display: flex; justify-content: space-between; align-items: center; padding: 10px 0; border-bottom: 1px solid #262626; gap: 10px; }
                .book:last-child { border-bottom: 0; }
                .bname { font-size: 14px; word-break: break-word; }
                .bmeta { font-size: 12px; color: #8c8c8c; margin-top: 2px; }
                h2 { font-size: 13px; text-transform: uppercase; letter-spacing: 0.08em; color: #a3a3a3; margin: 0 0 12px; }
              </style>
            </head>
            <body>
              <div class="wrap">
                <h1>Light Phone E-Reader</h1>
                <p class="sub">Drop DRM-free <b>.epub</b>, <b>.pdf</b>, <b>.txt</b>, or <b>.md</b> files directly onto your phone over Wi-Fi.</p>

                <div class="card">
                  <h2>Upload From This Device</h2>
                  <div class="drop" id="drop">
                    <p style="margin:0 0 12px;font-size:15px;">Drag &amp; drop books here</p>
                    <button class="btn" type="button" id="pickBtn">Choose Book Files</button>
                    <input type="file" id="fileInput" multiple accept=".epub,.pdf,.txt,.md,.markdown" style="display:none">
                  </div>
                  <div id="status"></div>
                </div>

                <div class="card">
                  <h2>Or Send a Direct Book Link</h2>
                  <div class="row">
                    <input type="text" id="urlInput" placeholder="https://example.com/mybook.epub">
                    <button class="btn" type="button" id="urlBtn">Send</button>
                  </div>
                </div>

                <div class="card">
                  <h2>Books on Your Light Phone (<span id="count">0</span>)</h2>
                  <div id="bookList" style="color:#8c8c8c;font-size:14px;">Loading...</div>
                </div>
              </div>

              <script>
                const drop = document.getElementById('drop');
                const fileInput = document.getElementById('fileInput');
                const pickBtn = document.getElementById('pickBtn');
                const statusEl = document.getElementById('status');
                const bookList = document.getElementById('bookList');
                const countEl = document.getElementById('count');
                const urlInput = document.getElementById('urlInput');
                const urlBtn = document.getElementById('urlBtn');

                pickBtn.onclick = () => fileInput.click();
                drop.onclick = (e) => { if (e.target !== pickBtn) fileInput.click(); };
                drop.ondragover = (e) => { e.preventDefault(); drop.classList.add('over'); };
                drop.ondragleave = () => drop.classList.remove('over');
                drop.ondrop = (e) => { e.preventDefault(); drop.classList.remove('over'); uploadFiles(e.dataTransfer.files); };
                fileInput.onchange = () => { uploadFiles(fileInput.files); fileInput.value = ''; };

                function fmtSize(b) {
                  if (b < 1024) return b + ' B';
                  if (b < 1024 * 1024) return (b / 1024).toFixed(0) + ' KB';
                  return (b / (1024 * 1024)).toFixed(1) + ' MB';
                }

                async function uploadFiles(files) {
                  if (!files || !files.length) return;
                  for (let i = 0; i < files.length; i++) {
                    const f = files[i];
                    statusEl.textContent = 'Uploading (' + (i + 1) + '/' + files.length + '): ' + f.name + '...';
                    try {
                      const res = await fetch('/api/upload?name=' + encodeURIComponent(f.name), {
                        method: 'POST',
                        body: f
                      });
                      const data = await res.json();
                      if (!res.ok || !data.ok) {
                        statusEl.textContent = 'Error uploading ' + f.name + ': ' + (data.error || 'Failed');
                        return;
                      }
                    } catch (err) {
                      statusEl.textContent = 'Connection lost while uploading ' + f.name;
                      return;
                    }
                  }
                  statusEl.textContent = 'Done! Uploaded ' + files.length + ' book(s).';
                  refreshBooks();
                }

                urlBtn.onclick = async () => {
                  const link = urlInput.value.trim();
                  if (!link) return;
                  urlBtn.disabled = true;
                  statusEl.textContent = 'Downloading book on Light Phone...';
                  try {
                    const res = await fetch('/api/url', { method: 'POST', body: link });
                    const data = await res.json();
                    if (res.ok && data.ok) {
                      statusEl.textContent = 'Downloaded: ' + data.name;
                      urlInput.value = '';
                      refreshBooks();
                    } else {
                      statusEl.textContent = 'Download error: ' + (data.error || 'Failed');
                    }
                  } catch (e) {
                    statusEl.textContent = 'Could not reach Light Phone.';
                  } finally {
                    urlBtn.disabled = false;
                  }
                };

                async function deleteBook(name) {
                  if (!confirm('Delete "' + name + '" from your Light Phone?')) return;
                  await fetch('/api/delete?name=' + encodeURIComponent(name), { method: 'POST' });
                  refreshBooks();
                }

                async function refreshBooks() {
                  try {
                    const res = await fetch('/api/books');
                    const data = await res.json();
                    const list = data.books || [];
                    countEl.textContent = list.length;
                    if (!list.length) {
                      bookList.innerHTML = 'No books on phone yet.';
                      return;
                    }
                    bookList.innerHTML = '';
                    list.forEach(b => {
                      const div = document.createElement('div');
                      div.className = 'book';
                      const info = document.createElement('div');
                      info.innerHTML = '<div class="bname"></div><div class="bmeta">' + fmtSize(b.size) + '</div>';
                      info.querySelector('.bname').textContent = b.name;
                      const del = document.createElement('button');
                      del.className = 'btn-outline';
                      del.textContent = 'Delete';
                      del.onclick = () => deleteBook(b.name);
                      div.appendChild(info);
                      div.appendChild(del);
                      bookList.appendChild(div);
                    });
                  } catch (e) {}
                }
                refreshBooks();
              </script>
            </body>
            </html>
        """.trimIndent()
    }
}
