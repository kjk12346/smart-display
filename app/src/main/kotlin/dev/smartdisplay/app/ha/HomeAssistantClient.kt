package dev.smartdisplay.app.ha

import android.util.Log
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.random.Random
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.OkHttpClient

/** Supplies access tokens for the connection; [dev.smartdisplay.app.auth.Session] implements it. */
interface AccessTokenSource {
    /** A current access token, or null if signed out. Throws [IOException] if it can't be refreshed right now. */
    suspend fun accessToken(): String?

    /** Forgets the cached access token, so the next [accessToken] call refreshes it. */
    suspend fun invalidateAccessToken()
}

/** Home Assistant answered a command with `success: false`. */
class CommandFailedException(val code: String?, message: String?) : IOException("$code: $message")

/** A command was sent while there was no live connection. */
class NotConnectedException : IOException("Not connected to Home Assistant")

/** Retry delays: doubling from [baseMillis] up to [maxMillis], with ±20% jitter so displays don't retry in step. */
class Backoff(
    private val baseMillis: Long = 1_000,
    private val maxMillis: Long = 30_000,
    private val random: Random = Random.Default,
) {
    fun delayMillis(attempt: Int): Long {
        val exponential = baseMillis * (1L shl (attempt - 1).coerceIn(0, 16))
        val capped = exponential.coerceAtMost(maxMillis)
        return (capped * random.nextDouble(0.8, 1.2)).toLong()
    }
}

/**
 * The live connection to Home Assistant's WebSocket API. It connects while anything collects [state] (and for a few
 * seconds after, so a screen rotation doesn't reconnect), keeps the data current from events, and reconnects with
 * [Backoff] when the connection drops.
 */
class HomeAssistantClient(
    http: OkHttpClient,
    private val tokens: AccessTokenSource,
    private val serverUrl: () -> String?,
    scope: CoroutineScope,
    private val backoff: Backoff = Backoff(),
    stopDelayMillis: Long = STOP_DELAY_MS,
) {
    // No read or call timeout on a connection that stays open; pings find a dead one instead.
    private val socketHttp = http.newBuilder()
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .callTimeout(0, TimeUnit.MILLISECONDS)
        .pingInterval(PING_INTERVAL_S, TimeUnit.SECONDS)
        .build()

    private val _state = MutableStateFlow(HomeState())
    val state: StateFlow<HomeState> = _state.asStateFlow()

    private val live = AtomicReference<LiveConnection?>(null)

    init {
        @OptIn(FlowPreview::class)
        scope.launch(Dispatchers.Default) {
            // Debounce before de-duplicating: a brief gap in watching (a screen change) must not restart the connection.
            _state.subscriptionCount
                .map { it > 0 }
                .debounce { watched -> if (watched) 0 else stopDelayMillis }
                .distinctUntilChanged()
                .collectLatest { watched ->
                    if (watched) {
                        runUntilSignedOut()
                    } else {
                        _state.update { it.copy(status = ConnectionStatus.Idle) }
                    }
                }
        }
    }

    /** Sends a command on the live connection and returns its `result`. */
    suspend fun command(type: String, fields: JsonObject = JsonObject(emptyMap())): JsonElement =
        (live.get() ?: throw NotConnectedException()).command(type, fields)

    /** Calls a Home Assistant action (service). Set [returnResponse] for actions that return data. */
    suspend fun callService(
        domain: String,
        service: String,
        data: JsonObject = JsonObject(emptyMap()),
        target: JsonObject? = null,
        returnResponse: Boolean = false,
    ): JsonElement = command(
        "call_service",
        buildJsonObject {
            put("domain", domain)
            put("service", service)
            put("service_data", data)
            if (target != null) put("target", target)
            if (returnResponse) put("return_response", true)
        },
    )

    private suspend fun runUntilSignedOut() {
        var attempt = 0
        var retriedAuth = false
        while (true) {
            val server = serverUrl()
            if (server == null) {
                _state.update { it.copy(status = ConnectionStatus.SignedOut) }
                return
            }
            _state.update { it.copy(status = ConnectionStatus.Connecting) }
            val reason = try {
                when (connectOnce(server, onReady = { attempt = 0; retriedAuth = false })) {
                    Outcome.SignedOut -> {
                        _state.update { it.copy(status = ConnectionStatus.SignedOut) }
                        return
                    }
                    Outcome.AuthRejected -> {
                        // Usually an expired access token: refresh and retry once straight away.
                        tokens.invalidateAccessToken()
                        if (!retriedAuth) {
                            retriedAuth = true
                            continue
                        }
                        DisconnectReason.Rejected
                    }
                    Outcome.Closed -> DisconnectReason.Unreachable
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: ProtocolException) {
                Log.w(TAG, "Protocol error", e)
                DisconnectReason.Protocol
            } catch (e: CommandFailedException) {
                Log.w(TAG, "Command failed while connecting", e)
                DisconnectReason.Protocol
            } catch (e: IOException) {
                Log.w(TAG, "Connection failed or dropped: $e")
                DisconnectReason.Unreachable
            }
            attempt++
            val wait = backoff.delayMillis(attempt)
            Log.i(TAG, "Retrying in $wait ms ($reason, attempt $attempt)")
            _state.update {
                it.copy(status = ConnectionStatus.Waiting(System.currentTimeMillis() + wait, reason))
            }
            delay(wait)
        }
    }

    private enum class Outcome { SignedOut, AuthRejected, Closed }

    /** One connection, from opening to closing. Returns how it ended, or throws [IOException] if it failed. */
    private suspend fun connectOnce(server: String, onReady: () -> Unit): Outcome {
        val token = tokens.accessToken() ?: return Outcome.SignedOut
        val socket = WebSocketChannel.open(socketHttp, "$server/api/websocket")
        try {
            if (!authenticate(socket, token)) return Outcome.AuthRejected
            val connection = LiveConnection(socket)
            live.set(connection)
            try {
                coroutineScope {
                    val reader = launch { connection.readUntilClosed() }
                    loadAndSubscribe(connection, this)
                    _state.update { it.copy(status = ConnectionStatus.Connected) }
                    Log.i(TAG, "Connected")
                    onReady()
                    reader.join()
                }
            } finally {
                live.compareAndSet(connection, null)
            }
            return Outcome.Closed
        } finally {
            socket.close()
        }
    }

    private suspend fun authenticate(socket: WebSocketChannel, token: String): Boolean =
        withTimeout(AUTH_TIMEOUT_MS) {
            val hello = socket.incoming.receive()
            if (hello.string("type") != "auth_required") throw ProtocolException("Expected auth_required")
            socket.send(buildJsonObject {
                put("type", "auth")
                put("access_token", token)
            })
            when (socket.incoming.receive().string("type")) {
                "auth_ok" -> true
                "auth_invalid" -> false
                else -> throw ProtocolException("Expected auth_ok or auth_invalid")
            }
        }

    /**
     * Subscribes before loading, so no change falls between the two: a change made before `get_states` ran is in its
     * result, and one made after arrives as an event after it.
     */
    private suspend fun loadAndSubscribe(connection: LiveConnection, scope: CoroutineScope) {
        connection.subscribe("state_changed") { data ->
            _state.update { home ->
                val (changed, entities) = applyStateChanged(home.entities, data)
                home.copy(entities = entities, lastChange = changed ?: home.lastChange)
            }
        }
        // Keep areas, devices, entity names and settings current when they're edited in Home Assistant.
        connection.subscribe("area_registry_updated") { scope.launch { loadAreas(connection) } }
        connection.subscribe("device_registry_updated") { scope.launch { loadDevices(connection) } }
        connection.subscribe("entity_registry_updated") { scope.launch { loadEntityRegistry(connection) } }
        connection.subscribe("core_config_updated") { scope.launch { loadConfig(connection) } }

        coroutineScope {
            listOf(
                async { loadConfig(connection) },
                async { loadStates(connection) },
                async { loadAreas(connection) },
                async { loadDevices(connection) },
                async { loadEntityRegistry(connection) },
            ).awaitAll()
        }
    }

    private suspend fun loadConfig(c: LiveConnection) {
        val config = parseConfig(c.command("get_config"))
        _state.update { it.copy(config = config) }
    }

    private suspend fun loadStates(c: LiveConnection) {
        val entities = parseStates(c.command("get_states"))
        _state.update { it.copy(entities = entities) }
    }

    private suspend fun loadAreas(c: LiveConnection) {
        val areas = parseAreas(c.command("config/area_registry/list"))
        _state.update { it.copy(areas = areas) }
    }

    private suspend fun loadDevices(c: LiveConnection) {
        val devices = parseDevices(c.command("config/device_registry/list"))
        _state.update { it.copy(devices = devices) }
    }

    private suspend fun loadEntityRegistry(c: LiveConnection) {
        val registry = parseEntityRegistry(c.command("config/entity_registry/list"))
        _state.update { it.copy(registry = registry) }
    }

    /** An authenticated connection: numbers commands, matches results to them, and routes events. */
    private class LiveConnection(private val socket: WebSocketChannel) {
        private val sendLock = Any()
        private var nextId = 1
        private val pending = ConcurrentHashMap<Int, CompletableDeferred<JsonElement>>()
        private val subscriptions = ConcurrentHashMap<Int, (JsonObject) -> Unit>()

        /** Sends a command and waits for its result. [onEvent], if given, receives events sent under its id. */
        suspend fun command(
            type: String,
            fields: JsonObject = JsonObject(emptyMap()),
            onEvent: ((JsonObject) -> Unit)? = null,
        ): JsonElement {
            val result = CompletableDeferred<JsonElement>()
            val id: Int
            val sent: Boolean
            // Home Assistant rejects an id lower than one it has already seen ("id_reuse"), so numbering and queueing
            // for sending happen together. OkHttp's send only queues, so this lock is never held for long.
            synchronized(sendLock) {
                id = nextId++
                pending[id] = result
                // Registered before sending: events can follow the result immediately.
                if (onEvent != null) subscriptions[id] = onEvent
                sent = socket.send(JsonObject(fields + mapOf("id" to JsonPrimitive(id), "type" to JsonPrimitive(type))))
            }
            try {
                if (!sent) throw NotConnectedException()
                return result.await()
            } catch (e: Exception) {
                subscriptions.remove(id)
                throw e
            } finally {
                pending.remove(id)
            }
        }

        /** Subscribes to an event type; [onEvent] gets each event's `data` on the reader coroutine. */
        suspend fun subscribe(eventType: String, onEvent: (JsonObject) -> Unit) {
            command("subscribe_events", buildJsonObject { put("event_type", eventType) }, onEvent)
        }

        /** Routes messages until the connection closes, then fails any commands still waiting. */
        suspend fun readUntilClosed() {
            try {
                routeMessages()
            } finally {
                failPending()
            }
        }

        private suspend fun routeMessages() {
            for (message in socket.incoming) {
                val id = (message["id"] as? JsonPrimitive)?.content?.toIntOrNull()
                when (message.string("type")) {
                    "result" -> id?.let { pending[it] }?.let { deferred ->
                        if ((message["success"] as? JsonPrimitive)?.content == "true") {
                            deferred.complete(message["result"] ?: JsonObject(emptyMap()))
                        } else {
                            val error = message["error"] as? JsonObject
                            deferred.completeExceptionally(
                                CommandFailedException(error?.string("code"), error?.string("message"))
                            )
                        }
                    }
                    "event" -> {
                        val data = ((message["event"] as? JsonObject)?.get("data") as? JsonObject) ?: continue
                        id?.let { subscriptions[it] }?.invoke(data)
                    }
                }
            }
        }

        private fun failPending() {
            pending.values.forEach { it.completeExceptionally(NotConnectedException()) }
            pending.clear()
        }
    }

    private companion object {
        const val TAG = "HomeAssistantClient"
        const val STOP_DELAY_MS = 5_000L
        const val AUTH_TIMEOUT_MS = 15_000L
        const val PING_INTERVAL_S = 20L
    }
}
