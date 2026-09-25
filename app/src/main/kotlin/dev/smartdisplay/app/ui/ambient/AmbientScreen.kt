package dev.smartdisplay.app.ui.ambient

import android.text.format.DateFormat
import androidx.annotation.StringRes
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import coil3.compose.AsyncImage
import dev.smartdisplay.app.R
import dev.smartdisplay.app.ha.ConnectionStatus
import dev.smartdisplay.app.ha.DisconnectReason
import dev.smartdisplay.app.ha.HomeState
import dev.smartdisplay.app.kiosk.WallpaperConfig
import dev.smartdisplay.app.ui.common.ScreenBrightness
import dev.smartdisplay.app.ui.common.rememberMinuteClock
import dev.smartdisplay.app.ui.theme.SmartDisplayTheme
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Screen brightness during quiet hours (0 to 1). */
private const val NIGHT_BRIGHTNESS = 0.08f

/** Text brightness during quiet hours, on top of the lower screen brightness. */
private const val NIGHT_CONTENT_ALPHA = 0.55f

/**
 * The display's resting screen: a large clock, the date and the weather. Tap opens the controls, long-press settings.
 * The whole layout drifts a few dp each minute against burn-in, and dims during quiet hours.
 */
@Composable
fun AmbientScreen(
    home: HomeState,
    wallpaperConfig: WallpaperConfig,
    onOpenControls: () -> Unit,
    onOpenSettings: () -> Unit,
    viewModel: AmbientViewModel = viewModel(),
) {
    val weather = home.primaryWeatherEntity()?.toCurrentWeather()
    val connected = home.status == ConnectionStatus.Connected

    // Refresh the forecast only while connected and on screen.
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(connected, weather?.entityId) {
        val entityId = weather?.entityId
        if (connected && entityId != null) {
            lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) { viewModel.keepForecastFresh(entityId) }
        }
    }
    val forecast by viewModel.forecast.collectAsStateWithLifecycle()

    val now by rememberMinuteClock()
    val quiet = isQuietHours(now.toLocalTime())
    ScreenBrightness(if (quiet) NIGHT_BRIGHTNESS else null)

    // Photos behind the clock, except at night: a dark room should stay dark.
    LaunchedEffect(connected, wallpaperConfig, quiet) {
        if (connected && !quiet) {
            lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) { viewModel.keepWallpaperFresh(wallpaperConfig) }
        }
    }
    val wallpaper by viewModel.wallpaper.collectAsStateWithLifecycle()
    val wallpaperUrl = wallpaper?.takeIf { !quiet && it.folderId == wallpaperConfig.folderId }?.url

    AmbientContent(
        now = now,
        wallpaperUrl = wallpaperUrl,
        weather = weather,
        forecast = forecast?.takeIf { it.first == weather?.entityId }?.second,
        night = home.sunIsDown() ?: quiet,
        dimmed = quiet,
        status = home.status,
        loaded = home.loaded,
        onTap = onOpenControls,
        onLongPress = onOpenSettings,
    )
}

@Composable
private fun AmbientContent(
    now: LocalDateTime,
    wallpaperUrl: String?,
    weather: CurrentWeather?,
    forecast: DailyForecast?,
    night: Boolean,
    dimmed: Boolean,
    status: ConnectionStatus,
    loaded: Boolean,
    onTap: () -> Unit,
    onLongPress: () -> Unit,
) {
    val epochMinute = now.toLocalDate().toEpochDay() * 1_440 + now.hour * 60 + now.minute
    val (shiftX, shiftY) = burnInOffset(epochMinute)
    val offsetX by animateDpAsState(shiftX.dp, tween(DRIFT_MS), label = "driftX")
    val offsetY by animateDpAsState(shiftY.dp, tween(DRIFT_MS), label = "driftY")
    val contentAlpha by animateFloatAsState(if (dimmed) NIGHT_CONTENT_ALPHA else 1f, tween(2_000), label = "dim")

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .pointerInput(onTap, onLongPress) {
                detectTapGestures(onTap = { onTap() }, onLongPress = { onLongPress() })
            },
    ) {
        if (wallpaperUrl != null) WallpaperBackground(wallpaperUrl)
        val landscape = maxWidth > maxHeight
        // The clock's digit height drives everything else's size.
        val clock = if (landscape) minOf(maxHeight * 0.30f, maxWidth * 0.15f) else minOf(maxWidth * 0.26f, maxHeight * 0.15f)

        Box(
            modifier = Modifier
                .align(Alignment.Center)
                .offset { IntOffset(offsetX.roundToPx(), offsetY.roundToPx()) }
                .graphicsLayer { alpha = contentAlpha },
        ) {
            if (landscape) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(clock * 0.6f),
                ) {
                    Clock(now, clock, Alignment.Start)
                    if (weather != null) Weather(weather, forecast, night, clock)
                }
            } else {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(clock * 0.45f),
                ) {
                    Clock(now, clock, Alignment.CenterHorizontally)
                    if (weather != null) Weather(weather, forecast, night, clock)
                }
            }
        }

        val hint = when {
            status is ConnectionStatus.Waiting -> R.string.ambient_reconnecting
            status == ConnectionStatus.Connecting && !loaded -> R.string.live_connecting
            status == ConnectionStatus.Connecting -> R.string.ambient_reconnecting
            else -> null
        }
        if (hint != null) {
            Text(
                text = stringResource(hint),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 24.dp)
                    .graphicsLayer { alpha = contentAlpha * 0.8f },
            )
        }
    }
}

private const val DRIFT_MS = 4_000

/** A photo filling the screen, faded in over the last one, under a gradient that keeps the clock readable. */
@Composable
private fun WallpaperBackground(url: String) {
    val context = LocalContext.current
    Crossfade(targetState = url, animationSpec = tween(1_500), label = "wallpaper") { shown ->
        AsyncImage(
            model = wallpaperRequest(context, shown),
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize(),
        )
    }
    Box(
        Modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(listOf(SCRIM.copy(alpha = 0.35f), SCRIM.copy(alpha = 0.65f)))),
    )
}

private val SCRIM = Color.Black

@Composable
private fun Clock(now: LocalDateTime, size: Dp, alignment: Alignment.Horizontal) {
    val context = LocalContext.current
    val locale = LocalConfiguration.current.locales[0]
    val use24Hour = DateFormat.is24HourFormat(context)
    val timePattern = DateFormat.getBestDateTimePattern(locale, if (use24Hour) "Hm" else "hm")
    // The AM/PM marker is shown smaller, next to the digits.
    val time = format(now, timePattern.replace("a", "").trim(), locale, fallback = if (use24Hour) "H:mm" else "h:mm")
    val marker = if (use24Hour) null else format(now, "a", locale, fallback = "a")
    val date = format(now, DateFormat.getBestDateTimePattern(locale, "EEEEMMMMd"), locale, fallback = "EEEE, MMMM d")

    Column(horizontalAlignment = alignment) {
        Row {
            Text(
                text = time,
                fontSize = size.asTextSize(),
                lineHeight = size.asTextSize(),
                fontWeight = FontWeight.Light,
                letterSpacing = (-0.02).em,
                color = MaterialTheme.colorScheme.onBackground,
                modifier = Modifier.alignByBaseline(),
            )
            if (marker != null) {
                Text(
                    text = marker,
                    fontSize = (size * 0.2f).asTextSize(),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .alignByBaseline()
                        .padding(start = size * 0.08f),
                )
            }
        }
        Text(
            text = date,
            fontSize = (size * 0.17f).asTextSize(),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = size * 0.08f),
        )
    }
}

@Composable
private fun Weather(weather: CurrentWeather, forecast: DailyForecast?, night: Boolean, size: Dp) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        WeatherIcon(weather.condition, night, size = size * 0.9f)
        Column(modifier = Modifier.padding(start = size * 0.2f)) {
            Text(
                text = weather.temperature?.let(::formatTemperature) ?: "–",
                fontSize = (size * 0.55f).asTextSize(),
                lineHeight = (size * 0.6f).asTextSize(),
                fontWeight = FontWeight.Light,
                color = MaterialTheme.colorScheme.onBackground,
            )
            Text(
                text = conditionLabel(weather.condition, night),
                fontSize = (size * 0.15f).asTextSize(),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            val highLow = forecast?.let { highLowText(it) }
            if (highLow != null) {
                Text(
                    text = highLow,
                    fontSize = (size * 0.15f).asTextSize(),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = size * 0.04f),
                )
            }
        }
    }
}

@Composable
private fun highLowText(forecast: DailyForecast): String? {
    val high = forecast.high?.let(::formatTemperature)
    val low = forecast.low?.let(::formatTemperature)
    return when {
        high != null && low != null -> stringResource(R.string.ambient_high_low, high, low)
        high != null -> stringResource(R.string.ambient_high, high)
        low != null -> stringResource(R.string.ambient_low, low)
        else -> null
    }
}

@Composable
private fun conditionLabel(condition: String, night: Boolean): String {
    @StringRes val label: Int? = when (condition) {
        "clear-night" -> R.string.weather_clear
        "sunny" -> if (night) R.string.weather_clear else R.string.weather_sunny
        "partlycloudy" -> R.string.weather_partly_cloudy
        "cloudy" -> R.string.weather_cloudy
        "fog" -> R.string.weather_fog
        "hail" -> R.string.weather_hail
        "lightning", "lightning-rainy" -> R.string.weather_thunderstorms
        "pouring" -> R.string.weather_heavy_rain
        "rainy" -> R.string.weather_rain
        "snowy" -> R.string.weather_snow
        "snowy-rainy" -> R.string.weather_sleet
        "windy" -> R.string.weather_windy
        "windy-variant" -> R.string.weather_windy_cloudy
        "exceptional" -> R.string.weather_exceptional
        else -> null
    }
    return label?.let { stringResource(it) }
        ?: condition.replace('-', ' ').replaceFirstChar { it.titlecase(LocalConfiguration.current.locales[0]) }
}

/** Formats with a locale's best pattern, falling back if java.time doesn't know one of its letters. */
private fun format(time: LocalDateTime, pattern: String, locale: Locale, fallback: String): String = try {
    DateTimeFormatter.ofPattern(pattern, locale).format(time)
} catch (e: IllegalArgumentException) {
    DateTimeFormatter.ofPattern(fallback, locale).format(time)
}

/** Text sized in dp rather than sp: the clock should fill the same share of the screen whatever the font scale. */
@Composable
private fun Dp.asTextSize(): TextUnit = with(LocalDensity.current) { this@asTextSize.toSp() }

@Preview(widthDp = 1280, heightDp = 800)
@Composable
private fun AmbientLandscapePreview() {
    SmartDisplayTheme {
        AmbientContent(
            now = LocalDateTime.of(2026, 9, 23, 18, 42),
            wallpaperUrl = null,
            weather = CurrentWeather("weather.home", "partlycloudy", 71.6, "°F"),
            forecast = DailyForecast(78.0, 61.0),
            night = false,
            dimmed = false,
            status = ConnectionStatus.Connected,
            loaded = true,
            onTap = {},
            onLongPress = {},
        )
    }
}

@Preview(widthDp = 400, heightDp = 860)
@Composable
private fun AmbientPortraitPreview() {
    SmartDisplayTheme {
        AmbientContent(
            now = LocalDateTime.of(2026, 9, 23, 23, 5),
            wallpaperUrl = null,
            weather = CurrentWeather("weather.home", "rainy", 12.0, "°C"),
            forecast = DailyForecast(15.0, 9.0),
            night = true,
            dimmed = true,
            status = ConnectionStatus.Waiting(0, DisconnectReason.Unreachable),
            loaded = true,
            onTap = {},
            onLongPress = {},
        )
    }
}
