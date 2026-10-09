package dev.smartdisplay.app.ui.dashboard

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.text.HtmlCompat
import dev.smartdisplay.app.R
import dev.smartdisplay.app.alarm.timeText
import dev.smartdisplay.app.ui.theme.OnPersonColor

/**
 * An event's details, and who it's for: the household's people as chips, ticked for the people it's shown as now.
 * Saving gives it to the ticked people on this display (for a repeating event, this time or every time); none
 * ticked goes back to whose calendar it's on. Nothing changes in the calendar itself.
 */
@Composable
internal fun EventDialog(
    event: ShownEvent,
    people: List<CalendarOwner>,
    calendarName: (calendarId: String) -> String,
    onSave: (people: List<String>, everyTime: Boolean) -> Unit,
    onDismiss: () -> Unit,
) {
    val initial = remember(event) { event.owners.filter { owner -> people.any { it.id == owner } } }
    val chosen = remember(event) { mutableStateListOf(*initial.toTypedArray()) }
    val initialEveryTime = event.assigned != Assignment.ThisTime
    var everyTime by remember(event) { mutableStateOf(initialEveryTime) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(event.summary.ifEmpty { stringResource(R.string.event_untitled) }) },
        text = {
            Column(
                verticalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier.heightIn(max = 460.dp).verticalScroll(rememberScrollState()),
            ) {
                Text(eventWhen(event), style = MaterialTheme.typography.bodyLarge)
                event.location?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (event.calendarIds.isNotEmpty()) {
                    Text(
                        stringResource(
                            R.string.event_from,
                            event.calendarIds.joinToString(", ", transform = calendarName),
                        ),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                event.description?.let { description ->
                    // Google Calendar's notes can be HTML.
                    val text = HtmlCompat.fromHtml(description, HtmlCompat.FROM_HTML_MODE_COMPACT).toString().trim()
                    if (text.isNotEmpty()) {
                        Text(text, style = MaterialTheme.typography.bodyMedium, maxLines = 8)
                    }
                }

                Text(
                    stringResource(R.string.event_who),
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.secondary,
                    modifier = Modifier.padding(top = 12.dp),
                )
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
                if (event.repeats) {
                    SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth().padding(top = 4.dp)) {
                        SegmentedButton(
                            selected = !everyTime,
                            onClick = { everyTime = false },
                            shape = SegmentedButtonDefaults.itemShape(index = 0, count = 2),
                        ) { Text(stringResource(R.string.event_this_time)) }
                        SegmentedButton(
                            selected = everyTime,
                            onClick = { everyTime = true },
                            shape = SegmentedButtonDefaults.itemShape(index = 1, count = 2),
                        ) { Text(stringResource(R.string.event_every_time)) }
                    }
                }
                Text(
                    stringResource(if (chosen.isEmpty()) R.string.event_none_chosen else R.string.event_display_only),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    val changed = chosen.toSet() != initial.toSet() ||
                        (event.repeats && everyTime != initialEveryTime && event.assigned != Assignment.None)
                    if (changed) onSave(chosen.toList(), everyTime || !event.repeats)
                    onDismiss()
                },
            ) { Text(stringResource(R.string.pin_save)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

/** When an event is: its day (or days) and times, or "All day". */
@Composable
private fun eventWhen(event: ShownEvent): String {
    val context = LocalContext.current
    val locale = LocalConfiguration.current.locales[0]
    val firstDay = event.start.toLocalDate()
    val lastDay = event.lastDay()
    return when {
        event.allDay && lastDay == firstDay ->
            dayName(firstDay, locale) + " · " + stringResource(R.string.calendar_all_day)
        event.allDay -> dayName(firstDay, locale) + " – " + dayName(lastDay, locale)
        lastDay == firstDay ->
            dayName(firstDay, locale) + " · " + timeText(context, event.start) + " – " + timeText(context, event.end)
        else -> dayName(firstDay, locale) + ", " + timeText(context, event.start) + " – " +
            dayName(event.end.toLocalDate(), locale) + ", " + timeText(context, event.end)
    }
}
