package dev.smartdisplay.app.ui.settings

import androidx.annotation.StringRes
import dev.smartdisplay.app.R
import java.text.Normalizer

/**
 * A setting that search can find: its heading on [page], and other words someone might look for it by ([keywords],
 * space-separated, never shown).
 */
internal data class SearchTopic(val page: SettingsPage, @StringRes val title: Int, @StringRes val keywords: Int? = null)

/** Every setting search knows, page by page in the order Settings lists them. */
internal val SEARCH_TOPICS = listOf(
    SearchTopic(SettingsPage.Mode, R.string.settings_mode, R.string.search_kw_mode),
    SearchTopic(SettingsPage.Mode, R.string.guest_room, R.string.search_kw_guest_room),
    SearchTopic(SettingsPage.Mode, R.string.guest_elsewhere, R.string.search_kw_guest_elsewhere),
    SearchTopic(SettingsPage.Kitchen, R.string.kitchen_calendars, R.string.search_kw_calendars),
    SearchTopic(SettingsPage.Kitchen, R.string.kitchen_colors, R.string.search_kw_colors),
    SearchTopic(SettingsPage.Kitchen, R.string.kitchen_buttons, R.string.search_kw_buttons),
    SearchTopic(SettingsPage.Kitchen, R.string.kitchen_modes, R.string.search_kw_home_away),
    SearchTopic(SettingsPage.Kitchen, R.string.kitchen_screensaver, R.string.search_kw_screensaver),
    SearchTopic(SettingsPage.Display, R.string.settings_keep_on, R.string.search_kw_keep_on),
    SearchTopic(SettingsPage.Display, R.string.settings_dim_after, R.string.search_kw_dim),
    SearchTopic(SettingsPage.Display, R.string.wallpaper_title, R.string.search_kw_wallpaper),
    SearchTopic(SettingsPage.Display, R.string.wallpaper_interval, R.string.search_kw_wallpaper_interval),
    SearchTopic(SettingsPage.Display, R.string.wallpaper_clock, R.string.search_kw_clock),
    SearchTopic(SettingsPage.Display, R.string.wallpaper_photo_info, R.string.search_kw_photo_info),
    SearchTopic(SettingsPage.Wifi, R.string.settings_wifi, R.string.search_kw_wifi),
    SearchTopic(SettingsPage.Wifi, R.string.settings_wifi_hidden, R.string.search_kw_wifi),
    SearchTopic(SettingsPage.Alarms, R.string.alarm_sound, R.string.search_kw_alarm_sound),
    SearchTopic(SettingsPage.Alarms, R.string.alarm_lights, R.string.search_kw_alarm),
    SearchTopic(SettingsPage.Alarms, R.string.alarm_speaker, R.string.search_kw_alarm_speaker),
    SearchTopic(SettingsPage.Alarms, R.string.sunrise_setting, R.string.search_kw_sunrise),
    SearchTopic(SettingsPage.Alarms, R.string.alarm_clear_all, R.string.search_kw_alarm_clear),
    SearchTopic(SettingsPage.WindDown, R.string.wind_down_lights, R.string.search_kw_wind_down),
    SearchTopic(SettingsPage.WindDown, R.string.wind_down_length, R.string.search_kw_wind_down),
    SearchTopic(SettingsPage.WindDown, R.string.wind_down_auto, R.string.search_kw_wind_down_auto),
    SearchTopic(SettingsPage.WindDown, R.string.wind_down_sleep, R.string.search_kw_wind_down),
    SearchTopic(SettingsPage.WindDown, R.string.wind_down_no_alarm_nights, R.string.search_kw_wind_down),
    SearchTopic(SettingsPage.WindDown, R.string.wind_down_house_off, R.string.search_kw_house_off),
    SearchTopic(SettingsPage.Kiosk, R.string.settings_exit_pin, R.string.search_kw_pin),
    SearchTopic(SettingsPage.Kiosk, R.string.settings_home_app, R.string.search_kw_home_app),
    SearchTopic(SettingsPage.Kiosk, R.string.settings_pin_app, R.string.search_kw_pin_app),
    SearchTopic(SettingsPage.Connection, R.string.settings_connection, R.string.search_kw_connection),
    SearchTopic(SettingsPage.Connection, R.string.sign_out, R.string.search_kw_sign_out),
)

/**
 * Whether [query] finds a setting described by [texts] (its heading, its page's name, its keywords): every word of
 * the query appears somewhere in them. Case, accents and hyphens don't count, so "wifi" finds "Wi-Fi".
 */
internal fun searchMatches(query: String, texts: List<String>): Boolean {
    val words = simplify(query).split(' ').filter { it.isNotEmpty() }
    if (words.isEmpty()) return false
    val haystack = texts.joinToString(" ") { simplify(it) }
    return words.all { it in haystack }
}

private fun simplify(text: String): String =
    Normalizer.normalize(text, Normalizer.Form.NFD)
        .replace(Regex("\\p{M}+"), "")
        .lowercase()
        .replace("-", "")
        .replace(Regex("[^\\p{L}\\p{N}]+"), " ")
        .trim()
