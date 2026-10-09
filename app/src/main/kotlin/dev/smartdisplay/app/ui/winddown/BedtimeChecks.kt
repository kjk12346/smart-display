package dev.smartdisplay.app.ui.winddown

import dev.smartdisplay.app.ha.HomeState
import dev.smartdisplay.app.ha.string
import dev.smartdisplay.app.ui.controls.ServiceCall

/** Something worth a look before bed. */
enum class CheckKind { Unlocked, GarageOpen, Open, LightOn, MediaOn }

/** One thing left unlocked, open or on, and how to fix it with one tap ([fix] is null for doors and windows). */
data class BedtimeCheck(val entityId: String, val name: String, val kind: CheckKind, val fix: ServiceCall?)

private val OPENINGS = setOf("door", "window", "garage_door", "opening")
private val GARAGE_COVERS = setOf("garage", "door", "gate")

/**
 * What's still unlocked, open or on in the house, most important first: unlocked locks, open garage doors and gates,
 * open doors and windows, lights on, then TVs and the like on. Lights the wind-down looks after ([ignore]) and hidden
 * or disabled entities are left out.
 */
fun HomeState.bedtimeChecks(ignore: Set<String> = emptySet()): List<BedtimeCheck> = entities.values
    .filterNot { it.entityId in ignore }
    .filterNot { registry[it.entityId]?.let { entry -> entry.hidden || entry.disabled } == true }
    .mapNotNull { entity ->
        val id = entity.entityId
        val deviceClass = entity.attributes.string("device_class")
        when {
            entity.domain == "lock" && entity.state in setOf("unlocked", "open", "opening") ->
                CheckKind.Unlocked to ServiceCall("lock", "lock", id)
            entity.domain == "cover" && deviceClass in GARAGE_COVERS && entity.state in setOf("open", "opening") ->
                CheckKind.GarageOpen to ServiceCall("cover", "close_cover", id)
            entity.domain == "binary_sensor" && deviceClass in OPENINGS && entity.state == "on" ->
                CheckKind.Open to null
            entity.domain == "light" && entity.state == "on" ->
                CheckKind.LightOn to ServiceCall("light", "turn_off", id)
            entity.domain == "media_player" && entity.state in setOf("on", "playing") ->
                CheckKind.MediaOn to ServiceCall("media_player", "turn_off", id)
            else -> null
        }?.let { (kind, fix) -> BedtimeCheck(id, entity.friendlyName, kind, fix) }
    }
    .sortedWith(compareBy({ it.kind.ordinal }, { it.name.lowercase() }))
