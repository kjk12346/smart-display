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
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
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
import dev.smartdisplay.app.kiosk.CalendarView
import dev.smartdisplay.app.ui.theme.OnPersonColor
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Someone whose events the calendar can show on their own: a person, or the family. */
data class CalendarOwner(val id: String, val name: String, val color: Color)

/** Each day in the grid gets at least this much width: as many days as fit, up to a week. */
private val MIN_DAY_WIDTH = 84.dp

/** Fewer days than this fit, and the calendar is a list instead. */
private const val MIN_GRID_DAYS = 3

/** The agenda's days run in columns of at least this width, up to [MAX_AGENDA_COLUMNS], on a wide card. */
private val AGENDA_COLUMN_WIDTH = 340.dp
private const val MAX_AGENDA_COLUMNS = 3

/**
 * The family calendar: a chip per person (and the family) to show only theirs, then the days ahead, in its [view]:
 * a column per day, as many as fit up to a week (a list by day where even a few don't), or an agenda of the next
 * two weeks, in columns of days on a wide card. Each event is in its owner's color. A switch in the corner changes
 * the view ([onView]); stacked on a phone, it's always the list.
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
    view: CalendarView,
    onView: (CalendarView) -> Unit,
    now: LocalDateTime,
    bounded: Boolean,
    modifier: Modifier,
) {
    val agendaView = view == CalendarView.Agenda || !bounded
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
    Surface(
        color = dashCardColor(),
        contentColor = MaterialTheme.colorScheme.onSurface,
        shape = RoundedCornerShape(24.dp),
        modifier = modifier,
    ) {
        Column(modifier = Modifier.padding(20.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    stringResource(if (agendaView) R.string.calendar_agenda_title else R.string.dashboard_calendar),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.secondary,
                )
                Box(Modifier.weight(1f)) {
                    // The family's events are everyone's, so "Everyone" stands for them: no chip of their own.
                    val people = owners.filter { it.id != FAMILY }
                    if (owners.size > 1 && people.isNotEmpty()) {
                        Box(Modifier.padding(start = 16.dp)) { OwnerChips(people, selected, onToggle, onEveryone) }
                    }
                }
                if (bounded) {
                    Spacer(Modifier.width(12.dp))
                    ViewSwitch(view, onView)
                }
            }
            Spacer(Modifier.height(12.dp))
            if (bounded) {
                BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
                    val fit = (maxWidth / MIN_DAY_WIDTH).toInt().coerceAtMost(CALENDAR_DAYS)
                    val columns = (maxWidth / AGENDA_COLUMN_WIDTH).toInt().coerceIn(1, MAX_AGENDA_COLUMNS)
                    if (!agendaView && fit >= MIN_GRID_DAYS) {
                        WeekGrid(shown, now, fit, colorOf, onOpen)
                    } else {
                        // Too narrow for the week, the days go in one column of the agenda.
                        AgendaColumns(shown, now, if (agendaView) columns else 1, colorOf, onOpen, scroll = true)
                    }
                }
            } else {
                AgendaColumns(shown, now, 1, colorOf, onOpen, scroll = false)
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

/** Week or Agenda. */
@Composable
private fun ViewSwitch(view: CalendarView, onView: (CalendarView) -> Unit) {
    SingleChoiceSegmentedButtonRow {
        CalendarView.entries.forEachIndexed { index, option ->
            SegmentedButton(
                selected = view == option,
                onClick = { onView(option) },
                shape = SegmentedButtonDefaults.itemShape(index, CalendarView.entries.size),
                // No tick: the fill already shows which is on, and the switch stays narrow.
                icon = {},
            ) {
                Text(
                    stringResource(
                        when (option) {
                            CalendarView.Week -> R.string.calendar_view_week
                            CalendarView.Agenda -> R.string.calendar_view_agenda
                        },
                    ),
                )
            }
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
            if (time != null) {
                Text(time, style = MaterialTheme.typography.labelMedium, color = OnPersonColor, maxLines = 1)
            }
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

/**
 * The agenda: the days ahead with something on, each with its date on the left and its events beside it, running
 * down [columns] columns (read down, then across) of about the same length. Each event shows once, under its first
 * day still to come. It [scroll]s by itself in a card of fixed height, and mustn't in a page that scrolls.
 */
@Composable
private fun AgendaColumns(
    events: List<ShownEvent>?,
    now: LocalDateTime,
    columns: Int,
    colorOf: (String) -> Color,
    onOpen: (ShownEvent) -> Unit,
    scroll: Boolean,
) {
    val days = events?.let { agenda(it, now) }
    if (days != null && days.isEmpty()) {
        Text(
            stringResource(R.string.calendar_agenda_empty),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        return
    }
    val runs = remember(days, columns) { agendaColumns(days.orEmpty(), columns) }
    Row(
        horizontalArrangement = Arrangement.spacedBy(24.dp),
        modifier = if (scroll) {
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
        } else {
            Modifier.fillMaxWidth()
        },
    ) {
        runs.forEach { run ->
            Column(verticalArrangement = Arrangement.spacedBy(16.dp), modifier = Modifier.weight(1f)) {
                run.forEach { day ->
                    // The month under the first day shown, and wherever a new one starts.
                    val showMonth = day == days?.firstOrNull() || day.date.dayOfMonth == 1
                    AgendaDayRow(day, now, showMonth, colorOf, onOpen)
                }
            }
        }
        repeat(columns - runs.size) { Spacer(Modifier.weight(1f)) }
    }
}

/** A day in the agenda: its weekday, a large date (circled today) and maybe month on the left, its events beside. */
@Composable
private fun AgendaDayRow(
    day: AgendaDay,
    now: LocalDateTime,
    showMonth: Boolean,
    colorOf: (String) -> Color,
    onOpen: (ShownEvent) -> Unit,
) {
    val isToday = day.date == now.toLocalDate()
    val locale = LocalConfiguration.current.locales[0]
    Row {
        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.width(64.dp)) {
            Text(
                formatDate(day.date.atStartOfDay(), "EEE", locale),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .padding(top = 2.dp)
                    .size(48.dp)
                    .clip(CircleShape)
                    .background(if (isToday) MaterialTheme.colorScheme.primary else Color.Transparent),
            ) {
                Text(
                    day.date.dayOfMonth.toString(),
                    style = MaterialTheme.typography.headlineMedium,
                    color = if (isToday) OnPersonColor else MaterialTheme.colorScheme.onSurface,
                )
            }
            if (showMonth) {
                Text(
                    formatDate(day.date.atStartOfDay(), "MMM", locale),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Column(
            verticalArrangement = Arrangement.spacedBy(6.dp),
            modifier = Modifier
                .weight(1f)
                .padding(start = 12.dp, top = 6.dp),
        ) {
            day.events.forEach { event ->
                // An all-day event's fill already says so; one over several days says until when.
                val time = if (event.allDay && event.lastDay() <= day.date) {
                    null
                } else {
                    agendaTimeLabel(event, day.date, locale)
                }
                EventChip(event, time, past = false, colorOf) { onOpen(event) }
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
