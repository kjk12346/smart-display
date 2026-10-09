package dev.smartdisplay.app.ui.dashboard

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.smartdisplay.app.SmartDisplayApp
import java.io.IOException
import java.time.LocalDate
import java.time.ZonedDateTime
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** How a button's run went, shown on it for a moment. */
enum class RunState { Running, Done, Failed }

/** Runs the kitchen dashboard's buttons (scenes, scripts, automations, Home and Away) and keeps its calendar. */
class DashboardViewModel(application: Application) : AndroidViewModel(application) {
    private val home = (application as SmartDisplayApp).home
    private val kiosk = (application as SmartDisplayApp).kiosk

    private val _runs = MutableStateFlow<Map<String, RunState>>(emptyMap())

    /** Buttons running now or just finished, by entity ID. */
    val runs: StateFlow<Map<String, RunState>> = _runs.asStateFlow()

    private val _events = MutableStateFlow<Pair<List<String>, List<CalendarEvent>>?>(null)

    /** This week's events (from the start of today), and the calendars they're from. */
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

    /**
     * Fetches this week's events from [calendarIds] now and then every [CALENDAR_REFRESH_MS], until cancelled.
     * Run it only while connected and on screen.
     */
    suspend fun keepCalendarFresh(calendarIds: List<String>) {
        if (calendarIds.isEmpty()) return
        while (true) {
            val fetched = fetchEvents(calendarIds)
            delay(if (fetched) CALENDAR_REFRESH_MS else RETRY_MS)
        }
    }

    private suspend fun fetchEvents(calendarIds: List<String>): Boolean = try {
        val now = ZonedDateTime.now()
        val today = LocalDate.now(now.zone)
        val start = today.atStartOfDay(now.zone)
        val end = today.plusDays(CALENDAR_DAYS.toLong()).atStartOfDay(now.zone)
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
        true
    } catch (e: IOException) {
        // Includes not being connected; the last events stay on screen.
        Log.w(TAG, "Calendar events failed", e)
        false
    }

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
                home.callService(
                    domain = call.domain,
                    service = call.service,
                    data = call.data,
                    target = buildJsonObject { put("entity_id", entityId) },
                )
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
        const val CALENDAR_REFRESH_MS = 5 * 60_000L
        const val RETRY_MS = 60_000L
    }
}
