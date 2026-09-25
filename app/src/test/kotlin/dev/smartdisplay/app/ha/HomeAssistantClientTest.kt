package dev.smartdisplay.app.ha

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

class HomeAssistantClientTest {

    private class FakeTokens(private var token: String?, private val afterRefresh: String? = token) : AccessTokenSource {
        var invalidations = 0

        override suspend fun accessToken() = token

        override suspend fun invalidateAccessToken() {
            invalidations++
            token = afterRefresh
        }
    }

    private val fake = FakeHomeAssistant()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @Before
    fun setUp() = fake.start()

    @After
    fun tearDown() {
        runBlocking { scope.coroutineContext.job.cancelAndJoin() }
        fake.dropConnections()
        fake.close()
    }

    @Test
    fun `a brief gap in watching doesn't reconnect`() = runBlocking {
        fake.acceptConnection()
        val client = client(FakeTokens("good"))
        client.awaitState { it.isLive() }
        // awaitState stopped watching; watch again straight away.
        val keepConnected = launch { client.state.collect {} }
        delay(300)
        assertEquals(1, fake.tokensTried().size)
        assertEquals(ConnectionStatus.Connected, client.state.value.status)
        keepConnected.cancel()
    }

    private fun client(tokens: AccessTokenSource) = HomeAssistantClient(
        http = OkHttpClient(),
        tokens = tokens,
        serverUrl = { fake.url },
        scope = scope,
        backoff = Backoff(baseMillis = 20, maxMillis = 100),
    )

    private suspend fun HomeAssistantClient.awaitState(predicate: (HomeState) -> Boolean): HomeState =
        withTimeout(10_000) { state.first(predicate) }

    private fun HomeState.isLive() = status == ConnectionStatus.Connected && loaded

    @Test
    fun `connects, loads everything and follows changes`() = runBlocking {
        fake.acceptConnection()
        val client = client(FakeTokens("good"))

        val home = client.awaitState { it.isLive() }
        assertEquals("2026.9.3", home.config?.version)
        assertEquals("°F", home.config?.temperatureUnit)
        assertEquals(setOf("light.kitchen", "sensor.outside", "weather.home"), home.entities.keys)
        assertEquals(listOf("Kitchen", "Living Room"), home.areas.map { it.name })
        assertEquals("kitchen", home.areaIdOf("light.kitchen"))
        assertEquals("living_room", home.areaIdOf("sensor.outside"))
        assertNull(home.areaIdOf("weather.home"))

        val keepConnected = launch { client.state.collect {} }
        fake.fireStateChanged("light.kitchen", "on")
        val changed = client.awaitState { it.entities["light.kitchen"]?.state == "on" }
        assertEquals("light.kitchen", changed.lastChange?.entityId)

        fake.fireStateChanged("sensor.outside", null)
        client.awaitState { "sensor.outside" !in it.entities }
        keepConnected.cancel()
    }

    @Test
    fun `subscribes to changes before loading states`() = runBlocking {
        fake.acceptConnection()
        client(FakeTokens("good")).awaitState { it.isLive() }

        val types = fake.received.map { it["type"]!!.jsonPrimitive.content }
        assertTrue(types.indexOf("subscribe_events") < types.indexOf("get_states"))
    }

    @Test
    fun `refreshes a rejected token and reconnects`() = runBlocking {
        fake.acceptConnection()
        fake.acceptConnection()
        val tokens = FakeTokens("expired", afterRefresh = "good")

        client(tokens).awaitState { it.isLive() }
        assertEquals(1, tokens.invalidations)
        assertEquals(listOf("expired", "good"), fake.tokensTried())
    }

    @Test
    fun `keeps data and reconnects after Home Assistant restarts`() = runBlocking {
        fake.acceptConnection()
        fake.acceptConnection()
        val client = client(FakeTokens("good"))
        client.awaitState { it.isLive() }
        val keepConnected = launch { client.state.collect {} }

        fake.dropConnections()
        val down = client.awaitState { it.status !is ConnectionStatus.Connected }
        assertTrue("data kept while reconnecting", down.entities.isNotEmpty())

        client.awaitState { it.isLive() }
        assertEquals(2, fake.tokensTried().size)
        keepConnected.cancel()
    }

    @Test
    fun `calls a service and returns its response`() = runBlocking {
        fake.acceptConnection()
        val client = client(FakeTokens("good"))
        val keepConnected = launch { client.state.collect {} }
        client.awaitState { it.isLive() }

        val result = client.callService(
            domain = "weather",
            service = "get_forecasts",
            data = buildJsonObject { put("type", "daily") },
            target = buildJsonObject { put("entity_id", "weather.home") },
            returnResponse = true,
        )
        assertEquals("get_forecasts", result.jsonObject["response"]!!.jsonObject["echo"]!!.jsonPrimitive.content)

        val sent = fake.received.last { it["type"]!!.jsonPrimitive.content == "call_service" }
        assertEquals("true", sent["return_response"]!!.jsonPrimitive.content)
        assertEquals("daily", sent["service_data"]!!.jsonObject["type"]!!.jsonPrimitive.content)
        assertEquals("weather.home", sent["target"]!!.jsonObject["entity_id"]!!.jsonPrimitive.content)
        keepConnected.cancel()
    }

    @Test
    fun `many commands at once reach Home Assistant in id order`() = runBlocking {
        fake.acceptConnection()
        val client = client(FakeTokens("good"))
        val keepConnected = launch { client.state.collect {} }
        client.awaitState { it.isLive() }

        // From several threads at once, as the screens do.
        val results = (1..200).map {
            async(Dispatchers.Default) { client.command("get_config") }
        }.awaitAll()
        assertEquals(200, results.size)
        assertEquals(ConnectionStatus.Connected, client.state.value.status)
        keepConnected.cancel()
    }

    @Test
    fun `browses media and resolves a file to an absolute signed URL`() = runBlocking {
        fake.acceptConnection()
        val client = client(FakeTokens("good"))
        val keepConnected = launch { client.state.collect {} }
        client.awaitState { it.isLive() }

        val folder = client.browseMedia("media-source://media_source/local/photos")
        assertEquals("Photos", folder.item.title)
        assertEquals(listOf("beach.jpg", "Holidays", "chime.mp3"), folder.children.map { it.title })
        assertEquals(listOf(true, false, false), folder.children.map { it.isImage })
        assertEquals(listOf(false, true, false), folder.children.map { it.canExpand })
        assertTrue(folder.children[2].isAudio)

        val resolved = client.resolveMedia("media-source://media_source/local/photos/beach.jpg")
        assertEquals("${fake.url}/media/local/photos/beach.jpg?authSig=signed", resolved.url)
        assertEquals("image/jpeg", resolved.mimeType)
        keepConnected.cancel()
    }

    @Test
    fun `a failed command reports Home Assistant's error`() = runBlocking {
        fake.acceptConnection()
        val client = client(FakeTokens("good"))
        val keepConnected = launch { client.state.collect {} }
        client.awaitState { it.isLive() }

        try {
            client.command("no/such_command")
            fail("expected CommandFailedException")
        } catch (e: CommandFailedException) {
            assertEquals("unknown_command", e.code)
        }
        keepConnected.cancel()
    }

    @Test
    fun `signed out means no connection`() = runBlocking {
        val client = client(FakeTokens(null))
        client.awaitState { it.status == ConnectionStatus.SignedOut }
        assertEquals(0, fake.server.requestCount)
        assertFalse(client.state.value.loaded)
    }

    @Test
    fun `commands fail fast when not connected`() = runBlocking {
        val client = client(FakeTokens("good"))
        try {
            client.command("get_states")
            fail("expected NotConnectedException")
        } catch (e: NotConnectedException) {
            // expected
        }
    }
}
