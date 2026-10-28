package dev.smartdisplay.app.ui.dashboard

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
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
import dev.smartdisplay.app.kiosk.CardType
import dev.smartdisplay.app.kiosk.DashCard
import dev.smartdisplay.app.kiosk.DashLayout
import dev.smartdisplay.app.kiosk.Grid
import dev.smartdisplay.app.kiosk.KitchenConfig
import dev.smartdisplay.app.kiosk.TALL_GRID
import dev.smartdisplay.app.kiosk.WIDE_GRID
import dev.smartdisplay.app.kiosk.WallpaperConfig
import dev.smartdisplay.app.kiosk.personColors
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
import dev.smartdisplay.app.ui.controls.ControlsViewModel
import dev.smartdisplay.app.ui.controls.ServiceCall
import dev.smartdisplay.app.ui.theme.FamilyColor
import dev.smartdisplay.app.ui.theme.OnPersonColor
import dev.smartdisplay.app.ui.theme.color
import dev.smartdisplay.app.ui.winddown.WindDownButton
import java.time.LocalDate
import java.time.format.TextStyle

/**
 * The kitchen display's daytime screen: the time and weather with a few days' forecast, the family calendar, who's
 * home with Home and Away buttons, and the owner's buttons for scenes, scripts and automations, over the screensaver's
 * photos (dimmed).
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
    /** Home Assistant's address, for pictures it gives as paths (cameras, cover art). */
    baseUrl: String?,
    /** Arranging the cards: asked for with a long press (the caller checks the PIN), left with Done. */
    editing: Boolean,
    onEditRequest: () -> Unit,
    onEditDone: () -> Unit,
    onKitchenChange: (KitchenConfig) -> Unit,
    ambient: AmbientViewModel = viewModel(),
    viewModel: DashboardViewModel = viewModel(),
    controls: ControlsViewModel = viewModel(),
) {
    val weatherEntity = home.primaryWeatherEntity(kitchen.weatherEntity)
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
    var choosingWeather by remember { mutableStateOf(false) }
    var choosingStatus by remember { mutableStateOf(false) }
    var resetting by remember { mutableStateOf<Grid?>(null) }
    var adding by remember { mutableStateOf(false) }
    var sourcesOf by remember { mutableStateOf<String?>(null) }
    val layout = kitchen.layout
    val onLayoutChange = { changed: DashLayout -> onKitchenChange(kitchen.copy(layout = changed)) }
    // While arranging, Reset puts back the cards of the grid on screen.
    val navButtons: @Composable (grid: Grid) -> Unit = { grid ->
        if (editing) {
            TextButton(onClick = { choosingWeather = true }) { Text(stringResource(R.string.dashboard_edit_weather)) }
            TextButton(onClick = { choosingStatus = true }) { Text(stringResource(R.string.dashboard_edit_status)) }
            TextButton(onClick = { adding = true }) { Text(stringResource(R.string.dashboard_edit_add)) }
            TextButton(onClick = { resetting = grid }) { Text(stringResource(R.string.dashboard_edit_reset)) }
        } else {
            TextButton(onClick = onOpenControls) { Text(stringResource(R.string.dashboard_rooms)) }
            WindDownButton()
            TextButton(onClick = onOpenAlarms) { Text(stringResource(R.string.alarms_button)) }
            TextButton(onClick = onOpenSettings) { Text(stringResource(R.string.settings_eyebrow)) }
        }
    }
    // Straight to the screensaver (the clock over the photos), rather than waiting for it; or, while arranging, back
    // to the dashboard.
    val doneButton: @Composable () -> Unit = {
        if (editing) {
            Button(onClick = onEditDone, modifier = Modifier.padding(start = 8.dp)) {
                Text(stringResource(R.string.dashboard_edit_done))
            }
        } else {
            FilledTonalButton(onClick = onDone, modifier = Modifier.padding(start = 8.dp)) {
                Text(stringResource(R.string.settings_done))
            }
        }
    }
    val statusChips = home.statusChips(kitchen.statusEntities)
    // Wide: one row, with the status chips under the weather. Narrow: the clock and weather, then the forecast, the
    // status chips and the buttons, each on its own line.
    val header: @Composable (wide: Boolean, clock: Dp) -> Unit = { wide, clock ->
        if (wide) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Clock(now, nextAlarm, size = clock, alignment = Alignment.Start)
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier.weight(1f).padding(horizontal = 16.dp),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        currentWeather()
                        ForecastStrip(days, today = now.toLocalDate())
                    }
                    if (statusChips.isNotEmpty()) StatusBar(statusChips)
                }
                // Two by two, so a fourth button (Wind down) doesn't make the header taller.
                FlowRow(maxItemsInEachRow = 2, horizontalArrangement = Arrangement.End) { navButtons(WIDE_GRID) }
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
                if (statusChips.isNotEmpty()) StatusBar(statusChips)
                FlowRow(verticalArrangement = Arrangement.Center) {
                    navButtons(TALL_GRID)
                    doneButton()
                }
            }
        }
    }
    val peopleCard: @Composable (Modifier, Boolean) -> Unit = { modifier, scroll ->
        PeopleCard(
            people = people.filterNot { it.entityId in kitchen.hiddenPeople },
            colorOf = personColor,
            kitchen = kitchen,
            runs = runs,
            onRun = viewModel::run,
            modifier = modifier,
            scroll = scroll,
        )
    }
    val calendarCard: @Composable (Modifier, Boolean) -> Unit = { modifier, bounded ->
        if (calendarIds.isEmpty()) {
            TitledCard(stringResource(R.string.dashboard_calendar), modifier, bounded) {
                Text(
                    stringResource(R.string.kitchen_calendars_none),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
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

    val onCall = { call: ServiceCall, name: String -> controls.call(call, name) }
    val cardContent: @Composable (DashCard, Modifier, Boolean) -> Unit = { card, modifier, bounded ->
        when (card.type) {
            CardType.Calendar -> calendarCard(modifier, bounded)
            CardType.People -> peopleCard(modifier, bounded)
            CardType.Buttons -> buttonsCard(modifier, bounded)
            CardType.Devices -> DevicesCard(card, home, onCall, modifier, bounded)
            CardType.Sensors -> SensorsCard(card, home, modifier, bounded)
            CardType.NowPlaying -> NowPlayingCard(card, home, baseUrl, onCall, modifier, bounded)
            CardType.Camera -> CameraCard(card, home, baseUrl, modifier, bounded)
        }
    }

    // The whole dashboard drifts a few pixels each minute, like the clock, so nothing sits in one place all day; but
    // not while cards are being arranged.
    val epochMinute = now.toLocalDate().toEpochDay() * 1_440 + now.hour * 60 + now.minute
    val (shiftX, shiftY) = if (editing) 0 to 0 else burnInOffset(epochMinute)
    val driftX by animateDpAsState(shiftX.dp, tween(DRIFT_MS), label = "driftX")
    val driftY by animateDpAsState(shiftY.dp, tween(DRIFT_MS), label = "driftY")

    // The screensaver's photos behind everything, dimmed so the clock and weather read on them; the cards let them
    // show through a little. A long press anywhere not taken by a card's own buttons starts arranging.
    Box(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .pointerInput(editing) { if (!editing) detectTapGestures(onLongPress = { onEditRequest() }) },
    ) {
        if (photoUrl != null) {
            WallpaperBackground(photoUrl, wallpaperConfig.intervalMinutes * 60_000L, scrim = false)
            // Darkest at the top, where the clock, weather and buttons sit straight on the photo.
            val dim = listOf(Color.Black.copy(alpha = PHOTO_DIM_TOP), Color.Black.copy(alpha = PHOTO_DIM))
            Box(Modifier.fillMaxSize().background(Brush.verticalGradient(dim, endY = HEADER_FADE_PX)))
        }
        CompositionLocalProvider(LocalOverPhoto provides (photoUrl != null)) {
            DashboardLayout(
                header, layout, editing, onLayoutChange, onSources = { sourcesOf = it.id }, cardContent, driftX, driftY,
            )
        }
    }

    if (choosingWeather) {
        WeatherSourceDialog(kitchen, home, onKitchenChange, onDismiss = { choosingWeather = false })
    }
    if (choosingStatus) {
        StatusSourcesDialog(kitchen, home, onKitchenChange, onDismiss = { choosingStatus = false })
    }
    resetting?.let { grid ->
        AlertDialog(
            onDismissRequest = { resetting = null },
            title = { Text(stringResource(R.string.dashboard_reset_title)) },
            text = {
                Text(
                    stringResource(
                        if (grid == WIDE_GRID) R.string.dashboard_reset_wide_body else R.string.dashboard_reset_tall_body,
                    ),
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    onLayoutChange(layout.reset(grid))
                    resetting = null
                }) { Text(stringResource(R.string.dashboard_reset_confirm)) }
            },
            dismissButton = { TextButton(onClick = { resetting = null }) { Text(stringResource(R.string.cancel)) } },
        )
    }
    if (adding) {
        AddCardDialog(
            layout = layout,
            onAdd = { type ->
                val name = type.name.lowercase()
                val id = if (type.single) name else "$name-${System.currentTimeMillis()}"
                val added = layout.add(type, id)
                if (added != null) {
                    onLayoutChange(added)
                    adding = false
                    // A new card of the kinds that keep their own sources starts with none: choose them now.
                    if (!type.single) sourcesOf = id
                }
                added != null
            },
            onDismiss = { adding = false },
        )
    }
    sourcesOf?.let { id -> layout.cards.firstOrNull { it.id == id } }?.let { card ->
        CardSourcesDialog(
            card = card,
            kitchen = kitchen,
            home = home,
            onKitchenChange = onKitchenChange,
            onCardChange = { onLayoutChange(layout.update(it)) },
            onDismiss = { sourcesOf = null },
        )
    }
}

/**
 * The header, then the cards: on a [WIDE_GRID] in landscape, a [TALL_GRID] on a tablet in portrait, and on a phone
 * stacked in the tall grid's order (top to bottom, then left to right) in a page that scrolls.
 */
@Composable
private fun DashboardLayout(
    header: @Composable (wide: Boolean, clock: Dp) -> Unit,
    layout: DashLayout,
    editing: Boolean,
    onLayoutChange: (DashLayout) -> Unit,
    onSources: (DashCard) -> Unit,
    cardContent: @Composable (DashCard, Modifier, Boolean) -> Unit,
    driftX: Dp,
    driftY: Dp,
) {
    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .safeDrawingPadding()
            .padding(horizontal = 24.dp, vertical = 16.dp)
            .offset(driftX, driftY),
    ) {
        val wide = maxWidth > maxHeight && maxWidth >= 720.dp
        val tablet = wide || maxWidth >= 600.dp
        val grid = if (wide) WIDE_GRID else TALL_GRID
        // Bigger on bigger screens: 56 dp on a phone, up to 80 on a big tablet.
        val clock = ((if (wide) maxHeight else maxWidth) * 0.11f).coerceIn(56.dp, 80.dp)
        if (tablet) {
            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                header(wide, clock)
                CardGrid(
                    layout = layout,
                    grid = grid,
                    editing = editing,
                    onLayoutChange = onLayoutChange,
                    onSources = onSources,
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                ) { card, modifier -> cardContent(card, modifier, true) }
            }
        } else {
            Column(
                verticalArrangement = Arrangement.spacedBy(16.dp),
                modifier = Modifier.verticalScroll(rememberScrollState()),
            ) {
                header(false, clock)
                if (editing) {
                    // Arranged on the tall grid, at a height that gives its cells room.
                    CardGrid(
                        layout = layout,
                        grid = TALL_GRID,
                        editing = true,
                        onLayoutChange = onLayoutChange,
                        onSources = onSources,
                        modifier = Modifier.fillMaxWidth().height(PHONE_EDIT_HEIGHT),
                    ) { card, modifier -> cardContent(card, modifier, true) }
                } else {
                    layout.cards.sortedWith(compareBy({ it.tall.y }, { it.tall.x })).forEach { card ->
                        key(card.id) { cardContent(card, Modifier.fillMaxWidth(), false) }
                    }
                }
            }
        }
    }
}

/** The kinds of card that can be added, and a note when the one picked has no room. */
@Composable
private fun AddCardDialog(layout: DashLayout, onAdd: (CardType) -> Boolean, onDismiss: () -> Unit) {
    var full by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.dashboard_edit_add)) },
        text = {
            Column {
                layout.addable().forEach { type ->
                    TextButton(onClick = { full = !onAdd(type) }, modifier = Modifier.fillMaxWidth()) {
                        Column(Modifier.fillMaxWidth()) {
                            Text(stringResource(type.label()), style = MaterialTheme.typography.titleMedium)
                            Text(
                                stringResource(type.description()),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
                if (full) {
                    Text(
                        stringResource(R.string.dashboard_edit_full),
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

private fun CardType.label(): Int = when (this) {
    CardType.Calendar -> R.string.dashboard_calendar
    CardType.People -> R.string.dashboard_people
    CardType.Buttons -> R.string.dashboard_buttons
    CardType.Devices -> R.string.card_devices
    CardType.Sensors -> R.string.card_sensors
    CardType.NowPlaying -> R.string.card_now_playing
    CardType.Camera -> R.string.card_camera
}

private fun CardType.description(): Int = when (this) {
    CardType.Calendar -> R.string.card_calendar_about
    CardType.People -> R.string.card_people_about
    CardType.Buttons -> R.string.card_buttons_about
    CardType.Devices -> R.string.card_devices_about
    CardType.Sensors -> R.string.card_sensors_about
    CardType.NowPlaying -> R.string.card_now_playing_about
    CardType.Camera -> R.string.card_camera_about
}

/** How tall the tall grid is while arranging cards on a phone. */
private val PHONE_EDIT_HEIGHT = 1_100.dp

/** Whether the dashboard is over a photo, so its cards are a little see-through. */
private val LocalOverPhoto = staticCompositionLocalOf { false }

/** The cards' color: the usual surface, letting a photo behind show through a little. */
@Composable
internal fun dashCardColor(): Color =
    MaterialTheme.colorScheme.surfaceContainer.let { if (LocalOverPhoto.current) it.copy(alpha = CARD_ALPHA) else it }

private const val FORECAST_DAYS = 5
private const val DRIFT_MS = 2_000
private const val PHOTO_DIM = 0.45f
private const val PHOTO_DIM_TOP = 0.65f
private const val HEADER_FADE_PX = 500f
private const val CARD_ALPHA = 0.85f

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
internal fun TitledCard(
    title: String,
    modifier: Modifier,
    scroll: Boolean,
    content: @Composable ColumnScope.() -> Unit,
) {
    Surface(
        color = dashCardColor(),
        contentColor = MaterialTheme.colorScheme.onSurface,
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
    TitledCard(stringResource(R.string.dashboard_people), modifier, scroll) {
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
    TitledCard(stringResource(R.string.dashboard_buttons), modifier, scroll) {
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

