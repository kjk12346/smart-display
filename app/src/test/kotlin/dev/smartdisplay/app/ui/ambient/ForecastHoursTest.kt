package dev.smartdisplay.app.ui.ambient

import java.time.LocalDateTime
import java.time.ZoneId
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ForecastHoursTest {
    private val chicago = ZoneId.of("America/Chicago")

    @Test
    fun `hours are read in local time, in order, with the chance of rain`() {
        val result = Json.parseToJsonElement(
            """{"response": {"weather.home": {"forecast": [
                {"datetime": "2026-10-28T21:00:00+00:00", "condition": "rainy", "temperature": 66.2,
                 "precipitation_probability": 60},
                {"datetime": "2026-10-28T20:00:00+00:00", "condition": "sunny", "temperature": 74},
                {"datetime": "not a time", "temperature": 1},
                {"temperature": 2}
            ]}}}"""
        )
        assertEquals(
            listOf(
                ForecastHour(LocalDateTime.of(2026, 10, 28, 15, 0), "sunny", 74.0, null),
                ForecastHour(LocalDateTime.of(2026, 10, 28, 16, 0), "rainy", 66.2, 60),
            ),
            parseForecastHours(result, "weather.home", chicago),
        )
    }

    @Test
    fun `the coming hours start after now`() {
        val hours = (14..17).map { ForecastHour(LocalDateTime.of(2026, 10, 28, it, 0), null, null, null) }
        assertEquals(
            listOf(15, 16, 17),
            hours.comingAfter(LocalDateTime.of(2026, 10, 28, 14, 20)).map { it.time.hour },
        )
    }

    @Test
    fun `night is between sunset and sunrise, whether the sun is up or down now`() {
        val day = SunTimes(
            nextRising = LocalDateTime.of(2026, 10, 29, 7, 10),
            nextSetting = LocalDateTime.of(2026, 10, 28, 18, 5),
        )
        assertFalse(day.isNight(LocalDateTime.of(2026, 10, 28, 17, 0)))
        assertTrue(day.isNight(LocalDateTime.of(2026, 10, 28, 19, 0)))
        assertFalse(day.isNight(LocalDateTime.of(2026, 10, 29, 8, 0)))
        val night = SunTimes(
            nextRising = LocalDateTime.of(2026, 10, 29, 7, 10),
            nextSetting = LocalDateTime.of(2026, 10, 29, 18, 4),
        )
        assertTrue(night.isNight(LocalDateTime.of(2026, 10, 29, 3, 0)))
        assertFalse(night.isNight(LocalDateTime.of(2026, 10, 29, 12, 0)))
        assertTrue(night.isNight(LocalDateTime.of(2026, 10, 29, 19, 0)))
    }
}
