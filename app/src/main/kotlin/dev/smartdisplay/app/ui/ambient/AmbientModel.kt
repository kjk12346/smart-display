package dev.smartdisplay.app.ui.ambient

import dev.smartdisplay.app.ha.EntityState
import dev.smartdisplay.app.ha.HomeState
import java.time.LocalTime
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

/** The weather entity to show: the first by entity ID that isn't hidden or disabled in Home Assistant. */
fun HomeState.primaryWeatherEntity(): EntityState? =
    entities.values
        .filter { it.domain == "weather" }
        .filterNot { registry[it.entityId]?.let { entry -> entry.hidden || entry.disabled } == true }
        .minByOrNull { it.entityId }

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

/** Whole degrees with a degree sign; the unit is left out, as on most displays. */
fun formatTemperature(value: Double): String = "${value.roundToInt()}°"

/** Night hours, when the display dims. The window can wrap past midnight. */
fun isQuietHours(time: LocalTime, start: LocalTime = QUIET_START, end: LocalTime = QUIET_END): Boolean =
    if (start <= end) time >= start && time < end else time >= start || time < end

val QUIET_START: LocalTime = LocalTime.of(22, 0)
val QUIET_END: LocalTime = LocalTime.of(7, 0)

/** True when Home Assistant's sun entity says the sun is down, for night versions of weather icons. */
fun HomeState.sunIsDown(): Boolean? = entities["sun.sun"]?.state?.let { it == "below_horizon" }

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
