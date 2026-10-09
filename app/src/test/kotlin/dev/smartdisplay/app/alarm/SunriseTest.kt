package dev.smartdisplay.app.alarm

import dev.smartdisplay.app.ha.EntityState
import java.time.DayOfWeek
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZonedDateTime
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SunriseTest {

    @Test
    fun `brightness starts at 1 percent, rises slowly at first, and ends full and cool`() {
        assertEquals(SunriseLevel(1, 2000), sunriseLevel(0f))
        assertEquals(SunriseLevel(100, 4000), sunriseLevel(1f))
        val half = sunriseLevel(0.5f)
        assertTrue("half way is still dim: $half", half.brightnessPercent in 20..30)
        assertEquals(3000, half.kelvin)
        val levels = (0..20).map { sunriseLevel(it / 20f).brightnessPercent }
        assertEquals(levels.sorted(), levels)
        assertEquals(SunriseLevel(100, 4000), sunriseLevel(1.5f))
    }

    @Test
    fun `progress runs from start to ring`() {
        val sunrise = Sunrise(1, startMillis = 1_000, ringMillis = 3_000)
        assertEquals(0f, sunrise.progress(500))
        assertEquals(0.5f, sunrise.progress(2_000))
        assertEquals(1f, sunrise.progress(9_000))
    }

    private fun light(attributes: String) =
        EntityState("light.lamp", "off", Json.parseToJsonElement(attributes).jsonObject, null).sunriseLight()

    @Test
    fun `a colour-temperature light gets warm to cool within its own range, gliding if it can`() {
        val lamp = light(
            """{"supported_color_modes":["color_temp"],"min_color_temp_kelvin":2200,"max_color_temp_kelvin":6500,
               "supported_features":32}"""
        )
        val start = sunriseCall(lamp, sunriseLevel(0f), 30)!!
        assertEquals(1, start.int("brightness_pct"))
        assertEquals(2200, start.int("color_temp_kelvin"))
        assertEquals(30, start.int("transition"))
        assertEquals(4000, sunriseCall(lamp, sunriseLevel(1f), 30)!!.int("color_temp_kelvin"))
    }

    @Test
    fun `a colour light takes a colour temperature too, a plain dimmer only brightness`() {
        val bulb = light("""{"supported_color_modes":["hs"],"supported_features":0}""")
        val call = sunriseCall(bulb, sunriseLevel(0.2f), 30)!!
        assertEquals(2400, call.int("color_temp_kelvin"))
        assertNull(call["transition"])

        val dimmer = sunriseCall(light("""{"supported_color_modes":["brightness"]}"""), sunriseLevel(0.2f), 30)!!
        assertNull(dimmer["color_temp_kelvin"])
        assertEquals(5, dimmer.int("brightness_pct"))
    }

    @Test
    fun `an on-off light waits for the alarm`() {
        val plug = light("""{"supported_color_modes":["onoff"]}""")
        assertFalse(plug.dimmable)
        assertNull(sunriseCall(plug, sunriseLevel(0.5f), 30))
    }

    private fun JsonObject.int(key: String) = this[key]?.jsonPrimitive?.int
}

class SkipTest {
    private val zone = ZoneId.of("America/Chicago")

    // 2026-09-24 is a Thursday.
    private fun at(day: Int, hour: Int, minute: Int) =
        ZonedDateTime.of(LocalDateTime.of(2026, 9, day, hour, minute), zone)

    @Test
    fun `a skipped ring moves a repeating alarm to its next day`() {
        val weekdays = Alarm(1, 7, 0, days = DayOfWeek.entries.toSet() - setOf(DayOfWeek.SATURDAY, DayOfWeek.SUNDAY))
        val now = at(24, 6, 45)
        assertEquals(at(24, 7, 0), nextRing(listOf(weekdays), null, now)!!.at)
        val skip = Skip(1, at(24, 7, 0).toInstant().toEpochMilli())
        assertEquals(at(25, 7, 0), nextRing(listOf(weekdays), null, now, skip)!!.at)
    }

    @Test
    fun `a skip only affects its own alarm, and is forgotten once past`() {
        val mine = Alarm(1, 7, 0, days = DayOfWeek.entries.toSet())
        val other = Alarm(2, 7, 30, days = DayOfWeek.entries.toSet())
        val skip = Skip(1, at(24, 7, 0).toInstant().toEpochMilli())
        assertEquals(2, nextRing(listOf(mine, other), null, at(24, 6, 45), skip)!!.alarm.id)
        // The next morning the old skip is in the past and changes nothing.
        assertEquals(at(25, 7, 0), nextRing(listOf(mine, other), null, at(25, 6, 0), skip)!!.at)
    }
}
