package dev.smartdisplay.app.ui.controls

import dev.smartdisplay.app.ha.HomeState
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** The keys of a TV remote the display offers. */
enum class TvKey { Up, Down, Left, Right, Select, Back, Home }

/**
 * How to press a TV's remote keys through Home Assistant. Each integration has its own way: a `remote` entity on the
 * TV's device taking `remote.send_command` (Android TV Remote, Roku, Apple TV, Samsung), or an action of its own on
 * the media player (LG webOS, Android TV over ADB).
 */
class TvRemote(
    /** What the keys are sent to: the remote entity, or the media player itself. */
    val entityId: String,
    private val domain: String,
    private val service: String,
    private val field: String,
    private val keys: Map<TvKey, String>,
) {
    fun press(key: TvKey): ServiceCall =
        ServiceCall(domain, service, entityId, buildJsonObject { put(field, keys.getValue(key)) })
}

private fun keys(up: String, down: String, left: String, right: String, select: String, back: String, home: String) =
    mapOf(
        TvKey.Up to up, TvKey.Down to down, TvKey.Left to left, TvKey.Right to right,
        TvKey.Select to select, TvKey.Back to back, TvKey.Home to home,
    )

private val ANDROID_TV_REMOTE = keys("DPAD_UP", "DPAD_DOWN", "DPAD_LEFT", "DPAD_RIGHT", "DPAD_CENTER", "BACK", "HOME")
private val ROKU = keys("up", "down", "left", "right", "select", "back", "home")
private val APPLE_TV = keys("up", "down", "left", "right", "select", "menu", "home")
private val SAMSUNG = keys("KEY_UP", "KEY_DOWN", "KEY_LEFT", "KEY_RIGHT", "KEY_ENTER", "KEY_RETURN", "KEY_HOME")
private val WEBOS = keys("UP", "DOWN", "LEFT", "RIGHT", "ENTER", "BACK", "HOME")
private val ADB = keys("UP", "DOWN", "LEFT", "RIGHT", "CENTER", "BACK", "HOME")

/** The remote for [mediaPlayerId], if its integration offers one this display knows; null otherwise. */
fun HomeState.tvRemote(mediaPlayerId: String): TvRemote? {
    val entry = registry[mediaPlayerId] ?: return null
    when (entry.platform) {
        "webostv" -> return TvRemote(mediaPlayerId, "webostv", "button", "button", WEBOS)
        "androidtv" -> return TvRemote(mediaPlayerId, "androidtv", "adb_command", "command", ADB)
    }
    val deviceId = entry.deviceId ?: return null
    val remote = registry.values.firstOrNull {
        it.deviceId == deviceId && it.entityId.startsWith("remote.") && !it.disabled
    } ?: return null
    val keys = when (remote.platform) {
        "androidtv_remote" -> ANDROID_TV_REMOTE
        "roku" -> ROKU
        "apple_tv" -> APPLE_TV
        "samsungtv" -> SAMSUNG
        else -> return null
    }
    return TvRemote(remote.entityId, "remote", "send_command", "command", keys)
}
