package dev.smartdisplay.app.ha

import kotlin.random.Random
import org.junit.Assert.assertTrue
import org.junit.Test

class BackoffTest {
    private val backoff = Backoff(baseMillis = 1_000, maxMillis = 30_000, random = Random(42))

    private fun assertIn(range: LongRange, value: Long) = assertTrue("$value not in $range", value in range)

    @Test
    fun `doubles from the base with jitter`() {
        assertIn(800L..1_200L, backoff.delayMillis(1))
        assertIn(1_600L..2_400L, backoff.delayMillis(2))
        assertIn(3_200L..4_800L, backoff.delayMillis(3))
    }

    @Test
    fun `stops growing at the maximum`() {
        assertIn(24_000L..36_000L, backoff.delayMillis(6))
        assertIn(24_000L..36_000L, backoff.delayMillis(1_000))
    }
}
