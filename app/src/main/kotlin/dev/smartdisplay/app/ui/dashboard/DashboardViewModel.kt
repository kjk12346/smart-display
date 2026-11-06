package dev.smartdisplay.app.ui.dashboard

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.smartdisplay.app.R
import dev.smartdisplay.app.SmartDisplayApp
import dev.smartdisplay.app.kiosk.ReminderConfig
import dev.smartdisplay.app.ui.ambient.isQuietHours
import java.io.IOException
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZonedDateTime
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** How a button's run went, shown on it for a moment. */
enum class RunState { Running, Done, Failed }

/** A reminder on screen: the event, and when it came up. */
data class ReminderNotice(val event: ShownEvent, val shownAt: LocalDateTime)

/**
 * Runs the kitchen dashboard's buttons (scenes, scripts, automations, Home and Away) and keeps its calendar, which
 * reminders and the morning summary read too, whichever screen is showing.
 */
class DashboardViewModel(application: Application) : AndroidViewModel(application) {
    private val home = (application as SmartDisplayApp).home
    private val kiosk = (application as SmartDisplayApp).kiosk

    private val _runs = MutableStateFlow<Map<String, RunState>>(emptyMap())

    /** Buttons running now or just finished, by entity ID. */
    val runs: StateFlow<Map<String, RunState>> = _runs.asStateFlow()

    private val _events = MutableStateFlow<Pair<List<String>, List<CalendarEvent>>?>(null)

    /** Events from the start of today and any further days asked for, and the calendars they're from. */
    val events: StateFlow<Pair<List<String>, List<CalendarEvent>>?> = _events.asStateFlow()

    private val _selectedOwners = MutableStateFlow<Set<String>>(emptySet())

    /** Whose events the calendar shows: people's entity IDs and [FAMILY]; empty for everyone's. */
    val selectedOwners: StateFlow<Set<String>> = _selectedOwners.asStateFlow()

    /** Shows or stops showing [owner]'s events. Selecting every one of [allOwners] goes back to everyone. */
    fun toggleOwner(owner: String, allOwners: Collection<String>) {
        _selectedOwners.update { selected ->
            val next = if (owner in selected) selected - owner else selected + owner
            if (next.containsAll(allOwners)) emptySet() else next
        }
    }

    fun showEveryone() {
        _selectedOwners.value = emptySet()
    }

    private val range = MutableStateFlow<Pair<LocalDate, LocalDate>?>(null)
    private var fetchedCalendars: List<String> = emptyList()

    /** Also fetches the days from [start] to [end], for a month or another day on screen; null for just the usual. */
    fun showRange(start: LocalDate?, end: LocalDate?) {
        range.value = if (start != null && end != null) start to end else null
    }

    /**
     * Fetches the events from [calendarIds] now and then every [CALENDAR_REFRESH_MS], until cancelled, and straight
     * away when other days are asked for. Run it only while connected and on screen.
     */
    suspend fun keepCalendarFresh(calendarIds: List<String>) {
        fetchedCalendars = calendarIds
        if (calendarIds.isEmpty()) return
        range.collectLatest { days ->
            while (true) {
                val fetched = fetchEvents(calendarIds, days) != null
                delay(if (fetched) CALENDAR_REFRESH_MS else RETRY_MS)
            }
        }
    }

    private suspend fun fetchEvents(
        calendarIds: List<String>,
        days: Pair<LocalDate, LocalDate>?,
    ): List<CalendarEvent>? = try {
        val now = ZonedDateTime.now()
        val today = LocalDate.now(now.zone)
        val first = days?.first?.takeIf { it < today } ?: today
        val last = maxOf(today.plusDays(AGENDA_DAYS.toLong()), days?.second?.plusDays(1) ?: today)
        val start = first.atStartOfDay(now.zone)
        val end = last.atStartOfDay(now.zone)
        // Through the REST API, one request per calendar, for the events' IDs (see calendarEvents).
        val events = coroutineScope {
            calendarIds.map { id ->
                async {
                    val result = home.calendarEvents(
                        id,
                        start.toOffsetDateTime().toString(),
                        end.toOffsetDateTime().toString(),
                    )
                    parseCalendarApiEvents(id, result)
                }
            }.awaitAll().flatten()
        }
        _events.value = calendarIds to events
        events
    } catch (e: IOException) {
        // Includes not being connected; the last events stay on screen.
        Log.w(TAG, "Calendar events failed", e)
        null
    }

    /** Adds an event to [calendarId], and so to Google Calendar or wherever that is. False if it couldn't be saved. */
    suspend fun createEvent(calendarId: String, draft: EventDraft, people: List<String>): Boolean = writing {
        home.command("calendar/event/create", createEventFields(calendarId, draft))
        kiosk.setKitchen(kiosk.config.value.kitchen.copy(lastCalendar = calendarId))
        afterWrite(calendarId, draft, people, everyTime = true)
    }

    /**
     * Saves a new version of [event]. A calendar that can't update an event (Google, through Home Assistant) gets the
     * new one added first and then the old deleted, so a failure never loses it. That gives it a new ID, so who it's
     * for is saved again.
     */
    suspend fun editEvent(
        event: ShownEvent,
        source: EventSource,
        features: Int,
        draft: EventDraft,
        people: List<String>,
        thisAndLater: Boolean,
    ): Boolean = writing {
        if (features and CALENDAR_UPDATE != 0) {
            val rule = source.rrule?.takeIf { thisAndLater || !event.repeats }
            home.command("calendar/event/update", updateEventFields(source, draft.copy(rrule = rule), thisAndLater))
            afterWrite(source.calendarId, draft, people, everyTime = thisAndLater || !event.repeats)
        } else {
            home.command("calendar/event/create", createEventFields(source.calendarId, draft.copy(rrule = null)))
            home.command("calendar/event/delete", deleteEventFields(source, thisAndLater = false))
            forgetAssignment(event)
            afterWrite(source.calendarId, draft, people, everyTime = true)
        }
    }

    /** Deletes [event]: this time of a repeating one, or [thisAndLater] times too. */
    suspend fun deleteEvent(event: ShownEvent, source: EventSource, thisAndLater: Boolean): Boolean = writing {
        home.command("calendar/event/delete", deleteEventFields(source, thisAndLater))
        forgetAssignment(event)
        fetchEvents(fetchedCalendars, range.value)
    }

    private suspend fun writing(write: suspend () -> Unit): Boolean = try {
        write()
        true
    } catch (e: IOException) {
        Log.w(TAG, "Saving to the calendar failed", e)
        false
    }

    /** Shows the change now, and gives the saved event to [people], once it can be found among the events. */
    private suspend fun afterWrite(calendarId: String, draft: EventDraft, people: List<String>, everyTime: Boolean) {
        val summary = draft.summary.trim()
        repeat(2) { attempt ->
            val saved = fetchEvents(fetchedCalendars, range.value)
                ?.firstOrNull { it.calendarId == calendarId && it.summary == summary && it.start == draft.start }
            if (saved != null) {
                val key = if (everyTime) saved.seriesKey else saved.instanceKey
                val kitchen = kiosk.config.value.kitchen
                val assigned = kitchen.eventPeople[key].orEmpty()
                if (assigned != people) {
                    val chosen = if (people.isEmpty()) kitchen.eventPeople - key else kitchen.eventPeople + (key to people)
                    kiosk.setKitchen(kitchen.copy(eventPeople = chosen))
                }
                return
            }
            // Some calendars take a moment to show what was just added.
            if (attempt == 0) delay(SETTLE_MS)
        }
    }

    private fun forgetAssignment(event: ShownEvent) {
        val kitchen = kiosk.config.value.kitchen
        val key = if (event.repeats) event.instanceKey else event.seriesKey
        if (key in kitchen.eventPeople) kiosk.setKitchen(kitchen.copy(eventPeople = kitchen.eventPeople - key))
    }

    private val announcer by lazy { Announcer(application) }
    private var remindedUpTo: LocalDateTime? = null
    private val _reminders = MutableStateFlow<List<ReminderNotice>>(emptyList())

    /** Reminders on screen now. */
    val reminders: StateFlow<List<ReminderNotice>> = _reminders.asStateFlow()

    /**
     * Brings up any reminders that have come due since it was last called, once a minute. They're silent in the quiet
     * hours, so an early event doesn't wake the house. A display that's been away doesn't catch up on old ones.
     */
    fun checkReminders(events: List<ShownEvent>, config: ReminderConfig, now: LocalDateTime) {
        val since = maxOf(remindedUpTo ?: now, now.minusMinutes(REMINDER_CATCH_UP_MINUTES))
        remindedUpTo = now
        _reminders.update { shown -> shown.filter { it.shownAt.plusMinutes(REMINDER_SHOWN_MINUTES) > now } }
        val due = dueReminders(events, config, since, now)
        if (due.isEmpty()) return
        _reminders.update { shown -> shown + due.map { ReminderNotice(it, now) } }
        if (!isQuietHours(now.toLocalTime())) {
            announcer.announce(
                due.joinToString(". ") { reminderSentence(it, now) },
                sound = config.sound,
                voice = config.voice,
            )
        }
    }

    fun dismissReminder(notice: ReminderNotice) {
        _reminders.update { it - notice }
    }

    /** Plays a reminder as it would be, for Settings. */
    fun tryReminder(config: ReminderConfig) {
        val sample = getApplication<Application>().getString(R.string.reminder_try_sentence)
        announcer.announce(sample, sound = config.sound, voice = config.voice)
    }

    private fun reminderSentence(event: ShownEvent, now: LocalDateTime): String {
        val context = getApplication<Application>()
        val name = event.summary.ifEmpty { context.getString(R.string.event_untitled) }
        val minutes = java.time.Duration.between(now, event.start).toMinutes().toInt()
        return when {
            event.allDay -> context.getString(R.string.reminder_today, name)
            minutes <= 0 -> context.getString(R.string.reminder_now, name)
            else -> context.resources.getQuantityString(R.plurals.reminder_in_minutes, minutes, name, minutes)
        }
    }

    override fun onCleared() {
        announcer.close()
    }

    /** Whether the morning summary has already come up today, by itself. */
    var morningShownOn: LocalDate? = null

    /** The summary on screen came up by itself, so it goes by itself too. */
    var morningAuto: Boolean = false

    /**
     * Gives [event] to [people] on this display, this time only or [everyTime] it happens; no people goes back to
     * its calendar's owner.
     */
    fun assign(event: ShownEvent, people: List<String>, everyTime: Boolean) {
        val kitchen = kiosk.config.value.kitchen
        kiosk.setKitchen(kitchen.copy(eventPeople = assign(kitchen.eventPeople, event, people, everyTime)))
    }

    fun run(entityId: String) {
        val call = runCall(entityId) ?: return
        if (_runs.value[entityId] == RunState.Running) return
        _runs.update { it + (entityId to RunState.Running) }
        viewModelScope.launch {
            val result = try {
                // Home Assistant answers an automation's trigger only once its actions finish, waits and delays
                // included, which can be hours; no answer by then means it's under way.
                withTimeoutOrNull(RUN_WAIT_MS) {
                    home.callService(
                        domain = call.domain,
                        service = call.service,
                        data = call.data,
                        target = buildJsonObject { put("entity_id", entityId) },
                    )
                }
                RunState.Done
            } catch (e: IOException) {
                // Includes not being connected.
                Log.w(TAG, "${call.domain}.${call.service} on $entityId failed", e)
                RunState.Failed
            }
            _runs.update { it + (entityId to result) }
            delay(RESULT_SHOWN_MS)
            _runs.update { if (it[entityId] == result) it - entityId else it }
        }
    }

    private companion object {
        const val TAG = "DashboardViewModel"
        const val RESULT_SHOWN_MS = 2_500L
        const val RUN_WAIT_MS = 5_000L
        const val CALENDAR_REFRESH_MS = 5 * 60_000L
        const val RETRY_MS = 60_000L
        const val SETTLE_MS = 2_000L
        const val REMINDER_CATCH_UP_MINUTES = 5L
        const val REMINDER_SHOWN_MINUTES = 10L
    }
}
