package dev.smartdisplay.app.ui.dashboard

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
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
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.smartdisplay.app.R
import dev.smartdisplay.app.ha.HomeState
import dev.smartdisplay.app.kiosk.KitchenConfig
import dev.smartdisplay.app.kiosk.WallpaperConfig
import dev.smartdisplay.app.ui.ambient.AmbientViewModel
import dev.smartdisplay.app.ui.ambient.Clock
import dev.smartdisplay.app.ui.ambient.ForecastDay
import dev.smartdisplay.app.ui.ambient.KeepWeatherAndPhotosFresh
import dev.smartdisplay.app.ui.ambient.WallpaperBackground
import dev.smartdisplay.app.ui.ambient.WeatherIcon
import dev.smartdisplay.app.ui.ambient.conditionLabel
import dev.smartdisplay.app.ui.ambient.formatTemperature
import dev.smartdisplay.app.ui.ambient.primaryWeatherEntity
import dev.smartdisplay.app.ui.ambient.rememberNextAlarmText
import dev.smartdisplay.app.ui.ambient.sunIsDown
import dev.smartdisplay.app.ui.ambient.toCurrentWeather
import dev.smartdisplay.app.ui.common.rememberMinuteClock
import java.time.LocalDate
import java.time.format.TextStyle

/**
 * The kitchen display's daytime screen: the time and weather with a few days' forecast, who's home with Home and
 * Away buttons, the owner's buttons for scenes, scripts and automations, and a photo. Wide screens get columns;
 * narrow ones stack and scroll.
 */
@Composable
fun DashboardScreen(
    home: HomeState,
    kitchen: KitchenConfig,
    wallpaperConfig: WallpaperConfig,
    onOpenControls: () -> Unit,
    onOpenAlarms: () -> Unit,
    onOpenSettings: () -> Unit,
    ambient: AmbientViewModel = viewModel(),
    viewModel: DashboardViewModel = viewModel(),
) {
    val weatherEntity = home.primaryWeatherEntity()
    KeepWeatherAndPhotosFresh(ambient, home, weatherEntity?.entityId, wallpaperConfig, photos = true)
    val forecastDays by ambient.forecastDays.collectAsStateWithLifecycle()
    val wallpaper by ambient.wallpaper.collectAsStateWithLifecycle()
    val runs by viewModel.runs.collectAsStateWithLifecycle()
    val now by rememberMinuteClock()
    val nextAlarm = rememberNextAlarmText(now)

    val days = forecastDays?.takeIf { it.first == weatherEntity?.entityId }?.second.orEmpty()
        .filter { !it.date.isBefore(now.toLocalDate()) }
        .take(FORECAST_DAYS)
    val photoUrl = wallpaper?.takeIf { it.folderId == wallpaperConfig.folderId }?.url
    val names = home.entities

    val header: @Composable () -> Unit = {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Clock(now, nextAlarm, size = 56.dp, alignment = Alignment.Start)
            Spacer(Modifier.weight(1f))
            val weather = weatherEntity?.toCurrentWeather()
            if (weather != null) {
                val night = home.sunIsDown() ?: false
                WeatherIcon(weather.condition, night, size = 48.dp)
                Column(modifier = Modifier.padding(start = 12.dp, end = 24.dp)) {
                    Text(
                        weather.temperature?.let(::formatTemperature) ?: "–",
                        fontSize = 36.sp,
                        fontWeight = FontWeight.Light,
                    )
                    Text(
                        conditionLabel(weather.condition, night),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            ForecastStrip(days, today = now.toLocalDate())
            Spacer(Modifier.weight(1f))
            Column(horizontalAlignment = Alignment.End) {
                TextButton(onClick = onOpenControls) { Text(stringResource(R.string.dashboard_rooms)) }
                TextButton(onClick = onOpenAlarms) { Text(stringResource(R.string.alarms_button)) }
                TextButton(onClick = onOpenSettings) { Text(stringResource(R.string.settings_eyebrow)) }
            }
        }
    }
    val peopleCard: @Composable (Modifier) -> Unit = { modifier ->
        PeopleCard(
            people = home.people(),
            kitchen = kitchen,
            runs = runs,
            onRun = viewModel::run,
            modifier = modifier,
        )
    }
    val buttonsCard: @Composable (Modifier) -> Unit = { modifier ->
        ButtonsCard(
            shortcuts = kitchen.shortcuts.filter { it in names },
            nameOf = { names[it]?.friendlyName ?: it },
            runs = runs,
            onRun = viewModel::run,
            modifier = modifier,
        )
    }

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .safeDrawingPadding()
            .padding(horizontal = 24.dp, vertical = 16.dp),
    ) {
        if (maxWidth > maxHeight && maxWidth >= 720.dp) {
            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                header()
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp), modifier = Modifier.weight(1f)) {
                    peopleCard(Modifier.weight(1f).fillMaxHeight())
                    buttonsCard(Modifier.weight(1.3f).fillMaxHeight())
                    PhotoTile(photoUrl, wallpaperConfig, Modifier.weight(1.2f).fillMaxHeight())
                }
            }
        } else {
            Column(
                verticalArrangement = Arrangement.spacedBy(16.dp),
                modifier = Modifier.verticalScroll(rememberScrollState()),
            ) {
                header()
                peopleCard(Modifier.fillMaxWidth())
                buttonsCard(Modifier.fillMaxWidth())
                PhotoTile(photoUrl, wallpaperConfig, Modifier.fillMaxWidth().aspectRatio(4f / 3f))
            }
        }
    }
}

private const val FORECAST_DAYS = 5

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
                )
            }
        }
    }
}

@Composable
private fun DashCard(title: String, modifier: Modifier, content: @Composable ColumnScope.() -> Unit) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainer,
        shape = RoundedCornerShape(24.dp),
        modifier = modifier,
    ) {
        Column(
            modifier = Modifier
                .verticalScroll(rememberScrollState())
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
    kitchen: KitchenConfig,
    runs: Map<String, RunState>,
    onRun: (String) -> Unit,
    modifier: Modifier,
) {
    DashCard(stringResource(R.string.dashboard_people), modifier) {
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
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.surfaceContainerHighest
                            }
                        ),
                ) {
                    Text(
                        person.initials,
                        style = MaterialTheme.typography.titleMedium,
                        color = if (home) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface,
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
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.padding(top = 16.dp)) {
                if (homeAction != null) {
                    val label = stringResource(R.string.mode_home)
                    RunButton(label, runs[homeAction], filled = true) { onRun(homeAction) }
                }
                if (awayAction != null) {
                    val label = stringResource(R.string.mode_away)
                    RunButton(label, runs[awayAction], filled = false) { onRun(awayAction) }
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
) {
    DashCard(stringResource(R.string.dashboard_buttons), modifier) {
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
private fun RunButton(label: String, state: RunState?, filled: Boolean, onClick: () -> Unit) {
    val text = when (state) {
        RunState.Running -> "…"
        RunState.Done -> stringResource(R.string.run_done)
        RunState.Failed -> stringResource(R.string.run_failed)
        null -> label
    }
    val modifier = Modifier.heightIn(min = 64.dp).widthIn(min = 120.dp)
    val content: @Composable () -> Unit = {
        Text(text, fontSize = 18.sp, textAlign = TextAlign.Center, maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
    if (filled) {
        Button(onClick = onClick, modifier = modifier) { content() }
    } else {
        FilledTonalButton(
            onClick = onClick,
            modifier = modifier,
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
