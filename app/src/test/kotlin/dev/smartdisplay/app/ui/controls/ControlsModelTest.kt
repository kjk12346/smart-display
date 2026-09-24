package dev.smartdisplay.app.ui.controls

import dev.smartdisplay.app.ha.Area
import dev.smartdisplay.app.ha.Device
import dev.smartdisplay.app.ha.EntityEntry
import dev.smartdisplay.app.ha.EntityState
import dev.smartdisplay.app.ha.HomeState
import java.util.Locale
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ControlsModelTest {

    private fun entity(id: String, state: String, attributes: String = "{}") =
        EntityState(id, state, Json.parseToJsonElement(attributes).jsonObject, lastChanged = null)

    private fun named(id: String, state: String, name: String) = entity(id, state, """{"friendly_name":"$name"}""")

    private fun entry(
        id: String,
        area: String? = null,
        device: String? = null,
        hidden: Boolean = false,
        disabled: Boolean = false,
        category: String? = null,
    ) = EntityEntry(id, null, area, device, hidden, disabled, category)

    @Test
    fun `rooms follow areas, with devices' areas and an Other room`() {
        val home = HomeState(
            areas = listOf(Area("kitchen", "Kitchen", null, null), Area("living", "Living Room", null, null)),
            devices = mapOf("tv" to Device("tv", "TV", "living")),
            entities = listOf(
                named("light.kitchen_ceiling", "on", "Kitchen Ceiling light"),
                named("switch.kettle", "off", "Kettle"),
                named("media_player.tv", "playing", "Living Room TV"),
                named("light.porch", "off", "Porch"),
                named("sensor.temperature", "20", "Temperature"),
            ).associateBy { it.entityId },
            registry = listOf(
                entry("light.kitchen_ceiling", area = "kitchen"),
                entry("switch.kettle", area = "kitchen"),
                entry("media_player.tv", device = "tv"),
                entry("sensor.temperature", area = "kitchen"),
            ).associateBy { it.entityId },
        )

        val rooms = home.rooms()
        assertEquals(listOf("Kitchen", "Living Room", null), rooms.map { it.name })
        assertEquals(listOf("Ceiling light", "Kettle"), rooms[0].controls.map { it.name })
        assertEquals(listOf(ControlKind.Light, ControlKind.Switch), rooms[0].controls.map { it.kind })
        assertEquals(listOf("TV"), rooms[1].controls.map { it.name })
        assertEquals(listOf("light.porch"), rooms[2].controls.map { it.entityId })
    }

    @Test
    fun `hidden, disabled and config entities are left out`() {
        val home = HomeState(
            entities = listOf(
                named("light.a", "on", "A"),
                named("light.b", "on", "B"),
                named("light.c", "on", "C"),
                named("switch.d", "on", "D"),
            ).associateBy { it.entityId },
            registry = listOf(
                entry("light.a", hidden = true),
                entry("light.b", disabled = true),
                entry("switch.d", category = "config"),
            ).associateBy { it.entityId },
        )
        assertEquals(listOf("light.c"), home.rooms().single().controls.map { it.entityId })
    }

    @Test
    fun `no controllable entities means no rooms`() {
        assertTrue(HomeState(entities = mapOf("sun.sun" to entity("sun.sun", "above_horizon"))).rooms().isEmpty())
    }

    @Test
    fun `room names are only stripped at a word boundary`() {
        assertEquals("Lamp", displayName("Kitchen lamp", "Kitchen"))
        assertEquals("Kitchenette lamp", displayName("Kitchenette lamp", "Kitchen"))
        assertEquals("Kitchen", displayName("Kitchen", "Kitchen"))
        assertEquals("Lamp", displayName("Lamp", null))
    }

    @Test
    fun `a name repeated twice is shown once`() {
        assertEquals("Backyard left", displayName("Backyard left Backyard left", null))
        assertEquals("Lamp", displayName("Lamp Lamp", null))
        assertEquals("Porch light", displayName("Porch light", null))
        assertEquals("Left left right", displayName("Left left right", null))
        assertEquals("Lamp", displayName("Kitchen Lamp Kitchen Lamp", "Kitchen"))
    }

    @Test
    fun `lights report dimming and brightness`() {
        val dimmable = entity("light.a", "on", """{"supported_color_modes":["brightness"],"brightness":128}""")
        assertEquals(LightInfo(on = true, dimmable = true, brightnessPercent = 50), dimmable.lightInfo())

        val onOff = entity("light.b", "on", """{"supported_color_modes":["onoff"]}""")
        assertFalse(onOff.lightInfo().dimmable)

        val off = entity("light.c", "off", """{"supported_color_modes":["color_temp"]}""")
        assertEquals(LightInfo(on = false, dimmable = true, brightnessPercent = null), off.lightInfo())
    }

    @Test
    fun `media players read features, volume and what's playing`() {
        val info = entity(
            "media_player.tv", "playing",
            """{"supported_features":21437,"volume_level":0.35,"media_title":"Song","media_artist":"Band"}""",
        ).mediaInfo()
        assertTrue(info.active && info.playing && info.canPlayPause && info.canSetVolume)
        assertEquals(0.35f, info.volume!!, 0.001f)
        assertEquals("Song" to "Band", info.title to info.artist)

        val off = entity("media_player.speaker", "off", """{"supported_features":0}""").mediaInfo()
        assertFalse(off.active || off.canPlayPause || off.canSetVolume)
    }

    @Test
    fun `thermostat steps stay on the grid and within limits`() {
        val info = entity(
            "climate.hall", "heat",
            """{"hvac_modes":["off","heat","cool"],"current_temperature":66,"temperature":68,
                "min_temp":60,"max_temp":70}""",
        ).climateInfo("°F")
        assertEquals(listOf("off", "heat", "cool"), info.modes)
        assertEquals(1.0, info.step, 0.0)
        assertEquals(69.0, info.adjust(68.0, 1), 0.0)
        assertEquals(70.0, info.adjust(68.0, 5), 0.0)
        assertEquals(60.0, info.adjust(61.0, -3), 0.0)
    }

    @Test
    fun `celsius thermostats default to half-degree steps`() {
        val info = entity("climate.bed", "heat_cool", """{"target_temp_low":19.5,"target_temp_high":23}""")
            .climateInfo("°C")
        assertEquals(0.5, info.step, 0.0)
        assertNull(info.target)
        assertEquals(20.0, info.adjust(info.targetLow!!, 1), 0.0)
    }

    @Test
    fun `setpoints show half degrees only when needed`() {
        assertEquals("68°", formatSetpoint(68.0, Locale.US))
        assertEquals("19.5°", formatSetpoint(19.5, Locale.US))
        assertEquals("19,5°", formatSetpoint(19.5, Locale.GERMANY))
        assertEquals("20°", formatSetpoint(19.999, Locale.US))
    }

    @Test
    fun `service calls carry the right data`() {
        val light = Control(named("light.a", "on", "A"), "A", ControlKind.Light)
        assertEquals(ServiceCall("light", "toggle", "light.a"), Calls.toggle(light))
        assertEquals("100", Calls.brightness("light.a", 140).data["brightness_pct"]!!.jsonPrimitive.content)
        assertEquals(ServiceCall("light", "turn_off", "light.a"), Calls.brightness("light.a", 0))
        assertEquals("0.0", Calls.volume("media_player.tv", -1f).data["volume_level"]!!.jsonPrimitive.content)
        val range = Calls.temperatureRange("climate.bed", 19.5, 23.0)
        assertEquals(setOf("target_temp_low", "target_temp_high"), range.data.keys)
        assertEquals(JsonObject(emptyMap()), Calls.playPause("media_player.tv").data)
    }
}
