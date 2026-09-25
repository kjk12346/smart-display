package dev.smartdisplay.app.kiosk

/**
 * Photos behind the clock: the images in one folder of Home Assistant's media browser, changing every
 * [intervalMinutes]. No folder means a plain background.
 */
data class WallpaperConfig(
    /** The folder's `media-source://` ID. */
    val folderId: String? = null,
    /** The folder's name, for Settings. */
    val folderTitle: String? = null,
    val intervalMinutes: Int = 5,
)

/** Choices offered for [WallpaperConfig.intervalMinutes]. */
val WALLPAPER_INTERVAL_CHOICES = listOf(1, 5, 15, 60)
