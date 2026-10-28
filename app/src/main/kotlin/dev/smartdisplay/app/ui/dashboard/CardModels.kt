package dev.smartdisplay.app.ui.dashboard

import dev.smartdisplay.app.ha.EntityState
import dev.smartdisplay.app.ha.HomeState
import dev.smartdisplay.app.ha.string
import dev.smartdisplay.app.ui.controls.Control
import dev.smartdisplay.app.ui.controls.rooms
import java.math.BigDecimal
import java.math.RoundingMode

/**
 * A Devices card's controls: everything in [areaId] (so devices added there later appear too), then each of
 * [entities] not already in it, in the order chosen. Entities that are gone are skipped.
 */
fun HomeState.cardControls(areaId: String?, entities: List<String>): List<Control> {
    val rooms = rooms()
    val inArea = areaId?.let { id -> rooms.firstOrNull { it.id == id }?.controls }.orEmpty()
    val byId = rooms.flatMap { it.controls }.associateBy { it.entityId }
    val picked = entities.filterNot { id -> inArea.any { it.entityId == id } }.mapNotNull { byId[it] }
    return inArea + picked
}

/** Entities that can go on a card of these [domains], skipping hidden and disabled ones, by name. */
fun HomeState.choosable(vararg domains: String): List<EntityState> =
    entities.values
        .filter { it.domain in domains }
        .filterNot { registry[it.entityId]?.let { entry -> entry.hidden || entry.disabled } == true }
        .sortedBy { it.friendlyName.lowercase() }

/** What a binary sensor's on and off mean, by device class: open/closed, detected/clear, and so on. */
enum class BinaryMeaning { OpenClosed, DetectedClear, WetDry, OnOff }

private val OPEN_CLASSES = setOf("door", "garage_door", "opening", "window", "lock")
private val DETECTED_CLASSES = setOf("motion", "occupancy", "presence", "smoke", "gas", "carbon_monoxide", "sound")

fun binaryMeaning(deviceClass: String?): BinaryMeaning = when (deviceClass) {
    in OPEN_CLASSES -> BinaryMeaning.OpenClosed
    in DETECTED_CLASSES -> BinaryMeaning.DetectedClear
    "moisture" -> BinaryMeaning.WetDry
    else -> BinaryMeaning.OnOff
}

/**
 * A sensor's value to show: a number with at most one decimal and its unit ("21.5 °C", "48 %"), or the state as Home
 * Assistant gives it; null when it's unavailable. Binary sensors are left to [binaryMeaning].
 */
fun sensorValue(entity: EntityState): String? {
    if (entity.state == "unavailable" || entity.state == "unknown") return null
    val unit = entity.attributes.string("unit_of_measurement")
    val number = entity.state.toBigDecimalOrNull()?.let { value ->
        val rounded = if (value.scale() > 1) value.setScale(1, RoundingMode.HALF_UP) else value
        rounded.stripTrailingZeros().let { if (it.scale() < 0) it.setScale(0) else it }.toPlainString()
    }
    return when {
        number == null -> entity.state
        unit.isNullOrBlank() -> number
        else -> "$number $unit"
    }
}

private fun String.toBigDecimalOrNull(): BigDecimal? = runCatching { BigDecimal(this) }.getOrNull()

/** [path] (an `entity_picture`, which Home Assistant gives relative to itself) as a full URL on [base]. */
fun absoluteUrl(base: String?, path: String?): String? = when {
    path.isNullOrBlank() -> null
    path.startsWith("http://") || path.startsWith("https://") -> path
    base == null -> null
    else -> base.trimEnd('/') + "/" + path.trimStart('/')
}

/** What a status chip says about something with a few states: a door open, a lock jammed, the alarm armed. */
enum class StatusWord {
    Open, Detected, Wet, On, Unlocked, Jammed, Disarmed, ArmedHome, ArmedAway, ArmedNight, Armed, Arming, Triggered,
}

/** A chip in the dashboard's status bar: a sensor's [value], or a [word] for the rest; [alert] ones stand out. */
data class StatusChip(
    val entityId: String,
    val name: String,
    val value: String? = null,
    val word: StatusWord? = null,
    val alert: Boolean = false,
)

/** Binary sensors whose "on" is a problem, not just news. */
private val SAFETY_CLASSES = setOf("smoke", "gas", "carbon_monoxide", "moisture", "problem", "safety", "tamper")

/**
 * The status bar's chips for the chosen [ids], in order. Sensors and alarm panels always show; doors, windows,
 * locks, covers and other on/off things only while they're open, unlocked or on, so the bar is what needs a look.
 * Unavailable ones are left out.
 */
fun HomeState.statusChips(ids: List<String>): List<StatusChip> = ids.mapNotNull { entities[it]?.let(::statusChip) }

fun statusChip(entity: EntityState): StatusChip? {
    fun chip(word: StatusWord, alert: Boolean) =
        StatusChip(entity.entityId, entity.friendlyName, word = word, alert = alert)
    return when (entity.domain) {
        "sensor" -> sensorValue(entity)?.let { StatusChip(entity.entityId, entity.friendlyName, value = it) }
        "binary_sensor" -> entity.takeIf { it.state == "on" }?.let {
            val deviceClass = it.attributes.string("device_class")
            val word = when (binaryMeaning(deviceClass)) {
                BinaryMeaning.OpenClosed -> StatusWord.Open
                BinaryMeaning.DetectedClear -> StatusWord.Detected
                BinaryMeaning.WetDry -> StatusWord.Wet
                BinaryMeaning.OnOff -> StatusWord.On
            }
            chip(word, alert = deviceClass in SAFETY_CLASSES)
        }
        "lock" -> when (entity.state) {
            "unlocked", "unlocking" -> chip(StatusWord.Unlocked, alert = false)
            "open", "opening" -> chip(StatusWord.Open, alert = false)
            "jammed" -> chip(StatusWord.Jammed, alert = true)
            else -> null
        }
        "cover" -> chip(StatusWord.Open, alert = false).takeIf { entity.state in setOf("open", "opening", "closing") }
        "alarm_control_panel" -> when (entity.state) {
            "disarmed" -> chip(StatusWord.Disarmed, alert = false)
            "armed_home" -> chip(StatusWord.ArmedHome, alert = false)
            "armed_away" -> chip(StatusWord.ArmedAway, alert = false)
            "armed_night" -> chip(StatusWord.ArmedNight, alert = false)
            "armed_vacation", "armed_custom_bypass" -> chip(StatusWord.Armed, alert = false)
            "arming", "pending" -> chip(StatusWord.Arming, alert = false)
            "triggered" -> chip(StatusWord.Triggered, alert = true)
            else -> null
        }
        else -> null
    }
}

/** What can go in the status bar. */
val STATUS_DOMAINS = arrayOf("sensor", "binary_sensor", "lock", "cover", "alarm_control_panel")
