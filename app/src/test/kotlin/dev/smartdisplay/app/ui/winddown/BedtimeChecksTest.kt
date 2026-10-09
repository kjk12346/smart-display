package dev.smartdisplay.app.ui.winddown

import dev.smartdisplay.app.ha.EntityEntry
import dev.smartdisplay.app.ha.EntityState
import dev.smartdisplay.app.ha.HomeState
import dev.smartdisplay.app.ui.dashboard.CalendarEvent
import java.time.LocalDate
import java.time.LocalDateTime
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BedtimeChecksTest {
    private fun entity(id: String, state: String, name: String, deviceClass: String? = null) = EntityState(
        id, state,
        Json.parseToJsonElement(
            """{"friendly_name": "$name"${deviceClass?.let { ", \"device_class\": \"$it\"" }.orEmpty()}}""",
        ).jsonObject,
        lastChanged = null,
    )

    @Test
    fun `lists what's unlocked, open or on, most important first, with fixes`() {
        val home = HomeState(
            entities = listOf(
                entity("light.kitchen", "on", "Kitchen"),
                entity("light.bedroom", "on", "Bedroom lamp"),
                entity("light.hall", "off", "Hall"),
                entity("light.hidden", "on", "Hidden"),
                entity("lock.front", "unlocked", "Front door lock"),
                entity("lock.back", "locked", "Back door lock"),
                entity("cover.garage", "open", "Garage", "garage"),
                entity("cover.blinds", "open", "Blinds", "shade"),
                entity("binary_sensor.window", "on", "Bedroom window", "window"),
                entity("binary_sensor.motion", "on", "Motion", "motion"),
                entity("media_player.tv", "on", "TV"),
                entity("media_player.speaker", "idle", "Speaker"),
            ).associateBy { it.entityId },
            registry = mapOf("light.hidden" to EntityEntry("light.hidden", null, null, null, true, false, null)),
        )
        val checks = home.bedtimeChecks(ignore = setOf("light.bedroom"))
        assertEquals(
            listOf(
                "lock.front" to CheckKind.Unlocked,
                "cover.garage" to CheckKind.GarageOpen,
                "binary_sensor.window" to CheckKind.Open,
                "light.kitchen" to CheckKind.LightOn,
                "media_player.tv" to CheckKind.MediaOn,
            ),
            checks.map { it.entityId to it.kind },
        )
        assertEquals("lock.lock", checks[0].fix?.let { "${it.domain}.${it.service}" })
        assertEquals("cover.close_cover", checks[1].fix?.let { "${it.domain}.${it.service}" })
        assertNull(checks[2].fix)
        assertEquals("light.turn_off", checks[3].fix?.let { "${it.domain}.${it.service}" })
    }

    @Test
    fun `tomorrow's first event is its first timed one, else its first all-day one`() {
        val day = LocalDate.parse("2026-10-10")
        fun event(summary: String, start: String, allDay: Boolean = false) = CalendarEvent(
            "calendar.family", summary, LocalDateTime.parse(start), LocalDateTime.parse(start).plusHours(1), allDay,
        )
        val bins = event("Bins out", "2026-10-10T00:00", allDay = true)
        val dentist = event("Dentist", "2026-10-10T09:00")
        val football = event("Football", "2026-10-10T08:00")
        val sunday = event("Brunch", "2026-10-11T08:00")
        assertEquals(football, firstEvent(listOf(bins, dentist, football, sunday), day))
        assertEquals(bins, firstEvent(listOf(bins, sunday), day))
        assertNull(firstEvent(listOf(sunday), day))
    }
}
