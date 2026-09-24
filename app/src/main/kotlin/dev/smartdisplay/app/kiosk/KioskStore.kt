package dev.smartdisplay.app.kiosk

import android.content.Context
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
) {
    /** Home app or pinning is on: the display shouldn't be left without the PIN. */
    val locked: Boolean get() = homeApp || pinApp
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

    fun setPin(pin: String) {
        val stored = ExitPin.hash(pin)
        update {
            putString(KEY_PIN_SALT, stored.salt)
            putString(KEY_PIN_HASH, stored.hash)
        }
    }

    /** Removes the PIN; refused (false) while Home app or pinning is on, which need it. */
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

    private fun update(change: android.content.SharedPreferences.Editor.() -> Unit) {
        prefs.edit(action = change)
        _config.value = load()
    }

    private fun load() = KioskConfig(
        keepScreenOn = prefs.getBoolean(KEY_KEEP_ON, true),
        dimAfterMinutes = prefs.getInt(KEY_DIM_AFTER, 5),
        homeApp = prefs.getBoolean(KEY_HOME, false),
        pinApp = prefs.getBoolean(KEY_PIN_APP, false),
        hasPin = prefs.contains(KEY_PIN_HASH),
    )

    private companion object {
        const val KEY_KEEP_ON = "keep_screen_on"
        const val KEY_DIM_AFTER = "dim_after_minutes"
        const val KEY_HOME = "home_app"
        const val KEY_PIN_APP = "pin_app"
        const val KEY_PIN_SALT = "pin_salt"
        const val KEY_PIN_HASH = "pin_hash"
    }
}
