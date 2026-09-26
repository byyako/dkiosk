package com.tyllad.dkiosk.settings

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PinTest {

    @Test
    fun validPinsAreFourToTwelveDigits() {
        assertTrue(Pin.isValid("1234"))
        assertTrue(Pin.isValid("123456789012"))
        assertFalse(Pin.isValid("123"))
        assertFalse(Pin.isValid("1234567890123"))
        assertFalse(Pin.isValid("12a4"))
        assertFalse(Pin.isValid("12 34"))
        assertFalse(Pin.isValid(""))
    }

    @Test
    fun hashMatchesOnlyTheSamePin() {
        val stored = Pin.hash("2468")
        assertTrue(Pin.matches("2468", stored))
        assertFalse(Pin.matches("2469", stored))
        assertFalse(Pin.matches("", stored))
        assertFalse(Pin.matches("24 68", stored))
    }

    @Test(expected = IllegalArgumentException::class)
    fun refusesToHashAnInvalidPin() {
        Pin.hash("12")
    }

    @Test
    fun hashesAreSaltedAndDontContainThePin() {
        val first = Pin.hash("2468")
        val second = Pin.hash("2468")
        assertNotEquals(first, second)
        assertFalse("2468" in first)
        assertTrue(first.startsWith("pbkdf2:"))
    }

    @Test
    fun malformedStoredValuesNeverMatch() {
        assertFalse(Pin.matches("2468", ""))
        assertFalse(Pin.matches("2468", "2468"))
        assertFalse(Pin.matches("2468", "pbkdf2:abc:xx:yy"))
        assertFalse(Pin.matches("2468", "pbkdf2:1000:%%%:%%%"))
        assertFalse(Pin.matches("2468", "md5:1000:AAAA:AAAA"))
        assertFalse(Pin.matches("2468", "pbkdf2:1000::AAAA"))
        assertFalse(Pin.matches("2468", "pbkdf2:0:AAAA:AAAA"))
    }
}
