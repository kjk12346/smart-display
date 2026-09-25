package dev.smartdisplay.app.ha

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener

/**
 * Speaks enough of Home Assistant's WebSocket API for tests: auth, the initial loads, subscriptions, and
 * `call_service`. Each [acceptConnection] lets one client connect.
 */
class FakeHomeAssistant(private val validTokens: Set<String> = setOf("good")) : WebSocketListener() {
    val server = MockWebServer()

    /** Every message received, in order, across all connections. */
    val received = CopyOnWriteArrayList<JsonObject>()
    private val sockets = CopyOnWriteArrayList<WebSocket>()
    private val subscriptions = ConcurrentHashMap<String, Pair<WebSocket, Int>>()
    private val lastIds = ConcurrentHashMap<WebSocket, Int>()

    val url: String get() = server.url("/").toString().trimEnd('/')

    fun start() = server.start()

    fun close() = server.close()

    fun acceptConnection() {
        server.enqueue(MockResponse.Builder().webSocketUpgrade(this).build())
    }

    fun tokensTried(): List<String> =
        received.filter { it.type == "auth" }.map { it["access_token"]!!.jsonPrimitive.content }

    fun fireStateChanged(entityId: String, newState: String?) {
        val (socket, id) = subscriptions.getValue("state_changed")
        socket.send(buildJsonObject {
            put("id", id)
            put("type", "event")
            putJsonObject("event") {
                put("event_type", "state_changed")
                putJsonObject("data") {
                    put("entity_id", entityId)
                    put("new_state", if (newState == null) JsonNull else state(entityId, newState))
                }
            }
        }.toString())
    }

    /** Closes the current connection from the server side, like a Home Assistant restart. */
    fun dropConnections() {
        sockets.forEach { it.close(1001, "restarting") }
        sockets.clear()
    }

    override fun onOpen(webSocket: WebSocket, response: Response) {
        sockets += webSocket
        webSocket.send("""{"type":"auth_required","ha_version":"2026.9.3"}""")
    }

    override fun onMessage(webSocket: WebSocket, text: String) {
        val message = Json.parseToJsonElement(text).jsonObject
        received += message
        val id = message["id"]?.jsonPrimitive?.content?.toInt()
        // Like Home Assistant: each command's id must be higher than the last one on this connection.
        if (id != null) {
            val last = lastIds[webSocket] ?: 0
            if (id <= last) {
                webSocket.send(buildJsonObject {
                    put("id", id)
                    put("type", "result")
                    put("success", false)
                    putJsonObject("error") {
                        put("code", "id_reuse")
                        put("message", "Identifier values have to increase.")
                    }
                }.toString())
                return
            }
            lastIds[webSocket] = id
        }
        when (message.type) {
            "auth" -> if (message["access_token"]!!.jsonPrimitive.content in validTokens) {
                webSocket.send("""{"type":"auth_ok","ha_version":"2026.9.3"}""")
            } else {
                webSocket.send("""{"type":"auth_invalid","message":"Invalid access token or password"}""")
                webSocket.close(1000, null)
            }
            "subscribe_events" -> {
                subscriptions[message["event_type"]!!.jsonPrimitive.content] = webSocket to id!!
                webSocket.result(id, JsonNull)
            }
            "get_config" -> webSocket.result(id!!, CONFIG)
            "get_states" -> webSocket.result(id!!, buildJsonArray {
                add(state("light.kitchen", "off"))
                add(state("sensor.outside", "12.5"))
                add(state("weather.home", "cloudy"))
            })
            "config/area_registry/list" -> webSocket.result(id!!, buildJsonArray {
                add(buildJsonObject { put("area_id", "living_room"); put("name", "Living Room") })
                add(buildJsonObject { put("area_id", "kitchen"); put("name", "Kitchen") })
            })
            "config/device_registry/list" -> webSocket.result(id!!, buildJsonArray {
                add(buildJsonObject { put("id", "dev1"); put("name", "Ceiling light"); put("area_id", "kitchen") })
            })
            "config/entity_registry/list" -> webSocket.result(id!!, buildJsonArray {
                // In the kitchen through its device.
                add(buildJsonObject { put("entity_id", "light.kitchen"); put("device_id", "dev1") })
                // Assigned an area directly.
                add(buildJsonObject { put("entity_id", "sensor.outside"); put("area_id", "living_room") })
            })
            "media_source/browse_media" -> webSocket.result(id!!, buildJsonObject {
                put("title", "Photos")
                put("media_content_id", message["media_content_id"]!!.jsonPrimitive.content)
                put("media_class", "directory")
                put("can_expand", true)
                put("can_play", false)
                put("children", buildJsonArray {
                    add(buildJsonObject {
                        put("title", "beach.jpg")
                        put("media_content_id", "media-source://media_source/local/photos/beach.jpg")
                        put("media_class", "image")
                        put("media_content_type", "image/jpeg")
                        put("can_expand", false)
                        put("can_play", true)
                    })
                    add(buildJsonObject {
                        put("title", "Holidays")
                        put("media_content_id", "media-source://media_source/local/photos/holidays")
                        put("media_class", "directory")
                        put("can_expand", true)
                        put("can_play", false)
                    })
                    add(buildJsonObject {
                        put("title", "chime.mp3")
                        put("media_content_id", "media-source://media_source/local/chime.mp3")
                        put("media_class", "music")
                        put("media_content_type", "audio/mpeg")
                        put("can_expand", false)
                        put("can_play", true)
                    })
                })
            })
            "media_source/resolve_media" -> webSocket.result(id!!, buildJsonObject {
                val path = message["media_content_id"]!!.jsonPrimitive.content.substringAfter("media_source")
                put("url", "/media$path?authSig=signed")
                put("mime_type", "image/jpeg")
            })
            "call_service" -> webSocket.result(id!!, buildJsonObject {
                putJsonObject("context") { put("id", "ctx1") }
                putJsonObject("response") { put("echo", message["service"]!!) }
            })
            else -> webSocket.send(buildJsonObject {
                put("id", id)
                put("type", "result")
                put("success", false)
                putJsonObject("error") { put("code", "unknown_command"); put("message", "Unknown command.") }
            }.toString())
        }
    }

    private fun WebSocket.result(id: Int, result: JsonElement) {
        send(buildJsonObject {
            put("id", id)
            put("type", "result")
            put("success", true)
            put("result", result)
        }.toString())
    }

    private val JsonObject.type get() = this["type"]?.jsonPrimitive?.content

    private companion object {
        val CONFIG = buildJsonObject {
            put("location_name", "Home")
            put("version", "2026.9.3")
            put("time_zone", "America/New_York")
            putJsonObject("unit_system") { put("temperature", "°F") }
        }

        fun state(entityId: String, state: String) = buildJsonObject {
            put("entity_id", entityId)
            put("state", state)
            putJsonObject("attributes") { put("friendly_name", entityId.substringAfter('.').replaceFirstChar(Char::uppercase)) }
            put("last_changed", "2026-09-23T18:00:00+00:00")
        }
    }
}
