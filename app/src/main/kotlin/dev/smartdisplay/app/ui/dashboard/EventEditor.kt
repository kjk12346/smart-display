package dev.smartdisplay.app.ui.dashboard

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.smartdisplay.app.R
import dev.smartdisplay.app.alarm.timeText
import dev.smartdisplay.app.ui.theme.OnPersonColor
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneOffset
import kotlinx.coroutines.launch

/** What the calendar card needs from outside to add, change and delete events. */
internal class CalendarActions(
    /** The calendars that can take a new event, as (entity ID, name), the last one used first. */
    val writable: List<Pair<String, String>>,
    val featuresOf: (calendarId: String) -> Int,
    val create: suspend (calendarId: String, draft: EventDraft, people: List<String>) -> Boolean,
    val edit: suspend (ShownEvent, EventSource, draft: EventDraft, people: List<String>, thisAndLater: Boolean) -> Boolean,
    val delete: suspend (ShownEvent, EventSource, thisAndLater: Boolean) -> Boolean,
    val showRange: (start: LocalDate?, end: LocalDate?) -> Unit,
)

/**
 * A new event on [day], or a change to [event] (on [source]). Saved to Home Assistant's calendar, so it reaches Google
 * Calendar or wherever that calendar lives; who it's for stays on this display, as in [EventDialog].
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun EventEditor(
    event: ShownEvent?,
    source: EventSource?,
    day: LocalDate,
    actions: CalendarActions,
    people: List<CalendarOwner>,
    onDismiss: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val locale = LocalConfiguration.current.locales[0]
    var title by remember { mutableStateOf(event?.summary.orEmpty()) }
    var allDay by remember { mutableStateOf(event?.allDay ?: false) }
    val startAt = event?.start ?: nextHalfHour(day)
    var startDate by remember { mutableStateOf(startAt.toLocalDate()) }
    var startTime by remember { mutableStateOf(startAt.toLocalTime()) }
    // An all-day event's last day, shown as such; its end is the midnight after.
    val endAt = event?.end ?: startAt.plusHours(1)
    var endDate by remember { mutableStateOf(if (event?.allDay == true) event.lastDay() else endAt.toLocalDate()) }
    var endTime by remember { mutableStateOf(endAt.toLocalTime()) }
    var location by remember { mutableStateOf(event?.location.orEmpty()) }
    var notes by remember { mutableStateOf(event?.description.orEmpty()) }
    var repeat by remember { mutableStateOf(EventRepeat.Never) }
    var calendar by remember { mutableStateOf(source?.calendarId ?: actions.writable.firstOrNull()?.first) }
    val chosen = remember {
        mutableStateListOf(*(event?.owners.orEmpty().filter { owner -> people.any { it.id == owner } }).toTypedArray())
    }
    val canUpdate = source != null && actions.featuresOf(source.calendarId) and CALENDAR_UPDATE != 0
    var thisAndLater by remember { mutableStateOf(false) }
    var picking by remember { mutableStateOf<Picking?>(null) }
    var saving by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }

    val start = if (allDay) startDate.atStartOfDay() else startDate.atTime(startTime)
    val end = if (allDay) endDate.plusDays(1).atStartOfDay() else endDate.atTime(endTime)
    val valid = title.isNotBlank() && end > start && calendar != null

    AlertDialog(
        onDismissRequest = { if (!saving) onDismiss() },
        title = { Text(stringResource(if (event == null) R.string.event_add else R.string.event_edit)) },
        text = {
            Column(
                verticalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier.heightIn(max = 520.dp).verticalScroll(rememberScrollState()),
            ) {
                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it },
                    label = { Text(stringResource(R.string.event_title)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.calendar_all_day), Modifier.weight(1f))
                    Switch(checked = allDay, onCheckedChange = { allDay = it })
                }
                WhenRow(stringResource(R.string.event_starts)) {
                    OutlinedButton(onClick = { picking = Picking.StartDate }) { Text(dayName(startDate, locale)) }
                    if (!allDay) {
                        OutlinedButton(onClick = { picking = Picking.StartTime }) { Text(timeText(context, startTime)) }
                    }
                }
                WhenRow(stringResource(if (allDay) R.string.event_last_day else R.string.event_ends)) {
                    OutlinedButton(onClick = { picking = Picking.EndDate }) { Text(dayName(endDate, locale)) }
                    if (!allDay) {
                        OutlinedButton(onClick = { picking = Picking.EndTime }) { Text(timeText(context, endTime)) }
                    }
                }
                if (end <= start) {
                    Text(
                        stringResource(R.string.event_end_before_start),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                if (event == null) {
                    Label(stringResource(R.string.event_repeat))
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        EventRepeat.entries.forEach { option ->
                            FilterChip(
                                selected = repeat == option,
                                onClick = { repeat = option },
                                label = { Text(stringResource(option.label())) },
                            )
                        }
                    }
                    if (actions.writable.size > 1) {
                        Label(stringResource(R.string.event_calendar))
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            actions.writable.forEach { (id, name) ->
                                FilterChip(
                                    selected = calendar == id,
                                    onClick = { calendar = id },
                                    label = { Text(name, maxLines = 1) },
                                )
                            }
                        }
                    }
                } else if (event.repeats) {
                    if (canUpdate) {
                        SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                            SegmentedButton(
                                selected = !thisAndLater,
                                onClick = { thisAndLater = false },
                                shape = SegmentedButtonDefaults.itemShape(index = 0, count = 2),
                            ) { Text(stringResource(R.string.event_this_time)) }
                            SegmentedButton(
                                selected = thisAndLater,
                                onClick = { thisAndLater = true },
                                shape = SegmentedButtonDefaults.itemShape(index = 1, count = 2),
                            ) { Text(stringResource(R.string.event_this_and_later)) }
                        }
                    } else {
                        Text(
                            stringResource(R.string.event_edit_one_time),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                OutlinedTextField(
                    value = location,
                    onValueChange = { location = it },
                    label = { Text(stringResource(R.string.event_location)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = notes,
                    onValueChange = { notes = it },
                    label = { Text(stringResource(R.string.event_notes)) },
                    maxLines = 4,
                    modifier = Modifier.fillMaxWidth(),
                )
                if (people.isNotEmpty()) {
                    Label(stringResource(R.string.event_who))
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        people.forEach { person ->
                            val on = person.id in chosen
                            FilterChip(
                                selected = on,
                                onClick = { if (on) chosen.remove(person.id) else chosen.add(person.id) },
                                label = { Text(person.name) },
                                leadingIcon = {
                                    Box(
                                        Modifier
                                            .size(12.dp)
                                            .clip(CircleShape)
                                            .background(if (on) OnPersonColor else person.color),
                                    )
                                },
                                colors = FilterChipDefaults.filterChipColors(
                                    selectedContainerColor = person.color,
                                    selectedLabelColor = OnPersonColor,
                                ),
                            )
                        }
                    }
                }
                if (failed) {
                    Text(stringResource(R.string.event_save_failed), color = MaterialTheme.colorScheme.error)
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = valid && !saving,
                onClick = {
                    saving = true
                    failed = false
                    val draft = EventDraft(
                        summary = title,
                        start = start,
                        end = end,
                        allDay = allDay,
                        location = location,
                        description = notes,
                        rrule = repeat.rrule,
                    )
                    scope.launch {
                        val saved = if (event != null && source != null) {
                            actions.edit(event, source, draft, chosen.toList(), thisAndLater)
                        } else {
                            actions.create(calendar!!, draft, chosen.toList())
                        }
                        saving = false
                        if (saved) onDismiss() else failed = true
                    }
                },
            ) { Text(stringResource(if (saving) R.string.event_saving else R.string.pin_save)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !saving) { Text(stringResource(R.string.cancel)) }
        },
    )

    when (picking) {
        Picking.StartDate -> DayPicker(startDate, onDismiss = { picking = null }) { picked ->
            // The end moves with the start, keeping the event as long.
            val length = java.time.temporal.ChronoUnit.DAYS.between(startDate, endDate)
            startDate = picked
            endDate = picked.plusDays(length.coerceAtLeast(0))
            picking = null
        }
        Picking.EndDate -> DayPicker(endDate, onDismiss = { picking = null }) { picked ->
            endDate = picked
            picking = null
        }
        Picking.StartTime -> TimeOfDayPicker(startTime, onDismiss = { picking = null }) { picked ->
            val minutes = java.time.Duration.between(startDate.atTime(startTime), endDate.atTime(endTime))
            val moved = startDate.atTime(picked).plus(if (minutes.isNegative) java.time.Duration.ofHours(1) else minutes)
            startTime = picked
            endDate = moved.toLocalDate()
            endTime = moved.toLocalTime()
            picking = null
        }
        Picking.EndTime -> TimeOfDayPicker(endTime, onDismiss = { picking = null }) { picked ->
            endTime = picked
            picking = null
        }
        null -> Unit
    }
}

private enum class Picking { StartDate, StartTime, EndDate, EndTime }

@Composable
private fun WhenRow(label: String, buttons: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Label(label)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { buttons() }
    }
}

@Composable
private fun Label(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.secondary,
        modifier = Modifier.padding(top = 2.dp),
    )
}

/** A day to pick, from a month's grid. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun DayPicker(day: LocalDate, onDismiss: () -> Unit, onPick: (LocalDate) -> Unit) {
    // The picker works in UTC midnights, whatever the time zone.
    val state = rememberDatePickerState(initialSelectedDateMillis = day.atStartOfDay().toInstant(ZoneOffset.UTC).toEpochMilli())
    DatePickerDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(
                onClick = {
                    state.selectedDateMillis?.let { onPick(Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate()) }
                        ?: onDismiss()
                },
            ) { Text(stringResource(R.string.settings_done)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    ) { DatePicker(state = state) }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TimeOfDayPicker(time: LocalTime, onDismiss: () -> Unit, onPick: (LocalTime) -> Unit) {
    val context = LocalContext.current
    val state = rememberTimePickerState(
        initialHour = time.hour,
        initialMinute = time.minute,
        is24Hour = android.text.format.DateFormat.is24HourFormat(context),
    )
    AlertDialog(
        onDismissRequest = onDismiss,
        text = { TimePicker(state = state) },
        confirmButton = {
            TextButton(onClick = { onPick(LocalTime.of(state.hour, state.minute)) }) {
                Text(stringResource(R.string.settings_done))
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

/** Where a new event starts: the next half hour today, or 9 AM another day. */
private fun nextHalfHour(day: LocalDate): LocalDateTime {
    val now = LocalDateTime.now()
    if (day != now.toLocalDate()) return day.atTime(9, 0)
    val rounded = now.withSecond(0).withNano(0).plusMinutes((30 - now.minute % 30).toLong())
    // Late in the evening that could be tomorrow; keep it today.
    return if (rounded.toLocalDate() == day) rounded else day.atTime(23, 0)
}

private fun EventRepeat.label(): Int = when (this) {
    EventRepeat.Never -> R.string.event_repeat_never
    EventRepeat.Daily -> R.string.event_repeat_daily
    EventRepeat.Weekly -> R.string.event_repeat_weekly
    EventRepeat.Monthly -> R.string.event_repeat_monthly
    EventRepeat.Yearly -> R.string.event_repeat_yearly
}
