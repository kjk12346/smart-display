package dev.smartdisplay.app.kiosk

/**
 * Who the display is for: the owner (every room), a guest room (one room, plus chosen extras), or a family's shared
 * kitchen (a daytime dashboard; every room, like the owner).
 */
enum class DisplayMode { Owner, Guest, Kitchen }

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
