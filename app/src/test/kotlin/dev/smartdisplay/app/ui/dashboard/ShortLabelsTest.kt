package dev.smartdisplay.app.ui.dashboard

import org.junit.Assert.assertEquals
import org.junit.Test

class ShortLabelsTest {
    @Test
    fun `the words every button starts with are left out`() {
        val names = mapOf(
            "script.a" to "Away mode: guest room at night",
            "script.b" to "Away mode: living room at sunset",
        )
        assertEquals(
            mapOf("script.a" to "Guest room at night", "script.b" to "Living room at sunset"),
            shortLabels(names),
        )
    }

    @Test
    fun `the owner's own names win, and a lone button keeps its whole name`() {
        val names = mapOf("script.a" to "Away mode: guest room at night", "script.b" to "Away mode: living room")
        assertEquals("Guest night", shortLabels(names, mapOf("script.a" to " Guest night "))["script.a"])
        assertEquals("Living room", shortLabels(names, mapOf("script.a" to " "))["script.b"])
        assertEquals("Away mode: guest room", shortLabels(mapOf("s" to "Away mode: guest room"))["s"])
    }

    @Test
    fun `nothing is cut when the names share no first word, or one would be left empty`() {
        val names = mapOf("a" to "Movie night", "b" to "Bedtime")
        assertEquals(names, shortLabels(names))
        val same = mapOf("a" to "Good night", "b" to "Good night all")
        assertEquals(mapOf("a" to "Night", "b" to "Night all"), shortLabels(same))
    }
}
