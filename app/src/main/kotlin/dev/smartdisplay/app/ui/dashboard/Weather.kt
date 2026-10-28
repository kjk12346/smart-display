package dev.smartdisplay.app.ui.dashboard

import android.text.format.DateFormat
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
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

/** How many hours the header shows, and the weather card's graph. */
private const val HEADER_HOURS = 6
private const val GRAPH_HOURS = 12

/** A chance of rain worth showing, in percent. */
private const val RAIN_WORTH_SHOWING = 20

/** The next few hours, for the header: time, icon, temperature and, if it might rain, the chance. */
@Composable
internal fun HourlyStrip(hours: List<ForecastHour>, sun: SunTimes?) {
    val format = rememberHourFormat()
    Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        hours.take(HEADER_HOURS).forEach { hour ->
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
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
                    Text("$it%", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.secondary)
                }
            }
        }
    }
}

/**
 * The weather card: now (with feels-like, wind and humidity when the integration gives them, and the next sunrise or
 * sunset), a graph of the next hours' temperature with their chance of rain, then the days ahead.
 */
@Composable
internal fun WeatherCard(
    weather: EntityState?,
    hours: List<ForecastHour>,
    days: List<ForecastDay>,
    sun: SunTimes?,
    now: LocalDateTime,
    modifier: Modifier,
    scroll: Boolean,
) {
    TitledCard(stringResource(R.string.card_weather), modifier, scroll) {
        val current = weather?.toCurrentWeather()
        if (current == null) {
            Text(
                stringResource(R.string.card_weather_none),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            return@TitledCard
        }
        val night = sun?.isNight(now) ?: false
        Row(verticalAlignment = Alignment.CenterVertically) {
            WeatherIcon(current.condition, night, size = 56.dp)
            Text(
                current.temperature?.let(::formatTemperature) ?: "–",
                fontSize = 44.sp,
                fontWeight = FontWeight.Light,
                modifier = Modifier.padding(horizontal = 12.dp),
            )
            Column(Modifier.weight(1f)) {
                Text(conditionLabel(current.condition, night), style = MaterialTheme.typography.titleMedium)
                weatherDetails(weather)?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                sun?.let { SunText(it) }
            }
        }
        val coming = hours.take(GRAPH_HOURS)
        if (coming.size >= 2) {
            Spacer(Modifier.height(16.dp))
            HourGraph(coming)
        }
        if (days.isNotEmpty()) {
            Spacer(Modifier.height(16.dp))
            ForecastStrip(days, today = now.toLocalDate(), arrangement = Arrangement.SpaceBetween)
        }
    }
}

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
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/**
 * The coming hours as a line of temperatures (labelled every third hour) over bars for the chance of rain, with the
 * hours along the bottom.
 */
@Composable
private fun HourGraph(hours: List<ForecastHour>) {
    val measurer = rememberTextMeasurer()
    val format = rememberHourFormat()
    val line = MaterialTheme.colorScheme.primary
    val rain = MaterialTheme.colorScheme.secondary
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    val ink = MaterialTheme.colorScheme.onSurface
    val labelStyle = MaterialTheme.typography.labelMedium
    Canvas(Modifier.fillMaxWidth().height(132.dp)) {
        val column = size.width / hours.size
        val temps = hours.mapNotNull { it.temperature }
        val low = temps.minOrNull() ?: 0.0
        val high = temps.maxOrNull() ?: 0.0
        val spread = (high - low).coerceAtLeast(1.0)
        // Top to bottom: temperature labels, the line, the rain bars, the hours.
        val lineTop = 22.dp.toPx()
        val lineBottom = 62.dp.toPx()
        val barBottom = 104.dp.toPx()
        val barMax = 28.dp.toPx()
        fun x(i: Int) = column * (i + 0.5f)
        fun y(t: Double) = lineBottom - ((t - low) / spread).toFloat() * (lineBottom - lineTop)

        hours.forEachIndexed { i, hour ->
            val chance = hour.precipitationChance ?: return@forEachIndexed
            if (chance <= 0) return@forEachIndexed
            val height = barMax * chance / 100f
            val width = (column * 0.5f).coerceAtMost(14.dp.toPx())
            drawRoundRect(
                rain.copy(alpha = 0.45f),
                topLeft = Offset(x(i) - width / 2, barBottom - height),
                size = Size(width, height),
                cornerRadius = CornerRadius(3.dp.toPx()),
            )
            if (chance >= RAIN_WORTH_SHOWING) {
                val text = measurer.measure("$chance%", TextStyle(fontSize = 11.sp, color = rain))
                drawText(text, topLeft = Offset(x(i) - text.size.width / 2f, barBottom - height - text.size.height))
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
                drawText(text, topLeft = Offset(x(i) - text.size.width / 2f, y(t) - text.size.height - 4.dp.toPx()))
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
