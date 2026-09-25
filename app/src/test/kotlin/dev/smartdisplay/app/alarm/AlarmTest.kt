package dev.smartdisplay.app.alarm

import java.time.DayOfWeek.FRIDAY
import java.time.DayOfWeek.MONDAY
import java.time.DayOfWeek.SATURDAY
import java.time.DayOfWeek.SUNDAY
import java.time.DayOfWeek.WEDNESDAY
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AlarmTest {

    private val zone = ZoneId.of("America/Chicago")

    // 2026-09-24 is a Thursday.
    private fun at(day: Int, hour: Int, minute: Int, month: Int = 9) =
        ZonedDateTime.of(LocalDateTime.of(2026, month, day, hour, minute), zone)

    @Test
    fun `a one-time alarm rings today if it's still ahead, else tomorrow`() {
        val alarm = Alarm(1, 7, 30)
        assertEquals(at(24, 7, 30), alarm.nextTrigger(at(24, 6, 0)))
        assertEquals(at(25, 7, 30), alarm.nextTrigger(at(24, 7, 30)))
        assertEquals(at(25, 7, 30), alarm.nextTrigger(at(24, 22, 0)))
    }

    @Test
    fun `a repeating alarm rings on its next day`() {
        val weekdays = Alarm(1, 6, 45, days = setOf(MONDAY, WEDNESDAY, FRIDAY))
        // Thursday evening: next is Friday.
        assertEquals(at(25, 6, 45), weekdays.nextTrigger(at(24, 20, 0)))
        // Friday after it rang: next is Monday.
        assertEquals(at(28, 6, 45), weekdays.nextTrigger(at(25, 6, 45)))
    }

    @Test
    fun `an alarm on only today's weekday, already past, rings next week`() {
        val thursdays = Alarm(1, 7, 0, days = setOf(java.time.DayOfWeek.THURSDAY))
        assertEquals(at(1, 7, 0, month = 10), thursdays.nextTrigger(at(24, 8, 0)))
    }

    @Test
    fun `an alarm that's off never rings`() {
        assertNull(Alarm(1, 7, 0, enabled = false).nextTrigger(at(24, 6, 0)))
    }

    @Test
    fun `a time skipped by daylight saving rings just after the gap`() {
        // 2027-03-14 at 02:00 Chicago clocks jump to 03:00.
        val alarm = Alarm(1, 2, 30)
        val before = ZonedDateTime.of(LocalDateTime.of(2027, 3, 14, 1, 0), zone)
        val next = alarm.nextTrigger(before)!!
        assertEquals(LocalDateTime.of(2027, 3, 14, 3, 30), next.toLocalDateTime())
    }

    @Test
    fun `the next ring is the soonest alarm or snooze`() {
        val early = Alarm(1, 6, 0)
        val late = Alarm(2, 9, 0)
        val now = at(24, 5, 0)
        assertEquals(early to at(24, 6, 0), nextRing(listOf(late, early), null, now)!!.let { it.alarm to it.at })

        val snooze = Snooze(alarmId = 2, atMillis = at(24, 5, 10).toInstant().toEpochMilli())
        val next = nextRing(listOf(late, early), snooze, now)!!
        assertEquals(2, next.alarm.id)
        assertTrue(next.snoozed)
        assertEquals(at(24, 5, 10).toInstant(), next.at.toInstant())
    }

    @Test
    fun `a past snooze or one for a deleted alarm is ignored`() {
        val alarm = Alarm(1, 6, 0)
        val now = at(24, 5, 0)
        assertEquals(false, nextRing(listOf(alarm), Snooze(1, at(24, 4, 0).toInstant().toEpochMilli()), now)!!.snoozed)
        assertEquals(1, nextRing(listOf(alarm), Snooze(99, at(24, 5, 5).toInstant().toEpochMilli()), now)!!.alarm.id)
        assertNull(nextRing(emptyList(), null, now))
    }

    @Test
    fun `days read naturally`() {
        val words = DayWords("Once", "Every day", "Weekdays", "Weekends")
        val all = java.time.DayOfWeek.entries.toSet()
        assertEquals("Once", daysSummary(emptySet(), Locale.US, words))
        assertEquals("Every day", daysSummary(all, Locale.US, words))
        assertEquals("Weekdays", daysSummary(all - setOf(SATURDAY, SUNDAY), Locale.US, words))
        assertEquals("Weekends", daysSummary(setOf(SATURDAY, SUNDAY), Locale.US, words))
        assertEquals("Sun, Mon, Fri", daysSummary(setOf(FRIDAY, MONDAY, SUNDAY), Locale.US, words))
        assertEquals("Mon, Fri, Sun", daysSummary(setOf(FRIDAY, MONDAY, SUNDAY), Locale.UK, words))
    }
}

class AlarmStorageTest {
    @Test
    fun `alarms survive saving and loading`() {
        val alarms = listOf(
            Alarm(1, 7, 30, setOf(MONDAY, FRIDAY), "Work", enabled = true),
            Alarm(2, 22, 5, emptySet(), "", enabled = false),
        )
        assertEquals(alarms, Alarms.decode(Alarms.encode(alarms)))
    }

    @Test
    fun `damaged or odd saved data is skipped, not fatal`() {
        assertEquals(emptyList<Alarm>(), Alarms.decode("not json"))
        assertEquals(emptyList<Alarm>(), Alarms.decode("""{"id":1}"""))
        assertEquals(
            listOf(Alarm(3, 6, 0)),
            Alarms.decode("""[{"id":3,"hour":6,"minute":0},{"id":4,"hour":25,"minute":0},{"hour":1,"minute":1}]"""),
        )
    }
}
