package dev.smartdisplay.app.kiosk

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

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
    val wallpaper: WallpaperConfig = WallpaperConfig(),
    val kitchen: KitchenConfig = KitchenConfig(),
) {
    val isGuest: Boolean get() = mode == DisplayMode.Guest
    val isKitchen: Boolean get() = mode == DisplayMode.Kitchen

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

    fun setWallpaper(wallpaper: WallpaperConfig) = update {
        putString(KEY_WALLPAPER_FOLDER, wallpaper.folderId)
        putString(KEY_WALLPAPER_TITLE, wallpaper.folderTitle)
        putInt(KEY_WALLPAPER_INTERVAL, wallpaper.intervalMinutes)
    }

    fun setKitchen(kitchen: KitchenConfig) = update {
        putString(KEY_KITCHEN_SHORTCUTS, encodeIds(kitchen.shortcuts))
        putString(KEY_KITCHEN_HOME, kitchen.homeAction)
        putString(KEY_KITCHEN_AWAY, kitchen.awayAction)
        putStringSet(KEY_KITCHEN_HIDDEN_CALENDARS, kitchen.hiddenCalendars)
        putString(KEY_KITCHEN_CALENDAR_OWNERS, encodeMap(kitchen.calendarOwners))
        putString(KEY_KITCHEN_PERSON_COLORS, encodeMap(kitchen.personColors.mapValues { it.value.name }))
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
        wallpaper = WallpaperConfig(
            folderId = prefs.getString(KEY_WALLPAPER_FOLDER, null),
            folderTitle = prefs.getString(KEY_WALLPAPER_TITLE, null),
            intervalMinutes = prefs.getInt(KEY_WALLPAPER_INTERVAL, 5),
        ),
        kitchen = KitchenConfig(
            shortcuts = decodeIds(prefs.getString(KEY_KITCHEN_SHORTCUTS, null)),
            homeAction = prefs.getString(KEY_KITCHEN_HOME, null),
            awayAction = prefs.getString(KEY_KITCHEN_AWAY, null),
            hiddenCalendars = prefs.getStringSet(KEY_KITCHEN_HIDDEN_CALENDARS, null)?.toSet().orEmpty(),
            calendarOwners = decodeMap(prefs.getString(KEY_KITCHEN_CALENDAR_OWNERS, null)),
            personColors = decodeMap(prefs.getString(KEY_KITCHEN_PERSON_COLORS, null)).mapNotNull { (person, name) ->
                PersonColor.entries.firstOrNull { it.name == name }?.let { person to it }
            }.toMap(),
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
        const val KEY_WALLPAPER_FOLDER = "wallpaper_folder"
        const val KEY_WALLPAPER_TITLE = "wallpaper_title"
        const val KEY_WALLPAPER_INTERVAL = "wallpaper_interval"
        const val KEY_KITCHEN_SHORTCUTS = "kitchen_shortcuts"
        const val KEY_KITCHEN_HOME = "kitchen_home"
        const val KEY_KITCHEN_AWAY = "kitchen_away"
        const val KEY_KITCHEN_HIDDEN_CALENDARS = "kitchen_hidden_calendars"
        const val KEY_KITCHEN_CALENDAR_OWNERS = "kitchen_calendar_owners"
        const val KEY_KITCHEN_PERSON_COLORS = "kitchen_person_colors"
    }
}

/** An ordered list of entity IDs, stored as a JSON array (string sets lose the order). */
internal fun encodeIds(ids: List<String>): String = JsonArray(ids.map(::JsonPrimitive)).toString()

internal fun decodeIds(text: String?): List<String> {
    val array = text?.let { runCatching { Json.parseToJsonElement(it) }.getOrNull() } as? JsonArray ?: return emptyList()
    return array.mapNotNull { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content }.distinct()
}

/** A map of strings, stored as a JSON object. */
internal fun encodeMap(map: Map<String, String>): String = JsonObject(map.mapValues { JsonPrimitive(it.value) }).toString()

internal fun decodeMap(text: String?): Map<String, String> {
    val obj = text?.let { runCatching { Json.parseToJsonElement(it) }.getOrNull() } as? JsonObject ?: return emptyMap()
    return obj.mapNotNull { (key, value) ->
        (value as? JsonPrimitive)?.takeIf { it.isString }?.content?.let { key to it }
    }.toMap()
}
