package dev.smartdisplay.app.kiosk

/** Whether the display is the owner's (every room) or a guest room's (one room, plus chosen extras). */
enum class DisplayMode { Owner, Guest }

/**
 * What a guest-room display may control: the controllable devices in [areaId] except [hidden], plus [extras] from
 * other areas (a speaker in a shared bathroom, say). New devices added to the room in Home Assistant appear
 * automatically; extras never do unless added here.
 */
data class GuestConfig(
    val areaId: String? = null,
    val hidden: Set<String> = emptySet(),
    val extras: Set<String> = emptySet(),
)
