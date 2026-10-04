package com.byyako.dkiosk.config

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDateTime
import java.time.LocalTime

class KioskPrefsTest {

    @Test
    fun mqttHostLosesSchemePortAndPath() {
        assertEquals("broker.local", KioskPrefs.cleanMqttHost("broker.local"))
        assertEquals("broker.local", KioskPrefs.cleanMqttHost(" mqtt://broker.local:1883/ "))
        assertEquals("192.168.1.10", KioskPrefs.cleanMqttHost("192.168.1.10:8883"))
        assertEquals("fd00::5", KioskPrefs.cleanMqttHost("[fd00::5]:1883"))
        assertNull(KioskPrefs.cleanMqttHost("  "))
        assertNull(KioskPrefs.cleanMqttHost("mqtt://"))
        assertNull(KioskPrefs.cleanMqttHost(null))
    }

    @Test
    fun nightPageDuringItsHours() {
        val from = LocalTime.of(22, 0)
        val until = LocalTime.of(7, 0)
        fun at(hour: Int) = KioskPrefs.activeHome("day", "night", from, until, LocalDateTime.of(2026, 10, 4, hour, 30))
        assertEquals("night", at(23))
        assertEquals("night", at(3))
        assertEquals("day", at(7))
        assertEquals("day", at(12))
        assertEquals("day", KioskPrefs.activeHome("day", null, from, until, LocalDateTime.of(2026, 10, 4, 23, 0)))
    }

    @Test
    fun brightness() {
        assertEquals(40, KioskPrefs.parseBrightness("40"))
        assertNull(KioskPrefs.parseBrightness("auto"))
        assertNull(KioskPrefs.parseBrightness("0"))
        assertNull(KioskPrefs.parseBrightness("101"))
    }
}
