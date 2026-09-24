package dev.smartdisplay.app.kiosk

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** The display's kiosk options, as chosen in Settings. */
data class KioskConfig(
    val keepScreenOn: Boolean = true,
    /** Minutes without a touch before the screen dims; 0 for never. */
    val dimAfterMinutes: Int = 5,
    /** Offered as the Home app (the launcher alias is enabled). */
    val homeApp: Boolean = false,
    /** Screen pinning while the display is showing. */
    val pinApp: Boolean = false,
    val hasPin: Boolean = false,
    val mode: DisplayMode = DisplayMode.Owner,
    val guest: GuestConfig = GuestConfig(),
) {
    val isGuest: Boolean get() = mode == DisplayMode.Guest

    /** Home app, pinning or guest mode is on: the display shouldn't be left without the PIN. */
    val locked: Boolean get() = homeApp || pinApp || isGuest
}

/** Choices offered for [KioskConfig.dimAfterMinutes]. */
val DIM_AFTER_CHOICES = listOf(0, 1, 5, 15, 30)

/** Keeps [KioskConfig] and the exit PIN hash. */
class KioskStore(context: Context) {
    private val prefs = context.getSharedPreferences("kiosk", Context.MODE_PRIVATE)
    private val attempts = PinAttempts()
    private val _config = MutableStateFlow(load())
    val config: StateFlow<KioskConfig> = _config.asStateFlow()

    fun setKeepScreenOn(on: Boolean) = update { putBoolean(KEY_KEEP_ON, on) }

    fun setDimAfterMinutes(minutes: Int) = update { putInt(KEY_DIM_AFTER, minutes.coerceAtLeast(0)) }

    fun setHomeApp(on: Boolean) = update { putBoolean(KEY_HOME, on) }

    fun setPinApp(on: Boolean) = update { putBoolean(KEY_PIN_APP, on) }

    fun setMode(mode: DisplayMode) = update { putString(KEY_MODE, mode.name) }

    fun setGuest(guest: GuestConfig) = update {
        putString(KEY_GUEST_AREA, guest.areaId)
        putStringSet(KEY_GUEST_HIDDEN, guest.hidden)
        putStringSet(KEY_GUEST_EXTRAS, guest.extras)
    }

    fun setPin(pin: String) {
        val stored = ExitPin.hash(pin)
        update {
            putString(KEY_PIN_SALT, stored.salt)
            putString(KEY_PIN_HASH, stored.hash)
        }
    }

    /** Removes the PIN; refused (false) while Home app, pinning or guest mode is on, which need it. */
    fun removePin(): Boolean {
        if (_config.value.locked) return false
        update {
            remove(KEY_PIN_SALT)
            remove(KEY_PIN_HASH)
        }
        return true
    }

    fun checkPin(pin: String, now: Long = System.currentTimeMillis()): PinCheck {
        val stored = storedPin() ?: return PinCheck.Correct
        return attempts.check(pin, now) { ExitPin.verify(it, stored) }
    }

    private fun storedPin(): StoredPin? {
        val salt = prefs.getString(KEY_PIN_SALT, null) ?: return null
        val hash = prefs.getString(KEY_PIN_HASH, null) ?: return null
        return StoredPin(salt, hash)
    }

    private fun update(change: SharedPreferences.Editor.() -> Unit) {
        prefs.edit(action = change)
        _config.value = load()
    }

    private fun load() = KioskConfig(
        keepScreenOn = prefs.getBoolean(KEY_KEEP_ON, true),
        dimAfterMinutes = prefs.getInt(KEY_DIM_AFTER, 5),
        homeApp = prefs.getBoolean(KEY_HOME, false),
        pinApp = prefs.getBoolean(KEY_PIN_APP, false),
        hasPin = prefs.contains(KEY_PIN_HASH),
        mode = DisplayMode.entries.firstOrNull { it.name == prefs.getString(KEY_MODE, null) } ?: DisplayMode.Owner,
        guest = GuestConfig(
            areaId = prefs.getString(KEY_GUEST_AREA, null),
            // Copies: the sets SharedPreferences returns mustn't be kept or changed.
            hidden = prefs.getStringSet(KEY_GUEST_HIDDEN, null)?.toSet().orEmpty(),
            extras = prefs.getStringSet(KEY_GUEST_EXTRAS, null)?.toSet().orEmpty(),
        ),
    )

    private companion object {
        const val KEY_KEEP_ON = "keep_screen_on"
        const val KEY_DIM_AFTER = "dim_after_minutes"
        const val KEY_HOME = "home_app"
        const val KEY_PIN_APP = "pin_app"
        const val KEY_PIN_SALT = "pin_salt"
        const val KEY_PIN_HASH = "pin_hash"
        const val KEY_MODE = "mode"
        const val KEY_GUEST_AREA = "guest_area"
        const val KEY_GUEST_HIDDEN = "guest_hidden"
        const val KEY_GUEST_EXTRAS = "guest_extras"
    }
}
