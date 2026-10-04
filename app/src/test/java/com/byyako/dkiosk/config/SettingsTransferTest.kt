package com.byyako.dkiosk.config

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsTransferTest {

    private val stored = mapOf(
        KioskPrefs.HOME_URL to "https://ha.example.com/lovelace",
        KioskPrefs.ALLOWED_HOSTS to "sso.example.com",
        KioskPrefs.KEEP_SCREEN_ON to true,
        KioskPrefs.SCHEDULE_DAYS to setOf("1", "2"),
        KioskPrefs.MQTT_HOST to "broker.local",
        // Never exported:
        KioskPrefs.PIN_HASH to "pbkdf2:secret",
        KioskPrefs.API_TOKEN to "token",
        KioskPrefs.MQTT_PASSWORD to "password",
        KioskPrefs.MQTT_DEVICE_ID to "abc123",
        KioskPrefs.MQTT_NAME to "Hall kiosk",
        KioskPrefs.LOCKDOWN_ENABLED to true,
    )

    @Test
    fun roundTripWithoutSecrets() {
        val exported = SettingsTransfer.export(stored)
        for (secret in listOf("pbkdf2", "token", "password", "abc123", "Hall kiosk", KioskPrefs.LOCKDOWN_ENABLED)) {
            assertFalse("$secret leaked", exported.contains(secret))
        }
        val imported = SettingsTransfer.import(exported)
        assertEquals("https://ha.example.com/lovelace", imported[KioskPrefs.HOME_URL])
        assertEquals(true, imported[KioskPrefs.KEEP_SCREEN_ON])
        assertEquals(setOf("1", "2"), imported[KioskPrefs.SCHEDULE_DAYS])
        assertEquals(5, imported.size)
    }

    @Test
    fun importSkipsUnknownKeysAndWrongTypes() {
        val text = JSONObject()
            .put("format", "dkiosk-settings").put("version", 1)
            .put(
                "settings",
                JSONObject()
                    .put(KioskPrefs.HOME_URL, "https://a.com")
                    .put(KioskPrefs.PIN_HASH, "pbkdf2:sneaky")
                    .put(KioskPrefs.KEEP_SCREEN_ON, "yes")
                    .put("future_setting", true),
            )
            .toString()
        assertEquals(mapOf(KioskPrefs.HOME_URL to "https://a.com"), SettingsTransfer.import(text))
    }

    @Test
    fun importRejectsOtherFiles() {
        for (text in listOf("", "hello", "{}", """{"format": "dkiosk-settings", "version": 1, "settings": {}}""")) {
            val failed = runCatching { SettingsTransfer.import(text) }.exceptionOrNull()
            assertTrue("accepted: $text", failed is SettingsTransfer.ImportException)
        }
        val newer = """{"format": "dkiosk-settings", "version": 99, "settings": {"home_url": "https://a.com"}}"""
        assertTrue(runCatching { SettingsTransfer.import(newer) }.exceptionOrNull() is SettingsTransfer.ImportException)
    }
}
