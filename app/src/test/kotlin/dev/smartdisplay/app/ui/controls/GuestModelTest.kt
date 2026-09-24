package dev.smartdisplay.app.ui.controls

import dev.smartdisplay.app.ha.EntityState
import dev.smartdisplay.app.kiosk.GuestConfig
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GuestModelTest {

    private fun control(id: String, kind: ControlKind = ControlKind.Light) =
        Control(EntityState(id, "off", JsonObject(emptyMap()), null), id.substringAfter('.'), kind)

    private val rooms = listOf(
        Room("guest_room", "Guest Room", listOf(control("light.bed"), control("light.desk"), control("climate.guest"))),
        Room("hall_bath", "Hall Bathroom", listOf(control("media_player.bath_speaker"), control("light.bath"))),
        Room("kitchen", "Kitchen", listOf(control("light.kitchen"))),
        Room(null, null, listOf(control("switch.porch"))),
    )

    private fun List<Room>.ids() = map { room -> room.name to room.controls.map { it.entityId } }

    @Test
    fun `a guest sees their room's devices`() {
        assertEquals(
            listOf("Guest Room" to listOf("light.bed", "light.desk", "climate.guest")),
            rooms.forGuest(GuestConfig(areaId = "guest_room")).ids(),
        )
    }

    @Test
    fun `hidden devices are left out of the guest's room`() {
        val config = GuestConfig(areaId = "guest_room", hidden = setOf("climate.guest"))
        assertEquals(listOf("Guest Room" to listOf("light.bed", "light.desk")), rooms.forGuest(config).ids())
    }

    @Test
    fun `extras from other areas follow, under their own area`() {
        val config = GuestConfig(
            areaId = "guest_room",
            extras = setOf("media_player.bath_speaker", "switch.porch"),
        )
        assertEquals(
            listOf(
                "Guest Room" to listOf("light.bed", "light.desk", "climate.guest"),
                "Hall Bathroom" to listOf("media_player.bath_speaker"),
                null to listOf("switch.porch"),
            ),
            rooms.forGuest(config).ids(),
        )
    }

    @Test
    fun `nothing chosen means nothing to control`() {
        assertTrue(rooms.forGuest(GuestConfig()).isEmpty())
        assertTrue(rooms.guestEntityIds(GuestConfig()).isEmpty())
    }

    @Test
    fun `a room that no longer exists leaves just the extras`() {
        val config = GuestConfig(areaId = "deleted_room", extras = setOf("light.kitchen"))
        assertEquals(listOf("Kitchen" to listOf("light.kitchen")), rooms.forGuest(config).ids())
    }

    @Test
    fun `allowed ids are exactly what's shown`() {
        val config = GuestConfig(
            areaId = "guest_room",
            hidden = setOf("light.desk"),
            extras = setOf("media_player.bath_speaker", "sensor.not_a_control"),
        )
        assertEquals(
            setOf("light.bed", "climate.guest", "media_player.bath_speaker"),
            rooms.guestEntityIds(config),
        )
    }
}
