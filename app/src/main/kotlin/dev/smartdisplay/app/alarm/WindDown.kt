package dev.smartdisplay.app.alarm

import dev.smartdisplay.app.ha.EntityState
import java.time.LocalTime
import java.time.ZonedDateTime
import kotlin.math.roundToInt
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull

/**
 * The evening wind-down, as set in Settings: before bedtime, the chosen lights slowly dim and warm, then go off.
 * Bedtime is [sleepMinutes] before the next morning's alarm, or [fallbackBedtime] on nights without one.
 */
data class WindDownSettings(
    /** Starts by itself each night; without this it only runs from its button. */
    val enabled: Boolean = false,
    /** Lights that dim and warm, then go off at bedtime. */
    val lights: Set<String> = emptySet(),
    /** How long the dimming takes. */
    val minutes: Int = DEFAULT_WIND_DOWN_MINUTES,
    /** How long before a morning alarm the lights go off. */
    val sleepMinutes: Int = DEFAULT_SLEEP_MINUTES,
    /** Bedtime on nights without a morning alarm; null for no wind-down on those nights. */
    val fallbackBedtime: LocalTime? = DEFAULT_FALLBACK_BEDTIME,
    /** Lights, TVs, plugs and so on elsewhere, turned off when it starts. */
    val houseOff: Set<String> = emptySet(),
) {
    /** Has something to do, so it's worth offering its button. */
    val configured: Boolean get() = lights.isNotEmpty() || houseOff.isNotEmpty()
}

const val DEFAULT_WIND_DOWN_MINUTES = 45
const val DEFAULT_SLEEP_MINUTES = 8 * 60
val DEFAULT_FALLBACK_BEDTIME: LocalTime = LocalTime.of(23, 0)

/** The choices Settings offers. */
val WIND_DOWN_CHOICES = listOf(15, 30, 45, 60)
val SLEEP_CHOICES = listOf(7 * 60, 7 * 60 + 30, 8 * 60, 8 * 60 + 30, 9 * 60)
val FALLBACK_BEDTIME_CHOICES: List<LocalTime?> = listOf(
    null, LocalTime.of(22, 0), LocalTime.of(22, 30), LocalTime.of(23, 0), LocalTime.of(23, 30), LocalTime.MIDNIGHT,
)

/** Alarms ringing between these times count as a morning's wake-up alarm. */
private val WAKE_EARLIEST: LocalTime = LocalTime.of(3, 0)
private val WAKE_LATEST: LocalTime = LocalTime.NOON

/** A wind-down: the lights dim from [startMillis] and go off at [bedtimeMillis]. */
data class WindDown(val startMillis: Long, val bedtimeMillis: Long) {
    /** How far along it is at [nowMillis], from 0 to 1. */
    fun progress(nowMillis: Long): Float =
        ((nowMillis - startMillis).toFloat() / (bedtimeMillis - startMillis).coerceAtLeast(1)).coerceIn(0f, 1f)
}

/**
 * The next wind-down that should run by itself, possibly one already under way (it started before [now]), or null if
 * it's off or there's none in the coming week.
 *
 * Each night's bedtime is [WindDownSettings.sleepMinutes] before the first of [alarms] ringing the next morning
 * (3 AM to noon), or [WindDownSettings.fallbackBedtime] without one. A night whose bedtime is at or before
 * [doneUntilMillis] was already wound down (from its button) or stopped.
 */
fun nextWindDown(
    alarms: List<Alarm>,
    skip: Skip?,
    settings: WindDownSettings,
    now: ZonedDateTime,
    doneUntilMillis: Long = 0,
): WindDown? {
    if (!settings.enabled) return null
    val zone = now.zone
    val nowMillis = now.toInstant().toEpochMilli()
    // From last night (a bedtime after midnight may be still to come), through the next week.
    for (offset in -1L..7L) {
        val evening = now.toLocalDate().plusDays(offset)
        val morning = evening.plusDays(1)
        val wakeFrom = ZonedDateTime.of(morning, WAKE_EARLIEST, zone)
        val wakeBy = ZonedDateTime.of(morning, WAKE_LATEST, zone)
        val ring = nextRing(alarms, snooze = null, now = wakeFrom.minusSeconds(1), skip = skip)
            ?.at?.takeIf { it.isBefore(wakeBy) }
        val bedtime = when {
            ring != null -> ring.minusMinutes(settings.sleepMinutes.toLong())
            else -> settings.fallbackBedtime?.let { time ->
                // A bedtime after midnight is that next morning's.
                ZonedDateTime.of(if (time.isBefore(LocalTime.NOON)) morning else evening, time, zone)
            } ?: continue
        }
        val bedtimeMillis = bedtime.toInstant().toEpochMilli()
        if (bedtimeMillis <= nowMillis || bedtimeMillis <= doneUntilMillis) continue
        return WindDown(bedtimeMillis - settings.minutes * 60_000L, bedtimeMillis)
    }
    return null
}

/** A light as the wind-down found it: on, at this brightness (and color temperature, if it says). */
data class LightStart(val brightnessPercent: Int, val kelvin: Int?)

/** How [this] light is now, if it's on. A light on/off only counts as full brightness. */
fun EntityState.lightStart(): LightStart? {
    if (state != "on") return null
    fun number(key: String) = (attributes[key] as? JsonPrimitive)?.doubleOrNull
    val brightness = number("brightness")?.let { (it * 100 / 255).roundToInt().coerceIn(1, 100) } ?: 100
    return LightStart(brightness, number("color_temp_kelvin")?.roundToInt())
}

/**
 * The light at [progress] (0 to 1) through a wind-down from [start]: the brightness falls to a glow, faster at
 * first and lingering low (to the eye, an even fade), and the color warms to candlelight. Never brighter or cooler
 * than it started.
 */
fun windDownLevel(start: LightStart, progress: Float): SunriseLevel {
    val p = progress.coerceIn(0f, 1f)
    val remaining = (1 - p) * (1 - p)
    val brightness = END_PERCENT + (start.brightnessPercent - END_PERCENT) * remaining
    val fromKelvin = start.kelvin ?: ASSUMED_KELVIN
    val kelvin = if (fromKelvin <= END_KELVIN) {
        fromKelvin.toFloat()
    } else {
        fromKelvin + (END_KELVIN - fromKelvin) * p
    }
    return SunriseLevel(
        brightnessPercent = brightness.roundToInt().coerceIn(1, maxOf(start.brightnessPercent, 1)),
        kelvin = kelvin.roundToInt(),
    )
}

private const val END_PERCENT = 2
private const val END_KELVIN = 2200

/** A white light that doesn't say its color temperature is taken as an ordinary warm-white bulb. */
private const val ASSUMED_KELVIN = 2700
