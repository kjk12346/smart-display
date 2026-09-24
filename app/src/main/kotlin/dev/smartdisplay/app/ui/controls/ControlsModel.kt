package dev.smartdisplay.app.ui.controls

import dev.smartdisplay.app.ha.EntityState
import dev.smartdisplay.app.ha.HomeState
import java.util.Locale
import kotlin.math.roundToInt
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put

/** The kinds of entity the controls screen has cards for, in the order they're listed within a room. */
enum class ControlKind { Light, Switch, Climate, MediaPlayer }

private val KIND_BY_DOMAIN = mapOf(
    "light" to ControlKind.Light,
    "switch" to ControlKind.Switch,
    "climate" to ControlKind.Climate,
    "media_player" to ControlKind.MediaPlayer,
)

data class Control(val entity: EntityState, val name: String, val kind: ControlKind) {
    val entityId: String get() = entity.entityId
    val unavailable: Boolean get() = entity.state == "unavailable" || entity.state == "unknown"
}

/** A Home Assistant area with something to control. [id] is null for the "Other" room of entities without an area. */
data class Room(val id: String?, val name: String?, val controls: List<Control>)

/**
 * Rooms from Home Assistant's areas (in name order), each with its controllable entities, then an "Other" room for
 * those without an area. Hidden, disabled, and configuration or diagnostic entities are left out, as on Home
 * Assistant's own dashboards.
 */
fun HomeState.rooms(): List<Room> {
    val areaNames = areas.associate { it.id to it.name }
    val controlsByArea = entities.values.mapNotNull { entity ->
        val kind = KIND_BY_DOMAIN[entity.domain] ?: return@mapNotNull null
        val entry = registry[entity.entityId]
        if (entry != null && (entry.hidden || entry.disabled || entry.category != null)) return@mapNotNull null
        val areaId = areaIdOf(entity.entityId)?.takeIf { it in areaNames }
        areaId to Control(entity, displayName(entity.friendlyName, areaNames[areaId]), kind)
    }.groupBy({ it.first }, { it.second })

    val order = compareBy<Control>({ it.kind.ordinal }, { it.name.lowercase() })
    val rooms = areas.mapNotNull { area ->
        controlsByArea[area.id]?.let { Room(area.id, area.name, it.sortedWith(order)) }
    }
    val other = controlsByArea[null]?.let { Room(null, null, it.sortedWith(order)) }
    return rooms + listOfNotNull(other)
}

/**
 * A shorter name for a card: drops the room's name from the front ("Kitchen Ceiling light" is "Ceiling light" in
 * Kitchen), and collapses a name that's the device's name twice ("Backyard left Backyard left"), which Home Assistant
 * produces when an entity is named the same as its device.
 */
fun displayName(name: String, areaName: String?): String = stripArea(collapseRepeat(name), areaName)

private fun stripArea(name: String, areaName: String?): String {
    if (areaName == null || name.length <= areaName.length + 1) return name
    if (!name.startsWith(areaName, ignoreCase = true) || name[areaName.length] != ' ') return name
    return name.substring(areaName.length + 1).trim().replaceFirstChar { it.uppercase() }
}

private fun collapseRepeat(name: String): String {
    val words = name.trim().split(Regex("\\s+"))
    if (words.size < 2 || words.size % 2 != 0) return name
    val half = words.size / 2
    val first = words.subList(0, half)
    val second = words.subList(half, words.size)
    return if (first.zip(second).all { (a, b) -> a.equals(b, ignoreCase = true) }) first.joinToString(" ") else name
}

data class LightInfo(val on: Boolean, val dimmable: Boolean, val brightnessPercent: Int?)

fun EntityState.lightInfo(): LightInfo {
    val modes = (attributes["supported_color_modes"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.content }
    val dimmable = modes?.any { it != "onoff" } ?: (attributes["brightness"] != null)
    val on = state == "on"
    val brightness = attributes.number("brightness")
    return LightInfo(
        on = on,
        dimmable = dimmable,
        brightnessPercent = if (on) brightness?.let { (it / 255 * 100).roundToInt().coerceIn(1, 100) } else null,
    )
}

data class MediaInfo(
    val active: Boolean,
    val playing: Boolean,
    val canPlayPause: Boolean,
    val canSetVolume: Boolean,
    val volume: Float?,
    val title: String?,
    val artist: String?,
)

// media_player supported_features bits.
private const val MEDIA_PAUSE = 1
private const val MEDIA_VOLUME_SET = 4
private const val MEDIA_PLAY = 16384

fun EntityState.mediaInfo(): MediaInfo {
    val features = (attributes["supported_features"] as? JsonPrimitive)?.intOrNull ?: 0
    return MediaInfo(
        active = state !in setOf("off", "standby", "unavailable", "unknown"),
        playing = state == "playing",
        canPlayPause = features and (MEDIA_PAUSE or MEDIA_PLAY) != 0,
        canSetVolume = features and MEDIA_VOLUME_SET != 0,
        volume = attributes.number("volume_level")?.toFloat(),
        title = attributes.text("media_title"),
        artist = attributes.text("media_artist") ?: attributes.text("media_album_artist"),
    )
}

data class ClimateInfo(
    val mode: String,
    val modes: List<String>,
    val current: Double?,
    /** Single target, in modes like heat or cool. */
    val target: Double?,
    /** Range targets, in heat_cool mode. */
    val targetLow: Double?,
    val targetHigh: Double?,
    val step: Double,
    val min: Double,
    val max: Double,
) {
    /** [value] moved by [steps] steps, kept on the step grid and within the thermostat's limits. */
    fun adjust(value: Double, steps: Int): Double {
        val moved = ((value / step).roundToInt() + steps) * step
        return moved.coerceIn(min, max)
    }
}

/** Reads a climate entity; [temperatureUnit] (Home Assistant's, from its config) picks default steps and limits. */
fun EntityState.climateInfo(temperatureUnit: String?): ClimateInfo {
    val fahrenheit = temperatureUnit == "°F"
    return ClimateInfo(
        mode = state,
        modes = (attributes["hvac_modes"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.content }.orEmpty(),
        current = attributes.number("current_temperature"),
        target = attributes.number("temperature"),
        targetLow = attributes.number("target_temp_low"),
        targetHigh = attributes.number("target_temp_high"),
        step = attributes.number("target_temp_step") ?: if (fahrenheit) 1.0 else 0.5,
        min = attributes.number("min_temp") ?: if (fahrenheit) 45.0 else 7.0,
        max = attributes.number("max_temp") ?: if (fahrenheit) 95.0 else 35.0,
    )
}

/** An action to call on one entity. */
data class ServiceCall(
    val domain: String,
    val service: String,
    val entityId: String,
    val data: JsonObject = JsonObject(emptyMap()),
)

object Calls {
    fun toggle(control: Control) = ServiceCall(control.entity.domain, "toggle", control.entityId)

    /** Sets a light's brightness; 0% turns it off. */
    fun brightness(entityId: String, percent: Int) = if (percent <= 0) {
        ServiceCall("light", "turn_off", entityId)
    } else {
        ServiceCall("light", "turn_on", entityId, buildJsonObject { put("brightness_pct", percent.coerceAtMost(100)) })
    }

    fun playPause(entityId: String) = ServiceCall("media_player", "media_play_pause", entityId)

    fun volume(entityId: String, level: Float) = ServiceCall(
        "media_player", "volume_set", entityId, buildJsonObject { put("volume_level", level.coerceIn(0f, 1f)) },
    )

    fun hvacMode(entityId: String, mode: String) = ServiceCall(
        "climate", "set_hvac_mode", entityId, buildJsonObject { put("hvac_mode", mode) },
    )

    fun temperature(entityId: String, target: Double) = ServiceCall(
        "climate", "set_temperature", entityId, buildJsonObject { put("temperature", target) },
    )

    fun temperatureRange(entityId: String, low: Double, high: Double) = ServiceCall(
        "climate", "set_temperature", entityId,
        buildJsonObject {
            put("target_temp_low", low)
            put("target_temp_high", high)
        },
    )
}

/** A thermostat setpoint: whole degrees as "68°", half degrees as "19.5°" (in the device's locale). */
fun formatSetpoint(value: Double, locale: Locale = Locale.getDefault()): String {
    val tenths = (value * 10).roundToInt()
    return if (tenths % 10 == 0) "${tenths / 10}°" else String.format(locale, "%.1f°", tenths / 10.0)
}

private fun JsonObject.number(key: String): Double? = (this[key] as? JsonPrimitive)?.doubleOrNull

private fun JsonObject.text(key: String): String? =
    (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content?.takeIf { it.isNotBlank() }
