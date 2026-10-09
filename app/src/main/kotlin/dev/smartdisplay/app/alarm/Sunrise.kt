package dev.smartdisplay.app.alarm

import dev.smartdisplay.app.ha.EntityState
import kotlin.math.roundToInt
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put

/** How long before an alarm its sunrise starts, as offered in Settings; 0 is off. */
val SUNRISE_CHOICES = listOf(0, 10, 20, 30, 45)

const val DEFAULT_SUNRISE_MINUTES = 30

/** A sunrise in progress: the lights and screen brighten from [startMillis] until [alarmId] rings at [ringMillis]. */
data class Sunrise(val alarmId: Int, val startMillis: Long, val ringMillis: Long) {
    /** How far along it is at [nowMillis], from 0 to 1. */
    fun progress(nowMillis: Long): Float =
        ((nowMillis - startMillis).toFloat() / (ringMillis - startMillis).coerceAtLeast(1)).coerceIn(0f, 1f)
}

/**
 * The light at [progress] (0 to 1) through a sunrise: brightness rises slowly at first, as dawn does (and as eyes
 * see it), and the colour moves from a deep warm glow to soft daylight.
 */
data class SunriseLevel(val brightnessPercent: Int, val kelvin: Int)

fun sunriseLevel(progress: Float): SunriseLevel {
    val p = progress.coerceIn(0f, 1f)
    return SunriseLevel(
        brightnessPercent = (1 + 99 * p * p).roundToInt(),
        kelvin = (START_KELVIN + (END_KELVIN - START_KELVIN) * p).roundToInt(),
    )
}

private const val START_KELVIN = 2000
private const val END_KELVIN = 4000

/** What a light can do in a sunrise, from its Home Assistant attributes. */
data class SunriseLight(
    val entityId: String,
    /** Can be dimmed; on/off-only lights just come on when the alarm rings. */
    val dimmable: Boolean,
    /** Takes a colour temperature (Home Assistant converts it for colour lights without one). */
    val colored: Boolean,
    /** Its colour temperature range, when it says. */
    val minKelvin: Int?,
    val maxKelvin: Int?,
    /** Fades between levels itself, so each step can glide rather than jump. */
    val transitions: Boolean,
)

fun EntityState.sunriseLight(): SunriseLight {
    val modes = (attributes["supported_color_modes"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.content }
        .orEmpty()
    fun int(key: String) = (attributes[key] as? JsonPrimitive)?.let { it.intOrNull ?: it.doubleOrNull?.roundToInt() }
    return SunriseLight(
        entityId = entityId,
        dimmable = modes.any { it != "onoff" } || (modes.isEmpty() && attributes["brightness"] != null),
        colored = modes.any { it in COLOR_MODES },
        minKelvin = int("min_color_temp_kelvin"),
        maxKelvin = int("max_color_temp_kelvin"),
        transitions = (int("supported_features") ?: 0) and TRANSITION_FEATURE != 0,
    )
}

private val COLOR_MODES = setOf("color_temp", "hs", "xy", "rgb", "rgbw", "rgbww")

/** Home Assistant's LightEntityFeature.TRANSITION. */
private const val TRANSITION_FEATURE = 32

/**
 * The `light.turn_on` data for [light] at [level], gliding over [transitionSeconds] if it can; null for a light that
 * can't be dimmed (it waits for the alarm).
 */
fun sunriseCall(light: SunriseLight, level: SunriseLevel, transitionSeconds: Int): JsonObject? {
    if (!light.dimmable) return null
    return buildJsonObject {
        put("brightness_pct", level.brightnessPercent)
        if (light.colored) {
            val low = light.minKelvin ?: Int.MIN_VALUE
            val high = light.maxKelvin ?: Int.MAX_VALUE
            put("color_temp_kelvin", if (low <= high) level.kelvin.coerceIn(low, high) else level.kelvin)
        }
        if (light.transitions && transitionSeconds > 0) put("transition", transitionSeconds)
    }
}
