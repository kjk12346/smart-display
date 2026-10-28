package dev.smartdisplay.app.ui.ambient

import android.text.format.DateFormat
import androidx.annotation.StringRes
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.EaseInOutSine
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
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
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import coil3.compose.AsyncImage
import dev.smartdisplay.app.R
import dev.smartdisplay.app.SmartDisplayApp
import dev.smartdisplay.app.alarm.AlarmService
import dev.smartdisplay.app.alarm.nextRing
import dev.smartdisplay.app.alarm.nextRingText
import dev.smartdisplay.app.ha.ConnectionStatus
import dev.smartdisplay.app.ha.DisconnectReason
import dev.smartdisplay.app.ha.HomeState
import dev.smartdisplay.app.kiosk.WallpaperConfig
import dev.smartdisplay.app.ui.common.ScreenBrightness
import dev.smartdisplay.app.ui.common.rememberMinuteClock
import dev.smartdisplay.app.ui.theme.SmartDisplayTheme
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlinx.coroutines.delay

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
    /** Keep showing photos (dimmed) in quiet hours: for a kitchen, which needn't be dark like a bedroom. */
    photosAtNight: Boolean = false,
    viewModel: AmbientViewModel = viewModel(),
) {
    val weather = home.primaryWeatherEntity()?.toCurrentWeather()
    val now by rememberMinuteClock()
    val quiet = isQuietHours(now.toLocalTime())
    val photos = !quiet || photosAtNight
    KeepWeatherAndPhotosFresh(viewModel, home, weather?.entityId, wallpaperConfig, photos)
    val forecast by viewModel.forecast.collectAsStateWithLifecycle()

    // Before an alarm with a sunrise, the screen glows brighter and warmer (over the night dimming) until it rings.
    val sunrise by AlarmService.sunrise.collectAsStateWithLifecycle()
    val sunriseProgress by produceState<Float?>(null, sunrise) {
        val current = sunrise
        while (current != null) {
            value = current.progress(System.currentTimeMillis())
            delay(SUNRISE_TICK_MS)
        }
        value = null
    }
    val glow = sunriseProgress
    ScreenBrightness(
        when {
            glow != null -> NIGHT_BRIGHTNESS + (1f - NIGHT_BRIGHTNESS) * glow * glow
            quiet -> NIGHT_BRIGHTNESS
            else -> null
        }
    )

    val wallpaper by viewModel.wallpaper.collectAsStateWithLifecycle()
    val wallpaperUrl = wallpaper?.takeIf { photos && it.folderId == wallpaperConfig.folderId }?.url
    val nextAlarm = rememberNextAlarmText(now)
    val context = LocalContext.current

    AmbientContent(
        now = now,
        wallpaperUrl = wallpaperUrl,
        wallpaperIntervalMillis = wallpaperConfig.intervalMinutes * 60_000L,
        nextAlarm = nextAlarm,
        weather = weather,
        forecast = forecast?.takeIf { it.first == weather?.entityId }?.second,
        night = home.sunIsDown() ?: quiet,
        dimmed = quiet && glow == null,
        sunrise = glow,
        onImUp = { AlarmService.send(context, AlarmService.ACTION_AWAKE) },
        status = home.status,
        loaded = home.loaded,
        onTap = onOpenControls,
        onLongPress = onOpenSettings,
    )
}

/**
 * Keeps the forecast for [weatherEntityId] and the wallpaper photo fresh while this is on screen and connected; with
 * [photos] false, the photos pause (a dark night). Shared by the ambient screen and the kitchen dashboard.
 */
@Composable
internal fun KeepWeatherAndPhotosFresh(
    viewModel: AmbientViewModel,
    home: HomeState,
    weatherEntityId: String?,
    wallpaperConfig: WallpaperConfig,
    photos: Boolean,
) {
    val connected = home.status == ConnectionStatus.Connected
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(connected, weatherEntityId) {
        if (connected && weatherEntityId != null) {
            lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) { viewModel.keepForecastFresh(weatherEntityId) }
        }
    }
    LaunchedEffect(connected, wallpaperConfig, photos) {
        if (connected && photos) {
            lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) { viewModel.keepWallpaperFresh(wallpaperConfig) }
        }
    }
}

/** When the next alarm rings, as shown under the clock ("Sat 7:30 AM"), or null if none is set. */
@Composable
internal fun rememberNextAlarmText(now: LocalDateTime): String? {
    val context = LocalContext.current
    val alarms = (context.applicationContext as SmartDisplayApp).alarms
    val alarmList by alarms.alarms.collectAsStateWithLifecycle()
    val snooze by alarms.snooze.collectAsStateWithLifecycle()
    val skip by alarms.skip.collectAsStateWithLifecycle()
    return remember(alarmList, snooze, skip, now) {
        nextRing(alarmList, snooze, now.atZone(ZoneId.systemDefault()), skip)?.let { nextRingText(context, it) }
    }
}

@Composable
private fun AmbientContent(
    now: LocalDateTime,
    wallpaperUrl: String?,
    wallpaperIntervalMillis: Long,
    nextAlarm: String?,
    weather: CurrentWeather?,
    forecast: DailyForecast?,
    night: Boolean,
    dimmed: Boolean,
    sunrise: Float?,
    onImUp: () -> Unit,
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
        if (wallpaperUrl != null) WallpaperBackground(wallpaperUrl, wallpaperIntervalMillis)
        if (sunrise != null) SunriseGlow(sunrise)
        val landscape = maxWidth > maxHeight
        // The clock's digit height drives everything else's size. Capped, so a big tablet's clock isn't overwhelming.
        val fit = if (landscape) minOf(maxHeight * 0.30f, maxWidth * 0.15f) else minOf(maxWidth * 0.26f, maxHeight * 0.15f)
        val clock = minOf(fit, 150.dp)

        Box(
            modifier = Modifier
                .align(Alignment.Center)
                .offset { IntOffset(offsetX.roundToPx(), offsetY.roundToPx()) }
                .graphicsLayer { alpha = contentAlpha },
        ) {
            // On the bright sunrise glow, the softer greys and lavender would fade into it: use the main text colour.
            val colors = MaterialTheme.colorScheme
            val readable = if (sunrise == null) colors else colors.copy(
                onSurfaceVariant = colors.onBackground.copy(alpha = 0.9f),
                tertiary = colors.onBackground,
            )
            MaterialTheme(colorScheme = readable, typography = MaterialTheme.typography, shapes = MaterialTheme.shapes) {
                if (landscape) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(clock * 0.6f),
                    ) {
                        Clock(now, nextAlarm, clock, Alignment.Start)
                        if (weather != null) Weather(weather, forecast, night, clock)
                    }
                } else {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(clock * 0.45f),
                    ) {
                        Clock(now, nextAlarm, clock, Alignment.CenterHorizontally)
                        if (weather != null) Weather(weather, forecast, night, clock)
                    }
                }
            }
        }

        val hint = when {
            status is ConnectionStatus.Waiting -> R.string.ambient_reconnecting
            status == ConnectionStatus.Connecting && !loaded -> R.string.live_connecting
            status == ConnectionStatus.Connecting -> R.string.ambient_reconnecting
            else -> null
        }
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 24.dp),
        ) {
            if (sunrise != null) {
                // During a sunrise: when it rings, and a way to say "I'm up" (skips this ring).
                Text(
                    text = stringResource(R.string.sunrise_ambient, nextAlarm.orEmpty()),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onBackground,
                )
                // Big and bold: it's meant to be found by someone half awake.
                Button(
                    onClick = onImUp,
                    modifier = Modifier
                        .padding(top = 16.dp, bottom = 24.dp)
                        .height(72.dp)
                        .widthIn(min = 240.dp),
                ) {
                    Text(stringResource(R.string.sunrise_im_up), fontSize = 24.sp)
                }
            }
            if (hint != null) {
                Text(
                    text = stringResource(hint),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.graphicsLayer { alpha = contentAlpha * 0.8f },
                )
            }
        }
    }
}

private const val DRIFT_MS = 4_000

/** How often the sunrise glow and screen brightness move on. */
private const val SUNRISE_TICK_MS = 1_000L

/**
 * A sunrise from below the screen: at [progress] 0 a faint deep-red glow at the bottom edge, growing into a warm
 * golden light across the screen by 1. It stays orange behind the clock, so white text stays readable.
 */
@Composable
private fun SunriseGlow(progress: Float) {
    Box(
        Modifier
            .fillMaxSize()
            .drawBehind {
                val p = progress.coerceIn(0f, 1f)
                val core = sunriseColor(DAWN_CORE, MORNING_CORE, DAY_CORE, p)
                val edge = sunriseColor(DAWN_EDGE, MORNING_EDGE, DAY_EDGE, p)
                drawRect(
                    Brush.radialGradient(
                        0f to core.copy(alpha = 0.35f + 0.5f * p),
                        0.55f to edge.copy(alpha = 0.25f + 0.5f * p),
                        1f to Color.Transparent,
                        center = Offset(size.width / 2, size.height * 1.1f),
                        radius = size.height * (0.35f + 1.15f * p),
                    )
                )
            },
    )
}

/** Dawn colour at 0, morning at 0.5, day at 1. */
private fun sunriseColor(dawn: Color, morning: Color, day: Color, p: Float): Color =
    if (p < 0.5f) lerp(dawn, morning, p * 2) else lerp(morning, day, p * 2 - 1)

private val DAWN_CORE = Color(0xFF8A1E00)
private val DAWN_EDGE = Color(0xFF3A0800)
private val MORNING_CORE = Color(0xFFFF7A1A)
private val MORNING_EDGE = Color(0xFFB8400A)
private val DAY_CORE = Color(0xFFFFC27A)
private val DAY_EDGE = Color(0xFFE0702A)

/** A photo's pan and zoom lasts as long as it's shown, within these limits. */
private const val MIN_MOVE_MS = 20_000L
private const val MAX_MOVE_MS = 120_000L

/**
 * A photo filling the space, faded in over the last one, under a gradient that keeps the clock readable (unless
 * [scrim] is false, for a photo tile). Each photo slowly pans and zooms ([PanZoom]) over the time it's shown; if it
 * stays longer than [MAX_MOVE_MS], the move eases back and forth.
 */
@Composable
internal fun WallpaperBackground(url: String, intervalMillis: Long, scrim: Boolean = true) {
    val context = LocalContext.current
    val moveMillis = intervalMillis.coerceIn(MIN_MOVE_MS, MAX_MOVE_MS).toInt()
    Crossfade(targetState = url, animationSpec = tween(1_500), label = "wallpaper") { shown ->
        val move = remember(shown) { PanZoom.random() }
        val progress by rememberInfiniteTransition(label = "panZoom").animateFloat(
            initialValue = 0f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(tween(moveMillis, easing = EaseInOutSine), RepeatMode.Reverse),
            label = "panZoomProgress",
        )
        AsyncImage(
            model = wallpaperRequest(context, shown),
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer {
                    // Read only here, so the move redraws the photo without recomposing the screen.
                    val framing = move.at(progress)
                    scaleX = framing.zoom
                    scaleY = framing.zoom
                    translationX = framing.x * size.width * (framing.zoom - 1f) / 2f
                    translationY = framing.y * size.height * (framing.zoom - 1f) / 2f
                },
        )
    }
    if (scrim) {
        Box(
            Modifier
                .fillMaxSize()
                .background(Brush.verticalGradient(listOf(SCRIM.copy(alpha = 0.35f), SCRIM.copy(alpha = 0.65f)))),
        )
    }
}

private val SCRIM = Color.Black

@Composable
internal fun Clock(now: LocalDateTime, nextAlarm: String?, size: Dp, alignment: Alignment.Horizontal) {
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
        if (nextAlarm != null) {
            Text(
                text = stringResource(R.string.alarms_next, nextAlarm),
                fontSize = (size * 0.12f).asTextSize(),
                color = MaterialTheme.colorScheme.tertiary,
                modifier = Modifier.padding(top = size * 0.04f),
            )
        }
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
internal fun conditionLabel(condition: String, night: Boolean): String {
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
            wallpaperIntervalMillis = 60_000L,
            nextAlarm = null,
            weather = CurrentWeather("weather.home", "partlycloudy", 71.6, "°F"),
            forecast = DailyForecast(78.0, 61.0),
            night = false,
            dimmed = false,
            sunrise = null,
            onImUp = {},
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
            wallpaperIntervalMillis = 60_000L,
            nextAlarm = null,
            weather = CurrentWeather("weather.home", "rainy", 12.0, "°C"),
            forecast = DailyForecast(15.0, 9.0),
            night = true,
            dimmed = true,
            sunrise = null,
            onImUp = {},
            status = ConnectionStatus.Waiting(0, DisconnectReason.Unreachable),
            loaded = true,
            onTap = {},
            onLongPress = {},
        )
    }
}
