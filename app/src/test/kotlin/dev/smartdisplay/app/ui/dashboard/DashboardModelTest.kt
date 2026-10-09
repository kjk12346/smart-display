package dev.smartdisplay.app.ui.dashboard

import dev.smartdisplay.app.ha.EntityEntry
import dev.smartdisplay.app.ha.EntityState
import dev.smartdisplay.app.ha.HomeState
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DashboardModelTest {

    private fun entity(id: String, state: String, name: String) = EntityState(
        id, state, Json.parseToJsonElement("""{"friendly_name":"$name"}""").jsonObject, lastChanged = null,
    )

    private fun home(vararg entities: EntityState, registry: Map<String, EntityEntry> = emptyMap()) =
        HomeState(entities = entities.associateBy { it.entityId }, registry = registry)

    @Test
    fun `each kind of button runs the right way`() {
        assertEquals("scene" to "turn_on", runCall("scene.dinner")!!.let { it.domain to it.service })
        assertEquals("script" to "turn_on", runCall("script.leaving")!!.let { it.domain to it.service })
        assertEquals("automation" to "trigger", runCall("automation.away")!!.let { it.domain to it.service })
        assertEquals("button" to "press", runCall("button.doorbell_chime")!!.let { it.domain to it.service })
        assertNull(runCall("light.kitchen"))
    }

    @Test
    fun `buttons can be scenes, scripts, automations and buttons, by name, not hidden ones`() {
        val state = home(
            entity("script.z_leaving", "off", "Leaving"),
            entity("scene.dinner", "unknown", "Dinner"),
            entity("light.kitchen", "on", "Kitchen"),
            entity("automation.hidden", "on", "Hidden"),
            registry = mapOf(
                "automation.hidden" to EntityEntry("automation.hidden", null, null, null, true, false, null),
            ),
        )
        assertEquals(listOf("scene.dinner", "script.z_leaving"), state.runnables().map { it.entityId })
    }

    @Test
    fun `people come home first, then away, with a named zone kept`() {
        val state = home(
            entity("person.sam", "not_home", "Sam"),
            entity("person.alex", "Work", "Alex Kim"),
            entity("person.jo", "home", "Jo"),
            entity("person.pat", "unknown", "Pat"),
        )
        val people = state.people()
        assertEquals(listOf("Jo", "Alex Kim", "Sam", "Pat"), people.map { it.name })
        assertEquals(listOf(Presence.Home, Presence.Away, Presence.Away, Presence.Unknown), people.map { it.presence })
        assertEquals("Work", people[1].place)
        assertNull(people[2].place)
        assertEquals("AK", people[1].initials)
    }

    @Test
    fun `initials are one or two letters`() {
        assertEquals("JA", initials("Joseph  Aquino"))
        assertEquals("M", initials("mum"))
        assertEquals("AB", initials("Ann Beth Cole"))
        assertEquals("?", initials("  "))
    }
}
