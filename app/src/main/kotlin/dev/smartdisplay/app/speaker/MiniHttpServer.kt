package dev.smartdisplay.app.speaker

import android.util.Log
import java.io.BufferedInputStream
import java.io.IOException
import java.io.InputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** A request to the speaker's server: header names are lower case. */
class HttpRequest(val method: String, val path: String, val headers: Map<String, String>, val body: String)

class HttpResponse(
    val status: Int,
    val reason: String,
    val body: String = "",
    val contentType: String = "text/xml; charset=\"utf-8\"",
    val headers: List<Pair<String, String>> = emptyList(),
) {
    companion object {
        fun ok(body: String) = HttpResponse(200, "OK", body)
        val NOT_FOUND = HttpResponse(404, "Not Found")
    }
}

/**
 * Just enough HTTP/1.1 for UPnP: one request per connection, any method (SUBSCRIBE and UNSUBSCRIBE included), bodies
 * by Content-Length. Home Assistant is its only real caller, on the local network.
 */
class MiniHttpServer(private val handle: suspend (HttpRequest) -> HttpResponse) {
    private var socket: ServerSocket? = null

    /** Listens on [preferredPort], or any free port if that's taken; returns the port. */
    fun bind(preferredPort: Int): Int {
        val server = ServerSocket()
        server.reuseAddress = true
        try {
            server.bind(InetSocketAddress(preferredPort))
        } catch (e: IOException) {
            server.bind(InetSocketAddress(0))
        }
        socket = server
        return server.localPort
    }

    /** Answers requests until cancelled. */
    suspend fun serve() = withContext(Dispatchers.IO) {
        val server = socket ?: return@withContext
        try {
            while (isActive) {
                val client = server.accept()
                launch { client.use { answer(it) } }
            }
        } catch (e: SocketException) {
            // Closed by stop().
        }
    }

    fun stop() {
        runCatching { socket?.close() }
        socket = null
    }

    private suspend fun answer(client: Socket) {
        try {
            client.soTimeout = 10_000
            val input = BufferedInputStream(client.getInputStream())
            val request = read(input) ?: return
            val response = try {
                handle(request)
            } catch (e: Exception) {
                Log.w(TAG, "${request.method} ${request.path} failed", e)
                HttpResponse(500, "Internal Server Error")
            }
            write(client, response)
        } catch (e: IOException) {
            Log.d(TAG, "Connection dropped", e)
        }
    }

    private fun read(input: InputStream): HttpRequest? {
        val requestLine = readLine(input) ?: return null
        val parts = requestLine.split(" ")
        if (parts.size < 2) return null
        val headers = mutableMapOf<String, String>()
        while (true) {
            val line = readLine(input) ?: break
            if (line.isEmpty()) break
            val colon = line.indexOf(':')
            if (colon > 0) headers[line.substring(0, colon).trim().lowercase()] = line.substring(colon + 1).trim()
        }
        val length = headers["content-length"]?.toIntOrNull()?.coerceIn(0, MAX_BODY) ?: 0
        val body = ByteArray(length)
        var read = 0
        while (read < length) {
            val n = input.read(body, read, length - read)
            if (n < 0) break
            read += n
        }
        return HttpRequest(parts[0].uppercase(), parts[1].substringBefore('?'), headers, String(body, 0, read))
    }

    /** A line up to CRLF (or LF), as ISO-8859-1; null at the end of the stream. */
    private fun readLine(input: InputStream): String? {
        val line = StringBuilder()
        while (true) {
            val c = input.read()
            if (c < 0) return if (line.isEmpty()) null else line.toString()
            if (c == '\n'.code) return line.toString().trimEnd('\r')
            line.append(c.toChar())
            if (line.length > MAX_LINE) throw IOException("Line too long")
        }
    }

    private fun write(client: Socket, response: HttpResponse) {
        val body = response.body.toByteArray()
        val head = buildString {
            append("HTTP/1.1 ${response.status} ${response.reason}\r\n")
            if (body.isNotEmpty()) append("Content-Type: ${response.contentType}\r\n")
            append("Content-Length: ${body.size}\r\n")
            for ((name, value) in response.headers) append("$name: $value\r\n")
            append("Server: $SERVER_HEADER\r\n")
            append("Connection: close\r\n\r\n")
        }
        client.getOutputStream().apply {
            write(head.toByteArray(Charsets.ISO_8859_1))
            write(body)
            flush()
        }
    }

    private companion object {
        const val TAG = "SpeakerHttp"
        const val MAX_LINE = 8 * 1024
        const val MAX_BODY = 512 * 1024
    }
}

/** Says who we are in HTTP and SSDP, as UPnP asks: OS, UPnP version, product. */
val SERVER_HEADER = "Android/${android.os.Build.VERSION.RELEASE} UPnP/1.0 SmartDisplay/1.0"
