package dev.smartdisplay.app.ui.dashboard

import dev.smartdisplay.app.ha.EntityState
import dev.smartdisplay.app.ha.HomeState
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.OffsetDateTime
import java.time.ZoneId
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** How many days the dashboard's calendar shows, today included. */
const val CALENDAR_DAYS = 7

/** The owner of calendars that aren't any one person's: the whole household's. */
const val FAMILY = "family"

/** Home Assistant's calendars (Google Calendar, Local Calendar and so on), by name, not hidden ones. */
fun HomeState.calendars(): List<EntityState> = entities.values
    .filter { it.domain == "calendar" }
    .filterNot { registry[it.entityId]?.let { entry -> entry.hidden || entry.disabled } == true }
    .sortedBy { it.friendlyName.lowercase() }

/**
 * One event from one calendar, in the display's time zone. An all-day event starts at midnight of its first day;
 * [end] is exclusive, as Home Assistant gives it (an all-day event on the 9th ends at midnight starting the 10th).
 */
data class CalendarEvent(
    val calendarId: String,
    val summary: String,
    val start: LocalDateTime,
    val end: LocalDateTime,
    val allDay: Boolean,
)

/**
 * An event as the dashboard shows it, with whose it is: a person's entity ID or [FAMILY]. The same event in several
 * calendars (an invitation, say) is one event with each calendar's owner.
 */
data class ShownEvent(
    val summary: String,
    val start: LocalDateTime,
    val end: LocalDateTime,
    val allDay: Boolean,
    val owners: List<String>,
)

/** A day and its events. */
data class AgendaDay(val date: LocalDate, val events: List<ShownEvent>)

/**
 * Reads the events from a `calendar.get_events` result, which has a list per calendar. Each `start` and `end` is
 * either a date (all day) or an ISO timestamp; events that can't be read are skipped.
 */
fun parseCalendarEvents(result: JsonElement, zone: ZoneId = ZoneId.systemDefault()): List<CalendarEvent> {
    val response = (result as? JsonObject)?.get("response") as? JsonObject ?: return emptyList()
    return response.flatMap { (calendarId, value) ->
        val events = (value as? JsonObject)?.get("events") as? JsonArray ?: return@flatMap emptyList()
        events.mapNotNull { item ->
            val event = item as? JsonObject ?: return@mapNotNull null
            val start = event.text("start")?.let { readTime(it, zone) } ?: return@mapNotNull null
            val end = event.text("end")?.let { readTime(it, zone) } ?: start
            CalendarEvent(
                calendarId = calendarId,
                summary = event.text("summary")?.trim().orEmpty(),
                start = start.first,
                end = maxOf(end.first, start.first),
                allDay = start.second,
            )
        }
    }
}

/** A start or end: the local time, and whether it was a plain date (an all-day event). */
private fun readTime(text: String, zone: ZoneId): Pair<LocalDateTime, Boolean>? {
    if (text.length == 10) return runCatching { LocalDate.parse(text).atStartOfDay() to true }.getOrNull()
    return runCatching { OffsetDateTime.parse(text).atZoneSameInstant(zone).toLocalDateTime() to false }
        .recoverCatching { LocalDateTime.parse(text) to false }
        .getOrNull()
}

private fun JsonObject.text(key: String): String? = (get(key) as? JsonPrimitive)?.takeIf { it.isString }?.content

/** Joins the same event from several calendars into one, with the owner of each calendar ([ownerOf]). */
fun mergeEvents(events: List<CalendarEvent>, ownerOf: (calendarId: String) -> String): List<ShownEvent> =
    events.groupBy { Triple(it.summary, it.start, it.end) }.values.map { same ->
        val first = same.first()
        ShownEvent(first.summary, first.start, first.end, first.allDay, same.map { ownerOf(it.calendarId) }.distinct())
    }

/** The events of the [selected] owners; all of them when nobody is selected. */
fun List<ShownEvent>.forOwners(selected: Set<String>): List<ShownEvent> =
    if (selected.isEmpty()) this else filter { event -> event.owners.any { it in selected } }

private val dayOrder = compareBy<ShownEvent>({ !it.allDay }, { it.start }, { it.summary.lowercase() })

/**
 * The events not yet over, by day for the [days] days from [now]'s, for a list. One that started on an earlier day
 * and is still going shows once, under today.
 */
fun agenda(events: List<ShownEvent>, now: LocalDateTime, days: Int = CALENDAR_DAYS): List<AgendaDay> {
    val today = now.toLocalDate()
    val last = today.plusDays(days.toLong() - 1)
    return events
        .filter { it.end > now }
        .groupBy { maxOf(it.start.toLocalDate(), today) }
        .filterKeys { it <= last }
        .toSortedMap()
        .map { (date, list) -> AgendaDay(date, list.sortedWith(dayOrder)) }
}

/**
 * Every one of the [days] days from [today], each with every event that falls on it, for a week grid. An event
 * over several days is on each of them; today's include ones already over.
 */
fun week(events: List<ShownEvent>, today: LocalDate, days: Int = CALENDAR_DAYS): List<AgendaDay> =
    (0 until days).map { offset ->
        val date = today.plusDays(offset.toLong())
        val dayStart = date.atStartOfDay()
        val dayEnd = date.plusDays(1).atStartOfDay()
        val onDay = events.filter { event ->
            // A moment-long event (start = end) counts on the day it's at.
            event.start < dayEnd && (event.end > dayStart || event.start == event.end && event.start >= dayStart)
        }
        AgendaDay(date, onDay.sortedWith(dayOrder))
    }

/**
 * The last day an event covers: an all-day event's end is the midnight after it, and a timed one ending exactly at
 * midnight belongs to the day before.
 */
fun ShownEvent.lastDay(): LocalDate {
    val endDate = end.toLocalDate()
    return if (end.toLocalTime() == LocalTime.MIDNIGHT && end > start) endDate.minusDays(1) else endDate
}

/** Whose [calendarId] is: the person chosen for it while they're still in Home Assistant, else the family's. */
fun calendarOwner(calendarId: String, chosen: Map<String, String>, people: Set<String>): String =
    chosen[calendarId]?.takeIf { it in people } ?: FAMILY
