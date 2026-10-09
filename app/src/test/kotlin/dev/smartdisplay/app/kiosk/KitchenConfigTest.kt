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
}
