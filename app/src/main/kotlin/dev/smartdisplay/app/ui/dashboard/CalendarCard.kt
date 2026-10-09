package dev.smartdisplay.app.ui.dashboard

import android.text.format.DateFormat
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.smartdisplay.app.R
import dev.smartdisplay.app.alarm.timeText
import dev.smartdisplay.app.ui.theme.OnPersonColor
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Someone whose events the calendar can show on their own: a person, or the family. */
data class CalendarOwner(val id: String, val name: String, val color: Color)

/** Each day in the grid gets at least this much width: as many days as fit, up to a week. */
private val MIN_DAY_WIDTH = 100.dp

/** Fewer days than this fit, and the calendar is a list instead. */
private const val MIN_GRID_DAYS = 3

/**
 * The family calendar: a chip per person (and the family) to show only theirs, then the days ahead as a column per
 * day, as many as fit up to a week, or as a list by day where even a few don't. Each event is in its owner's color.
 *
 * Tapping an event opens its details, where it can be given to [people] ([onAssign]).
 *
 * [events] is null until they've been fetched. In a column of fixed height ([bounded]) the days scroll by
 * themselves; stacked in a page that scrolls, they mustn't.
 */
@Composable
internal fun CalendarCard(
    events: List<ShownEvent>?,
    owners: List<CalendarOwner>,
    people: List<CalendarOwner>,
    selected: Set<String>,
    onToggle: (String) -> Unit,
    onEveryone: () -> Unit,
    calendarName: (calendarId: String) -> String,
    onAssign: (event: ShownEvent, people: List<String>, everyTime: Boolean) -> Unit,
    now: LocalDateTime,
    bounded: Boolean,
    modifier: Modifier,
) {
    val colors = (owners + people).associate { it.id to it.color }
    val colorOf = { owner: String -> colors[owner] ?: Color.Gray }
    val shown = events?.forOwners(selected)
    var open by remember { mutableStateOf<ShownEvent?>(null) }
    val onOpen = { event: ShownEvent -> open = event }
    open?.let { event ->
        EventDialog(
            event = event,
            people = people,
            calendarName = calendarName,
            onSave = { chosen, everyTime -> onAssign(event, chosen, everyTime) },
            onDismiss = { open = null },
        )
    }
    Surface(color = MaterialTheme.colorScheme.surfaceContainer, shape = RoundedCornerShape(24.dp), modifier = modifier) {
        Column(modifier = Modifier.padding(20.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    stringResource(R.string.dashboard_calendar),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.secondary,
                )
                if (owners.size > 1) {
                    Spacer(Modifier.width(16.dp))
                    OwnerChips(owners, selected, onToggle, onEveryone)
                }
            }
            Spacer(Modifier.height(12.dp))
            if (bounded) {
                BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
                    val fit = (maxWidth / MIN_DAY_WIDTH).toInt().coerceAtMost(CALENDAR_DAYS)
                    if (fit >= MIN_GRID_DAYS) {
                        WeekGrid(shown, now, fit, colorOf, onOpen)
                    } else {
                        Agenda(shown, now, colorOf, onOpen, Modifier.verticalScroll(rememberScrollState()))
                    }
                }
            } else {
                Agenda(shown, now, colorOf, onOpen, Modifier)
            }
        }
    }
}

@Composable
private fun OwnerChips(
    owners: List<CalendarOwner>,
    selected: Set<String>,
    onToggle: (String) -> Unit,
    onEveryone: () -> Unit,
) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.horizontalScroll(rememberScrollState()),
    ) {
        FilterChip(
            selected = selected.isEmpty(),
            onClick = onEveryone,
            label = { Text(stringResource(R.string.calendar_everyone)) },
        )
        owners.forEach { owner ->
            val on = owner.id in selected
            FilterChip(
                selected = on,
                onClick = { onToggle(owner.id) },
                label = { Text(owner.name, maxLines = 1) },
                leadingIcon = {
                    Box(
                        Modifier
                            .size(12.dp)
                            .clip(CircleShape)
                            .background(if (on) OnPersonColor else owner.color),
                    )
                },
                colors = FilterChipDefaults.filterChipColors(
                    selectedContainerColor = owner.color,
                    selectedLabelColor = OnPersonColor,
                ),
            )
        }
    }
}

/** A column for each of [dayCount] days, today first; each column scrolls if its day is full. */
@Composable
private fun WeekGrid(
    events: List<ShownEvent>?,
    now: LocalDateTime,
    dayCount: Int,
    colorOf: (String) -> Color,
    onOpen: (ShownEvent) -> Unit,
) {
    val today = now.toLocalDate()
    val days = week(events.orEmpty(), today, dayCount)
    val locale = LocalConfiguration.current.locales[0]
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxSize()) {
        days.forEach { day ->
            val isToday = day.date == today
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .clip(RoundedCornerShape(16.dp))
                    .background(
                        if (isToday) MaterialTheme.colorScheme.surfaceContainerHigh else Color.Transparent,
                    )
                    .padding(horizontal = 4.dp, vertical = 8.dp),
            ) {
                Text(
                    formatDate(day.date.atStartOfDay(), "EEE", locale),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .padding(top = 2.dp, bottom = 8.dp)
                        .size(32.dp)
                        .clip(CircleShape)
                        .background(if (isToday) MaterialTheme.colorScheme.primary else Color.Transparent),
                ) {
                    Text(
                        day.date.dayOfMonth.toString(),
                        style = MaterialTheme.typography.titleMedium,
                        color = if (isToday) OnPersonColor else MaterialTheme.colorScheme.onSurface,
                    )
                }
                Column(
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState()),
                ) {
                    day.events.forEach { event ->
                        EventChip(event, gridTimeLabel(event, day.date), past = event.end <= now, colorOf) {
                            onOpen(event)
                        }
                    }
                }
            }
        }
    }
}

/**
 * An event in the week grid: all-day ones filled with their owner's color, timed ones tinted with a bar of it.
 * An event shared by several people shows the others' colors as dots.
 */
@Composable
private fun EventChip(
    event: ShownEvent,
    time: String?,
    past: Boolean,
    colorOf: (String) -> Color,
    onClick: () -> Unit,
) {
    val color = colorOf(event.owners.first())
    val others: @Composable () -> Unit = {
        if (event.owners.size > 1) {
            Row(horizontalArrangement = Arrangement.spacedBy(3.dp), modifier = Modifier.padding(top = 3.dp)) {
                event.owners.drop(1).forEach { owner ->
                    Box(Modifier.size(8.dp).clip(CircleShape).background(colorOf(owner)))
                }
            }
        }
    }
    val shape = RoundedCornerShape(10.dp)
    val modifier = Modifier.fillMaxWidth().alpha(if (past) 0.45f else 1f).clip(shape).clickable(onClick = onClick)
    if (event.allDay) {
        Column(modifier = modifier.background(color).padding(horizontal = 8.dp, vertical = 6.dp)) {
            Text(
                event.summary,
                style = MaterialTheme.typography.labelLarge,
                color = OnPersonColor,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
            others()
        }
    } else {
        Row(modifier = modifier.background(color.copy(alpha = 0.16f)).height(IntrinsicSize.Min)) {
            Box(Modifier.width(4.dp).fillMaxHeight().background(color))
            Column(modifier = Modifier.padding(horizontal = 6.dp, vertical = 5.dp)) {
                if (time != null) {
                    Text(time, style = MaterialTheme.typography.labelMedium, color = color, maxLines = 1)
                }
                Text(
                    event.summary,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
                others()
            }
        }
    }
}

/** A list by day, for narrow screens: each event once, under its first day still to come. */
@Composable
private fun Agenda(
    events: List<ShownEvent>?,
    now: LocalDateTime,
    colorOf: (String) -> Color,
    onOpen: (ShownEvent) -> Unit,
    modifier: Modifier,
) {
    val today = now.toLocalDate()
    val locale = LocalConfiguration.current.locales[0]
    val days = events?.let { agenda(it, now) }
    Column(modifier = modifier) {
        if (days != null && days.isEmpty()) {
            Text(
                stringResource(R.string.dashboard_calendar_empty),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        days.orEmpty().forEachIndexed { index, day ->
            Text(
                when (day.date) {
                    today -> stringResource(R.string.forecast_today)
                    today.plusDays(1) -> stringResource(R.string.calendar_tomorrow)
                    else -> dayName(day.date, locale)
                },
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = if (index == 0) 0.dp else 12.dp, bottom = 2.dp),
            )
            day.events.forEach { event ->
                // The name gets the whole width, with the time under it.
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .clickable { onOpen(event) }
                        .padding(vertical = 5.dp),
                ) {
                    Column(
                        verticalArrangement = Arrangement.spacedBy(3.dp),
                        modifier = Modifier.padding(top = 7.dp),
                    ) {
                        event.owners.forEach { owner ->
                            Box(Modifier.size(10.dp).clip(CircleShape).background(colorOf(owner)))
                        }
                    }
                    Column(modifier = Modifier.padding(start = 12.dp)) {
                        Text(
                            event.summary,
                            style = MaterialTheme.typography.bodyLarge,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            agendaTimeLabel(event, day.date, locale),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }
    }
}

/**
 * The time on a week-grid chip for [day]: the start on its first day, "Until" the end on its last, nothing for
 * all-day events (their fill says so) and "All day" for the days in between.
 */
@Composable
private fun gridTimeLabel(event: ShownEvent, day: LocalDate): String? {
    val context = LocalContext.current
    val starts = event.start.toLocalDate() == day
    val ends = event.lastDay() == day
    return when {
        event.allDay -> null
        starts -> timeText(context, event.start)
        ends -> stringResource(R.string.calendar_until, timeText(context, event.end))
        else -> stringResource(R.string.calendar_all_day)
    }
}

/** The time in the list, where an event shows once under [day]: when it starts, or until when it goes on. */
@Composable
private fun agendaTimeLabel(event: ShownEvent, day: LocalDate, locale: Locale): String {
    val context = LocalContext.current
    val lastDay = event.lastDay()
    return when {
        // Goes on past this day: when it ends, as a day, or a time if that's tomorrow.
        lastDay > day -> stringResource(
            R.string.calendar_until,
            if (event.allDay || lastDay > day.plusDays(1)) dayName(lastDay, locale) else timeText(context, event.end),
        )
        event.allDay -> stringResource(R.string.calendar_all_day)
        // Started on an earlier day and ends today.
        event.start.toLocalDate() < day -> stringResource(R.string.calendar_until, timeText(context, event.end))
        else -> timeText(context, event.start)
    }
}

internal fun dayName(date: LocalDate, locale: Locale): String =
    formatDate(date.atStartOfDay(), DateFormat.getBestDateTimePattern(locale, "EEEMMMd"), locale)

/** Formats with a locale's best pattern, falling back if java.time doesn't know one of its letters. */
private fun formatDate(time: LocalDateTime, pattern: String, locale: Locale): String = try {
    DateTimeFormatter.ofPattern(pattern, locale).format(time)
} catch (e: IllegalArgumentException) {
    DateTimeFormatter.ofPattern("EEE d MMM", locale).format(time)
}
