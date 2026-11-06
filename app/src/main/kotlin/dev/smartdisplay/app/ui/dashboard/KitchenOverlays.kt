package dev.smartdisplay.app.ui.dashboard

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.smartdisplay.app.R
import dev.smartdisplay.app.alarm.timeText
import dev.smartdisplay.app.ha.ConnectionStatus
import dev.smartdisplay.app.ha.HomeState
import dev.smartdisplay.app.kiosk.KitchenConfig
import dev.smartdisplay.app.kiosk.coming
import dev.smartdisplay.app.kiosk.personColors
import dev.smartdisplay.app.ui.ambient.AmbientViewModel
import dev.smartdisplay.app.ui.ambient.Clock
import dev.smartdisplay.app.ui.ambient.QUIET_END
import dev.smartdisplay.app.ui.ambient.WeatherIcon
import dev.smartdisplay.app.ui.ambient.burnInOffset
import dev.smartdisplay.app.ui.ambient.conditionLabel
import dev.smartdisplay.app.ui.ambient.formatTemperature
import dev.smartdisplay.app.ui.ambient.primaryWeatherEntity
import dev.smartdisplay.app.ui.ambient.rememberNextAlarmText
import dev.smartdisplay.app.ui.ambient.sunIsDown
import dev.smartdisplay.app.ui.ambient.toCurrentWeather
import dev.smartdisplay.app.ui.common.rememberMinuteClock
import dev.smartdisplay.app.ui.theme.color
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime

/** The morning summary comes up by itself until this time, if nobody has dismissed it. */
private val MORNING_UNTIL: LocalTime = LocalTime.of(9, 0)

/**
 * What a kitchen display does whichever screen is showing: keeps the calendar fresh, says reminders, and brings up the
 * morning summary. [resting] is the clock or dashboard, the only screens these show over. [summaryOpen] is the morning
 * summary, whether it came up by itself or from a tap on the clock.
 */
@Composable
fun KitchenOverlays(
    home: HomeState,
    kitchen: KitchenConfig,
    resting: Boolean,
    summaryOpen: Boolean,
    onSummaryOpen: (Boolean) -> Unit,
    viewModel: DashboardViewModel = viewModel(),
    ambient: AmbientViewModel = viewModel(),
) {
    val calendarIds = remember(home.entities, home.registry, kitchen.hiddenCalendars) {
        home.calendars().map { it.entityId }.filterNot { it in kitchen.hiddenCalendars }
    }
    val connected = home.status == ConnectionStatus.Connected
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(connected, calendarIds) {
        if (connected) lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) { viewModel.keepCalendarFresh(calendarIds) }
    }
    val now by rememberMinuteClock()
    val events by viewModel.events.collectAsStateWithLifecycle()
    val people = home.people()
    val colors = remember(people.map { it.entityId }.toSet(), kitchen.personColors) {
        personColors(people.map { it.entityId }, kitchen.personColors)
    }
    val shown = remember(events, calendarIds, kitchen.calendarOwners, kitchen.eventPeople, colors.keys) {
        events?.takeIf { it.first == calendarIds }?.second?.let { list ->
            mergeEvents(
                list,
                { calendarOwner(it, kitchen.calendarOwners, colors.keys) },
                kitchen.eventPeople,
                colors.keys,
            )
        }
    }

    LaunchedEffect(now) {
        // Nothing to remind about until there are events; the time keeps moving, so none fires late on arrival.
        viewModel.checkReminders(shown.orEmpty(), kitchen.reminders, now)
    }

    // At the end of the quiet hours (once a day), unless nobody wants it.
    LaunchedEffect(now, kitchen.morningSummary, resting) {
        val today = now.toLocalDate()
        val time = now.toLocalTime()
        when {
            time >= MORNING_UNTIL && viewModel.morningShownOn == today && summaryOpen && viewModel.morningAuto -> {
                viewModel.morningAuto = false
                onSummaryOpen(false)
            }
            kitchen.morningSummary && resting && viewModel.morningShownOn != today &&
                time >= QUIET_END && time < MORNING_UNTIL -> {
                viewModel.morningShownOn = today
                viewModel.morningAuto = true
                onSummaryOpen(true)
            }
        }
    }

    if (resting && summaryOpen) {
        val household = people.sortedBy { it.name.lowercase() }.map { person ->
            CalendarOwner(
                person.entityId,
                person.name.substringBefore(' '),
                colors[person.entityId]?.color() ?: Color.Gray,
            )
        }
        val forecast by ambient.forecastDays.collectAsStateWithLifecycle()
        val weatherEntity = home.primaryWeatherEntity(kitchen.weatherEntity)
        val today = forecast?.takeIf { it.first == weatherEntity?.entityId }?.second
            ?.firstOrNull { it.date == now.toLocalDate() }
        MorningSummary(
            home = home,
            kitchen = kitchen,
            events = shown.orEmpty(),
            household = household,
            high = today?.high,
            low = today?.low,
            now = now,
            onDismiss = {
                viewModel.morningAuto = false
                onSummaryOpen(false)
            },
        )
    }
    if (resting) {
        val reminders by viewModel.reminders.collectAsStateWithLifecycle()
        ReminderBanners(reminders, viewModel::dismissReminder)
    }
}

/** Reminders on screen, across the top until dismissed or a few minutes have passed. */
@Composable
private fun ReminderBanners(reminders: List<ReminderNotice>, onDismiss: (ReminderNotice) -> Unit) {
    if (reminders.isEmpty()) return
    val context = LocalContext.current
    Box(Modifier.fillMaxSize().safeDrawingPadding().padding(top = 12.dp), contentAlignment = Alignment.TopCenter) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            reminders.forEach { notice ->
                Surface(
                    color = MaterialTheme.colorScheme.primaryContainer,
                    contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                    shape = RoundedCornerShape(20.dp),
                    shadowElevation = 6.dp,
                    modifier = Modifier.widthIn(min = 320.dp, max = 640.dp),
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(start = 20.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
                    ) {
                        Column(Modifier.weight(1f, fill = false).padding(end = 12.dp)) {
                            Text(
                                notice.event.summary.ifEmpty { stringResource(R.string.event_untitled) },
                                style = MaterialTheme.typography.titleMedium,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                if (notice.event.allDay) {
                                    stringResource(R.string.calendar_all_day)
                                } else {
                                    timeText(context, notice.event.start)
                                },
                                style = MaterialTheme.typography.bodyMedium,
                            )
                        }
                        TextButton(onClick = { onDismiss(notice) }) { Text(stringResource(R.string.reminder_dismiss)) }
                    }
                }
            }
        }
    }
}

/** Today at a glance: the day's events, the weather, routines still to do, what's coming and pinned notes. */
@Composable
private fun MorningSummary(
    home: HomeState,
    kitchen: KitchenConfig,
    events: List<ShownEvent>,
    household: List<CalendarOwner>,
    high: Double?,
    low: Double?,
    now: LocalDateTime,
    onDismiss: () -> Unit,
) {
    BackHandler(onBack = onDismiss)
    val today = now.toLocalDate()
    val nextAlarm = rememberNextAlarmText(now)
    val epochMinute = today.toEpochDay() * 1_440 + now.hour * 60 + now.minute
    val (driftX, driftY) = burnInOffset(epochMinute)
    Surface(
        color = MaterialTheme.colorScheme.background,
        contentColor = MaterialTheme.colorScheme.onBackground,
        modifier = Modifier.fillMaxSize().clickable(onClick = onDismiss),
    ) {
        BoxWithConstraints(
            Modifier
                .fillMaxSize()
                .safeDrawingPadding()
                .padding(horizontal = 36.dp, vertical = 24.dp)
                .offset(driftX.dp, driftY.dp),
        ) {
            val wide = maxWidth > maxHeight && maxWidth >= 720.dp
            val heading: @Composable () -> Unit = {
                Text(
                    stringResource(greeting(now.toLocalTime())),
                    style = MaterialTheme.typography.headlineMedium,
                    color = MaterialTheme.colorScheme.secondary,
                )
                Clock(now, nextAlarm, size = 64.dp, alignment = Alignment.Start)
                SummaryWeather(home, kitchen, high, low)
            }
            val agenda: @Composable () -> Unit = { TodaysEvents(events, today, now, household) }
            val more: @Composable () -> Unit = { TodaysExtras(kitchen, household, today) }
            if (wide) {
                Row(horizontalArrangement = Arrangement.spacedBy(36.dp), modifier = Modifier.fillMaxSize()) {
                    Column(
                        verticalArrangement = Arrangement.spacedBy(16.dp),
                        modifier = Modifier.weight(1f).verticalScroll(rememberScrollState()),
                    ) {
                        heading()
                        more()
                    }
                    Column(
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                        modifier = Modifier.weight(1.3f).verticalScroll(rememberScrollState()),
                    ) { agenda() }
                }
            } else {
                Column(
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                    modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
                ) {
                    heading()
                    agenda()
                    more()
                }
            }
            Text(
                stringResource(R.string.summary_tap),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.align(Alignment.BottomEnd),
            )
        }
    }
}

private fun greeting(time: LocalTime): Int = when {
    time.hour < 12 -> R.string.summary_morning
    time.hour < 18 -> R.string.summary_afternoon
    else -> R.string.summary_evening
}

@Composable
private fun SummaryWeather(home: HomeState, kitchen: KitchenConfig, high: Double?, low: Double?) {
    val weather = home.primaryWeatherEntity(kitchen.weatherEntity)?.toCurrentWeather() ?: return
    val night = home.sunIsDown() ?: false
    Row(verticalAlignment = Alignment.CenterVertically) {
        WeatherIcon(weather.condition, night, size = 44.dp)
        Column(Modifier.padding(start = 12.dp)) {
            Text(
                weather.temperature?.let(::formatTemperature) ?: "–",
                fontSize = 30.sp,
                fontWeight = FontWeight.Light,
                color = MaterialTheme.colorScheme.onBackground,
            )
            Text(
                listOfNotNull(
                    conditionLabel(weather.condition, night),
                    listOfNotNull(high, low).joinToString(" / ") { formatTemperature(it) }.takeIf { it.isNotEmpty() },
                ).joinToString(" · "),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun TodaysEvents(events: List<ShownEvent>, today: LocalDate, now: LocalDateTime, household: List<CalendarOwner>) {
    val context = LocalContext.current
    val onToday = week(events, today, 1).first().events
    Text(
        stringResource(R.string.summary_today),
        style = MaterialTheme.typography.titleMedium,
        color = MaterialTheme.colorScheme.secondary,
    )
    if (onToday.isEmpty()) {
        Text(stringResource(R.string.summary_no_events), color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    val colors = household.associate { it.id to it.color }
    onToday.forEach { event ->
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth().alpha(if (event.end <= now) PAST_ALPHA else 1f),
        ) {
            Text(
                if (event.allDay) stringResource(R.string.calendar_all_day) else timeText(context, event.start),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.widthIn(min = 84.dp),
            )
            event.owners.forEach { owner ->
                Box(
                    Modifier
                        .padding(end = 4.dp)
                        .size(10.dp)
                        .clip(CircleShape)
                        .background(colors[owner] ?: MaterialTheme.colorScheme.onSurfaceVariant),
                )
            }
            Text(
                event.summary.ifEmpty { stringResource(R.string.event_untitled) },
                style = MaterialTheme.typography.titleMedium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(start = 6.dp),
            )
        }
    }
}

/** Only what there is: routines someone still has today, the next countdown or two, and pinned notes. */
@Composable
private fun TodaysExtras(kitchen: KitchenConfig, household: List<CalendarOwner>, today: LocalDate) {
    val book = kitchen.routineBook
    household.forEach { person ->
        val (done, total) = book.progress(person.id, today)
        if (total > 0) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(10.dp).clip(CircleShape).background(person.color))
                Text(
                    if (done == total) {
                        stringResource(R.string.routines_all_done, person.name)
                    } else {
                        pluralStringResource(R.plurals.summary_routines_left, total - done, person.name, total - done)
                    },
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.padding(start = 8.dp),
                )
            }
        }
    }
    kitchen.countdowns.coming(today).take(SUMMARY_COUNTDOWNS).forEach { CountdownRow(it, today) }
    kitchen.stickies.filter { it.pinned }.forEach { StickyNote(it) }
}

private const val SUMMARY_COUNTDOWNS = 2
