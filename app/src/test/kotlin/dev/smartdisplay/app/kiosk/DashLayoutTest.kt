package dev.smartdisplay.app.kiosk

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class DashLayoutTest {
    @Test
    fun `the default layout fits both grids without overlaps`() {
        val layout = DashLayout()
        layout.cards.forEach { card ->
            assertTrue(layout.canPlace(card.id, WIDE_GRID, card.wide))
            assertTrue(layout.canPlace(card.id, TALL_GRID, card.tall))
        }
    }

    @Test
    fun `a card moves into free space, but not over another card or off the grid`() {
        val layout = DashLayout()
        // Buttons (9,4 3×4) up into Who's home (9,0 3×4): refused, unchanged.
        assertSame(layout, layout.move("buttons", WIDE_GRID, CardRect(9, 2, 3, 4)))
        assertSame(layout, layout.move("buttons", WIDE_GRID, CardRect(10, 4, 3, 4)))
        // Shrink the calendar, then the buttons can go where it was.
        val smaller = layout.move("calendar", WIDE_GRID, CardRect(0, 0, 6, 8))
        val moved = smaller.move("buttons", WIDE_GRID, CardRect(6, 0, 3, 4))
        assertEquals(CardRect(6, 0, 3, 4), moved.cards.first { it.id == "buttons" }.wide)
        // Its portrait place is left alone.
        assertEquals(CardRect(3, 7, 3, 5), moved.cards.first { it.id == "buttons" }.tall)
    }

    @Test
    fun `cards can't be smaller than the minimum`() {
        assertFalse(DashLayout().canPlace("buttons", WIDE_GRID, CardRect(9, 4, 1, 4)))
    }

    @Test
    fun `adding finds free space, shrinking the new card if it has to`() {
        val layout = DashLayout().remove("buttons")
        val added = layout.add(CardType.Sensors, "sensors-1")!!
        val sensors = added.cards.first { it.id == "sensors-1" }
        assertEquals(CardRect(9, 4, 3, 3), sensors.wide)
        assertEquals(CardRect(3, 7, 3, 3), sensors.tall)
    }

    @Test
    fun `with no room, the biggest card makes room`() {
        val added = DashLayout().add(CardType.Sensors, "sensors-1")!!
        // The calendar gave up two rows each way (one wouldn't leave room even for the smallest card).
        assertEquals(CardRect(0, 0, 9, 6), added.cards.first { it.id == "calendar" }.wide)
        assertEquals(CardRect(0, 0, 6, 5), added.cards.first { it.id == "calendar" }.tall)
        assertEquals(CardRect(0, 6, 3, 2), added.cards.first { it.id == "sensors-1" }.wide)
        // In portrait, Who's home and Buttons slid up under it, and the new card went at the bottom.
        assertEquals(CardRect(0, 10, 3, 2), added.cards.first { it.id == "sensors-1" }.tall)
    }

    @Test
    fun `adding gives up only when every card is as small as it goes`() {
        var layout = DashLayout(emptyList())
        var n = 0
        while (true) layout = layout.add(CardType.Sensors, "s${n++}") ?: break
        // Many cards fit, shrunk as needed, before the grids run out.
        assertTrue(layout.cards.size >= 12)
        assertNull(layout.add(CardType.Sensors, "one-more"))
    }

    @Test
    fun `one-of-a-kind cards can only be added back once hidden`() {
        val layout = DashLayout()
        assertFalse(CardType.Calendar in layout.addable())
        assertTrue(CardType.Calendar in layout.remove("calendar").addable())
        assertTrue(CardType.Sensors in layout.addable())
    }

    @Test
    fun `resetting puts one grid's cards back as they started`() {
        val moved = DashLayout()
            .move("calendar", WIDE_GRID, CardRect(0, 0, 6, 8))
            .move("buttons", WIDE_GRID, CardRect(6, 4, 3, 4))
            .move("people", TALL_GRID, CardRect(0, 7, 2, 5))
        val reset = moved.reset(WIDE_GRID)
        assertEquals(DashLayout().cards.map { it.wide }, reset.cards.map { it.wide })
        // Portrait keeps its own arrangement.
        assertEquals(moved.cards.map { it.tall }, reset.cards.map { it.tall })
    }

    @Test
    fun `resetting fits added cards in around the first three`() {
        val layout = DashLayout().add(CardType.Sensors, "sensors-1")!!
            .move("calendar", WIDE_GRID, CardRect(0, 0, 5, 6))
        val reset = layout.reset(WIDE_GRID)
        assertEquals(listOf("calendar", "people", "buttons", "sensors-1"), reset.cards.map { it.id })
        assertEquals(CardRect(9, 0, 3, 4), reset.cards.first { it.id == "people" }.wide)
        assertTrue(reset.cards.all { it.wide.fits(WIDE_GRID) })
        reset.cards.forEach { a ->
            reset.cards.filter { it !== a }.forEach { b -> assertFalse(a.wide.overlaps(b.wide)) }
        }
        assertEquals(layout.cards.map { it.tall }, reset.cards.map { it.tall })
    }

    /** Portrait with a weather card along the bottom, as on the 15.6" tablet. */
    private val withWeather = DashLayout(
        listOf(
            DashCard("calendar", CardType.Calendar, wide = CardRect(0, 0, 9, 8), tall = CardRect(0, 0, 6, 6)),
            DashCard("people", CardType.People, wide = CardRect(9, 0, 3, 4), tall = CardRect(0, 6, 3, 4)),
            DashCard("buttons", CardType.Buttons, wide = CardRect(9, 4, 3, 4), tall = CardRect(3, 6, 3, 4)),
            DashCard("weather", CardType.Weather, wide = CardRect(0, 0, 2, 2), tall = CardRect(0, 10, 6, 2)),
        ),
    )

    private fun DashLayout.tall(id: String) = cards.first { it.id == id }.tall

    @Test
    fun `a card dragged to the top pushes the others down`() {
        val moved = withWeather.arrange("weather", TALL_GRID, CardRect(0, 0, 6, 2))!!
        assertEquals(CardRect(0, 0, 6, 2), moved.tall("weather"))
        assertEquals(CardRect(0, 2, 6, 6), moved.tall("calendar"))
        assertEquals(CardRect(0, 8, 3, 4), moved.tall("people"))
        assertEquals(CardRect(3, 8, 3, 4), moved.tall("buttons"))
        // Landscape is left alone.
        assertEquals(withWeather.cards.map { it.wide }, moved.cards.map { it.wide })
    }

    @Test
    fun `a card dragged into the middle goes between, and the rest close up behind it`() {
        val top = withWeather.arrange("weather", TALL_GRID, CardRect(0, 0, 6, 2))!!
        val middle = top.arrange("weather", TALL_GRID, CardRect(0, 6, 6, 2))!!
        assertEquals(CardRect(0, 0, 6, 6), middle.tall("calendar"))
        assertEquals(CardRect(0, 6, 6, 2), middle.tall("weather"))
        assertEquals(CardRect(0, 8, 3, 4), middle.tall("people"))
    }

    @Test
    fun `a card dropped on its neighbour swaps with it`() {
        val swapped = withWeather.arrange("people", TALL_GRID, CardRect(3, 6, 3, 4))!!
        assertEquals(CardRect(3, 6, 3, 4), swapped.tall("people"))
        assertEquals(CardRect(0, 6, 3, 4), swapped.tall("buttons"))
        assertEquals(CardRect(0, 0, 6, 6), swapped.tall("calendar"))
    }

    @Test
    fun `a card made taller pushes the ones below down, and the tallest give way if they must`() {
        val taller = withWeather.arrange("calendar", TALL_GRID, CardRect(0, 0, 6, 7))!!
        assertEquals(CardRect(0, 0, 6, 7), taller.tall("calendar"))
        // People and Buttons gave up a row so everything still fits.
        assertEquals(12, taller.cards.maxOf { it.tall.y + it.tall.h })
        taller.cards.forEach { a ->
            taller.cards.filter { it !== a }.forEach { b -> assertFalse(a.tall.overlaps(b.tall)) }
        }
        assertNull(withWeather.arrange("calendar", TALL_GRID, CardRect(0, 0, 6, 13)))
    }

    @Test
    fun `the layout survives saving`() {
        val layout = DashLayout().remove("buttons").add(CardType.Devices, "devices-1")!!.let { added ->
            added.update(added.cards.last().copy(areaId = "kitchen", entities = listOf("light.a", "switch.b")))
        }
        assertEquals(layout, decodeLayout(encodeLayout(layout)))
    }

    @Test
    fun `damaged saved layouts fall back, and bad cards are dropped`() {
        assertNull(decodeLayout(null))
        assertNull(decodeLayout("not json"))
        val text = """[
            {"id": "calendar", "type": "Calendar", "wide": [0, 0, 9, 8], "tall": [0, 0, 6, 7]},
            {"id": "x", "type": "Hologram", "wide": [9, 0, 3, 4], "tall": [0, 7, 3, 5]},
            {"id": "over", "type": "Sensors", "wide": [8, 0, 3, 4], "tall": [0, 7, 3, 5]},
            {"id": "calendar2", "type": "Calendar", "wide": [9, 0, 3, 4], "tall": [0, 7, 3, 5]},
            {"id": "ok", "type": "Sensors", "wide": [9, 0, 3, 4], "tall": [0, 7, 3, 5], "entities": ["sensor.t"]}
        ]"""
        val layout = decodeLayout(text)!!
        assertEquals(listOf("calendar", "ok"), layout.cards.map { it.id })
        assertEquals(listOf("sensor.t"), layout.cards.last().entities)
    }
}
