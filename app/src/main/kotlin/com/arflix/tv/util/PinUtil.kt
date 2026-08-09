package com.arflix.tv.util

import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

/**
 * PIN validation and hashing utility for profile locking (4-5 digits)
 * Uses salted SHA-256 hashing to avoid storing plaintext PINs
 */
object PinUtil {
    private const val MIN_LENGTH = 4
    private const val MAX_LENGTH = 5
    private const val SALT_LENGTH = 16 // bytes

    fun isValidPin(pin: String): Boolean {
        return pin.length in MIN_LENGTH..MAX_LENGTH && pin.all { it.isDigit() }
    }

    fun formatPinInput(input: String): String {
        return input.filter { it.isDigit() }.take(MAX_LENGTH)
    }

    /**
     * Hash a PIN with a random salt.
     * Returns a string in format: "salt$hash" where both are Base64-encoded
     */
    fun hashPin(pin: String): String {
        if (!isValidPin(pin)) throw IllegalArgumentException("Invalid PIN")

        val salt = ByteArray(SALT_LENGTH).also { SecureRandom().nextBytes(it) }
        val hash = computeHash(salt, pin)

        val saltB64 = Base64.getEncoder().encodeToString(salt)
        val hashB64 = Base64.getEncoder().encodeToString(hash)
        return "$saltB64\$$hashB64"
    }

    /**
     * Verify that an entered PIN matches the stored hashed PIN
     */
    fun verifyPin(inputPin: String, storedHashedPin: String?): Boolean {
        if (storedHashedPin == null || !isValidPin(inputPin)) return false

        return try {
            val parts = storedHashedPin.split("$")
            if (parts.size != 2) return false

            val salt = Base64.getDecoder().decode(parts[0])
            val storedHash = Base64.getDecoder().decode(parts[1])

            val inputHash = computeHash(salt, inputPin)
            inputHash.contentEquals(storedHash)
        } catch (e: IllegalArgumentException) {
            // decode/format errors are intentionally treated as non-matches
            false
        }
    }

    private fun computeHash(salt: ByteArray, pin: String): ByteArray {
        val digest = MessageDigest.getInstance("SHA-256")
        digest.update(salt)
        return digest.digest(pin.toByteArray(Charsets.UTF_8))
    }
}
