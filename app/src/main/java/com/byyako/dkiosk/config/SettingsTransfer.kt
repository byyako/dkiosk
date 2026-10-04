package com.byyako.dkiosk.config

import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

/**
 * Settings as JSON, to copy a kiosk's setup to another device by file or QR code. Secrets (the PIN,
 * API token, MQTT password) and what belongs to one device (its MQTT identity and name, lockdown)
 * are never exported, and only known settings of the right type are imported.
 */
object SettingsTransfer {

    private const val FORMAT = "dkiosk-settings"
    private const val VERSION = 1

    private val STRINGS = setOf(
        KioskPrefs.HOME_URL, KioskPrefs.ALLOWED_HOSTS, KioskPrefs.USER_AGENT,
        KioskPrefs.NIGHT_PAGE_URL, KioskPrefs.NIGHT_PAGE_FROM, KioskPrefs.NIGHT_PAGE_UNTIL,
        KioskPrefs.IDLE_HOME_MINUTES, KioskPrefs.RELOAD_MINUTES,
        KioskPrefs.SCHEDULE_OFF, KioskPrefs.SCHEDULE_ON, KioskPrefs.WAKE_MINUTES, KioskPrefs.BRIGHTNESS,
        KioskPrefs.SCREENSAVER_MINUTES, KioskPrefs.SCREENSAVER_MODE, KioskPrefs.SCREENSAVER_URL,
        KioskPrefs.SCREEN_OFF_MINUTES, KioskPrefs.MOTION_SENSITIVITY,
        KioskPrefs.API_PORT, KioskPrefs.MQTT_HOST, KioskPrefs.MQTT_PORT, KioskPrefs.MQTT_USERNAME,
    )
    private val BOOLEANS = setOf(
        KioskPrefs.RESTRICT_NAVIGATION, KioskPrefs.ALLOW_ZOOM, KioskPrefs.ALLOW_AUTOPLAY, KioskPrefs.NIGHT_PAGE_ENABLED,
        KioskPrefs.KEEP_SCREEN_ON, KioskPrefs.BURN_IN_SHIFT, KioskPrefs.SCHEDULE_ENABLED,
        KioskPrefs.WAKE_PROXIMITY, KioskPrefs.WAKE_MOTION,
        KioskPrefs.API_ENABLED, KioskPrefs.API_SCREENSHOTS, KioskPrefs.MQTT_ENABLED, KioskPrefs.MQTT_TLS,
    )
    private val STRING_SETS = setOf(KioskPrefs.SCHEDULE_DAYS, KioskPrefs.TRUSTED_CERTS, KioskPrefs.SITE_PERMISSIONS)

    /** Every setting that can be copied; an import resets the ones it doesn't mention. */
    val KEYS: Set<String> = STRINGS + BOOLEANS + STRING_SETS

    class ImportException(message: String) : Exception(message)

    /** [stored] is everything in the settings file; only exportable settings are written. */
    fun export(stored: Map<String, *>): String {
        val settings = JSONObject()
        for ((key, value) in stored.toSortedMap()) {
            when {
                key in STRINGS && value is String -> settings.put(key, value)
                key in BOOLEANS && value is Boolean -> settings.put(key, value)
                key in STRING_SETS && value is Set<*> -> settings.put(key, JSONArray(value.filterIsInstance<String>().sorted()))
            }
        }
        return JSONObject().put("format", FORMAT).put("version", VERSION).put("settings", settings).toString()
    }

    /** The settings to apply, checked against the known keys and types. */
    fun import(text: String): Map<String, Any> {
        val root = try {
            JSONObject(text.trim())
        } catch (_: JSONException) {
            throw ImportException("This isn't a dKiosk settings file")
        }
        if (root.optString("format") != FORMAT) throw ImportException("This isn't a dKiosk settings file")
        if (root.optInt("version", 0) > VERSION) throw ImportException("These settings are from a newer dKiosk; update this one first")
        val settings = root.optJSONObject("settings") ?: throw ImportException("The file has no settings in it")

        val result = mutableMapOf<String, Any>()
        for (key in settings.keys()) {
            val value = settings.get(key)
            when {
                key in STRINGS && value is String -> result[key] = value
                key in BOOLEANS && value is Boolean -> result[key] = value
                key in STRING_SETS && value is JSONArray -> {
                    val items = (0 until value.length()).map { value.opt(it) }
                    if (items.all { it is String }) result[key] = items.map { it as String }.toSet()
                }
                // Unknown keys (from a newer version, say) and wrong types are skipped.
            }
        }
        if (KioskPrefs.HOME_URL !in result) throw ImportException("The settings have no home page")
        return result
    }
}
