package dev.smartdisplay.app.kiosk

import java.time.DayOfWeek
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FamilyBoardTest {
    private val friday = LocalDate.of(2026, 11, 6)
    private val dishes = Routine("r1", "Dishes", "🍽️", listOf("person.a"), setOf(DayOfWeek.FRIDAY), points = 5)
    private val book = RoutineBook(routines = listOf(dishes), rewards = listOf(Reward("g1", "Movie", cost = 5)))

    @Test
    fun `a routine is due only on its days and for its people`() {
        assertEquals(listOf(dishes), book.routinesFor("person.a", friday))
        assertTrue(book.routinesFor("person.b", friday).isEmpty())
        assertTrue(book.routinesFor("person.a", friday.plusDays(1)).isEmpty())
    }

    @Test
    fun `doing a routine earns its points and undoing takes them back`() {
        val done = book.toggled(dishes, "person.a", friday)
        assertTrue(done.isDone(dishes, "person.a", friday))
        assertEquals(5, done.balances["person.a"])
        assertEquals(1 to 1, done.progress("person.a", friday))
        assertEquals(1, done.history.size)

        val undone = done.toggled(dishes, "person.a", friday)
        assertFalse(undone.isDone(dishes, "person.a", friday))
        assertEquals(0, undone.balances["person.a"])
        assertTrue(undone.history.isEmpty())
    }

    @Test
    fun `a routine is done per person and per day`() {
        val shared = dishes.copy(people = listOf("person.a", "person.b"))
        val done = RoutineBook(routines = listOf(shared)).toggled(shared, "person.a", friday)
        assertFalse(done.isDone(shared, "person.b", friday))
        assertFalse(done.isDone(shared, "person.a", friday.plusDays(7)))
    }

    @Test
    fun `a reward needs enough points`() {
        val reward = book.rewards.first()
        assertEquals(book, book.redeemed(reward, "person.a", friday))
        val earned = book.toggled(dishes, "person.a", friday)
        val spent = earned.redeemed(reward, "person.a", friday)
        assertEquals(0, spent.balances["person.a"])
        assertEquals(-5, spent.history.first().delta)
    }

    @Test
    fun `points never go below zero by hand`() {
        assertEquals(0, book.adjusted("person.a", -3, "Oops", friday).balances["person.a"])
    }

    @Test
    fun `old done marks are dropped but recent ones stay`() {
        val old = doneKey(friday.minusDays(100), "r1", "person.a")
        val recent = doneKey(friday.minusDays(3), "r1", "person.a")
        val kept = book.copy(done = setOf(old, recent)).toggled(dishes, "person.a", friday).done
        assertFalse(old in kept)
        assertTrue(recent in kept)
    }

    @Test
    fun `a one-off countdown ends and a yearly one comes round again`() {
        val trip = Countdown("c1", "Trip", friday.plusDays(12))
        assertEquals(12L, trip.daysLeft(friday))
        assertEquals(0L, trip.daysLeft(friday.plusDays(12)))
        assertNull(trip.daysLeft(friday.plusDays(13)))

        val birthday = Countdown("c2", "Birthday", LocalDate.of(2010, 11, 5), yearly = true)
        assertEquals(LocalDate.of(2027, 11, 5), birthday.nextDate(friday))
        assertEquals(0L, birthday.daysLeft(LocalDate.of(2027, 11, 5)))
    }

    @Test
    fun `a leap day birthday falls back in other years`() {
        val leap = Countdown("c", "Leap", LocalDate.of(2024, 2, 29), yearly = true)
        assertEquals(LocalDate.of(2027, 2, 28), leap.nextDate(friday))
    }

    @Test
    fun `countdowns come soonest first without the past`() {
        val past = Countdown("a", "Past", friday.minusDays(1))
        val later = Countdown("b", "Later", friday.plusDays(9))
        val soon = Countdown("c", "Soon", friday.plusDays(1))
        assertEquals(listOf(soon, later), listOf(past, later, soon).coming(friday))
    }

    @Test
    fun `notes countdowns and routines survive being saved`() {
        val stickies = listOf(Sticky("s", "Lunch is in the fridge", "🍕", PersonColor.Mint, pinned = true), Sticky("t", "Hi"))
        assertEquals(stickies, decodeStickies(encodeStickies(stickies)))

        val countdowns = listOf(Countdown("c", "Trip", friday, "✈️", yearly = true), Countdown("d", "Visit", friday))
        assertEquals(countdowns, decodeCountdowns(encodeCountdowns(countdowns)))

        val saved = book.toggled(dishes, "person.a", friday)
        assertEquals(saved, decodeRoutineBook(encodeRoutineBook(saved)))
    }

    @Test
    fun `damaged saves read as empty`() {
        assertTrue(decodeStickies("not json").isEmpty())
        assertTrue(decodeCountdowns("""[{"id":"c","title":"No date"}]""").isEmpty())
        assertEquals(RoutineBook(), decodeRoutineBook(null))
    }
}
