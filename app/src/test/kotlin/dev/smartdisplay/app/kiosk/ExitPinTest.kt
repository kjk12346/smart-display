package dev.smartdisplay.app.kiosk

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ExitPinTest {

    @Test
    fun `only 4 to 8 digits are valid`() {
        assertTrue(ExitPin.isValid("1234"))
        assertTrue(ExitPin.isValid("12345678"))
        assertFalse(ExitPin.isValid("123"))
        assertFalse(ExitPin.isValid("123456789"))
        assertFalse(ExitPin.isValid("12a4"))
        assertFalse(ExitPin.isValid(""))
    }

    @Test
    fun `verifies the right PIN and rejects others`() {
        val stored = ExitPin.hash("2468")
        assertTrue(ExitPin.verify("2468", stored))
        assertFalse(ExitPin.verify("2469", stored))
        assertFalse(ExitPin.verify("24680", stored))
        assertFalse(ExitPin.verify("", stored))
    }

    @Test
    fun `the same PIN hashes differently with different salts`() {
        val a = ExitPin.hash("2468")
        val b = ExitPin.hash("2468")
        assertNotEquals(a.salt, b.salt)
        assertNotEquals(a.hash, b.hash)
        assertTrue(ExitPin.verify("2468", b))
    }

    @Test
    fun `locks out after five wrong tries, then allows again`() {
        val attempts = PinAttempts(maxFailures = 5, lockoutMillis = 30_000)
        val verify = { pin: String -> pin == "1111" }
        repeat(4) { assertEquals(PinCheck.Wrong, attempts.check("0000", now = 1_000L, verify = verify)) }
        assertEquals(PinCheck.LockedOut(30_000), attempts.check("0000", now = 1_000L, verify = verify))
        // Even the right PIN is refused during the lockout.
        assertEquals(PinCheck.LockedOut(20_000), attempts.check("1111", now = 11_000L, verify = verify))
        assertEquals(PinCheck.Correct, attempts.check("1111", now = 31_000L, verify = verify))
    }

    @Test
    fun `a correct PIN resets the count`() {
        val attempts = PinAttempts(maxFailures = 3)
        val verify = { pin: String -> pin == "1111" }
        attempts.check("0000", 0, verify)
        attempts.check("0000", 0, verify)
        assertEquals(PinCheck.Correct, attempts.check("1111", 0, verify))
        assertEquals(PinCheck.Wrong, attempts.check("0000", 0, verify))
        assertEquals(PinCheck.Wrong, attempts.check("0000", 0, verify))
    }
}
