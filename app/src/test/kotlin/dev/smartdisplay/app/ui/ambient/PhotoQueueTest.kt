package dev.smartdisplay.app.ui.ambient

import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PhotoQueueTest {

    private val photos = listOf("a", "b", "c", "d", "e")

    @Test
    fun `each photo once per round`() {
        val queue = PhotoQueue(Random(1))
        repeat(4) {
            val round = List(photos.size) { queue.next(photos)!! }
            assertEquals(photos.toSet(), round.toSet())
        }
    }

    @Test
    fun `never the same photo twice in a row, across many rounds and seeds`() {
        for (seed in 0 until 50) {
            val queue = PhotoQueue(Random(seed))
            var previous: String? = null
            repeat(40) {
                val next = queue.next(photos)
                assertNotEquals("seed $seed", previous, next)
                previous = next
            }
        }
    }

    @Test
    fun `removed photos drop out and new ones join`() {
        val queue = PhotoQueue(Random(2))
        queue.next(photos)
        val shrunk = listOf("a", "b")
        val next = List(4) { queue.next(shrunk) }
        assertTrue(next.all { it in shrunk })
        val grown = shrunk + "z"
        assertTrue(List(6) { queue.next(grown) }.contains("z"))
    }

    @Test
    fun `a photo added mid-round shows in that round, not after it`() {
        val many = List(10) { "p$it" }
        for (seed in 0 until 20) {
            val queue = PhotoQueue(Random(seed))
            val shown = List(3) { queue.next(many)!! }
            val grown = many + "new"
            // The rest of this round: the 7 not yet shown, plus the new one.
            val rest = List(8) { queue.next(grown)!! }
            assertEquals("seed $seed", (grown - shown.toSet()).toSet(), rest.toSet())
            assertTrue(queue.roundOver)
        }
    }

    @Test
    fun `no photos means no wallpaper, and one photo repeats`() {
        val queue = PhotoQueue()
        assertNull(queue.next(emptyList()))
        assertEquals(listOf("only", "only"), List(2) { queue.next(listOf("only")) })
    }
}

class PanZoomTest {
    @Test
    fun `a move zooms between the limits and pans across the middle, always within the room it has`() {
        for (seed in 0 until 50) {
            val move = PanZoom.random(Random(seed))
            val start = move.at(0f)
            val end = move.at(1f)
            assertEquals(setOf(PanZoom.MIN_ZOOM, PanZoom.MAX_ZOOM), setOf(start.zoom, end.zoom))
            assertEquals(-start.x, end.x, 1e-6f)
            assertEquals(-start.y, end.y, 1e-6f)
            for (step in 0..10) {
                val framing = move.at(step / 10f)
                assertTrue(framing.zoom in PanZoom.MIN_ZOOM - 1e-6f..PanZoom.MAX_ZOOM + 1e-6f)
                assertTrue(framing.x in -1f..1f && framing.y in -1f..1f)
            }
        }
    }
}
