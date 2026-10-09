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
 * [uid] identifies the event (every time, for a repeating one) and [recurrenceId] this time of a repeating one; some
 * calendars give neither.
 */
data class CalendarEvent(
    val calendarId: String,
    val summary: String,
    val start: LocalDateTime,
    val end: LocalDateTime,
    val allDay: Boolean,
    val uid: String? = null,
    val recurrenceId: String? = null,
    val description: String? = null,
    val location: String? = null,
) {
    /** The key an assignment to people is saved under for every time this event happens. */
    val seriesKey: String get() = uid?.let { "uid:$it" } ?: "at:$summary|$start"

    /** The key for just this time; the same as [seriesKey] for an event that doesn't repeat. */
    val instanceKey: String get() = if (uid != null && recurrenceId != null) "$seriesKey#$recurrenceId" else seriesKey
}

/**
 * An event as the dashboard shows it, with whose it is: people's entity IDs, or [FAMILY]. The same event in several
 * calendars (an invitation, say) is one event, from each of [calendarIds]. [owners] are the people it's been
 * assigned to on this display ([assigned]), or else its calendars' owners.
 */
data class ShownEvent(
    val summary: String,
    val start: LocalDateTime,
    val end: LocalDateTime,
    val allDay: Boolean,
    val owners: List<String>,
    val calendarIds: List<String> = emptyList(),
    val seriesKey: String = "at:$summary|$start",
    val instanceKey: String = seriesKey,
    val description: String? = null,
    val location: String? = null,
    val assigned: Assignment = Assignment.None,
) {
    /** One time of a repeating event, so it can be assigned this time or every time. */
    val repeats: Boolean get() = instanceKey != seriesKey
}

/** Whether an event's people were chosen on this display, and for which times. */
enum class Assignment { None, ThisTime, EveryTime }

/** A day and its events. */
data class AgendaDay(val date: LocalDate, val events: List<ShownEvent>)

/**
 * Reads [calendarId]'s events from Home Assistant's calendar REST API (`/api/calendars/<id>`), a list where each
 * `start` and `end` has either a `date` (all day) or a `dateTime`. Events that can't be read are skipped.
 */
fun parseCalendarApiEvents(
    calendarId: String,
    result: JsonElement,
    zone: ZoneId = ZoneId.systemDefault(),
): List<CalendarEvent> {
    val events = result as? JsonArray ?: return emptyList()
    return events.mapNotNull { item ->
        val event = item as? JsonObject ?: return@mapNotNull null
        val start = (event["start"] as? JsonObject)?.let { readApiTime(it, zone) } ?: return@mapNotNull null
        val end = (event["end"] as? JsonObject)?.let { readApiTime(it, zone) } ?: start
        CalendarEvent(
            calendarId = calendarId,
            summary = event.text("summary")?.trim().orEmpty(),
            start = start.first,
            end = maxOf(end.first, start.first),
            allDay = start.second,
            uid = event.text("uid")?.takeIf { it.isNotBlank() },
            recurrenceId = event.text("recurrence_id")?.takeIf { it.isNotBlank() },
            description = event.text("description")?.trim()?.takeIf { it.isNotEmpty() },
            location = event.text("location")?.trim()?.takeIf { it.isNotEmpty() },
        )
    }
}

private fun readApiTime(time: JsonObject, zone: ZoneId): Pair<LocalDateTime, Boolean>? =
    (time.text("dateTime") ?: time.text("date"))?.let { readTime(it, zone) }

/** A start or end: the local time, and whether it was a plain date (an all-day event). */
private fun readTime(text: String, zone: ZoneId): Pair<LocalDateTime, Boolean>? {
    if (text.length == 10) return runCatching { LocalDate.parse(text).atStartOfDay() to true }.getOrNull()
    return runCatching { OffsetDateTime.parse(text).atZoneSameInstant(zone).toLocalDateTime() to false }
        .recoverCatching { LocalDateTime.parse(text) to false }
        .getOrNull()
}

private fun JsonObject.text(key: String): String? = (get(key) as? JsonPrimitive)?.takeIf { it.isString }?.content

/**
 * Joins the same event from several calendars into one. Its people are the ones it was assigned to on this display
 * ([assignments], by [CalendarEvent.instanceKey], else [CalendarEvent.seriesKey]), else each calendar's owner
 * ([ownerOf]). Assigned people no longer in Home Assistant ([people]) are dropped.
 */
fun mergeEvents(
    events: List<CalendarEvent>,
    ownerOf: (calendarId: String) -> String,
    assignments: Map<String, List<String>> = emptyMap(),
    people: Set<String>? = null,
): List<ShownEvent> =
    events.groupBy { Triple(it.summary, it.start, it.end) }.values.map { same ->
        val first = same.first()
        fun chosen(key: String) =
            assignments[key]?.filter { people == null || it in people }?.takeIf { it.isNotEmpty() }
        val thisTime = chosen(first.instanceKey).takeIf { first.instanceKey != first.seriesKey }
        val everyTime = chosen(first.seriesKey)
        ShownEvent(
            summary = first.summary,
            start = first.start,
            end = first.end,
            allDay = first.allDay,
            owners = thisTime ?: everyTime ?: same.map { ownerOf(it.calendarId) }.distinct(),
            calendarIds = same.map { it.calendarId }.distinct(),
            seriesKey = first.seriesKey,
            instanceKey = first.instanceKey,
            description = same.firstNotNullOfOrNull { it.description },
            location = same.firstNotNullOfOrNull { it.location },
            assigned = when {
                thisTime != null -> Assignment.ThisTime
                everyTime != null -> Assignment.EveryTime
                else -> Assignment.None
            },
        )
    }

/**
 * [assignments] with [event] given to [people]: this time only, or [everyTime] it happens. No people goes back to
 * its calendar's owner (for this time, or every time).
 */
fun assign(
    assignments: Map<String, List<String>>,
    event: ShownEvent,
    people: List<String>,
    everyTime: Boolean,
): Map<String, List<String>> {
    // Choosing for every time also clears a choice made for just this time, so the new one shows.
    val key = if (everyTime) event.seriesKey else event.instanceKey
    val cleared = if (everyTime) assignments - event.instanceKey else assignments
    return if (people.isEmpty()) cleared - key else cleared + (key to people.distinct())
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
