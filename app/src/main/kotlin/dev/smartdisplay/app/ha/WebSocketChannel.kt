package dev.smartdisplay.app.ha

import java.io.IOException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener

class ProtocolException(message: String) : IOException(message)

/**
 * An OkHttp WebSocket as a channel of JSON messages. Incoming text is parsed on OkHttp's reader thread; the channel
 * closes normally when the server closes the connection, and with the error when it fails.
 */
internal class WebSocketChannel private constructor() : WebSocketListener() {
    private val messages = Channel<JsonObject>(Channel.UNLIMITED)
    private lateinit var socket: WebSocket

    val incoming: ReceiveChannel<JsonObject> get() = messages

    /** Queues [message] for sending. False if the connection is closing or closed. */
    fun send(message: JsonObject): Boolean = socket.send(message.toString())

    fun close() {
        socket.close(NORMAL_CLOSURE, null)
        messages.close()
    }

    override fun onMessage(webSocket: WebSocket, text: String) {
        try {
            when (val json = Json.parseToJsonElement(text)) {
                is JsonObject -> messages.trySend(json)
                // Home Assistant can batch messages into an array (only if asked to, but it costs nothing to accept).
                is JsonArray -> json.filterIsInstance<JsonObject>().forEach { messages.trySend(it) }
                else -> fail(webSocket, ProtocolException("Unexpected message: $text"))
            }
        } catch (e: SerializationException) {
            fail(webSocket, ProtocolException("Invalid JSON"))
        }
    }

    override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
        webSocket.close(NORMAL_CLOSURE, null)
    }

    override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
        messages.close()
    }

    override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
        messages.close(t as? IOException ?: IOException(t))
    }

    private fun fail(webSocket: WebSocket, error: IOException) {
        webSocket.cancel()
        messages.close(error)
    }

    companion object {
        private const val NORMAL_CLOSURE = 1000

        /** Starts connecting to [url] (http or https; OkHttp upgrades it). Failures arrive through [incoming]. */
        fun open(http: OkHttpClient, url: String): WebSocketChannel {
            val channel = WebSocketChannel()
            channel.socket = http.newWebSocket(Request.Builder().url(url).build(), channel)
            return channel
        }
    }
}
