package dev.smartdisplay.app.ui.ambient

import dev.smartdisplay.app.ha.EntityState
import dev.smartdisplay.app.ha.HomeState
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.OffsetDateTime
import java.time.ZoneId
import kotlin.math.roundToInt
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull

/** What the ambient screen shows for the weather right now. */
data class CurrentWeather(
    val entityId: String,
    /** A Home Assistant condition, such as `partlycloudy` or `clear-night`. */
    val condition: String,
    val temperature: Double?,
    val unit: String?,
)

/** Today's high and low, from `weather.get_forecasts`. */
data class DailyForecast(val high: Double?, val low: Double?)

private val UNAVAILABLE = setOf("unavailable", "unknown")

/**
 * The weather entity to show: [preferred] (the owner's choice) if it still exists, else the first by entity ID that
 * isn't hidden or disabled in Home Assistant.
 */
fun HomeState.primaryWeatherEntity(preferred: String? = null): EntityState? =
    preferred?.let { entities[it] } ?: weatherEntities().firstOrNull()

/** The weather entities that aren't hidden or disabled, by entity ID. */
fun HomeState.weatherEntities(): List<EntityState> =
    entities.values
        .filter { it.domain == "weather" }
        .filterNot { registry[it.entityId]?.let { entry -> entry.hidden || entry.disabled } == true }
        .sortedBy { it.entityId }

fun EntityState.toCurrentWeather(): CurrentWeather? {
    if (state in UNAVAILABLE) return null
    return CurrentWeather(
        entityId = entityId,
        condition = state,
        temperature = (attributes["temperature"] as? JsonPrimitive)?.doubleOrNull,
        unit = (attributes["temperature_unit"] as? JsonPrimitive)?.takeIf { it.isString }?.content,
    )
}

/**
 * Reads today's entry from a `weather.get_forecasts` call result:
 * `{"response": {"weather.home": {"forecast": [{"temperature": 78, "templow": 61, ...}, ...]}}}`.
 */
fun parseDailyForecast(result: JsonElement, entityId: String): DailyForecast? {
    val response = (result as? JsonObject)?.get("response") as? JsonObject ?: return null
    val forecast = (response[entityId] as? JsonObject)?.get("forecast") as? JsonArray ?: return null
    val today = forecast.firstOrNull() as? JsonObject ?: return null
    val high = (today["temperature"] as? JsonPrimitive)?.doubleOrNull
    val low = (today["templow"] as? JsonPrimitive)?.doubleOrNull
    return if (high == null && low == null) null else DailyForecast(high, low)
}

/** One day of a forecast, for the kitchen dashboard's forecast strip. */
data class ForecastDay(val date: LocalDate, val condition: String?, val high: Double?, val low: Double?)

/**
 * Reads every day from a `weather.get_forecasts` (daily) result, in [zone]'s calendar days. Each entry's `datetime`
 * is an ISO timestamp (or a date); days that can't be read are skipped.
 */
fun parseForecastDays(
    result: JsonElement,
    entityId: String,
    zone: ZoneId = ZoneId.systemDefault(),
): List<ForecastDay> {
    val response = (result as? JsonObject)?.get("response") as? JsonObject ?: return emptyList()
    val forecast = (response[entityId] as? JsonObject)?.get("forecast") as? JsonArray ?: return emptyList()
    return forecast.mapNotNull { item ->
        val day = item as? JsonObject ?: return@mapNotNull null
        val text = (day["datetime"] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: return@mapNotNull null
        val date = runCatching { OffsetDateTime.parse(text).atZoneSameInstant(zone).toLocalDate() }
            .recoverCatching { LocalDate.parse(text.take(10)) }
            .getOrNull() ?: return@mapNotNull null
        ForecastDay(
            date = date,
            condition = (day["condition"] as? JsonPrimitive)?.takeIf { it.isString }?.content,
            high = (day["temperature"] as? JsonPrimitive)?.doubleOrNull,
            low = (day["templow"] as? JsonPrimitive)?.doubleOrNull,
        )
    }.distinctBy { it.date }
}

/** One hour of a forecast: its start, condition, temperature and chance of rain (0–100). */
data class ForecastHour(
    val time: LocalDateTime,
    val condition: String?,
    val temperature: Double?,
    val precipitationChance: Int?,
)

/**
 * Reads every hour from a `weather.get_forecasts` (hourly) result, in [zone]'s local time. Hours that can't be read
 * are skipped.
 */
fun parseForecastHours(
    result: JsonElement,
    entityId: String,
    zone: ZoneId = ZoneId.systemDefault(),
): List<ForecastHour> {
    val response = (result as? JsonObject)?.get("response") as? JsonObject ?: return emptyList()
    val forecast = (response[entityId] as? JsonObject)?.get("forecast") as? JsonArray ?: return emptyList()
    return forecast.mapNotNull { item ->
        val hour = item as? JsonObject ?: return@mapNotNull null
        val text = (hour["datetime"] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: return@mapNotNull null
        val time = runCatching { OffsetDateTime.parse(text).atZoneSameInstant(zone).toLocalDateTime() }
            .getOrNull() ?: return@mapNotNull null
        ForecastHour(
            time = time,
            condition = (hour["condition"] as? JsonPrimitive)?.takeIf { it.isString }?.content,
            temperature = (hour["temperature"] as? JsonPrimitive)?.doubleOrNull,
            precipitationChance = (hour["precipitation_probability"] as? JsonPrimitive)?.doubleOrNull?.roundToInt(),
        )
    }.distinctBy { it.time }.sortedBy { it.time }
}

/** The hours from the one after [now] on: the forecast's coming hours, not the one already under way. */
fun List<ForecastHour>.comingAfter(now: LocalDateTime): List<ForecastHour> = filter { it.time.isAfter(now) }

/** Whole degrees with a degree sign; the unit is left out, as on most displays. */
fun formatTemperature(value: Double): String = "${value.roundToInt()}°"

/** Night hours, when the display dims. The window can wrap past midnight. */
fun isQuietHours(time: LocalTime, start: LocalTime = QUIET_START, end: LocalTime = QUIET_END): Boolean =
    if (start <= end) time >= start && time < end else time >= start || time < end

val QUIET_START: LocalTime = LocalTime.of(22, 0)
val QUIET_END: LocalTime = LocalTime.of(7, 0)

/** True when Home Assistant's sun entity says the sun is down, for night versions of weather icons. */
fun HomeState.sunIsDown(): Boolean? = entities["sun.sun"]?.state?.let { it == "below_horizon" }

/** The sun's next rising and setting, in [zone]'s local time, from Home Assistant's `sun.sun`; null if unknown. */
data class SunTimes(val nextRising: LocalDateTime, val nextSetting: LocalDateTime) {
    /**
     * Whether the sun is down at [time], for night icons on forecast hours: between the next setting and the next
     * rising, or (while it's already down) before the next rising. Good for the coming day, which is all it's for.
     */
    fun isNight(time: LocalDateTime): Boolean = if (nextSetting.isBefore(nextRising)) {
        !time.isBefore(nextSetting) && time.isBefore(nextRising)
    } else {
        time.isBefore(nextRising) || !time.isBefore(nextSetting)
    }
}

fun HomeState.sunTimes(zone: ZoneId = ZoneId.systemDefault()): SunTimes? {
    val sun = entities["sun.sun"] ?: return null
    fun at(key: String) = (sun.attributes[key] as? JsonPrimitive)?.takeIf { it.isString }?.content?.let {
        runCatching { OffsetDateTime.parse(it).atZoneSameInstant(zone).toLocalDateTime() }.getOrNull()
    }
    return SunTimes(at("next_rising") ?: return null, at("next_setting") ?: return null)
}

/**
 * Where to nudge the whole screen this minute, in dp, so no pixel shows the same thing all day. A fixed tour of
 * points within [BURN_IN_RADIUS_DP] of the centre, one step a minute.
 */
fun burnInOffset(epochMinute: Long): Pair<Int, Int> = BURN_IN_PATH[Math.floorMod(epochMinute, BURN_IN_PATH.size.toLong()).toInt()]

const val BURN_IN_RADIUS_DP = 10

private val BURN_IN_PATH = listOf(
    0 to 0, 6 to -4, 10 to 2, 4 to 8, -3 to 5, -7 to 7, -10 to 0, -6 to -7, -1 to -10, 5 to -9,
    8 to -2, 2 to 3, -5 to -2, -9 to 5, 0 to 10, 7 to 6,
)
