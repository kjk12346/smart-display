package dev.smartdisplay.app.ui.dashboard

import dev.smartdisplay.app.ha.EntityEntry
import dev.smartdisplay.app.ha.EntityState
import dev.smartdisplay.app.ha.HomeState
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CalendarModelTest {
    private val zone = ZoneId.of("America/Los_Angeles")

    private fun event(
        summary: String,
        start: String,
        end: String,
        allDay: Boolean = false,
        calendar: String = "calendar.family",
    ) = CalendarEvent(calendar, summary, LocalDateTime.parse(start), LocalDateTime.parse(end), allDay)

    private fun shown(
        summary: String,
        start: String,
        end: String,
        allDay: Boolean = false,
        owners: List<String> = listOf(FAMILY),
    ) = ShownEvent(summary, LocalDateTime.parse(start), LocalDateTime.parse(end), allDay, owners)

    @Test
    fun `reads timed and all-day events from each calendar, in the display's zone`() {
        val result = Json.parseToJsonElement(
            """
            {"response": {
              "calendar.family": {"events": [
                {"start": "2026-10-09T17:30:00+00:00", "end": "2026-10-09T18:30:00+00:00", "summary": " Dentist "},
                {"start": "2026-10-10", "end": "2026-10-11", "summary": "Bins out"},
                {"summary": "No start"}
              ]},
              "calendar.work": {"events": [
                {"start": "2026-10-09T09:00:00-07:00", "end": "2026-10-09T10:00:00-07:00", "summary": "Stand-up"}
              ]}
            }}
            """,
        )
        assertEquals(
            listOf(
                event("Dentist", "2026-10-09T10:30", "2026-10-09T11:30"),
                event("Bins out", "2026-10-10T00:00", "2026-10-11T00:00", allDay = true),
                event("Stand-up", "2026-10-09T09:00", "2026-10-09T10:00", calendar = "calendar.work"),
            ),
            parseCalendarEvents(result, zone),
        )
    }

    @Test
    fun `an unexpected answer gives no events`() {
        assertTrue(parseCalendarEvents(Json.parseToJsonElement("""{"response": null}"""), zone).isEmpty())
        assertTrue(parseCalendarEvents(Json.parseToJsonElement("[]"), zone).isEmpty())
    }

    @Test
    fun `an event in two people's calendars is one event, with both of them`() {
        val owners = mapOf("calendar.joseph" to "person.joseph", "calendar.sam" to "person.sam")
        val merged = mergeEvents(
            listOf(
                event("Dinner", "2026-10-09T19:00", "2026-10-09T21:00", calendar = "calendar.joseph"),
                event("Dinner", "2026-10-09T19:00", "2026-10-09T21:00", calendar = "calendar.sam"),
                event("Football", "2026-10-10T10:00", "2026-10-10T11:00", calendar = "calendar.sam"),
            ),
        ) { owners[it] ?: FAMILY }
        assertEquals(
            listOf(listOf("person.joseph", "person.sam"), listOf("person.sam")),
            merged.map { it.owners },
        )
    }

    @Test
    fun `choosing people shows their events, shared ones included`() {
        val events = listOf(
            shown("Dinner", "2026-10-09T19:00", "2026-10-09T21:00", owners = listOf("person.joseph", "person.sam")),
            shown("Football", "2026-10-10T10:00", "2026-10-10T11:00", owners = listOf("person.sam")),
            shown("Bins out", "2026-10-10T00:00", "2026-10-11T00:00", allDay = true),
        )
        assertEquals(events, events.forOwners(emptySet()))
        assertEquals(listOf("Dinner"), events.forOwners(setOf("person.joseph")).map { it.summary })
        assertEquals(listOf("Dinner", "Bins out"), events.forOwners(setOf("person.joseph", FAMILY)).map { it.summary })
    }

    @Test
    fun `a calendar is its chosen person's while they're in Home Assistant, else the family's`() {
        val chosen = mapOf("calendar.a" to "person.joseph", "calendar.b" to "person.gone")
        val people = setOf("person.joseph")
        assertEquals("person.joseph", calendarOwner("calendar.a", chosen, people))
        assertEquals(FAMILY, calendarOwner("calendar.b", chosen, people))
        assertEquals(FAMILY, calendarOwner("calendar.c", chosen, people))
    }

    @Test
    fun `the list groups by day, all-day first, and drops what's over`() {
        val now = LocalDateTime.parse("2026-10-09T12:00")
        val days = agenda(
            listOf(
                shown("Lunch (over)", "2026-10-09T11:00", "2026-10-09T11:45"),
                shown("Pick-up", "2026-10-09T15:00", "2026-10-09T15:30"),
                shown("Bins out", "2026-10-09T00:00", "2026-10-10T00:00", allDay = true),
                shown("Football", "2026-10-11T10:00", "2026-10-11T11:00"),
                shown("Too far", "2026-10-16T10:00", "2026-10-16T11:00"),
            ),
            now,
        )
        assertEquals(listOf(LocalDate.parse("2026-10-09"), LocalDate.parse("2026-10-11")), days.map { it.date })
        assertEquals(listOf("Bins out", "Pick-up"), days[0].events.map { it.summary })
    }

    @Test
    fun `in the list, something still going from an earlier day shows once, today`() {
        val days = agenda(
            listOf(shown("Holiday", "2026-10-07T00:00", "2026-10-12T00:00", allDay = true)),
            LocalDateTime.parse("2026-10-09T08:00"),
        )
        assertEquals(listOf(LocalDate.parse("2026-10-09")), days.map { it.date })
        assertEquals(LocalDate.parse("2026-10-11"), days[0].events[0].lastDay())
    }

    @Test
    fun `the week has every day, with each event on every day it covers`() {
        val days = week(
            listOf(
                shown("Holiday", "2026-10-07T00:00", "2026-10-12T00:00", allDay = true),
                shown("Breakfast (over)", "2026-10-09T07:00", "2026-10-09T08:00"),
                shown("Party", "2026-10-10T20:00", "2026-10-11T00:00"),
                shown("Overnight", "2026-10-12T22:00", "2026-10-13T06:00"),
                shown("Reminder", "2026-10-14T09:00", "2026-10-14T09:00"),
            ),
            LocalDate.parse("2026-10-09"),
        )
        assertEquals(7, days.size)
        assertEquals(LocalDate.parse("2026-10-15"), days.last().date)
        assertEquals(
            listOf(
                listOf("Holiday", "Breakfast (over)"),
                listOf("Holiday", "Party"),
                listOf("Holiday"),
                listOf("Overnight"),
                listOf("Overnight"),
                listOf("Reminder"),
                emptyList(),
            ),
            days.map { day -> day.events.map { it.summary } },
        )
    }

    @Test
    fun `an event ending at midnight belongs to the day before`() {
        assertEquals(LocalDate.parse("2026-10-09"), shown("Party", "2026-10-09T20:00", "2026-10-10T00:00").lastDay())
        assertEquals(LocalDate.parse("2026-10-10"), shown("Late", "2026-10-09T20:00", "2026-10-10T01:00").lastDay())
    }

    @Test
    fun `calendars are by name, without hidden ones`() {
        fun entity(id: String, name: String) = EntityState(
            id, "off", Json.parseToJsonElement("""{"friendly_name":"$name"}""").jsonObject, lastChanged = null,
        )
        val home = HomeState(
            entities = listOf(
                entity("calendar.work", "Work"),
                entity("calendar.family", "Family"),
                entity("calendar.hidden", "Hidden"),
                entity("light.kitchen", "Kitchen"),
            ).associateBy { it.entityId },
            registry = mapOf(
                "calendar.hidden" to EntityEntry("calendar.hidden", null, null, null, true, false, null),
            ),
        )
        assertEquals(listOf("calendar.family", "calendar.work"), home.calendars().map { it.entityId })
    }
}
