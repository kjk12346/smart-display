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
    fun `reads events from the REST API, with their IDs, in the display's zone`() {
        val result = Json.parseToJsonElement(
            """
            [
              {"start": {"dateTime": "2026-10-09T17:30:00+00:00"}, "end": {"dateTime": "2026-10-09T18:30:00+00:00"},
               "summary": " Dentist ", "uid": "abc@google.com", "recurrence_id": null,
               "description": "Bring the form", "location": ""},
              {"start": {"date": "2026-10-10"}, "end": {"date": "2026-10-11"}, "summary": "Bins out",
               "uid": "bins@google.com", "recurrence_id": "20261010"},
              {"summary": "No start"}
            ]
            """,
        )
        assertEquals(
            listOf(
                CalendarEvent(
                    "calendar.family", "Dentist", LocalDateTime.parse("2026-10-09T10:30"),
                    LocalDateTime.parse("2026-10-09T11:30"), allDay = false, uid = "abc@google.com",
                    description = "Bring the form",
                ),
                CalendarEvent(
                    "calendar.family", "Bins out", LocalDateTime.parse("2026-10-10T00:00"),
                    LocalDateTime.parse("2026-10-11T00:00"), allDay = true, uid = "bins@google.com",
                    recurrenceId = "20261010",
                ),
            ),
            parseCalendarApiEvents("calendar.family", result, zone),
        )
    }

    @Test
    fun `an unexpected answer gives no events`() {
        assertTrue(parseCalendarApiEvents("calendar.a", Json.parseToJsonElement("{}"), zone).isEmpty())
    }

    @Test
    fun `events are keyed by ID, and by time for one time of a repeating event`() {
        val once = event("Dentist", "2026-10-09T10:00", "2026-10-09T11:00").copy(uid = "abc")
        assertEquals("uid:abc", once.seriesKey)
        assertEquals("uid:abc", once.instanceKey)
        val weekly = once.copy(recurrenceId = "20261009")
        assertEquals("uid:abc", weekly.seriesKey)
        assertEquals("uid:abc#20261009", weekly.instanceKey)
        val noId = event("Dentist", "2026-10-09T10:00", "2026-10-09T11:00")
        assertEquals("at:Dentist|2026-10-09T10:00", noId.seriesKey)
    }

    @Test
    fun `an event given to people shows as theirs, this time over every time`() {
        val football = event("Football", "2026-10-10T10:00", "2026-10-10T11:00").copy(uid = "f", recurrenceId = "1010")
        val people = setOf("person.sam", "person.alex")
        fun ownersWith(assignments: Map<String, List<String>>) =
            mergeEvents(listOf(football), { FAMILY }, assignments, people).single()

        assertEquals(listOf(FAMILY), ownersWith(emptyMap()).owners)
        assertEquals(Assignment.None, ownersWith(emptyMap()).assigned)

        val everyWeek = mapOf("uid:f" to listOf("person.sam"))
        assertEquals(listOf("person.sam"), ownersWith(everyWeek).owners)
        assertEquals(Assignment.EveryTime, ownersWith(everyWeek).assigned)

        val thisWeekToo = everyWeek + ("uid:f#1010" to listOf("person.alex"))
        assertEquals(listOf("person.alex"), ownersWith(thisWeekToo).owners)
        assertEquals(Assignment.ThisTime, ownersWith(thisWeekToo).assigned)

        // Someone no longer in Home Assistant is dropped; with no one left, it's the calendar's again.
        assertEquals(listOf(FAMILY), ownersWith(mapOf("uid:f" to listOf("person.gone"))).owners)
    }

    @Test
    fun `assigning saves this time or every time, and no one clears it`() {
        val shown = mergeEvents(
            listOf(event("Football", "2026-10-10T10:00", "2026-10-10T11:00").copy(uid = "f", recurrenceId = "1010")),
            { FAMILY },
        ).single()
        val thisTime = assign(emptyMap(), shown, listOf("person.sam"), everyTime = false)
        assertEquals(mapOf("uid:f#1010" to listOf("person.sam")), thisTime)
        // Every time replaces a choice made for this time.
        val everyTime = assign(thisTime, shown, listOf("person.alex"), everyTime = true)
        assertEquals(mapOf("uid:f" to listOf("person.alex")), everyTime)
        assertEquals(emptyMap<String, List<String>>(), assign(everyTime, shown, emptyList(), everyTime = true))
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
            ownerOf = { owners[it] ?: FAMILY },
        )
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
                shown("Too far", "2026-10-23T10:00", "2026-10-23T11:00"),
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
    fun `the list looks two weeks ahead`() {
        val now = LocalDateTime.parse("2026-10-28T09:00")
        val days = agenda(
            listOf(
                shown("Soon", "2026-11-10T10:00", "2026-11-10T11:00"),
                shown("Too far", "2026-11-11T10:00", "2026-11-11T11:00"),
            ),
            now,
        )
        assertEquals(listOf("Soon"), days.flatMap { day -> day.events.map { it.summary } })
    }

    @Test
    fun `agenda columns split the days in order, about evenly, and never leave a column empty`() {
        val start = LocalDate.parse("2026-10-28")
        fun day(offset: Long, events: Int) = AgendaDay(
            start.plusDays(offset),
            List(events) { shown("E$it", "2026-10-28T10:00", "2026-10-28T11:00") },
        )
        val days = listOf(day(0, 3), day(1, 1), day(2, 1), day(3, 3), day(4, 1), day(5, 1))
        val runs = agendaColumns(days, 2)
        assertEquals(days, runs.flatten())
        assertEquals(listOf(3, 3), runs.map { it.size })
        // Two days in three columns: two columns, a day each.
        assertEquals(listOf(1, 1), agendaColumns(days.take(2), 3).map { it.size })
        assertEquals(listOf(days), agendaColumns(days, 1))
        assertEquals(listOf(emptyList<AgendaDay>()), agendaColumns(emptyList(), 3))
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
