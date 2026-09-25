package dev.smartdisplay.app.ui.settings

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import dev.smartdisplay.app.SmartDisplayApp
import dev.smartdisplay.app.ha.MediaFolder
import dev.smartdisplay.app.ha.MediaItem
import dev.smartdisplay.app.kiosk.DisplayMode
import dev.smartdisplay.app.kiosk.HomeApp
import dev.smartdisplay.app.kiosk.KioskConfig
import dev.smartdisplay.app.kiosk.PinCheck
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext

/** Why a kiosk option couldn't be turned on as asked. */
enum class KioskProblem {
    /** Home app, pinning and guest mode need an exit PIN first, so there's a way out. */
    NeedsPin,
    /** The Home app was offered, but this device has no Home app chooser (Fire tablets). */
    NoHomeChooser,
    /** The PIN can't be removed while Home app, pinning or guest mode is on. */
    PinInUse,
}

class SettingsViewModel(application: Application) : AndroidViewModel(application) {
    private val kiosk = (application as SmartDisplayApp).kiosk
    private val home = (application as SmartDisplayApp).home

    val config: StateFlow<KioskConfig> = kiosk.config

    fun setKeepScreenOn(on: Boolean) = kiosk.setKeepScreenOn(on)

    fun setDimAfterMinutes(minutes: Int) = kiosk.setDimAfterMinutes(minutes)

    /** Hashing is deliberately slow, so it runs off the main thread. */
    suspend fun setPin(pin: String) = withContext(Dispatchers.Default) { kiosk.setPin(pin) }

    fun removePin(): KioskProblem? = if (kiosk.removePin()) null else KioskProblem.PinInUse

    fun setHomeApp(on: Boolean): KioskProblem? {
        if (on && !config.value.hasPin) return KioskProblem.NeedsPin
        val context = getApplication<Application>()
        HomeApp.setEnabled(context, on)
        kiosk.setHomeApp(on)
        return if (on && !HomeApp.openHomeChooser(context)) KioskProblem.NoHomeChooser else null
    }

    fun setPinApp(on: Boolean): KioskProblem? {
        if (on && !config.value.hasPin) return KioskProblem.NeedsPin
        kiosk.setPinApp(on)
        return null
    }

    /** Lists a folder in Home Assistant's media browser, for picking a wallpaper folder or an alarm sound. */
    suspend fun browseMedia(contentId: String): MediaFolder = home.browseMedia(contentId)

    fun setWallpaperFolder(folder: MediaItem?) {
        kiosk.setWallpaper(config.value.wallpaper.copy(folderId = folder?.contentId, folderTitle = folder?.title))
    }

    fun setWallpaperInterval(minutes: Int) {
        kiosk.setWallpaper(config.value.wallpaper.copy(intervalMinutes = minutes))
    }

    /** Guest mode needs the exit PIN, so switching back to the owner's view is always behind it. */
    fun setMode(mode: DisplayMode): KioskProblem? {
        if (mode == DisplayMode.Guest && !config.value.hasPin) return KioskProblem.NeedsPin
        kiosk.setMode(mode)
        return null
    }

    /** Chooses the guest's room. Devices hidden in the previous room no longer apply. */
    fun setGuestArea(areaId: String) {
        val guest = config.value.guest
        if (guest.areaId != areaId) kiosk.setGuest(guest.copy(areaId = areaId, hidden = emptySet()))
    }

    /** Lets guests use a device in their room, or hides it from them. */
    fun setGuestAllowed(entityId: String, allowed: Boolean) {
        val guest = config.value.guest
        kiosk.setGuest(guest.copy(hidden = if (allowed) guest.hidden - entityId else guest.hidden + entityId))
    }

    /** Adds or removes a device from another room. */
    fun setGuestExtra(entityId: String, included: Boolean) {
        val guest = config.value.guest
        kiosk.setGuest(guest.copy(extras = if (included) guest.extras + entityId else guest.extras - entityId))
    }
}

/** Checks the exit PIN off the main thread (see [SettingsViewModel.setPin]). */
suspend fun SmartDisplayApp.checkExitPin(pin: String): PinCheck =
    withContext(Dispatchers.Default) { kiosk.checkPin(pin) }
