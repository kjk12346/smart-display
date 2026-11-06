package dev.smartdisplay.app.ui.dashboard

import dev.smartdisplay.app.ha.EntityState
import dev.smartdisplay.app.ha.HomeState
import dev.smartdisplay.app.kiosk.ReminderConfig
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.OffsetDateTime
import java.time.YearMonth
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.TemporalAdjusters
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put

/** How many days the dashboard's calendar shows, today included. */
const val CALENDAR_DAYS = 7

/** The agenda looks further ahead than the week: the events are fetched for this many days. */
const val AGENDA_DAYS = 14

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
    /** How it repeats, where the calendar says (Google's times of a repeating event don't). */
    val rrule: String? = null,
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
    /** Where it can be changed: its ID on each calendar that has one. */
    val sources: List<EventSource> = emptyList(),
) {
    /** One time of a repeating event, so it can be assigned this time or every time. */
    val repeats: Boolean get() = instanceKey != seriesKey
}

/** An event on one calendar, as that calendar knows it. */
data class EventSource(
    val calendarId: String,
    val uid: String,
    val recurrenceId: String? = null,
    val rrule: String? = null,
)

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
            rrule = event.text("rrule")?.takeIf { it.isNotBlank() },
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
            sources = same.mapNotNull { event ->
                event.uid?.let { EventSource(event.calendarId, it, event.recurrenceId, event.rrule) }
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
fun agenda(events: List<ShownEvent>, now: LocalDateTime, days: Int = AGENDA_DAYS): List<AgendaDay> {
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
 * [days] split into at most [columns] runs of days, in order, for an agenda across a wide card: each run about as
 * long as the others, counting a day's heading and each of its events as a line. Fewer runs if there aren't enough
 * days.
 */
fun agendaColumns(days: List<AgendaDay>, columns: Int): List<List<AgendaDay>> {
    if (days.isEmpty() || columns <= 1) return listOf(days)
    val lines = days.map { 1 + it.events.size }
    val target = lines.sum().toDouble() / columns
    val runs = mutableListOf<MutableList<AgendaDay>>(mutableListOf())
    var filled = 0
    days.forEachIndexed { index, day ->
        val run = runs.last()
        // Start the next run once this one is full, but keep at least a day for each run still to come.
        val daysLeft = days.size - index
        val runsLeft = columns - runs.size
        val full = filled + lines[index] / 2.0 > target * runs.size
        if (run.isNotEmpty() && runsLeft > 0 && (full || daysLeft <= runsLeft)) {
            runs += mutableListOf(day)
        } else {
            run += day
        }
        filled += lines[index]
    }
    return runs
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

/** What Home Assistant says a calendar can do (`supported_features`). */
const val CALENDAR_CREATE = 1
const val CALENDAR_DELETE = 2
const val CALENDAR_UPDATE = 4

/** A calendar's `supported_features`; 0 for one that's read-only, or gone. */
fun HomeState.calendarFeatures(calendarId: String): Int =
    (entities[calendarId]?.attributes?.get("supported_features") as? JsonPrimitive)?.intOrNull ?: 0

/**
 * The calendar an existing event can be changed on, given what each can do ([featuresOf]): the first that can delete
 * it, or update it. Null when it's read-only everywhere, or has no ID to change it by.
 */
fun ShownEvent.changeableSource(featuresOf: (calendarId: String) -> Int): EventSource? =
    sources.firstOrNull { featuresOf(it.calendarId) and (CALENDAR_DELETE or CALENDAR_UPDATE) != 0 }

/**
 * Whether a new version of an event can be saved on a calendar with these [features]: by updating it, or else by
 * adding the new one and deleting the old.
 */
fun canEdit(features: Int): Boolean =
    features and CALENDAR_UPDATE != 0 || (features and CALENDAR_CREATE != 0 && features and CALENDAR_DELETE != 0)

/** An event as typed on the display. [end] is exclusive: an all-day event ends at midnight after its last day. */
data class EventDraft(
    val summary: String,
    val start: LocalDateTime,
    val end: LocalDateTime,
    val allDay: Boolean,
    val location: String? = null,
    val description: String? = null,
    val rrule: String? = null,
)

/** How a new event can repeat: its iCalendar rule. */
enum class EventRepeat(val rrule: String?) {
    Never(null),
    Daily("FREQ=DAILY"),
    Weekly("FREQ=WEEKLY"),
    Monthly("FREQ=MONTHLY"),
    Yearly("FREQ=YEARLY"),
}

/** Asks for this and every later time of a repeating event, rather than just the one. */
private const val THIS_AND_FUTURE = "THISANDFUTURE"

private fun eventJson(draft: EventDraft, zone: ZoneId) = buildJsonObject {
    put("summary", draft.summary.trim())
    if (draft.allDay) {
        put("dtstart", draft.start.toLocalDate().toString())
        put("dtend", draft.end.toLocalDate().toString())
    } else {
        put("dtstart", DateTimeFormatter.ISO_OFFSET_DATE_TIME.format(draft.start.atZone(zone)))
        put("dtend", DateTimeFormatter.ISO_OFFSET_DATE_TIME.format(draft.end.atZone(zone)))
    }
    draft.location?.trim()?.takeIf { it.isNotEmpty() }?.let { put("location", it) }
    draft.description?.trim()?.takeIf { it.isNotEmpty() }?.let { put("description", it) }
    draft.rrule?.let { put("rrule", it) }
}

/** The fields of Home Assistant's `calendar/event/create` command. */
fun createEventFields(calendarId: String, draft: EventDraft, zone: ZoneId = ZoneId.systemDefault()): JsonObject =
    buildJsonObject {
        put("entity_id", calendarId)
        put("event", eventJson(draft, zone))
    }

/** The fields of `calendar/event/delete`: this time of a repeating event, or it and every later time. */
fun deleteEventFields(source: EventSource, thisAndLater: Boolean): JsonObject = buildJsonObject {
    put("entity_id", source.calendarId)
    put("uid", source.uid)
    source.recurrenceId?.let { put("recurrence_id", it) }
    if (thisAndLater && source.recurrenceId != null) put("recurrence_range", THIS_AND_FUTURE)
}

/** The fields of `calendar/event/update`, for calendars that can. */
fun updateEventFields(
    source: EventSource,
    draft: EventDraft,
    thisAndLater: Boolean,
    zone: ZoneId = ZoneId.systemDefault(),
): JsonObject = buildJsonObject {
    put("entity_id", source.calendarId)
    put("uid", source.uid)
    source.recurrenceId?.let { put("recurrence_id", it) }
    if (thisAndLater && source.recurrenceId != null) put("recurrence_range", THIS_AND_FUTURE)
    put("event", eventJson(draft, zone))
}

/**
 * The days a month view shows: whole weeks starting on [weekStart], from the week [month] begins in to the week it
 * ends in.
 */
fun monthDays(month: YearMonth, weekStart: DayOfWeek): List<LocalDate> {
    val first = month.atDay(1).with(TemporalAdjusters.previousOrSame(weekStart))
    val last = month.atEndOfMonth().with(TemporalAdjusters.nextOrSame(weekStart.plus(6)))
    return generateSequence(first) { it.plusDays(1) }.takeWhile { it <= last }.toList()
}

/** One event in a day's timeline: which of the [lanes] side by side it takes, so overlapping ones don't cover each other. */
data class TimelineEvent(val event: ShownEvent, val lane: Int, val lanes: Int)

private fun ShownEvent.timelineEnd(): LocalDateTime = maxOf(end, start.plusMinutes(MIN_TIMELINE_MINUTES))

/**
 * [events] (timed ones on one person's day) laid out in lanes: each in the first free lane, and everything that
 * overlaps sharing the width evenly.
 */
fun timeline(events: List<ShownEvent>): List<TimelineEvent> {
    val result = mutableListOf<TimelineEvent>()
    var group = mutableListOf<Pair<ShownEvent, Int>>()
    var groupEnd: LocalDateTime? = null
    fun finish() {
        val lanes = (group.maxOfOrNull { it.second } ?: 0) + 1
        group.forEach { (event, lane) -> result += TimelineEvent(event, lane, lanes) }
        group = mutableListOf()
        groupEnd = null
    }
    events.sortedWith(compareBy({ it.start }, { it.end })).forEach { event ->
        if (groupEnd?.let { event.start >= it } == true) finish()
        val free = (0..group.size).first { lane ->
            group.none { (other, used) -> used == lane && other.timelineEnd() > event.start }
        }
        group += event to free
        groupEnd = groupEnd?.let { maxOf(it, event.timelineEnd()) } ?: event.timelineEnd()
    }
    finish()
    return result
}

/** A shorter event still takes this much room in a timeline, so it can be read and tapped. */
private const val MIN_TIMELINE_MINUTES = 30L

/**
 * The hours a day's timeline covers: at least [FIRST_HOUR] to [LAST_HOUR], further if [timed] events on [date] need
 * it. The end is exclusive.
 */
fun timelineHours(timed: List<ShownEvent>, date: LocalDate): IntRange {
    val dayStart = date.atStartOfDay()
    val nextDay = date.plusDays(1).atStartOfDay()
    val first = timed.minOfOrNull { if (it.start < dayStart) 0 else it.start.hour } ?: FIRST_HOUR
    val last = timed.maxOfOrNull { event ->
        when {
            event.end >= nextDay -> 24
            event.end.minute > 0 -> event.end.hour + 1
            else -> event.end.hour
        }
    } ?: LAST_HOUR
    return minOf(first, FIRST_HOUR)..maxOf(last, LAST_HOUR)
}

private const val FIRST_HOUR = 7
private const val LAST_HOUR = 21

/** When an event reminds; null if it doesn't. All-day events do at [ALL_DAY_REMINDER] on their first day. */
fun reminderTime(event: ShownEvent, reminders: ReminderConfig): LocalDateTime? {
    val minutes = reminders.minutesBefore ?: return null
    return when {
        !event.allDay -> event.start.minusMinutes(minutes.toLong())
        reminders.allDay -> event.start.toLocalDate().atTime(ALL_DAY_REMINDER)
        else -> null
    }
}

/** The [events] whose reminder falls after [since], up to and including [now], soonest first. */
fun dueReminders(
    events: List<ShownEvent>,
    reminders: ReminderConfig,
    since: LocalDateTime,
    now: LocalDateTime,
): List<ShownEvent> = events
    .filter { event -> reminderTime(event, reminders)?.let { it > since && it <= now } == true }
    .sortedBy { it.start }

val ALL_DAY_REMINDER: LocalTime = LocalTime.of(9, 0)
