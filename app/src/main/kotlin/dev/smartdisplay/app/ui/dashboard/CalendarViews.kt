package dev.smartdisplay.app.ui.dashboard

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.smartdisplay.app.R
import dev.smartdisplay.app.alarm.timeText
import dev.smartdisplay.app.ui.theme.OnPersonColor
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle

/** Past events fade in every view, so what's left of the day stands out. */
internal const val PAST_ALPHA = 0.45f

/** A title between back and forward buttons, with a way back to today when away from it. */
@Composable
private fun PagerHeader(title: String, showToday: Boolean, onBack: () -> Unit, onForward: () -> Unit, onToday: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        TextButton(onClick = onBack) { Text("‹", style = MaterialTheme.typography.titleLarge) }
        Text(
            title,
            style = MaterialTheme.typography.titleMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        TextButton(onClick = onForward) { Text("›", style = MaterialTheme.typography.titleLarge) }
        Spacer(Modifier.weight(1f))
        if (showToday) TextButton(onClick = onToday) { Text(stringResource(R.string.forecast_today)) }
    }
}

/**
 * A month: a row for each week, starting on [weekStart], with as many of each day's events as fit in its height and a
 * count of the rest. Tapping a day opens it ([onOpenDay]).
 */
@Composable
internal fun MonthView(
    events: List<ShownEvent>?,
    month: YearMonth,
    onMonth: (YearMonth) -> Unit,
    weekStart: DayOfWeek,
    now: LocalDateTime,
    colorOf: (String) -> Color,
    onOpenDay: (LocalDate) -> Unit,
) {
    val locale = LocalConfiguration.current.locales[0]
    val today = now.toLocalDate()
    val days = monthDays(month, weekStart)
    val byDay = week(events.orEmpty(), days.first(), days.size)
    val weeks = byDay.chunked(7)
    // The weeks share the card's height, or scroll where that wouldn't leave room to read them.
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val weekHeight = ((maxHeight - MONTH_HEADER) / weeks.size).coerceAtLeast(MIN_WEEK_HEIGHT)
        val scroll = rememberScrollState()
        val density = LocalDensity.current
        // Too short for the whole month, it opens at this week.
        LaunchedEffect(month, weekHeight) {
            val thisWeek = weeks.indexOfFirst { week -> week.any { it.date == today } }
            if (thisWeek >= 0) scroll.scrollTo(with(density) { (MONTH_HEADER + weekHeight * thisWeek).roundToPx() })
        }
        Column(Modifier.fillMaxWidth().verticalScroll(scroll)) {
            PagerHeader(
                title = month.format(DateTimeFormatter.ofPattern("LLLL yyyy", locale)),
                showToday = month != YearMonth.from(today),
                onBack = { onMonth(month.minusMonths(1)) },
                onForward = { onMonth(month.plusMonths(1)) },
                onToday = { onMonth(YearMonth.from(today)) },
            )
            Row(Modifier.fillMaxWidth().padding(bottom = 4.dp)) {
                days.take(7).forEach { day ->
                    Text(
                        day.dayOfWeek.getDisplayName(TextStyle.SHORT, locale),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f).padding(start = 6.dp),
                    )
                }
            }
            weeks.forEach { weekDays ->
                Row(Modifier.height(weekHeight).fillMaxWidth()) {
                    weekDays.forEach { day ->
                        MonthCell(
                            day = day,
                            inMonth = YearMonth.from(day.date) == month,
                            isToday = day.date == today,
                            now = now,
                            colorOf = colorOf,
                            onClick = { onOpenDay(day.date) },
                            modifier = Modifier.weight(1f).fillMaxHeight(),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun MonthCell(
    day: AgendaDay,
    inMonth: Boolean,
    isToday: Boolean,
    now: LocalDateTime,
    colorOf: (String) -> Color,
    onClick: () -> Unit,
    modifier: Modifier,
) {
    Column(
        modifier = modifier
            .padding(1.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(if (isToday) MaterialTheme.colorScheme.surfaceContainerHigh else Color.Transparent)
            .clickable(onClick = onClick)
            .alpha(if (inMonth) 1f else OTHER_MONTH_ALPHA)
            .padding(2.dp),
    ) {
        // Worked out before the date, so the count of what doesn't fit can go beside it.
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val room = ((maxHeight - MONTH_DATE_SIZE + MONTH_EVENT_GAP) / (MONTH_EVENT_HEIGHT + MONTH_EVENT_GAP))
                .plus(ROUNDING).toInt().coerceAtLeast(0)
            val shown = day.events.take(room)
            Column(verticalArrangement = Arrangement.spacedBy(MONTH_EVENT_GAP)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier
                            .size(MONTH_DATE_SIZE)
                            .clip(CircleShape)
                            .background(if (isToday) MaterialTheme.colorScheme.primary else Color.Transparent),
                    ) {
                        Text(
                            day.date.dayOfMonth.toString(),
                            style = MaterialTheme.typography.labelMedium,
                            color = if (isToday) OnPersonColor else MaterialTheme.colorScheme.onSurface,
                        )
                    }
                    if (shown.size < day.events.size) {
                        Text(
                            stringResource(R.string.calendar_more, day.events.size - shown.size),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            modifier = Modifier.padding(start = 4.dp),
                        )
                    }
                }
                shown.forEach { event ->
                    val color = colorOf(event.owners.first())
                    Text(
                        event.summary,
                        style = MaterialTheme.typography.labelSmall,
                        color = if (event.allDay) OnPersonColor else MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Clip,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(MONTH_EVENT_HEIGHT)
                            .alpha(if (event.end <= now) PAST_ALPHA else 1f)
                            .clip(RoundedCornerShape(4.dp))
                            .background(if (event.allDay) color else color.copy(alpha = 0.22f))
                            .padding(horizontal = 3.dp),
                    )
                }
            }
        }
    }
}

private const val OTHER_MONTH_ALPHA = 0.4f
private val MONTH_DATE_SIZE = 22.dp
private val MONTH_EVENT_HEIGHT = 18.dp
private val MONTH_EVENT_GAP = 2.dp

/** Room for the month's name and the days of the week. */
private val MONTH_HEADER = 76.dp

/** Enough for the date and an event. */
private val MIN_WEEK_HEIGHT = 52.dp

/** So a day with just room for another line, in fractions of a pixel, gets it. */
private const val ROUNDING = 0.02f

/** One day's events, from a tap on the day, with a way to add another. */
@Composable
internal fun DayDialog(
    date: LocalDate,
    events: List<ShownEvent>,
    now: LocalDateTime,
    colorOf: (String) -> Color,
    canAdd: Boolean,
    onOpen: (ShownEvent) -> Unit,
    onAdd: () -> Unit,
    onDismiss: () -> Unit,
) {
    val locale = LocalConfiguration.current.locales[0]
    val context = LocalContext.current
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(dayName(date, locale)) },
        text = {
            Column(
                verticalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState()),
            ) {
                if (events.isEmpty()) {
                    Text(
                        stringResource(R.string.calendar_day_empty),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                events.forEach { event ->
                    val time = if (event.allDay) null else timeText(context, event.start)
                    EventChip(event, time, past = event.end <= now, colorOf) { onOpen(event) }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.settings_done)) } },
        dismissButton = if (canAdd) {
            { TextButton(onClick = onAdd) { Text(stringResource(R.string.event_add)) } }
        } else {
            null
        },
    )
}

/**
 * Family View: one day, with a column for each of [owners] down a shared timeline, so what overlaps between people
 * shows at a glance. An event for several people is in each one's column. All-day events run across the top.
 */
@Composable
internal fun FamilyDayView(
    events: List<ShownEvent>?,
    owners: List<CalendarOwner>,
    date: LocalDate,
    onDate: (LocalDate) -> Unit,
    now: LocalDateTime,
    colorOf: (String) -> Color,
    onOpen: (ShownEvent) -> Unit,
) {
    val locale = LocalConfiguration.current.locales[0]
    val context = LocalContext.current
    val today = now.toLocalDate()
    val onDay = week(events.orEmpty(), date, 1).first().events
    val timed = onDay.filterNot { it.allDay }
    val hours = timelineHours(timed, date)
    val hourCount = (hours.last - hours.first).coerceAtLeast(1)
    // The day and the names stay put; the hours scroll where they don't all fit, opening at the time now.
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val anyAllDay = onDay.any { it.allDay }
        val top = if (anyAllDay) FAMILY_HEADER + ALL_DAY_BAND else FAMILY_HEADER
        val room = maxHeight - FAMILY_PAGER - top
        val width = maxWidth
        val hourHeight = (room / hourCount).coerceAtLeast(MIN_HOUR_HEIGHT)
        val scroll = rememberScrollState()
        val density = LocalDensity.current
        LaunchedEffect(date, hourHeight, hours) {
            val hoursIn = if (date == today) (now.hour - hours.first - 1).coerceAtLeast(0) else 0
            scroll.scrollTo(with(density) { (hourHeight * hoursIn).roundToPx() })
        }
        Column(Modifier.fillMaxSize()) {
            PagerHeader(
                title = when (date) {
                    today -> stringResource(R.string.forecast_today) + " · " + dayName(date, locale)
                    else -> dayName(date, locale)
                },
                showToday = date != today,
                onBack = { onDate(date.minusDays(1)) },
                onForward = { onDate(date.plusDays(1)) },
                onToday = { onDate(today) },
            )
            val columnWidth = ((width - HOUR_LABEL_WIDTH) / owners.size.coerceAtLeast(1)).coerceAtLeast(MIN_PERSON_WIDTH)
            val fits = columnWidth * owners.size + HOUR_LABEL_WIDTH <= width
            Row(
                Modifier
                    .weight(1f)
                    .then(if (fits) Modifier.fillMaxWidth() else Modifier.horizontalScroll(rememberScrollState())),
            ) {
                Column(Modifier.fillMaxHeight()) {
                    // The names and all-day events, the same height for everyone so the hours line up.
                    Row(Modifier.height(top)) {
                        Spacer(Modifier.width(HOUR_LABEL_WIDTH))
                        owners.forEach { owner ->
                            Column(Modifier.width(columnWidth).padding(horizontal = 2.dp)) {
                                Text(
                                    owner.name,
                                    style = MaterialTheme.typography.labelLarge,
                                    color = owner.color,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.height(FAMILY_HEADER),
                                )
                                if (anyAllDay) {
                                    Column(
                                        verticalArrangement = Arrangement.spacedBy(2.dp),
                                        modifier = Modifier.height(ALL_DAY_BAND).verticalScroll(rememberScrollState()),
                                    ) {
                                        onDay.filter { it.allDay && owner.id in it.owners }.forEach { event ->
                                            AllDayPill(event, owner.color, event.end <= now) { onOpen(event) }
                                        }
                                    }
                                }
                            }
                        }
                    }
                    val lineColor = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
                    Row(Modifier.weight(1f).verticalScroll(scroll)) {
                        Box(Modifier.width(HOUR_LABEL_WIDTH).height(hourHeight * hourCount)) {
                            (hours.first until hours.last).forEach { hour ->
                                Text(
                                    timeText(context, LocalTime.of(hour, 0)),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    modifier = Modifier.offset(y = hourHeight * (hour - hours.first)),
                                )
                            }
                        }
                        owners.forEach { owner ->
                            PersonColumn(
                                events = timed.filter { owner.id in it.owners },
                                date = date,
                                firstHour = hours.first,
                                hourCount = hourCount,
                                hourHeight = hourHeight,
                                width = columnWidth,
                                lineColor = lineColor,
                                now = now,
                                color = owner.color,
                                onOpen = onOpen,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun AllDayPill(event: ShownEvent, color: Color, past: Boolean, onClick: () -> Unit) {
    Text(
        event.summary,
        style = MaterialTheme.typography.labelMedium,
        color = OnPersonColor,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier
            .fillMaxWidth()
            .alpha(if (past) PAST_ALPHA else 1f)
            .clip(RoundedCornerShape(6.dp))
            .background(color)
            .clickable(onClick = onClick)
            .padding(horizontal = 6.dp, vertical = 2.dp),
    )
}

/** One person's day: a line each hour, the time now, and their events in place, side by side where they overlap. */
@Composable
private fun PersonColumn(
    events: List<ShownEvent>,
    date: LocalDate,
    firstHour: Int,
    hourCount: Int,
    hourHeight: Dp,
    width: Dp,
    lineColor: Color,
    now: LocalDateTime,
    color: Color,
    onOpen: (ShownEvent) -> Unit,
) {
    val context = LocalContext.current
    val nowColor = MaterialTheme.colorScheme.primary
    val isToday = now.toLocalDate() == date
    fun hoursIn(time: LocalDateTime): Float {
        val start = date.atTime(firstHour, 0)
        val minutes = java.time.Duration.between(start, time).toMinutes().toFloat() / 60f
        return minutes.coerceIn(0f, hourCount.toFloat())
    }
    Box(
        Modifier
            .width(width)
            .height(hourHeight * hourCount)
            .padding(horizontal = 2.dp)
            .drawBehind {
                val step = hourHeight.toPx()
                for (hour in 0..hourCount) {
                    drawLine(lineColor, Offset(0f, step * hour), Offset(size.width, step * hour), 1f)
                }
                if (isToday) {
                    val y = hoursIn(now) * step
                    drawLine(nowColor, Offset(0f, y), Offset(size.width, y), 2.dp.toPx())
                }
            },
    ) {
        val laneWidth = width - 4.dp
        timeline(events).forEach { (event, lane, lanes) ->
            val top = hoursIn(event.start)
            // A short event still gets room to read and tap.
            val bottom = maxOf(hoursIn(event.end), top + 0.5f)
            Column(
                Modifier
                    .offset(x = laneWidth / lanes * lane, y = hourHeight * top)
                    .width(laneWidth / lanes)
                    .height(hourHeight * (bottom - top))
                    .padding(1.dp)
                    .alpha(if (event.end <= now) PAST_ALPHA else 1f)
                    .clip(RoundedCornerShape(6.dp))
                    .background(color.copy(alpha = 0.25f))
                    .clickable { onOpen(event) }
                    .padding(horizontal = 4.dp, vertical = 2.dp),
            ) {
                Text(
                    event.summary,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                if (hourHeight * (bottom - top) >= 44.dp) {
                    Text(
                        timeText(context, event.start),
                        style = MaterialTheme.typography.labelSmall,
                        color = color,
                        maxLines = 1,
                    )
                }
            }
        }
    }
}

private val HOUR_LABEL_WIDTH = 52.dp
private val MIN_PERSON_WIDTH = 96.dp
private val MIN_HOUR_HEIGHT = 30.dp
private val FAMILY_HEADER = 22.dp
private val ALL_DAY_BAND = 28.dp

/** Room for the day's name above it. */
private val FAMILY_PAGER = 48.dp
