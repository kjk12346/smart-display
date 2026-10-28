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
    /** Where the clock sits while a photo shows: in a corner (like a photo frame) or in the middle. */
    val clockCorner: Boolean = true,
    /** Show where and when each photo was taken (from its EXIF data), in the opposite corner. */
    val photoInfo: Boolean = true,
)

/** Choices offered for [WallpaperConfig.intervalMinutes]. */
val WALLPAPER_INTERVAL_CHOICES = listOf(1, 5, 15, 60)
