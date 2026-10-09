package dev.smartdisplay.app.ui.ambient

import java.time.LocalDate
import java.time.ZoneId
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Test

class ForecastDaysTest {
    private val chicago = ZoneId.of("America/Chicago")

    @Test
    fun `days are read in local calendar days, with condition, high and low`() {
        val result = Json.parseToJsonElement(
            """{"response": {"weather.home": {"forecast": [
                {"datetime": "2026-10-09T17:00:00+00:00", "condition": "cloudy", "temperature": 76, "templow": 54},
                {"datetime": "2026-10-10T17:00:00+00:00", "condition": "rainy", "temperature": 70.4},
                {"datetime": "nonsense"},
                {"condition": "sunny"}
            ]}}}"""
        )
        val days = parseForecastDays(result, "weather.home", chicago)
        assertEquals(
            listOf(
                ForecastDay(LocalDate.of(2026, 10, 9), "cloudy", 76.0, 54.0),
                ForecastDay(LocalDate.of(2026, 10, 10), "rainy", 70.4, null),
            ),
            days,
        )
    }

    @Test
    fun `midnight UTC is still the evening before in Chicago, and plain dates are kept as they are`() {
        val result = Json.parseToJsonElement(
            """{"response": {"weather.home": {"forecast": [
                {"datetime": "2026-10-10T00:00:00+00:00", "temperature": 1},
                {"datetime": "2026-10-11", "temperature": 2}
            ]}}}"""
        )
        assertEquals(
            listOf(LocalDate.of(2026, 10, 9), LocalDate.of(2026, 10, 11)),
            parseForecastDays(result, "weather.home", chicago).map { it.date },
        )
    }

    @Test
    fun `another entity or a missing forecast gives no days`() {
        val result = Json.parseToJsonElement("""{"response": {"weather.other": {"forecast": []}}}""")
        assertEquals(emptyList<ForecastDay>(), parseForecastDays(result, "weather.home", chicago))
        assertEquals(emptyList<ForecastDay>(), parseForecastDays(Json.parseToJsonElement("{}"), "weather.home"))
    }
}
