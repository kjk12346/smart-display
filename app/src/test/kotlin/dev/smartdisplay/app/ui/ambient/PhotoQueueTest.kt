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
    fun `no photos means no wallpaper, and one photo repeats`() {
        val queue = PhotoQueue()
        assertNull(queue.next(emptyList()))
        assertEquals(listOf("only", "only"), List(2) { queue.next(listOf("only")) })
    }
}
