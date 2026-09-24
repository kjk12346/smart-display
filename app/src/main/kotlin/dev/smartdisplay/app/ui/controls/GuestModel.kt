package dev.smartdisplay.app.ui.controls

import dev.smartdisplay.app.kiosk.GuestConfig

/**
 * The rooms a guest sees: their own room first (without hidden devices), then each other room holding an extra,
 * with only the extras in it. Empty when no room is chosen and there are no extras.
 */
fun List<Room>.forGuest(config: GuestConfig): List<Room> {
    val own = firstOrNull { it.id != null && it.id == config.areaId }
        ?.let { room -> room.copy(controls = room.controls.filterNot { it.entityId in config.hidden }) }
    val elsewhere = filter { it.id == null || it.id != config.areaId }.mapNotNull { room ->
        room.controls.filter { it.entityId in config.extras }
            .takeIf { it.isNotEmpty() }
            ?.let { room.copy(controls = it) }
    }
    return listOfNotNull(own) + elsewhere
}

/** Entity IDs a guest may control, for refusing anything else even if a screen offered it. */
fun List<Room>.guestEntityIds(config: GuestConfig): Set<String> =
    forGuest(config).flatMap { room -> room.controls.map { it.entityId } }.toSet()
