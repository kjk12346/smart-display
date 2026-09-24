package dev.smartdisplay.app.ui.settings

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import dev.smartdisplay.app.SmartDisplayApp
import dev.smartdisplay.app.kiosk.HomeApp
import dev.smartdisplay.app.kiosk.KioskConfig
import dev.smartdisplay.app.kiosk.PinCheck
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext

/** Why a kiosk option couldn't be turned on as asked. */
enum class KioskProblem {
    /** Home app and pinning need an exit PIN first, so there's a way out. */
    NeedsPin,
    /** The Home app was offered, but this device has no Home app chooser (Fire tablets). */
    NoHomeChooser,
    /** The PIN can't be removed while Home app or pinning is on. */
    PinInUse,
}

class SettingsViewModel(application: Application) : AndroidViewModel(application) {
    private val kiosk = (application as SmartDisplayApp).kiosk

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
}

/** Checks the exit PIN off the main thread (see [SettingsViewModel.setPin]). */
suspend fun SmartDisplayApp.checkExitPin(pin: String): PinCheck =
    withContext(Dispatchers.Default) { kiosk.checkPin(pin) }
