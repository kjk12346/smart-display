package dev.smartdisplay.app.ui.dashboard

import android.text.format.DateFormat
import androidx.compose.foundation.Canvas
import androidx.compose.ui.unit.Dp
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.Box
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.material3.Surface
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.smartdisplay.app.R
import dev.smartdisplay.app.ha.EntityState
import dev.smartdisplay.app.ha.string
import dev.smartdisplay.app.ui.ambient.ForecastDay
import dev.smartdisplay.app.ui.ambient.ForecastHour
import dev.smartdisplay.app.ui.ambient.SunTimes
import dev.smartdisplay.app.ui.ambient.WeatherIcon
import dev.smartdisplay.app.ui.ambient.conditionLabel
import dev.smartdisplay.app.ui.ambient.formatTemperature
import dev.smartdisplay.app.ui.ambient.toCurrentWeather
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import kotlin.math.roundToInt
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull

/** How many hours the header shows (when there's room), and the weather card's graph. */
internal const val HEADER_HOURS = 6
private const val GRAPH_HOURS = 12

/** A chance of rain worth showing, in percent. */
private const val RAIN_WORTH_SHOWING = 20

/** Each hour's and day's width in the forecast strips, and the gap between them. */
internal val HOUR_WIDTH = 52.dp
internal val DAY_WIDTH = 64.dp
internal val FORECAST_GAP = 12.dp

/** The next [hours], for the header: time, icon, temperature and, if it might rain, the chance. */
@Composable
internal fun HourlyStrip(hours: List<ForecastHour>, sun: SunTimes?) {
    val format = rememberHourFormat()
    Row(horizontalArrangement = Arrangement.spacedBy(FORECAST_GAP)) {
        hours.forEach { hour ->
            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.width(HOUR_WIDTH)) {
                Text(
                    hour.time.format(format),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                WeatherIcon(
                    hour.condition ?: "",
                    night = sun?.isNight(hour.time) ?: false,
                    size = 32.dp,
                    modifier = Modifier.padding(vertical = 4.dp),
                )
                Text(
                    hour.temperature?.let(::formatTemperature) ?: "–",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onBackground,
                )
                hour.precipitationChance?.takeIf { it >= RAIN_WORTH_SHOWING }?.let {
                    Text(
                        "$it%",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.secondary,
                    )
                }
            }
        }
    }
}

/**
 * The weather card: now (with feels-like, wind and humidity when the integration gives them, and the next sunrise or
 * sunset), a graph of the next hours' temperature with their chance of rain, then the days ahead. It has no title
 * (it's plainly the weather), and fits the room it's given: the graph takes whatever height is left, and the days and
 * then the graph are left out of a card too short for them. [bounded] is a card of fixed size on the grid; stacked
 * on a phone it gets a set height instead.
 */
@Composable
internal fun WeatherCard(
    weather: EntityState?,
    hours: List<ForecastHour>,
    days: List<ForecastDay>,
    sun: SunTimes?,
    now: LocalDateTime,
    modifier: Modifier,
    bounded: Boolean,
) {
    Surface(
        color = dashCardColor(),
        contentColor = MaterialTheme.colorScheme.onSurface,
        shape = RoundedCornerShape(24.dp),
        modifier = modifier,
    ) {
        val current = weather?.toCurrentWeather()
        if (current == null) {
            Text(
                stringResource(R.string.card_weather_none),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(20.dp),
            )
            return@Surface
        }
        BoxWithConstraints(
            Modifier.padding(horizontal = 20.dp, vertical = 16.dp).then(
                if (bounded) Modifier.fillMaxSize() else Modifier.height(STACKED_HEIGHT),
            ),
        ) {
            val width = maxWidth
            val coming = hours.take(GRAPH_HOURS).takeIf { it.size >= 2 }.orEmpty()
            val night = sun?.isNight(now) ?: false
            val nowRow: @Composable () -> Unit = { NowRow(weather, current.condition, current.temperature, night, sun) }
            val dayCount = { room: Dp -> ((room + DAY_GAP) / (COMPACT_DAY_WIDTH + DAY_GAP)).toInt() }
            if (maxWidth >= SIDE_MIN_WIDTH && maxHeight < NOW_HEIGHT + GRAPH_MIN + DAYS_HEIGHT + PART_GAP * 2) {
                // Short and wide: now on the left, the graph (and the days, if they fit) beside it.
                val showDays = days.isNotEmpty() && maxHeight >= GRAPH_MIN + DAYS_HEIGHT + PART_GAP
                val side = maxWidth * SIDE_SHARE
                Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                    Box(Modifier.width(side).fillMaxHeight(), contentAlignment = Alignment.CenterStart) { nowRow() }
                    Column(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(PART_GAP)) {
                        if (coming.isNotEmpty()) HourGraph(coming, Modifier.weight(1f))
                        if (showDays) CompactDays(days.take(dayCount(width - side - 20.dp)), now.toLocalDate())
                    }
                }
            } else {
                val showGraph = coming.isNotEmpty() && maxHeight >= NOW_HEIGHT + GRAPH_MIN + PART_GAP
                val showDays = days.isNotEmpty() && maxHeight >= NOW_HEIGHT + GRAPH_MIN + DAYS_HEIGHT + PART_GAP * 2
                Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(PART_GAP)) {
                    nowRow()
                    if (showGraph) HourGraph(coming, Modifier.weight(1f))
                    if (showDays) CompactDays(days.take(dayCount(width)), now.toLocalDate())
                }
            }
        }
    }
}

/** The weather now: icon and temperature, then the condition, feels-like, wind and humidity, and the sun. */
@Composable
private fun NowRow(weather: EntityState, condition: String, temperature: Double?, night: Boolean, sun: SunTimes?) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        WeatherIcon(condition, night, size = 48.dp)
        Text(
            temperature?.let(::formatTemperature) ?: "–",
            fontSize = 40.sp,
            fontWeight = FontWeight.Light,
            maxLines = 1,
            modifier = Modifier.padding(horizontal = 12.dp),
        )
        Column(Modifier.weight(1f)) {
            Text(
                conditionLabel(condition, night),
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            weatherDetails(weather)?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            sun?.let { SunText(it) }
        }
    }
}

/** The days ahead in a short row: the day, a small icon, the high and low. */
@Composable
private fun CompactDays(days: List<ForecastDay>, today: LocalDate) {
    val locale = LocalConfiguration.current.locales[0]
    Row(horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
        days.forEach { day ->
            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.width(COMPACT_DAY_WIDTH)) {
                Text(
                    if (day.date == today) {
                        stringResource(R.string.forecast_today)
                    } else {
                        day.date.dayOfWeek.getDisplayName(java.time.format.TextStyle.SHORT, locale)
                    },
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                WeatherIcon(
                    day.condition ?: "", night = false, size = 24.dp, modifier = Modifier.padding(vertical = 2.dp),
                )
                Text(
                    listOfNotNull(day.high, day.low).joinToString(" ") { formatTemperature(it) },
                    style = MaterialTheme.typography.labelMedium,
                    maxLines = 1,
                )
            }
        }
    }
}

/** The weather card's parts, for deciding what fits: the "now" row, the smallest useful graph, the row of days. */
private val NOW_HEIGHT = 58.dp
private val GRAPH_MIN = 64.dp
private val DAYS_HEIGHT = 64.dp
private val PART_GAP = 12.dp

/** A card at least this wide but too short for everything stacked puts now and the graph side by side. */
private val SIDE_MIN_WIDTH = 480.dp
private const val SIDE_SHARE = 0.42f
private val COMPACT_DAY_WIDTH = 52.dp
private val DAY_GAP = 4.dp

/** The weather card's height stacked on a phone, where it isn't given one. */
private val STACKED_HEIGHT = 300.dp

/** "Feels 71° · Wind 8 mph · Humidity 50%", from whichever of those the weather entity has. */
@Composable
private fun weatherDetails(weather: EntityState): String? {
    fun number(key: String) = (weather.attributes[key] as? JsonPrimitive)?.doubleOrNull
    val parts = listOfNotNull(
        number("apparent_temperature")?.let { stringResource(R.string.weather_feels, formatTemperature(it)) },
        number("wind_speed")?.let { speed ->
            val unit = weather.attributes.string("wind_speed_unit").orEmpty()
            stringResource(R.string.weather_wind, "${speed.roundToInt()} $unit".trim())
        },
        number("humidity")?.let { stringResource(R.string.weather_humidity, it.roundToInt()) },
    )
    return parts.takeIf { it.isNotEmpty() }?.joinToString(" · ")
}

/** The sun's next rising or setting, whichever comes first. */
@Composable
private fun SunText(sun: SunTimes) {
    val format = rememberTimeFormat()
    val setting = sun.nextSetting.isBefore(sun.nextRising)
    Text(
        stringResource(
            if (setting) R.string.weather_sunset else R.string.weather_sunrise,
            (if (setting) sun.nextSetting else sun.nextRising).format(format),
        ),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = 1,
    )
}

/**
 * The coming hours as a line of temperatures (labelled every third hour) over faint bars for the chance of rain, with
 * the hours along the bottom. Fills the height it's given.
 */
@Composable
private fun HourGraph(hours: List<ForecastHour>, modifier: Modifier = Modifier) {
    val measurer = rememberTextMeasurer()
    val format = rememberHourFormat()
    val line = MaterialTheme.colorScheme.primary
    val rain = MaterialTheme.colorScheme.secondary
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    val ink = MaterialTheme.colorScheme.onSurface
    val labelStyle = MaterialTheme.typography.labelMedium
    Canvas(modifier.fillMaxWidth()) {
        val column = size.width / hours.size
        val temps = hours.mapNotNull { it.temperature }
        val low = temps.minOrNull() ?: 0.0
        val high = temps.maxOrNull() ?: 0.0
        val spread = (high - low).coerceAtLeast(1.0)
        val labelHeight = measurer.measure("0", labelStyle).size.height.toFloat()
        // Top to bottom: room for the temperature labels, the plot, the hours. The line keeps to the plot's upper
        // part and the rain bars rise from its bottom, behind it.
        val plotTop = labelHeight + 6.dp.toPx()
        val plotBottom = size.height - labelHeight - 4.dp.toPx()
        val plot = (plotBottom - plotTop).coerceAtLeast(1f)
        val lineBottom = plotTop + plot * 0.7f
        fun x(i: Int) = column * (i + 0.5f)
        fun y(t: Double) = lineBottom - ((t - low) / spread).toFloat() * (lineBottom - plotTop)

        hours.forEachIndexed { i, hour ->
            val chance = hour.precipitationChance ?: return@forEachIndexed
            if (chance <= 0) return@forEachIndexed
            val height = plot * 0.6f * chance / 100f
            val width = (column * 0.5f).coerceAtMost(14.dp.toPx())
            drawRoundRect(
                rain.copy(alpha = 0.35f),
                topLeft = Offset(x(i) - width / 2, plotBottom - height),
                size = Size(width, height),
                cornerRadius = CornerRadius(3.dp.toPx()),
            )
            // Between the labelled hours, so the numbers don't collide.
            if (chance >= RAIN_WORTH_SHOWING && i % 3 == 1) {
                val text = measurer.measure("$chance%", TextStyle(fontSize = 11.sp, color = rain))
                drawText(text, topLeft = Offset(x(i) - text.size.width / 2f, plotBottom - text.size.height))
            }
        }

        val path = Path()
        var started = false
        hours.forEachIndexed { i, hour ->
            val t = hour.temperature ?: return@forEachIndexed
            if (started) path.lineTo(x(i), y(t)) else path.moveTo(x(i), y(t)).also { started = true }
        }
        drawPath(path, line, style = Stroke(2.5.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))

        hours.forEachIndexed { i, hour ->
            if (i % 3 != 0) return@forEachIndexed
            hour.temperature?.let { t ->
                drawCircle(line, radius = 3.5.dp.toPx(), center = Offset(x(i), y(t)))
                val text = measurer.measure(formatTemperature(t), labelStyle.copy(color = ink))
                drawText(text, topLeft = Offset(x(i) - text.size.width / 2f, y(t) - text.size.height - 2.dp.toPx()))
            }
            val label = measurer.measure(hour.time.format(format), labelStyle.copy(color = muted))
            drawText(label, topLeft = Offset(x(i) - label.size.width / 2f, size.height - label.size.height))
        }
    }
}

/** An hour on its own: "3 PM", or "15:00" on a 24-hour display. */
@Composable
private fun rememberHourFormat(): DateTimeFormatter {
    val context = LocalContext.current
    val locale = LocalConfiguration.current.locales[0]
    return remember(locale) {
        DateTimeFormatter.ofPattern(if (DateFormat.is24HourFormat(context)) "H:00" else "h a", locale)
    }
}

/** A time of day: "6:12 PM", or "18:12". */
@Composable
private fun rememberTimeFormat(): DateTimeFormatter {
    val context = LocalContext.current
    val locale = LocalConfiguration.current.locales[0]
    return remember(locale) {
        DateTimeFormatter.ofPattern(if (DateFormat.is24HourFormat(context)) "H:mm" else "h:mm a", locale)
    }
}

/** Today's forecast, if the days include it, for its high and low. */
internal fun List<ForecastDay>.today(today: LocalDate): ForecastDay? = firstOrNull { it.date == today }
