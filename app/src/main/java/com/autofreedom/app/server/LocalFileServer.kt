package com.autofreedom.app.server

import android.util.Log
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.io.OutputStream
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import java.net.URLEncoder
import java.util.concurrent.Executors

/**
 * Lightweight embedded HTTP server that serves files from device storage.
 *
 * This enables the browser to access local video/audio/image files via
 * http://localhost:8080/path/to/file.mp4
 *
 * Why needed: WebView blocks file:// URLs for security. This server
 * provides a safe http:// interface to local storage.
 *
 * Features:
 * - Serves any file from /storage/emulated/0
 * - Proper MIME types for video/audio/images
 * - Range request support (for video seeking)
 * - Directory listing as HTML (browsable in browser)
 * - Minimal resource usage (thread pool of 4)
 */
class LocalFileServer(private val port: Int = 8080) {

    companion object {
        private const val TAG = "LocalFileServer"
        private val STORAGE_ROOT = "/storage/emulated/0"

        private val MIME_TYPES = mapOf(
            // Video
            "mp4" to "video/mp4",
            "mkv" to "video/x-matroska",
            "avi" to "video/x-msvideo",
            "mov" to "video/quicktime",
            "webm" to "video/webm",
            "3gp" to "video/3gpp",
            "flv" to "video/x-flv",
            "m4v" to "video/mp4",
            // Audio
            "mp3" to "audio/mpeg",
            "m4a" to "audio/mp4",
            "flac" to "audio/flac",
            "wav" to "audio/wav",
            "ogg" to "audio/ogg",
            "aac" to "audio/aac",
            "wma" to "audio/x-ms-wma",
            "opus" to "audio/opus",
            // Images
            "jpg" to "image/jpeg",
            "jpeg" to "image/jpeg",
            "png" to "image/png",
            "gif" to "image/gif",
            "webp" to "image/webp",
            "bmp" to "image/bmp",
            "svg" to "image/svg+xml",
            // Documents
            "html" to "text/html",
            "htm" to "text/html",
            "txt" to "text/plain",
            "css" to "text/css",
            "js" to "application/javascript",
            "json" to "application/json",
            "pdf" to "application/pdf",
            "xml" to "application/xml",
            // Default
            "" to "application/octet-stream"
        )

        var instance: LocalFileServer? = null
            private set
    }

    private var serverSocket: ServerSocket? = null
    private val executor = Executors.newFixedThreadPool(4)
    @Volatile
    private var isRunning = false

    /**
     * Start the HTTP server on a background thread.
     */
    fun start() {
        if (isRunning) return

        Thread {
            try {
                serverSocket = ServerSocket(port)
                isRunning = true
                instance = this
                Log.i(TAG, "Local file server started on port $port")

                while (isRunning) {
                    try {
                        val client = serverSocket?.accept() ?: break
                        executor.execute { handleClient(client) }
                    } catch (e: IOException) {
                        if (isRunning) Log.e(TAG, "Accept error", e)
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Server start failed", e)
            }
        }.apply {
            isDaemon = true
            name = "LocalFileServer"
            start()
        }
    }

    /**
     * Stop the HTTP server.
     */
    fun stop() {
        isRunning = false
        instance = null
        try {
            serverSocket?.close()
        } catch (_: Exception) {}
        executor.shutdownNow()
        Log.i(TAG, "Local file server stopped")
    }

    /**
     * Get the base URL for accessing local files.
     */
    fun getBaseUrl(): String = "http://localhost:$port"

    /**
     * Get a URL for a specific file path.
     */
    fun getFileUrl(absolutePath: String): String {
        val relativePath = absolutePath.removePrefix(STORAGE_ROOT).removePrefix("/")
        val encoded = relativePath.split("/").joinToString("/") {
            URLEncoder.encode(it, "UTF-8").replace("+", "%20")
        }
        return "http://localhost:$port/$encoded"
    }

    private fun handleClient(socket: Socket) {
        try {
            socket.soTimeout = 10000
            val input = socket.getInputStream().bufferedReader()
            val output = socket.getOutputStream()

            // Read request line
            val requestLine = input.readLine() ?: return
            val parts = requestLine.split(" ")
            if (parts.size < 2) return

            val method = parts[0]
            val rawPath = parts[1]

            // Read headers
            val headers = mutableMapOf<String, String>()
            var line: String?
            while (input.readLine().also { line = it } != null) {
                if (line.isNullOrEmpty()) break
                val colonIdx = line!!.indexOf(':')
                if (colonIdx > 0) {
                    headers[line!!.substring(0, colonIdx).trim().lowercase()] =
                        line!!.substring(colonIdx + 1).trim()
                }
            }

            if (method != "GET" && method != "HEAD") {
                sendError(output, 405, "Method Not Allowed")
                return
            }

            // Decode URL path
            val decodedPath = URLDecoder.decode(rawPath.split("?")[0], "UTF-8")
            val filePath = STORAGE_ROOT + decodedPath
            val file = File(filePath)

            // Security: prevent path traversal
            if (!file.canonicalPath.startsWith(STORAGE_ROOT)) {
                sendError(output, 403, "Forbidden")
                return
            }

            if (!file.exists()) {
                sendError(output, 404, "Not Found")
                return
            }

            if (file.isDirectory) {
                serveDirectory(output, file, decodedPath)
            } else {
                serveFile(output, file, headers, method == "HEAD")
            }

        } catch (e: Exception) {
            Log.w(TAG, "Client handling error: ${e.message}")
        } finally {
            try { socket.close() } catch (_: Exception) {}
        }
    }

    private fun serveFile(
        output: OutputStream,
        file: File,
        headers: Map<String, String>,
        headOnly: Boolean
    ) {
        val mimeType = MIME_TYPES[file.extension.lowercase()] ?: "application/octet-stream"
        val fileLength = file.length()

        // Handle Range requests (needed for video seeking)
        val rangeHeader = headers["range"]
        if (rangeHeader != null && rangeHeader.startsWith("bytes=")) {
            val rangeSpec = rangeHeader.removePrefix("bytes=")
            val rangeParts = rangeSpec.split("-")
            val start = rangeParts[0].toLongOrNull() ?: 0L
            val end = if (rangeParts.size > 1 && rangeParts[1].isNotEmpty()) {
                rangeParts[1].toLongOrNull() ?: (fileLength - 1)
            } else {
                fileLength - 1
            }
            val contentLength = end - start + 1

            val header = buildString {
                append("HTTP/1.1 206 Partial Content\r\n")
                append("Content-Type: $mimeType\r\n")
                append("Content-Length: $contentLength\r\n")
                append("Content-Range: bytes $start-$end/$fileLength\r\n")
                append("Accept-Ranges: bytes\r\n")
                append("Access-Control-Allow-Origin: *\r\n")
                append("Connection: close\r\n")
                append("\r\n")
            }
            output.write(header.toByteArray())

            if (!headOnly) {
                FileInputStream(file).use { fis ->
                    fis.skip(start)
                    val buffer = ByteArray(65536)
                    var remaining = contentLength
                    while (remaining > 0) {
                        val toRead = minOf(buffer.size.toLong(), remaining).toInt()
                        val read = fis.read(buffer, 0, toRead)
                        if (read <= 0) break
                        output.write(buffer, 0, read)
                        remaining -= read
                    }
                }
            }
        } else {
            // Full file response
            val header = buildString {
                append("HTTP/1.1 200 OK\r\n")
                append("Content-Type: $mimeType\r\n")
                append("Content-Length: $fileLength\r\n")
                append("Accept-Ranges: bytes\r\n")
                append("Access-Control-Allow-Origin: *\r\n")
                append("Connection: close\r\n")
                append("\r\n")
            }
            output.write(header.toByteArray())

            if (!headOnly) {
                FileInputStream(file).use { fis ->
                    val buffer = ByteArray(65536)
                    var read: Int
                    while (fis.read(buffer).also { read = it } > 0) {
                        output.write(buffer, 0, read)
                    }
                }
            }
        }
        output.flush()
    }

    private fun serveDirectory(output: OutputStream, dir: File, urlPath: String) {
        val files = dir.listFiles()
            ?.filter { !it.isHidden }
            ?.sortedWith(compareBy<File> { !it.isDirectory }.thenBy { it.name.lowercase() })
            ?: emptyList()

        val html = buildString {
            append("<!DOCTYPE html><html><head>")
            append("<meta charset='UTF-8'>")
            append("<meta name='viewport' content='width=device-width,initial-scale=1'>")
            append("<title>📂 ${dir.name}</title>")
            append("<style>")
            append("body{background:#0d0d1a;color:#fff;font-family:sans-serif;padding:16px;margin:0}")
            append("h1{color:#6c63ff;font-size:24px}")
            append("a{color:#00e5ff;text-decoration:none;display:block;padding:12px 16px;")
            append("margin:4px 0;background:#1a1a2e;border-radius:8px;font-size:18px}")
            append("a:hover{background:#16213e}")
            append(".size{color:#b0b0c8;font-size:14px;float:right}")
            append("</style></head><body>")
            append("<h1>📂 ${dir.name}</h1>")

            // Parent directory link
            if (urlPath != "/") {
                val parent = urlPath.removeSuffix("/").substringBeforeLast("/")
                append("<a href='${parent.ifEmpty { "/" }}'>⬆️ Parent Directory</a>")
            }

            for (file in files) {
                val name = file.name
                val encodedName = URLEncoder.encode(name, "UTF-8").replace("+", "%20")
                val href = "${urlPath.removeSuffix("/")}/$encodedName"

                if (file.isDirectory) {
                    append("<a href='$href'>📁 $name/</a>")
                } else {
                    val ext = file.extension.lowercase()
                    val icon = when {
                        ext in setOf("mp4","mkv","avi","mov","webm","3gp","flv") -> "🎬"
                        ext in setOf("mp3","m4a","flac","wav","ogg","aac") -> "🎵"
                        ext in setOf("jpg","jpeg","png","gif","webp") -> "🖼️"
                        ext == "pdf" -> "📄"
                        else -> "📎"
                    }
                    val size = formatSize(file.length())

                    if (ext in setOf("mp4","mkv","avi","mov","webm","3gp","flv")) {
                        // Video files get a special player page
                        append("<a href='$href'>$icon $name <span class='size'>$size ▶️ Tap to play</span></a>")
                    } else {
                        append("<a href='$href'>$icon $name <span class='size'>$size</span></a>")
                    }
                }
            }
            append("</body></html>")
        }

        val bytes = html.toByteArray()
        val header = buildString {
            append("HTTP/1.1 200 OK\r\n")
            append("Content-Type: text/html; charset=UTF-8\r\n")
            append("Content-Length: ${bytes.size}\r\n")
            append("Connection: close\r\n")
            append("\r\n")
        }
        output.write(header.toByteArray())
        output.write(bytes)
        output.flush()
    }

    private fun sendError(output: OutputStream, code: Int, message: String) {
        val body = "<html><body><h1>$code $message</h1></body></html>"
        val header = "HTTP/1.1 $code $message\r\nContent-Type: text/html\r\nContent-Length: ${body.length}\r\nConnection: close\r\n\r\n"
        output.write(header.toByteArray())
        output.write(body.toByteArray())
        output.flush()
    }

    private fun formatSize(bytes: Long): String = when {
        bytes >= 1_073_741_824 -> String.format("%.1f GB", bytes / 1_073_741_824.0)
        bytes >= 1_048_576 -> String.format("%.1f MB", bytes / 1_048_576.0)
        bytes >= 1024 -> String.format("%.1f KB", bytes / 1024.0)
        else -> "$bytes B"
    }
}
