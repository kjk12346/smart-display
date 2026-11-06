package dev.smartdisplay.app.ui.dashboard

import dev.smartdisplay.app.kiosk.ReminderConfig
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.YearMonth
import java.time.ZoneOffset
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CalendarFeaturesTest {
    private fun at(day: Int, hour: Int, minute: Int = 0) = LocalDateTime.of(2026, 11, day, hour, minute)

    private fun event(name: String, start: LocalDateTime, end: LocalDateTime, allDay: Boolean = false) =
        ShownEvent(name, start, end, allDay, listOf(FAMILY))

    private fun JsonObject.text(key: String) = (this[key] as JsonPrimitive).content

    @Test
    fun `a month is whole weeks from the chosen start day`() {
        val sunday = monthDays(YearMonth.of(2026, 11), DayOfWeek.SUNDAY)
        assertEquals(LocalDate.of(2026, 11, 1), sunday.first())
        assertEquals(LocalDate.of(2026, 12, 5), sunday.last())
        assertEquals(35, sunday.size)

        val monday = monthDays(YearMonth.of(2026, 11), DayOfWeek.MONDAY)
        assertEquals(LocalDate.of(2026, 10, 26), monday.first())
        assertEquals(LocalDate.of(2026, 12, 6), monday.last())
        assertEquals(DayOfWeek.MONDAY, monday.first().dayOfWeek)
        assertEquals(0, monday.size % 7)
    }

    @Test
    fun `events that overlap share the width and others have it all`() {
        val a = event("a", at(6, 9), at(6, 10))
        val b = event("b", at(6, 9, 30), at(6, 11))
        val c = event("c", at(6, 12), at(6, 13))
        val laid = timeline(listOf(c, b, a)).associateBy { it.event.summary }
        assertEquals(0, laid.getValue("a").lane)
        assertEquals(1, laid.getValue("b").lane)
        assertEquals(2, laid.getValue("a").lanes)
        assertEquals(2, laid.getValue("b").lanes)
        assertEquals(1, laid.getValue("c").lanes)
    }

    @Test
    fun `a free lane is reused within an overlapping group`() {
        val long = event("long", at(6, 9), at(6, 12))
        val first = event("first", at(6, 9), at(6, 10))
        val second = event("second", at(6, 10), at(6, 11))
        val laid = timeline(listOf(long, first, second)).associateBy { it.event.summary }
        assertEquals(laid.getValue("first").lane, laid.getValue("second").lane)
        assertEquals(2, laid.getValue("long").lanes)
    }

    @Test
    fun `a very short event still takes room in its lane`() {
        val brief = event("brief", at(6, 9), at(6, 9, 5))
        val next = event("next", at(6, 9, 10), at(6, 10))
        assertEquals(2, timeline(listOf(brief, next)).first().lanes)
    }

    @Test
    fun `the timeline covers the usual hours and any further events`() {
        val date = LocalDate.of(2026, 11, 6)
        assertEquals(7..21, timelineHours(emptyList(), date))
        val early = event("early", at(6, 5, 30), at(6, 6))
        val late = event("late", at(6, 22), at(6, 23, 15))
        assertEquals(5..24, timelineHours(listOf(early, late), date))
        val overnight = event("overnight", at(5, 22), at(7, 1))
        assertEquals(0..24, timelineHours(listOf(overnight), date))
    }

    @Test
    fun `a reminder is due once, between checks`() {
        val config = ReminderConfig(minutesBefore = 10)
        val practice = event("Practice", at(6, 16), at(6, 17))
        assertTrue(dueReminders(listOf(practice), config, at(6, 15, 48), at(6, 15, 49)).isEmpty())
        assertEquals(listOf(practice), dueReminders(listOf(practice), config, at(6, 15, 49), at(6, 15, 50)))
        assertTrue(dueReminders(listOf(practice), config, at(6, 15, 50), at(6, 15, 51)).isEmpty())
    }

    @Test
    fun `no reminders when off and all-day ones only if asked`() {
        val allDay = event("Holiday", at(6, 0), at(7, 0), allDay = true)
        assertNull(reminderTime(allDay, ReminderConfig(minutesBefore = null, allDay = true)))
        assertNull(reminderTime(allDay, ReminderConfig(minutesBefore = 10, allDay = false)))
        assertEquals(at(6, 9), reminderTime(allDay, ReminderConfig(minutesBefore = 10, allDay = true)))
    }

    @Test
    fun `a new timed event is sent with its offset`() {
        val draft = EventDraft("  Dentist ", at(6, 9), at(6, 10), allDay = false, location = " ", rrule = "FREQ=WEEKLY")
        val fields = createEventFields("calendar.family", draft, ZoneOffset.ofHours(-5))
        assertEquals("calendar.family", fields.text("entity_id"))
        val sent = fields.getValue("event").jsonObject
        assertEquals("Dentist", sent.text("summary"))
        assertEquals("2026-11-06T09:00:00-05:00", sent.text("dtstart"))
        assertEquals("2026-11-06T10:00:00-05:00", sent.text("dtend"))
        assertEquals("FREQ=WEEKLY", sent.text("rrule"))
        assertFalse("location" in sent)
    }

    @Test
    fun `a new all-day event is sent as dates, ending the day after`() {
        val draft = EventDraft("Trip", at(6, 0), at(9, 0), allDay = true)
        val sent = createEventFields("calendar.family", draft).getValue("event").jsonObject
        assertEquals("2026-11-06", sent.text("dtstart"))
        assertEquals("2026-11-09", sent.text("dtend"))
    }

    @Test
    fun `deleting says which times of a repeating event`() {
        val one = EventSource("calendar.family", "uid-1")
        assertEquals(setOf("entity_id", "uid"), deleteEventFields(one, thisAndLater = true).keys)

        val repeating = one.copy(recurrenceId = "20261106")
        assertFalse("recurrence_range" in deleteEventFields(repeating, thisAndLater = false))
        val later = deleteEventFields(repeating, thisAndLater = true)
        assertEquals("20261106", later.text("recurrence_id"))
        assertEquals("THISANDFUTURE", later.text("recurrence_range"))
    }

    @Test
    fun `an event can be changed only where its calendar allows`() {
        val merged = mergeEvents(
            listOf(
                CalendarEvent("calendar.holidays", "Shared", at(6, 9), at(6, 10), false, uid = "h"),
                CalendarEvent("calendar.family", "Shared", at(6, 9), at(6, 10), false, uid = "f"),
            ),
            { FAMILY },
        ).single()
        val features = mapOf("calendar.holidays" to 0, "calendar.family" to (CALENDAR_CREATE or CALENDAR_DELETE))
        assertEquals("calendar.family", merged.changeableSource { features.getValue(it) }?.calendarId)
        assertNull(merged.changeableSource { 0 })
    }

    @Test
    fun `an event without an ID can't be changed`() {
        val merged = mergeEvents(
            listOf(CalendarEvent("calendar.family", "No id", at(6, 9), at(6, 10), false)),
            { FAMILY },
        ).single()
        assertNull(merged.changeableSource { CALENDAR_CREATE or CALENDAR_DELETE or CALENDAR_UPDATE })
    }

    @Test
    fun `editing needs update, or both create and delete`() {
        assertTrue(canEdit(CALENDAR_UPDATE))
        assertTrue(canEdit(CALENDAR_CREATE or CALENDAR_DELETE))
        assertFalse(canEdit(CALENDAR_DELETE))
        assertFalse(canEdit(0))
    }

    @Test
    fun `to-do items are read with what's left first`() {
        val result = Json.parseToJsonElement(
            """{"items":[
                {"uid":"1","summary":"Milk","status":"completed"},
                {"uid":"2","summary":"Eggs","status":"needs_action"},
                {"summary":"No id","status":"needs_action"}
            ]}""",
        )
        val items = parseTodoItems(result)
        assertEquals(listOf("Milk", "Eggs"), items.map { it.summary })
        assertEquals(listOf("Eggs", "Milk"), items.toShow().map { it.summary })
        assertTrue(parseTodoItems(JsonObject(emptyMap())).isEmpty())
    }
}
