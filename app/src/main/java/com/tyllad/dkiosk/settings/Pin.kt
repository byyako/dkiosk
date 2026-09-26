package com.tyllad.dkiosk.settings

import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

/** The settings PIN is never stored, only a salted PBKDF2 hash: "pbkdf2:<iterations>:<salt>:<hash>". */
object Pin {

    const val MIN_LENGTH = 4
    const val MAX_LENGTH = 12

    private const val ITERATIONS = 20_000
    private const val KEY_BITS = 256

    fun isValid(pin: String): Boolean = pin.length in MIN_LENGTH..MAX_LENGTH && pin.all { it in '0'..'9' }

    fun hash(pin: String): String {
        require(isValid(pin)) { "PIN must be $MIN_LENGTH to $MAX_LENGTH digits" }
        val salt = ByteArray(16).also { SecureRandom().nextBytes(it) }
        val hash = derive(pin, salt, ITERATIONS)
        return "pbkdf2:$ITERATIONS:${encode(salt)}:${encode(hash)}"
    }

    fun matches(pin: String, stored: String): Boolean {
        // Also keeps empty input away from PBKDF2, which throws for an empty password on Android.
        if (!isValid(pin)) return false
        val parts = stored.split(":")
        if (parts.size != 4 || parts[0] != "pbkdf2") return false
        val iterations = parts[1].toIntOrNull()?.takeIf { it > 0 } ?: return false
        val salt = decode(parts[2])?.takeIf { it.isNotEmpty() } ?: return false
        val expected = decode(parts[3]) ?: return false
        return MessageDigest.isEqual(derive(pin, salt, iterations), expected)
    }

    private fun derive(pin: String, salt: ByteArray, iterations: Int): ByteArray {
        val spec = PBEKeySpec(pin.toCharArray(), salt, iterations, KEY_BITS)
        return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
    }

    private fun encode(bytes: ByteArray) = Base64.getEncoder().encodeToString(bytes)

    private fun decode(text: String) = try {
        Base64.getDecoder().decode(text)
    } catch (_: IllegalArgumentException) {
        null
    }
}
