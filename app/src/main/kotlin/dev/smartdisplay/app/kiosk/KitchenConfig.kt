package dev.smartdisplay.app.kiosk

/**
 * A family / kitchen display: by day a dashboard with buttons for Home Assistant scenes, scripts and automations,
 * and who's home, with Home and Away buttons.
 */
data class KitchenConfig(
    /** Scenes, scripts, automations and buttons shown as shortcut buttons, in this order. */
    val shortcuts: List<String> = emptyList(),
    /** What the Home and Away buttons run (a scene or script, say); null leaves the button out. */
    val homeAction: String? = null,
    val awayAction: String? = null,
)
