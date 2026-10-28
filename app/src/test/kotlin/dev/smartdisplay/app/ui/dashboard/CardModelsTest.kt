package dev.smartdisplay.app.ui.dashboard

import dev.smartdisplay.app.ha.Area
import dev.smartdisplay.app.ha.EntityEntry
import dev.smartdisplay.app.ha.EntityState
import dev.smartdisplay.app.ha.HomeState
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CardModelsTest {

    private fun entity(id: String, state: String, attributes: String = """{"friendly_name":"$id"}""") =
        EntityState(id, state, Json.parseToJsonElement(attributes).jsonObject, lastChanged = null)

    private fun entry(id: String, areaId: String?) = EntityEntry(id, null, areaId, null, false, false, null)

    @Test
    fun `a devices card shows its room, then other devices chosen, once each`() {
        val home = HomeState(
            entities = listOf(
                entity("light.kitchen", "on"),
                entity("switch.kettle", "off"),
                entity("light.hall", "off"),
                entity("media_player.bathroom", "idle"),
            ).associateBy { it.entityId },
            areas = listOf(Area("kitchen", "Kitchen", null, null), Area("hall", "Hall", null, null)),
            registry = mapOf(
                "light.kitchen" to entry("light.kitchen", "kitchen"),
                "switch.kettle" to entry("switch.kettle", "kitchen"),
                "light.hall" to entry("light.hall", "hall"),
            ),
        )
        val ids = home.cardControls("kitchen", listOf("media_player.bathroom", "light.kitchen", "light.gone"))
            .map { it.entityId }
        assertEquals(listOf("light.kitchen", "switch.kettle", "media_player.bathroom"), ids)
        assertEquals(listOf("light.hall"), home.cardControls(null, listOf("light.hall")).map { it.entityId })
    }

    @Test
    fun `sensor values are rounded to one decimal, with their unit`() {
        val unit = { u: String -> """{"unit_of_measurement":"$u"}""" }
        assertEquals("21.5 °C", sensorValue(entity("sensor.t", "21.46", unit("°C"))))
        assertEquals("48 %", sensorValue(entity("sensor.h", "48.0", unit("%"))))
        assertEquals("1200 W", sensorValue(entity("sensor.p", "1200", unit("W"))))
        assertEquals("3", sensorValue(entity("sensor.n", "3")))
        assertEquals("Dishwasher", sensorValue(entity("sensor.s", "Dishwasher")))
        assertNull(sensorValue(entity("sensor.x", "unavailable")))
    }

    @Test
    fun `binary sensors say what on means for their kind`() {
        assertEquals(BinaryMeaning.OpenClosed, binaryMeaning("door"))
        assertEquals(BinaryMeaning.DetectedClear, binaryMeaning("motion"))
        assertEquals(BinaryMeaning.WetDry, binaryMeaning("moisture"))
        assertEquals(BinaryMeaning.OnOff, binaryMeaning(null))
    }

    @Test
    fun `pictures Home Assistant gives as paths load from its address`() {
        val path = "/api/camera_proxy/camera.door?token=abc"
        assertEquals(
            "http://192.168.1.20:8123/api/camera_proxy/camera.door?token=abc",
            absoluteUrl("http://192.168.1.20:8123/", path),
        )
        assertEquals("https://x.example/a.jpg", absoluteUrl("http://ha", "https://x.example/a.jpg"))
        assertNull(absoluteUrl("http://ha", null))
        assertNull(absoluteUrl(null, path))
    }

    @Test
    fun `the status bar shows sensors always, and doors and locks only when they need a look`() {
        val door = """{"friendly_name":"Back door","device_class":"door"}"""
        val smoke = """{"friendly_name":"Smoke","device_class":"smoke"}"""
        val home = HomeState(
            entities = listOf(
                entity("sensor.water", "45", """{"friendly_name":"Hot water","unit_of_measurement":"°C"}"""),
                entity("binary_sensor.door_open", "on", door),
                entity("binary_sensor.door_shut", "off", door),
                entity("binary_sensor.smoke", "on", smoke),
                entity("lock.front", "locked"),
                entity("lock.back", "unlocked"),
                entity("lock.side", "jammed"),
                entity("cover.garage", "open"),
                entity("cover.blind", "closed"),
                entity("alarm_control_panel.home", "armed_away"),
                entity("sensor.gone", "unavailable"),
            ).associateBy { it.entityId },
        )
        val chips = home.statusChips(home.entities.keys.toList() + "sensor.missing")
        assertEquals(
            listOf(
                StatusChip("sensor.water", "Hot water", value = "45 °C"),
                StatusChip("binary_sensor.door_open", "Back door", word = StatusWord.Open),
                StatusChip("binary_sensor.smoke", "Smoke", word = StatusWord.Detected, alert = true),
                StatusChip("lock.back", "lock.back", word = StatusWord.Unlocked),
                StatusChip("lock.side", "lock.side", word = StatusWord.Jammed, alert = true),
                StatusChip("cover.garage", "cover.garage", word = StatusWord.Open),
                StatusChip("alarm_control_panel.home", "alarm_control_panel.home", word = StatusWord.ArmedAway),
            ),
            chips,
        )
    }
}
