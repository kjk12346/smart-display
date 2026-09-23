package dev.smartdisplay.app.ui.ambient

import dev.smartdisplay.app.ha.EntityEntry
import dev.smartdisplay.app.ha.EntityState
import dev.smartdisplay.app.ha.HomeState
import java.time.LocalTime
import kotlin.math.abs
import kotlin.math.hypot
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AmbientModelTest {

    private fun entity(id: String, state: String, attributes: JsonObject = JsonObject(emptyMap())) =
        EntityState(id, state, attributes, lastChanged = null)

    private fun entry(id: String, hidden: Boolean = false) =
        EntityEntry(id, name = null, areaId = null, deviceId = null, hidden = hidden, disabled = false, category = null)

    @Test
    fun `picks the first visible weather entity by id`() {
        val home = HomeState(
            entities = listOf(
                entity("weather.zulu", "sunny"),
                entity("weather.alpha", "rainy"),
                entity("weather.aaa_hidden", "cloudy"),
                entity("sensor.temperature", "20"),
            ).associateBy { it.entityId },
            registry = mapOf("weather.aaa_hidden" to entry("weather.aaa_hidden", hidden = true)),
        )
        assertEquals("weather.alpha", home.primaryWeatherEntity()?.entityId)
    }

    @Test
    fun `no weather entity means no weather`() {
        assertNull(HomeState(entities = mapOf("sun.sun" to entity("sun.sun", "above_horizon"))).primaryWeatherEntity())
    }

    @Test
    fun `current weather reads temperature and unit, and skips unavailable`() {
        val weather = entity("weather.home", "partlycloudy", buildJsonObject {
            put("temperature", 71.6)
            put("temperature_unit", "°F")
        }).toCurrentWeather()
        assertEquals(CurrentWeather("weather.home", "partlycloudy", 71.6, "°F"), weather)
        assertNull(entity("weather.home", "unavailable").toCurrentWeather())
    }

    @Test
    fun `forecast takes today's high and low`() {
        val result = Json.parseToJsonElement(
            """{"context":{"id":"x"},"response":{"weather.home":{"forecast":[
                {"datetime":"2026-09-23T16:00:00+00:00","condition":"sunny","temperature":78.0,"templow":61.0},
                {"datetime":"2026-09-24T16:00:00+00:00","condition":"rainy","temperature":70.0,"templow":58.0}
            ]}}}"""
        )
        assertEquals(DailyForecast(78.0, 61.0), parseDailyForecast(result, "weather.home"))
    }

    @Test
    fun `forecast without a low still has a high`() {
        val result = Json.parseToJsonElement(
            """{"response":{"weather.home":{"forecast":[{"temperature":20}]}}}"""
        )
        assertEquals(DailyForecast(20.0, null), parseDailyForecast(result, "weather.home"))
    }

    @Test
    fun `forecast for another entity or an empty list is nothing`() {
        val result = Json.parseToJsonElement("""{"response":{"weather.home":{"forecast":[]}}}""")
        assertNull(parseDailyForecast(result, "weather.home"))
        assertNull(parseDailyForecast(result, "weather.other"))
    }

    @Test
    fun `temperatures round to whole degrees`() {
        assertEquals("72°", formatTemperature(71.6))
        assertEquals("-3°", formatTemperature(-3.4))
    }

    @Test
    fun `quiet hours wrap past midnight`() {
        assertTrue(isQuietHours(LocalTime.of(22, 0)))
        assertTrue(isQuietHours(LocalTime.of(3, 30)))
        assertFalse(isQuietHours(LocalTime.of(7, 0)))
        assertFalse(isQuietHours(LocalTime.of(21, 59)))
        assertTrue(isQuietHours(LocalTime.of(13, 0), start = LocalTime.of(12, 0), end = LocalTime.of(14, 0)))
    }

    @Test
    fun `burn-in offset moves every minute and stays near the centre`() {
        val offsets = (0L until 16L).map(::burnInOffset)
        offsets.zipWithNext().forEach { (a, b) -> assertTrue("$a then $b", a != b) }
        offsets.forEach { (x, y) -> assertTrue("$x,$y", hypot(x.toDouble(), y.toDouble()) <= BURN_IN_RADIUS_DP + 0.5) }
        assertEquals(burnInOffset(0), burnInOffset(16))
        assertEquals(burnInOffset(-1), burnInOffset(15))
        assertTrue(offsets.map { abs(it.first) }.max() >= 8)
    }
}
