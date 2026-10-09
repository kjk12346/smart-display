package dev.smartdisplay.app.ui.winddown

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.smartdisplay.app.SmartDisplayApp
import dev.smartdisplay.app.ui.controls.ServiceCall
import dev.smartdisplay.app.ui.dashboard.CalendarEvent
import dev.smartdisplay.app.ui.dashboard.parseCalendarApiEvents
import java.io.IOException
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Tomorrow's first event for the wind-down panel, and its one-tap fixes for the bedtime check. */
class WindDownViewModel(application: Application) : AndroidViewModel(application) {
    private val home = (application as SmartDisplayApp).home

    private val _tomorrow = MutableStateFlow<Tomorrow?>(null)

    /** Tomorrow's first event (null until fetched), and the calendars it's from. */
    val tomorrow: StateFlow<Tomorrow?> = _tomorrow.asStateFlow()

    private val _fixing = MutableStateFlow<Set<String>>(emptySet())

    /** Entities with a fix on its way. */
    val fixing: StateFlow<Set<String>> = _fixing.asStateFlow()

    /** Fetches tomorrow's events from [calendarIds], keeping the first one. */
    suspend fun loadTomorrow(calendarIds: List<String>, zone: ZoneId = ZoneId.systemDefault()) {
        val day = LocalDate.now(zone).plusDays(1)
        val start = day.atStartOfDay(zone).toOffsetDateTime().toString()
        val end = day.plusDays(1).atStartOfDay(zone).toOffsetDateTime().toString()
        try {
            val events = coroutineScope {
                calendarIds.map { id ->
                    async { parseCalendarApiEvents(id, home.calendarEvents(id, start, end), zone) }
                }.awaitAll().flatten()
            }
            _tomorrow.value = Tomorrow(calendarIds, firstEvent(events, day))
        } catch (e: IOException) {
            Log.w(TAG, "Tomorrow's events failed", e)
        }
    }

    fun fix(calls: List<ServiceCall>) {
        _fixing.update { it + calls.map(ServiceCall::entityId) }
        viewModelScope.launch {
            for (call in calls) {
                try {
                    home.callService(
                        call.domain, call.service, call.data,
                        target = buildJsonObject { put("entity_id", call.entityId) },
                    )
                } catch (e: IOException) {
                    Log.w(TAG, "${call.domain}.${call.service} on ${call.entityId} failed", e)
                }
            }
            // The check list follows Home Assistant's states; this only shows the tap was taken.
            _fixing.update { it - calls.map(ServiceCall::entityId).toSet() }
        }
    }

    private companion object {
        const val TAG = "WindDownViewModel"
    }
}

/** Tomorrow's first event (none if the calendars are clear), fetched from [calendarIds]. */
data class Tomorrow(val calendarIds: List<String>, val first: CalendarEvent?)

/** The first timed event starting on [day], or failing that its first all-day event. */
fun firstEvent(events: List<CalendarEvent>, day: LocalDate): CalendarEvent? {
    val onDay = events.filter { it.start.toLocalDate() == day }
    return onDay.filterNot { it.allDay }.minByOrNull { it.start }
        ?: onDay.filter { it.allDay }.minByOrNull { it.summary.lowercase() }
}
