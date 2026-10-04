package com.byyako.dkiosk.config

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

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
    fun brightness() {
        assertEquals(40, KioskPrefs.parseBrightness("40"))
        assertNull(KioskPrefs.parseBrightness("auto"))
        assertNull(KioskPrefs.parseBrightness("0"))
        assertNull(KioskPrefs.parseBrightness("101"))
    }
}
