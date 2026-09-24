package dev.smartdisplay.app.kiosk

import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

/** A stored exit PIN: the salt and the PBKDF2 hash, both Base64. */
data class StoredPin(val salt: String, val hash: String)

/**
 * Hashing for the exit PIN that guards settings on a kiosk display. A 4–8 digit PIN is weak however it's stored; the
 * salted PBKDF2 hash keeps it from being read straight out of the app's files.
 */
object ExitPin {
    private val FORMAT = Regex("\\d{4,8}")
    private const val ITERATIONS = 20_000
    private const val KEY_BITS = 256
    private const val SALT_BYTES = 16

    fun isValid(pin: String): Boolean = FORMAT.matches(pin)

    fun hash(pin: String, salt: ByteArray = randomSalt()): StoredPin {
        require(isValid(pin)) { "PIN must be 4 to 8 digits" }
        val encoder = Base64.getEncoder()
        return StoredPin(encoder.encodeToString(salt), encoder.encodeToString(derive(pin, salt)))
    }

    fun verify(pin: String, stored: StoredPin): Boolean {
        if (!isValid(pin)) return false
        val decoder = Base64.getDecoder()
        val expected = decoder.decode(stored.hash)
        return MessageDigest.isEqual(expected, derive(pin, decoder.decode(stored.salt)))
    }

    private fun derive(pin: String, salt: ByteArray): ByteArray {
        val spec = PBEKeySpec(pin.toCharArray(), salt, ITERATIONS, KEY_BITS)
        return try {
            SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
        } finally {
            spec.clearPassword()
        }
    }

    private fun randomSalt() = ByteArray(SALT_BYTES).also(SecureRandom()::nextBytes)
}

sealed interface PinCheck {
    data object Correct : PinCheck
    data object Wrong : PinCheck
    /** Too many wrong tries; try again after this long. */
    data class LockedOut(val millis: Long) : PinCheck
}

/** Limits guessing: after [maxFailures] wrong PINs in a row, no tries for [lockoutMillis]. */
class PinAttempts(private val maxFailures: Int = 5, private val lockoutMillis: Long = 30_000) {
    private var failures = 0
    private var lockedUntil = 0L

    /** Checks [pin] with [verify], unless locked out at [now]. */
    @Synchronized
    fun check(pin: String, now: Long, verify: (String) -> Boolean): PinCheck {
        if (now < lockedUntil) return PinCheck.LockedOut(lockedUntil - now)
        if (verify(pin)) {
            failures = 0
            return PinCheck.Correct
        }
        failures++
        if (failures >= maxFailures) {
            failures = 0
            lockedUntil = now + lockoutMillis
            return PinCheck.LockedOut(lockoutMillis)
        }
        return PinCheck.Wrong
    }
}
