package dev.smartdisplay.app.alarm

import dev.smartdisplay.app.ha.EntityState
import java.time.DayOfWeek
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class WindDownTest {
    private val zone = ZoneId.of("America/Chicago")
    private val weekdays = DayOfWeek.entries.toSet() - setOf(DayOfWeek.SATURDAY, DayOfWeek.SUNDAY)
    private val weekdayAlarm = Alarm(id = 1, hour = 6, minute = 30, days = weekdays)
    private val on = WindDownSettings(enabled = true, lights = setOf("light.bedroom"))

    private fun at(text: String) = ZonedDateTime.of(java.time.LocalDateTime.parse(text), zone)
    private fun millis(text: String) = at(text).toInstant().toEpochMilli()

    private fun next(
        now: String,
        settings: WindDownSettings = on,
        alarms: List<Alarm> = listOf(weekdayAlarm),
        doneUntil: Long = 0,
    ) = nextWindDown(alarms, skip = null, settings = settings, now = at(now), doneUntilMillis = doneUntil)

    @Test
    fun `off, it doesn't start by itself`() {
        assertNull(next("2026-10-08T18:00", settings = on.copy(enabled = false)))
    }

    @Test
    fun `bedtime is a night's sleep before the next morning's alarm`() {
        // Thursday evening; Friday's alarm is at 6:30, so lights off at 10:30 after 45 minutes from 9:45.
        assertEquals(
            WindDown(millis("2026-10-08T21:45"), millis("2026-10-08T22:30")),
            next("2026-10-08T18:00"),
        )
    }

    @Test
    fun `one under way is still the next`() {
        assertEquals(millis("2026-10-08T22:30"), next("2026-10-08T22:00")?.bedtimeMillis)
    }

    @Test
    fun `a night without an alarm uses the fallback bedtime, or has none`() {
        // Friday night: no alarm on Saturday morning.
        assertEquals(
            WindDown(millis("2026-10-09T22:15"), millis("2026-10-09T23:00")),
            next("2026-10-09T18:00"),
        )
        // Without a fallback, the next is Sunday night, before Monday's alarm.
        assertEquals(
            millis("2026-10-11T22:30"),
            next("2026-10-09T18:00", settings = on.copy(fallbackBedtime = null))?.bedtimeMillis,
        )
    }

    @Test
    fun `a bedtime after midnight is the next morning's`() {
        assertEquals(
            millis("2026-10-10T00:00"),
            next("2026-10-09T18:00", settings = on.copy(fallbackBedtime = LocalTime.MIDNIGHT))?.bedtimeMillis,
        )
        // Just after midnight, last night's 12:30 bedtime is still to come.
        assertEquals(
            millis("2026-10-10T00:30"),
            next("2026-10-10T00:10", settings = on.copy(fallbackBedtime = LocalTime.of(0, 30)))?.bedtimeMillis,
        )
    }

    @Test
    fun `a night already wound down is skipped`() {
        assertEquals(
            millis("2026-10-09T23:00"),
            next("2026-10-08T18:00", doneUntil = millis("2026-10-08T22:30"))?.bedtimeMillis,
        )
    }

    @Test
    fun `an afternoon alarm isn't a wake-up alarm`() {
        val afternoon = Alarm(id = 2, hour = 15, minute = 0, days = DayOfWeek.entries.toSet())
        assertNull(next("2026-10-08T18:00", settings = on.copy(fallbackBedtime = null), alarms = listOf(afternoon)))
    }

    @Test
    fun `lights dim and warm to a glow, never brighter or cooler than they were`() {
        val start = LightStart(brightnessPercent = 80, kelvin = 4000)
        assertEquals(SunriseLevel(80, 4000), windDownLevel(start, 0f))
        assertEquals(SunriseLevel(22, 3100), windDownLevel(start, 0.5f))
        assertEquals(SunriseLevel(2, 2200), windDownLevel(start, 1f))
        // Already dimmer and warmer than the end: left there.
        assertEquals(SunriseLevel(1, 2000), windDownLevel(LightStart(1, 2000), 0.5f))
        // A white bulb that doesn't say its temperature counts as warm white.
        assertEquals(2450, windDownLevel(LightStart(50, null), 0.5f).kelvin)
    }

    @Test
    fun `a light's start comes from its brightness and color temperature`() {
        fun light(state: String, attributes: String) =
            EntityState("light.a", state, Json.parseToJsonElement(attributes).jsonObject, lastChanged = null)
        assertEquals(LightStart(100, 2700), light("on", """{"brightness": 255, "color_temp_kelvin": 2700}""").lightStart())
        assertEquals(LightStart(50, null), light("on", """{"brightness": 128}""").lightStart())
        assertEquals(LightStart(100, null), light("on", "{}").lightStart())
        assertNull(light("off", """{"brightness": 128}""").lightStart())
    }
}
