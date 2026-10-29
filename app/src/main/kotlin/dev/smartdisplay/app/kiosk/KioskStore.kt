package dev.smartdisplay.app.kiosk

import dev.smartdisplay.app.speaker.newSpeakerUdn
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
    /** The Wi-Fi guests join, shown as a QR code. */
    val wifi: WifiConfig = WifiConfig(),
    /** The display as a speaker Home Assistant can play to. */
    val speaker: SpeakerConfig = SpeakerConfig(),
    /** The room last chosen on the controls screen (its key), shown again when it reopens. */
    val lastRoom: String? = null,
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

    fun setLastRoom(key: String?) = update { putString(KEY_LAST_ROOM, key) }

    fun setGuest(guest: GuestConfig) = update {
        putString(KEY_GUEST_AREA, guest.areaId)
        putStringSet(KEY_GUEST_HIDDEN, guest.hidden)
        putStringSet(KEY_GUEST_EXTRAS, guest.extras)
    }

    fun setWallpaper(wallpaper: WallpaperConfig) = update {
        putString(KEY_WALLPAPER_FOLDER, wallpaper.folderId)
        putString(KEY_WALLPAPER_TITLE, wallpaper.folderTitle)
        putInt(KEY_WALLPAPER_INTERVAL, wallpaper.intervalMinutes)
        putBoolean(KEY_WALLPAPER_CORNER, wallpaper.clockCorner)
        putBoolean(KEY_WALLPAPER_INFO, wallpaper.photoInfo)
    }

    fun setKitchen(kitchen: KitchenConfig) = update {
        putString(KEY_KITCHEN_SHORTCUTS, encodeIds(kitchen.shortcuts))
        putString(KEY_KITCHEN_HOME, kitchen.homeAction)
        putString(KEY_KITCHEN_AWAY, kitchen.awayAction)
        putStringSet(KEY_KITCHEN_HIDDEN_CALENDARS, kitchen.hiddenCalendars)
        putString(KEY_KITCHEN_CALENDAR_OWNERS, encodeMap(kitchen.calendarOwners))
        putString(KEY_KITCHEN_PERSON_COLORS, encodeMap(kitchen.personColors.mapValues { it.value.name }))
        putString(KEY_KITCHEN_EVENT_PEOPLE, encodeListMap(kitchen.eventPeople))
        putInt(KEY_KITCHEN_SCREENSAVER, kitchen.screensaverMinutes)
        putString(KEY_KITCHEN_LAYOUT, encodeLayout(kitchen.layout))
        putStringSet(KEY_KITCHEN_HIDDEN_PEOPLE, kitchen.hiddenPeople)
        putString(KEY_KITCHEN_WEATHER, kitchen.weatherEntity)
        putString(KEY_KITCHEN_STATUS, encodeIds(kitchen.statusEntities))
        putString(KEY_KITCHEN_SHORTCUT_NAMES, encodeMap(kitchen.shortcutNames))
        putString(KEY_KITCHEN_SHORTCUT_ICONS, encodeMap(kitchen.shortcutIcons))
        putString(KEY_KITCHEN_CALENDAR_VIEW, kitchen.calendarView.name)
    }

    fun setSpeaker(speaker: SpeakerConfig) = update {
        putBoolean(KEY_SPEAKER_ON, speaker.enabled)
        putString(KEY_SPEAKER_NAME, speaker.name)
    }

    /** The speaker's UPnP identity, made the first time it's asked for and kept, so it stays the same speaker. */
    fun speakerUdn(): String = prefs.getString(KEY_SPEAKER_UDN, null)
        ?: newSpeakerUdn().also { udn -> prefs.edit { putString(KEY_SPEAKER_UDN, udn) } }

    fun setWifi(wifi: WifiConfig) = update {
        putString(KEY_WIFI_SSID, wifi.ssid?.takeIf { it.isNotBlank() })
        putString(KEY_WIFI_PASSWORD, wifi.password)
        putString(KEY_WIFI_SECURITY, wifi.security.name)
        putBoolean(KEY_WIFI_HIDDEN, wifi.hidden)
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
            clockCorner = prefs.getBoolean(KEY_WALLPAPER_CORNER, true),
            photoInfo = prefs.getBoolean(KEY_WALLPAPER_INFO, true),
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
            eventPeople = decodeListMap(prefs.getString(KEY_KITCHEN_EVENT_PEOPLE, null)),
            screensaverMinutes = prefs.getInt(KEY_KITCHEN_SCREENSAVER, DEFAULT_SCREENSAVER_MINUTES),
            layout = decodeLayout(prefs.getString(KEY_KITCHEN_LAYOUT, null)) ?: DashLayout(),
            hiddenPeople = prefs.getStringSet(KEY_KITCHEN_HIDDEN_PEOPLE, null)?.toSet().orEmpty(),
            weatherEntity = prefs.getString(KEY_KITCHEN_WEATHER, null),
            statusEntities = decodeIds(prefs.getString(KEY_KITCHEN_STATUS, null)),
            shortcutNames = decodeMap(prefs.getString(KEY_KITCHEN_SHORTCUT_NAMES, null)),
            shortcutIcons = decodeMap(prefs.getString(KEY_KITCHEN_SHORTCUT_ICONS, null)),
            calendarView = prefs.getString(KEY_KITCHEN_CALENDAR_VIEW, null)
                .let { view -> CalendarView.entries.firstOrNull { it.name == view } } ?: CalendarView.Week,
        ),
        wifi = WifiConfig(
            ssid = prefs.getString(KEY_WIFI_SSID, null),
            password = prefs.getString(KEY_WIFI_PASSWORD, null).orEmpty(),
            security = WifiSecurity.entries.firstOrNull { it.name == prefs.getString(KEY_WIFI_SECURITY, null) }
                ?: WifiSecurity.Wpa,
            hidden = prefs.getBoolean(KEY_WIFI_HIDDEN, false),
        ),
        speaker = SpeakerConfig(
            enabled = prefs.getBoolean(KEY_SPEAKER_ON, false),
            name = prefs.getString(KEY_SPEAKER_NAME, null),
        ),
        lastRoom = prefs.getString(KEY_LAST_ROOM, null),
    )

    private companion object {
        const val KEY_KEEP_ON = "keep_screen_on"
        const val KEY_DIM_AFTER = "dim_after_minutes"
        const val KEY_HOME = "home_app"
        const val KEY_PIN_APP = "pin_app"
        const val KEY_PIN_SALT = "pin_salt"
        const val KEY_PIN_HASH = "pin_hash"
        const val KEY_MODE = "mode"
        const val KEY_LAST_ROOM = "last_room"
        const val KEY_GUEST_AREA = "guest_area"
        const val KEY_GUEST_HIDDEN = "guest_hidden"
        const val KEY_GUEST_EXTRAS = "guest_extras"
        const val KEY_WALLPAPER_FOLDER = "wallpaper_folder"
        const val KEY_WALLPAPER_TITLE = "wallpaper_title"
        const val KEY_WALLPAPER_INTERVAL = "wallpaper_interval"
        const val KEY_WALLPAPER_CORNER = "wallpaper_clock_corner"
        const val KEY_WALLPAPER_INFO = "wallpaper_photo_info"
        const val KEY_KITCHEN_SHORTCUTS = "kitchen_shortcuts"
        const val KEY_KITCHEN_HOME = "kitchen_home"
        const val KEY_KITCHEN_AWAY = "kitchen_away"
        const val KEY_KITCHEN_HIDDEN_CALENDARS = "kitchen_hidden_calendars"
        const val KEY_KITCHEN_CALENDAR_OWNERS = "kitchen_calendar_owners"
        const val KEY_KITCHEN_PERSON_COLORS = "kitchen_person_colors"
        const val KEY_KITCHEN_EVENT_PEOPLE = "kitchen_event_people"
        const val KEY_KITCHEN_SCREENSAVER = "kitchen_screensaver_minutes"
        const val KEY_KITCHEN_LAYOUT = "kitchen_layout"
        const val KEY_KITCHEN_HIDDEN_PEOPLE = "kitchen_hidden_people"
        const val KEY_KITCHEN_WEATHER = "kitchen_weather"
        const val KEY_KITCHEN_STATUS = "kitchen_status"
        const val KEY_SPEAKER_ON = "speaker_on"
        const val KEY_SPEAKER_NAME = "speaker_name"
        const val KEY_SPEAKER_UDN = "speaker_udn"
        const val KEY_WIFI_SSID = "wifi_ssid"
        const val KEY_WIFI_PASSWORD = "wifi_password"
        const val KEY_WIFI_SECURITY = "wifi_security"
        const val KEY_WIFI_HIDDEN = "wifi_hidden"
        const val KEY_KITCHEN_SHORTCUT_NAMES = "kitchen_shortcut_names"
        const val KEY_KITCHEN_SHORTCUT_ICONS = "kitchen_shortcut_icons"
        const val KEY_KITCHEN_CALENDAR_VIEW = "kitchen_calendar_view"
    }
}

/** An ordered list of entity IDs, stored as a JSON array (string sets lose the order). */
internal fun encodeIds(ids: List<String>): String = JsonArray(ids.map(::JsonPrimitive)).toString()

internal fun decodeIds(text: String?): List<String> {
    val array = text?.let { runCatching { Json.parseToJsonElement(it) }.getOrNull() } as? JsonArray ?: return emptyList()
    return array.mapNotNull { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content }.distinct()
}

/** A map of string lists, stored as a JSON object of arrays. */
internal fun encodeListMap(map: Map<String, List<String>>): String =
    JsonObject(map.mapValues { (_, list) -> JsonArray(list.map(::JsonPrimitive)) }).toString()

internal fun decodeListMap(text: String?): Map<String, List<String>> {
    val obj = text?.let { runCatching { Json.parseToJsonElement(it) }.getOrNull() } as? JsonObject ?: return emptyMap()
    return obj.mapNotNull { (key, value) ->
        val list = (value as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content }
        list?.distinct()?.takeIf { it.isNotEmpty() }?.let { key to it }
    }.toMap()
}

/** A map of strings, stored as a JSON object. */
internal fun encodeMap(map: Map<String, String>): String =
    JsonObject(map.mapValues { JsonPrimitive(it.value) }).toString()

internal fun decodeMap(text: String?): Map<String, String> {
    val obj = text?.let { runCatching { Json.parseToJsonElement(it) }.getOrNull() } as? JsonObject ?: return emptyMap()
    return obj.mapNotNull { (key, value) ->
        (value as? JsonPrimitive)?.takeIf { it.isString }?.content?.let { key to it }
    }.toMap()
}
