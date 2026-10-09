package dev.smartdisplay.app.kiosk

import org.junit.Assert.assertEquals
import org.junit.Test

class KitchenConfigTest {
    @Test
    fun `button order survives saving`() {
        val ids = listOf("script.leaving", "scene.dinner", "automation.lights_out")
        assertEquals(ids, decodeIds(encodeIds(ids)))
    }

    @Test
    fun `damaged saved buttons are skipped, not fatal`() {
        assertEquals(emptyList<String>(), decodeIds(null))
        assertEquals(emptyList<String>(), decodeIds("not json"))
        assertEquals(listOf("scene.a"), decodeIds("""["scene.a", 3, "scene.a"]"""))
    }

    @Test
    fun `whose calendar is whose survives saving, and damage is skipped`() {
        val owners = mapOf("calendar.a" to "person.joseph", "calendar.b" to "person.sam")
        assertEquals(owners, decodeMap(encodeMap(owners)))
        assertEquals(emptyMap<String, String>(), decodeMap("not json"))
        assertEquals(mapOf("a" to "x"), decodeMap("""{"a": "x", "b": 3}"""))
    }

    @Test
    fun `people keep chosen colors, and the rest get unused ones in ID order`() {
        val colors = personColors(
            people = listOf("person.sam", "person.alex", "person.joseph"),
            chosen = mapOf("person.joseph" to PersonColor.Pink),
        )
        assertEquals(
            mapOf(
                "person.alex" to PersonColor.Blue,
                "person.joseph" to PersonColor.Pink,
                "person.sam" to PersonColor.Lavender,
            ),
            colors,
        )
    }

    @Test
    fun `with more people than colors, colors repeat`() {
        val people = (1..PersonColor.entries.size + 1).map { "person.p%02d".format(it) }
        val colors = personColors(people, emptyMap())
        assertEquals(PersonColor.entries.first(), colors.getValue(people.last()))
    }
}
