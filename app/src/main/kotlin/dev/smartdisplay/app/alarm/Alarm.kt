package dev.smartdisplay.app.alarm

import java.time.DayOfWeek
import java.time.LocalTime
import java.time.ZonedDateTime
import java.time.format.TextStyle
import java.time.temporal.WeekFields
import java.util.Locale

/** An alarm set on this display. No [days] means it rings once, then turns itself off. */
data class Alarm(
    val id: Int,
    val hour: Int,
    val minute: Int,
    val days: Set<DayOfWeek> = emptySet(),
    val label: String = "",
    val enabled: Boolean = true,
) {
    val repeats: Boolean get() = days.isNotEmpty()
    val time: LocalTime get() = LocalTime.of(hour, minute)
}

/** A snoozed alarm, ringing again at [atMillis]. */
data class Snooze(val alarmId: Int, val atMillis: Long)

/**
 * When [this] alarm next rings strictly after [after], in [after]'s time zone, or null if it's off. A time skipped
 * by a daylight-saving change rings at the first moment after the gap, as clocks do.
 */
fun Alarm.nextTrigger(after: ZonedDateTime): ZonedDateTime? {
    if (!enabled) return null
    for (offset in 0L..7L) {
        val date = after.toLocalDate().plusDays(offset)
        if (repeats && date.dayOfWeek !in days) continue
        val candidate = ZonedDateTime.of(date, time, after.zone)
        if (candidate.isAfter(after)) return candidate
    }
    return null
}

/** What rings next, if anything: an alarm at a time, possibly a snooze. */
data class NextRing(val alarm: Alarm, val at: ZonedDateTime, val snoozed: Boolean)

/** The soonest of [alarms] (and [snooze]) after [now]. */
fun nextRing(alarms: List<Alarm>, snooze: Snooze?, now: ZonedDateTime): NextRing? {
    val scheduled = alarms.mapNotNull { alarm -> alarm.nextTrigger(now)?.let { NextRing(alarm, it, snoozed = false) } }
    val snoozed = snooze?.let { s ->
        val alarm = alarms.firstOrNull { it.id == s.alarmId } ?: return@let null
        val at = ZonedDateTime.ofInstant(java.time.Instant.ofEpochMilli(s.atMillis), now.zone)
        if (at.isAfter(now)) NextRing(alarm, at, snoozed = true) else null
    }
    return (scheduled + listOfNotNull(snoozed)).minByOrNull { it.at.toInstant() }
}

/**
 * The days an alarm repeats on, for a list: "Once", "Every day", "Weekdays", "Weekends", or the days in the
 * locale's week order ("Mon, Wed, Fri").
 */
fun daysSummary(days: Set<DayOfWeek>, locale: Locale, words: DayWords): String {
    val weekend = setOf(DayOfWeek.SATURDAY, DayOfWeek.SUNDAY)
    return when (days) {
        emptySet<DayOfWeek>() -> words.once
        DayOfWeek.entries.toSet() -> words.everyDay
        DayOfWeek.entries.toSet() - weekend -> words.weekdays
        weekend -> words.weekends
        else -> weekOrder(locale).filter { it in days }.joinToString(", ") {
            it.getDisplayName(TextStyle.SHORT, locale)
        }
    }
}

/** Translated words for [daysSummary]. */
data class DayWords(val once: String, val everyDay: String, val weekdays: String, val weekends: String)

/** The days of the week starting from the locale's first day (Sunday in the US, Monday in much of Europe). */
fun weekOrder(locale: Locale): List<DayOfWeek> {
    val first = WeekFields.of(locale).firstDayOfWeek
    return List(7) { first.plus(it.toLong()) }
}
