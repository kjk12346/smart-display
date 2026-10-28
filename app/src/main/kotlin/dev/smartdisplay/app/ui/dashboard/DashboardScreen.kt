package dev.smartdisplay.app.ui.dashboard

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.coerceIn
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.smartdisplay.app.R
import dev.smartdisplay.app.ha.ConnectionStatus
import dev.smartdisplay.app.ha.HomeState
import dev.smartdisplay.app.kiosk.KitchenConfig
import dev.smartdisplay.app.kiosk.personColors
import dev.smartdisplay.app.kiosk.WallpaperConfig
import dev.smartdisplay.app.ui.ambient.AmbientViewModel
import dev.smartdisplay.app.ui.ambient.Clock
import dev.smartdisplay.app.ui.ambient.ForecastDay
import dev.smartdisplay.app.ui.ambient.KeepWeatherAndPhotosFresh
import dev.smartdisplay.app.ui.ambient.WallpaperBackground
import dev.smartdisplay.app.ui.ambient.WeatherIcon
import dev.smartdisplay.app.ui.ambient.burnInOffset
import dev.smartdisplay.app.ui.ambient.conditionLabel
import dev.smartdisplay.app.ui.ambient.formatTemperature
import dev.smartdisplay.app.ui.ambient.primaryWeatherEntity
import dev.smartdisplay.app.ui.ambient.rememberNextAlarmText
import dev.smartdisplay.app.ui.ambient.sunIsDown
import dev.smartdisplay.app.ui.ambient.toCurrentWeather
import dev.smartdisplay.app.ui.common.rememberMinuteClock
import dev.smartdisplay.app.ui.theme.FamilyColor
import dev.smartdisplay.app.ui.theme.OnPersonColor
import dev.smartdisplay.app.ui.theme.color
import dev.smartdisplay.app.ui.winddown.WindDownButton
import java.time.LocalDate
import java.time.format.TextStyle

/**
 * The kitchen display's daytime screen: the time and weather with a few days' forecast, the family calendar, who's
 * home with Home and Away buttons, the owner's buttons for scenes, scripts and automations, and a photo.
 *
 * Done goes straight to the clock over the photos, as being left alone does after a while.
 *
 * Wide screens give most of the room to the calendar's week, with the rest in a column beside it; narrow ones stack
 * everything, calendar first, and scroll. With no calendars shown, the calendar is left out and the rest share the
 * width. Each person has a color, on their calendar events and their avatar.
 */
@Composable
fun DashboardScreen(
    home: HomeState,
    kitchen: KitchenConfig,
    wallpaperConfig: WallpaperConfig,
    onOpenControls: () -> Unit,
    onOpenAlarms: () -> Unit,
    onOpenSettings: () -> Unit,
    onDone: () -> Unit,
    ambient: AmbientViewModel = viewModel(),
    viewModel: DashboardViewModel = viewModel(),
) {
    val weatherEntity = home.primaryWeatherEntity()
    KeepWeatherAndPhotosFresh(ambient, home, weatherEntity?.entityId, wallpaperConfig, photos = true)
    val forecastDays by ambient.forecastDays.collectAsStateWithLifecycle()
    val wallpaper by ambient.wallpaper.collectAsStateWithLifecycle()
    val runs by viewModel.runs.collectAsStateWithLifecycle()
    val calendarIds = remember(home.entities, home.registry, kitchen.hiddenCalendars) {
        home.calendars().map { it.entityId }.filterNot { it in kitchen.hiddenCalendars }
    }
    val connected = home.status == ConnectionStatus.Connected
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(connected, calendarIds) {
        if (connected) lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) { viewModel.keepCalendarFresh(calendarIds) }
    }
    val events by viewModel.events.collectAsStateWithLifecycle()
    val selectedOwners by viewModel.selectedOwners.collectAsStateWithLifecycle()
    val now by rememberMinuteClock()
    val nextAlarm = rememberNextAlarmText(now)

    val days = forecastDays?.takeIf { it.first == weatherEntity?.entityId }?.second.orEmpty()
        .filter { !it.date.isBefore(now.toLocalDate()) }
        .take(FORECAST_DAYS)
    val photoUrl = wallpaper?.takeIf { it.folderId == wallpaperConfig.folderId }?.url
    val names = home.entities
    val people = home.people()
    val colors = remember(people.map { it.entityId }.toSet(), kitchen.personColors) {
        personColors(people.map { it.entityId }, kitchen.personColors)
    }
    val personColor = { personId: String -> colors[personId]?.color() }
    fun ownerOf(calendarId: String) = calendarOwner(calendarId, kitchen.calendarOwners, colors.keys)
    // Everyone in the household, by name, for giving events to.
    val household = people.sortedBy { it.name.lowercase() }.map { person ->
        CalendarOwner(person.entityId, person.name.substringBefore(' '), personColor(person.entityId)!!)
    }
    // Null until the first fetch for these calendars, so the card starts empty, not "nothing coming up".
    val shownEvents = remember(events, calendarIds, kitchen.calendarOwners, kitchen.eventPeople, colors.keys) {
        events?.takeIf { it.first == calendarIds }?.second?.let {
            mergeEvents(it, ::ownerOf, kitchen.eventPeople, colors.keys)
        }
    }
    // The chips: people with a calendar or an event shown, then the family if any of those is everyone's.
    val owners = (calendarIds.map(::ownerOf) + shownEvents.orEmpty().flatMap { it.owners }).toSet().let { ids ->
        household.filter { it.id in ids } + listOfNotNull(
            CalendarOwner(FAMILY, stringResource(R.string.calendar_family), FamilyColor).takeIf { FAMILY in ids },
        )
    }
    // A choice of someone who no longer has a calendar shown is dropped.
    val selected = selectedOwners.filterTo(mutableSetOf()) { id -> owners.any { it.id == id } }

    val currentWeather: @Composable () -> Unit = {
        val weather = weatherEntity?.toCurrentWeather()
        if (weather != null) {
            val night = home.sunIsDown() ?: false
            WeatherIcon(weather.condition, night, size = 48.dp)
            Column(modifier = Modifier.padding(start = 12.dp, end = 24.dp)) {
                Text(
                    weather.temperature?.let(::formatTemperature) ?: "–",
                    fontSize = 36.sp,
                    fontWeight = FontWeight.Light,
                    color = MaterialTheme.colorScheme.onBackground,
                )
                Text(
                    conditionLabel(weather.condition, night),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
    val navButtons: @Composable () -> Unit = {
        TextButton(onClick = onOpenControls) { Text(stringResource(R.string.dashboard_rooms)) }
        WindDownButton()
        TextButton(onClick = onOpenAlarms) { Text(stringResource(R.string.alarms_button)) }
        TextButton(onClick = onOpenSettings) { Text(stringResource(R.string.settings_eyebrow)) }
    }
    // Straight to the screensaver (the clock over the photos), rather than waiting for it.
    val doneButton: @Composable () -> Unit = {
        FilledTonalButton(onClick = onDone, modifier = Modifier.padding(start = 8.dp)) {
            Text(stringResource(R.string.settings_done))
        }
    }
    // Wide: one row. Narrow: the clock and weather, then the forecast, then the buttons, each on its own line.
    val header: @Composable (wide: Boolean, clock: Dp) -> Unit = { wide, clock ->
        if (wide) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Clock(now, nextAlarm, size = clock, alignment = Alignment.Start)
                Spacer(Modifier.weight(1f))
                currentWeather()
                ForecastStrip(days, today = now.toLocalDate())
                Spacer(Modifier.weight(1f))
                // Two by two, so a fourth button (Wind down) doesn't make the header taller.
                FlowRow(maxItemsInEachRow = 2, horizontalArrangement = Arrangement.End) { navButtons() }
                doneButton()
            }
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                    Clock(now, nextAlarm, size = clock, alignment = Alignment.Start)
                    Spacer(Modifier.weight(1f))
                    currentWeather()
                }
                ForecastStrip(days, today = now.toLocalDate())
                FlowRow(verticalArrangement = Arrangement.Center) {
                    navButtons()
                    doneButton()
                }
            }
        }
    }
    val peopleCard: @Composable (Modifier, Boolean) -> Unit = { modifier, scroll ->
        PeopleCard(
            people = people,
            colorOf = personColor,
            kitchen = kitchen,
            runs = runs,
            onRun = viewModel::run,
            modifier = modifier,
            scroll = scroll,
        )
    }
    val showCalendar = calendarIds.isNotEmpty()
    val calendarCard: @Composable (Modifier, Boolean) -> Unit = { modifier, bounded ->
        CalendarCard(
            events = shownEvents,
            owners = owners,
            people = household,
            selected = selected,
            onToggle = { viewModel.toggleOwner(it, owners.map { owner -> owner.id }) },
            onEveryone = viewModel::showEveryone,
            calendarName = { names[it]?.friendlyName ?: it },
            onAssign = viewModel::assign,
            now = now,
            bounded = bounded,
            modifier = modifier,
        )
    }
    val buttonsCard: @Composable (Modifier, Boolean) -> Unit = { modifier, scroll ->
        ButtonsCard(
            shortcuts = kitchen.shortcuts.filter { it in names },
            nameOf = { names[it]?.friendlyName ?: it },
            runs = runs,
            onRun = viewModel::run,
            modifier = modifier,
            scroll = scroll,
        )
    }

    // The whole dashboard drifts a few pixels each minute, like the clock, so nothing sits in one place all day.
    val epochMinute = now.toLocalDate().toEpochDay() * 1_440 + now.hour * 60 + now.minute
    val (shiftX, shiftY) = burnInOffset(epochMinute)
    val driftX by animateDpAsState(shiftX.dp, tween(DRIFT_MS), label = "driftX")
    val driftY by animateDpAsState(shiftY.dp, tween(DRIFT_MS), label = "driftY")

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .safeDrawingPadding()
            .padding(horizontal = 24.dp, vertical = 16.dp)
            .offset(driftX, driftY),
    ) {
        if (maxWidth > maxHeight && maxWidth >= 720.dp) {
            // Bigger on taller screens: 56 dp on a phone in landscape, about 80 on a big tablet.
            val clock = (maxHeight * 0.11f).coerceIn(56.dp, 80.dp)
            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                header(true, clock)
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp), modifier = Modifier.weight(1f)) {
                    if (showCalendar) {
                        calendarCard(Modifier.weight(2.6f).fillMaxHeight(), true)
                        // The photo only where the column is tall enough for it to be more than a strip.
                        BoxWithConstraints(Modifier.weight(1f).fillMaxHeight()) {
                            val photo = maxHeight >= 520.dp
                            val peopleMax = maxHeight * 0.6f
                            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                                // Who's home takes what it needs (its Home and Away buttons included), up to most of
                                // the column; the buttons get the rest.
                                peopleCard(Modifier.fillMaxWidth().heightIn(max = peopleMax), true)
                                buttonsCard(Modifier.weight(1f).fillMaxWidth(), true)
                                if (photo) PhotoTile(photoUrl, wallpaperConfig, Modifier.weight(1.2f).fillMaxWidth())
                            }
                        }
                    } else {
                        peopleCard(Modifier.weight(1f).fillMaxHeight(), true)
                        buttonsCard(Modifier.weight(1.3f).fillMaxHeight(), true)
                        PhotoTile(photoUrl, wallpaperConfig, Modifier.weight(1.2f).fillMaxHeight())
                    }
                }
            }
        } else {
            // A phone's width keeps 56 dp; a tablet in portrait gets up to 80.
            val clock = (maxWidth * 0.11f).coerceIn(56.dp, 80.dp)
            Column(
                verticalArrangement = Arrangement.spacedBy(16.dp),
                modifier = Modifier.verticalScroll(rememberScrollState()),
            ) {
                header(false, clock)
                if (showCalendar) calendarCard(Modifier.fillMaxWidth(), false)
                peopleCard(Modifier.fillMaxWidth(), false)
                buttonsCard(Modifier.fillMaxWidth(), false)
                PhotoTile(photoUrl, wallpaperConfig, Modifier.fillMaxWidth().aspectRatio(4f / 3f))
            }
        }
    }
}

private const val FORECAST_DAYS = 5
private const val DRIFT_MS = 2_000

@Composable
private fun ForecastStrip(days: List<ForecastDay>, today: LocalDate) {
    val locale = LocalConfiguration.current.locales[0]
    Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        days.forEach { day ->
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    if (day.date == today) {
                        stringResource(R.string.forecast_today)
                    } else {
                        day.date.dayOfWeek.getDisplayName(TextStyle.SHORT, locale)
                    },
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                WeatherIcon(
                    day.condition ?: "", night = false, size = 32.dp, modifier = Modifier.padding(vertical = 4.dp),
                )
                Text(
                    listOfNotNull(day.high, day.low).joinToString(" ") { formatTemperature(it) },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onBackground,
                )
            }
        }
    }
}

/**
 * A titled card. In a column of fixed height it [scroll]s by itself; stacked in a page that scrolls, it mustn't
 * (Compose doesn't allow a scrolling view inside another scrolling the same way).
 */
@Composable
private fun DashCard(
    title: String,
    modifier: Modifier,
    scroll: Boolean,
    content: @Composable ColumnScope.() -> Unit,
) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainer,
        shape = RoundedCornerShape(24.dp),
        modifier = modifier,
    ) {
        Column(
            modifier = Modifier
                .then(if (scroll) Modifier.verticalScroll(rememberScrollState()) else Modifier)
                .padding(20.dp),
        ) {
            Text(title, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.secondary)
            Spacer(Modifier.size(12.dp))
            content()
        }
    }
}

@Composable
private fun PeopleCard(
    people: List<Person>,
    colorOf: (personId: String) -> Color?,
    kitchen: KitchenConfig,
    runs: Map<String, RunState>,
    onRun: (String) -> Unit,
    modifier: Modifier,
    scroll: Boolean,
) {
    DashCard(stringResource(R.string.dashboard_people), modifier, scroll) {
        if (people.isEmpty()) {
            Text(
                stringResource(R.string.dashboard_no_people),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        people.forEach { person ->
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 6.dp)) {
                val home = person.presence == Presence.Home
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .size(44.dp)
                        .clip(CircleShape)
                        .background(
                            if (home) {
                                colorOf(person.entityId) ?: MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.surfaceContainerHighest
                            }
                        ),
                ) {
                    Text(
                        person.initials,
                        style = MaterialTheme.typography.titleMedium,
                        color = if (home) OnPersonColor else MaterialTheme.colorScheme.onSurface,
                    )
                }
                Column(modifier = Modifier.padding(start = 12.dp)) {
                    Text(
                        person.name,
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        when (person.presence) {
                            Presence.Home -> stringResource(R.string.presence_home)
                            Presence.Away -> person.place ?: stringResource(R.string.presence_away)
                            Presence.Unknown -> stringResource(R.string.presence_unknown)
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        val homeAction = kitchen.homeAction
        val awayAction = kitchen.awayAction
        if (homeAction != null || awayAction != null) {
            // Side by side in equal halves, so neither gets squeezed in a narrow column.
            Row(
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.fillMaxWidth().padding(top = 16.dp),
            ) {
                if (homeAction != null) {
                    val label = stringResource(R.string.mode_home)
                    RunButton(label, runs[homeAction], filled = true, Modifier.weight(1f)) { onRun(homeAction) }
                }
                if (awayAction != null) {
                    val label = stringResource(R.string.mode_away)
                    RunButton(label, runs[awayAction], filled = false, Modifier.weight(1f)) { onRun(awayAction) }
                }
            }
        }
    }
}

@Composable
private fun ButtonsCard(
    shortcuts: List<String>,
    nameOf: (String) -> String,
    runs: Map<String, RunState>,
    onRun: (String) -> Unit,
    modifier: Modifier,
    scroll: Boolean,
) {
    DashCard(stringResource(R.string.dashboard_buttons), modifier, scroll) {
        if (shortcuts.isEmpty()) {
            Text(
                stringResource(R.string.dashboard_no_buttons),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            shortcuts.forEach { id -> RunButton(nameOf(id), runs[id], filled = false) { onRun(id) } }
        }
    }
}

/** A big button that runs something, showing for a moment whether it worked. */
@Composable
private fun RunButton(
    label: String,
    state: RunState?,
    filled: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val text = when (state) {
        RunState.Running -> "…"
        RunState.Done -> stringResource(R.string.run_done)
        RunState.Failed -> stringResource(R.string.run_failed)
        null -> label
    }
    val size = modifier.heightIn(min = 64.dp).widthIn(min = 120.dp)
    val padding = PaddingValues(horizontal = 16.dp, vertical = 8.dp)
    val content: @Composable () -> Unit = {
        Text(text, fontSize = 18.sp, textAlign = TextAlign.Center, maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
    if (filled) {
        Button(onClick = onClick, modifier = size, contentPadding = padding) { content() }
    } else {
        FilledTonalButton(
            onClick = onClick,
            modifier = size,
            contentPadding = padding,
            colors = if (state == RunState.Failed) {
                ButtonDefaults.filledTonalButtonColors(containerColor = MaterialTheme.colorScheme.errorContainer)
            } else {
                ButtonDefaults.filledTonalButtonColors()
            },
        ) { content() }
    }
}

@Composable
private fun PhotoTile(url: String?, config: WallpaperConfig, modifier: Modifier) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier
            .clip(RoundedCornerShape(24.dp))
            .background(MaterialTheme.colorScheme.surfaceContainer),
    ) {
        if (url != null) {
            WallpaperBackground(url, config.intervalMinutes * 60_000L, scrim = false)
        } else {
            Text(
                stringResource(R.string.dashboard_no_photos),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(24.dp),
            )
        }
    }
}
