package dev.smartdisplay.app.ui.controls

import dev.smartdisplay.app.ha.EntityEntry
import dev.smartdisplay.app.ha.EntityState
import dev.smartdisplay.app.ha.HomeState
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TvRemoteTest {
    private fun entry(id: String, device: String?, platform: String, disabled: Boolean = false) =
        EntityEntry(id, null, null, device, hidden = false, disabled = disabled, category = null, platform = platform)

    private fun home(vararg entries: EntityEntry) = HomeState(registry = entries.associateBy { it.entityId })

    @Test
    fun `a TV with a remote entity on its device sends that integration's keys`() {
        val home = home(
            entry("media_player.guest_tv", "tv1", "androidtv_remote"),
            entry("remote.guest_tv", "tv1", "androidtv_remote"),
        )
        val call = home.tvRemote("media_player.guest_tv")!!.press(TvKey.Select)
        assertEquals("remote", call.domain)
        assertEquals("send_command", call.service)
        assertEquals("remote.guest_tv", call.entityId)
        assertEquals(JsonPrimitive("DPAD_CENTER"), call.data["command"])
        val roku = home(entry("media_player.r", "d", "roku"), entry("remote.r", "d", "roku"))
        assertEquals(JsonPrimitive("back"), roku.tvRemote("media_player.r")!!.press(TvKey.Back).data["command"])
    }

    @Test
    fun `LG and Android TV over ADB use actions on the player itself`() {
        val lg = home(entry("media_player.lg", "d", "webostv")).tvRemote("media_player.lg")!!.press(TvKey.Home)
        assertEquals("webostv", lg.domain)
        assertEquals("button", lg.service)
        assertEquals("media_player.lg", lg.entityId)
        assertEquals(JsonPrimitive("HOME"), lg.data["button"])
        val adb = home(entry("media_player.a", "d", "androidtv")).tvRemote("media_player.a")!!.press(TvKey.Up)
        assertEquals("adb_command", adb.service)
        assertEquals(JsonPrimitive("UP"), adb.data["command"])
    }

    @Test
    fun `no remote for a plain cast device, an unknown integration, or a disabled remote`() {
        assertNull(home(entry("media_player.cc", "d", "cast")).tvRemote("media_player.cc"))
        assertNull(home(entry("media_player.x", "d", "x"), entry("remote.x", "d", "x")).tvRemote("media_player.x"))
        val disabled = home(entry("media_player.r", "d", "roku"), entry("remote.r", "d", "roku", disabled = true))
        assertNull(disabled.tvRemote("media_player.r"))
        assertNull(home().tvRemote("media_player.missing"))
    }

    @Test
    fun `a TV's features are read from its state`() {
        // Pause, volume set and mute, turn on and off, volume step, select source, play.
        val features = 1 + 4 + 8 + 128 + 256 + 1024 + 2048 + 16384
        val tv = EntityState(
            "media_player.tv",
            "on",
            Json.parseToJsonElement(
                """{"device_class":"tv","supported_features":$features,"is_volume_muted":true,
                   "source_list":["HDMI 1","YouTube"],"source":"YouTube"}""",
            ).jsonObject,
            lastChanged = null,
        ).mediaInfo()
        assertTrue(tv.isTv && tv.canTurnOn && tv.canTurnOff && tv.canStepVolume && tv.canMute && tv.muted)
        assertEquals(listOf("HDMI 1", "YouTube"), tv.sources)
        assertEquals("YouTube", tv.source)
        assertEquals(false, tv.canSkip)
    }
}
