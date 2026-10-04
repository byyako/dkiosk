package com.byyako.dkiosk.config

import android.content.Context
import androidx.core.content.edit
import java.time.DayOfWeek
import java.time.LocalTime

/**
 * All kiosk settings, stored in one SharedPreferences file that the settings screen edits directly.
 * List-style settings are kept as strings because that's what ListPreference writes.
 */
class KioskPrefs(context: Context) {

    private val prefs = context.getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE)

    // Setup

    var homeUrl: String?
        get() = prefs.getString(HOME_URL, null)
        set(value) = prefs.edit { putString(HOME_URL, value) }

    var pinHash: String?
        get() = prefs.getString(PIN_HASH, null)
        set(value) = prefs.edit { putString(PIN_HASH, value) }

    val isSetUp: Boolean
        get() = homeUrl != null && (prefs.getBoolean(SETUP_COMPLETE, false) || pinHash != null)

    var pinRequired: Boolean
        get() = prefs.getBoolean(PIN_REQUIRED, true)
        set(value) = prefs.edit { putBoolean(PIN_REQUIRED, value) }

    var lockdownEnabled: Boolean
        get() = prefs.getBoolean(LOCKDOWN_ENABLED, false)
        set(value) = prefs.edit { putBoolean(LOCKDOWN_ENABLED, value) }

    /** Save setup together so an optional PIN doesn't send the next launch back to setup. */
    fun completeSetup(url: String, hash: String?) = prefs.edit {
        putString(HOME_URL, url)
        putString(PIN_HASH, hash)
        putBoolean(PIN_REQUIRED, hash != null)
        putBoolean(SETUP_COMPLETE, true)
    }

    // Page

    val restrictNavigation: Boolean
        get() = prefs.getBoolean(RESTRICT_NAVIGATION, true)

    /** Extra sites the kiosk may open, one per line. */
    val allowedHosts: List<String>
        get() = prefs.getString(ALLOWED_HOSTS, "").orEmpty().lines().map { it.trim() }.filter { it.isNotEmpty() }

    val allowZoom: Boolean
        get() = prefs.getBoolean(ALLOW_ZOOM, false)

    val userAgent: String?
        get() = prefs.getString(USER_AGENT, null)?.trim()?.ifEmpty { null }

    var trustedCerts: Set<String>
        get() = prefs.getStringSet(TRUSTED_CERTS, null)?.toSet().orEmpty()
        set(value) = prefs.edit { putStringSet(TRUSTED_CERTS, value) }

    // Recovery

    /** Go back to the home page after this long without a touch; 0 turns it off. */
    val idleHomeMinutes: Int
        get() = prefs.getString(IDLE_HOME_MINUTES, "5")?.toIntOrNull() ?: 5

    /** Reload the page this often; 0 turns it off. */
    val reloadMinutes: Int
        get() = prefs.getString(RELOAD_MINUTES, "0")?.toIntOrNull() ?: 0

    // Screen

    val keepScreenOn: Boolean
        get() = prefs.getBoolean(KEEP_SCREEN_ON, true)

    val burnInShift: Boolean
        get() = prefs.getBoolean(BURN_IN_SHIFT, false)

    val scheduleEnabled: Boolean
        get() = prefs.getBoolean(SCHEDULE_ENABLED, false)

    val screenOffAt: LocalTime
        get() = parseTime(prefs.getString(SCHEDULE_OFF, null)) ?: DEFAULT_OFF

    val screenOnAt: LocalTime
        get() = parseTime(prefs.getString(SCHEDULE_ON, null)) ?: DEFAULT_ON

    /** Days on which the off period starts. */
    val scheduleDays: Set<DayOfWeek>
        get() {
            val stored = prefs.getStringSet(SCHEDULE_DAYS, null) ?: return DayOfWeek.entries.toSet()
            return stored.mapNotNull { it.toIntOrNull()?.takeIf { day -> day in 1..7 }?.let(DayOfWeek::of) }.toSet()
        }

    /** Window brightness from 1 to 100 percent, or null to follow Android's brightness setting. */
    var brightness: Int?
        get() = parseBrightness(prefs.getString(BRIGHTNESS, null))
        set(value) = prefs.edit { putString(BRIGHTNESS, value?.toString() ?: BRIGHTNESS_AUTO) }

    /** How long a touch wakes the screen during an off period. */
    val wakeMinutes: Int
        get() = prefs.getString(WAKE_MINUTES, "5")?.toIntOrNull() ?: 5

    // Remote control

    val apiEnabled: Boolean
        get() = prefs.getBoolean(API_ENABLED, false)

    val apiPort: Int
        get() = prefs.getString(API_PORT, null)?.toIntOrNull()?.takeIf { it in 1024..65535 } ?: DEFAULT_API_PORT

    val apiScreenshots: Boolean
        get() = prefs.getBoolean(API_SCREENSHOTS, false)

    var apiToken: String?
        get() = prefs.getString(API_TOKEN, null)
        set(value) = prefs.edit { putString(API_TOKEN, value) }

    companion object {
        const val FILE_NAME = "kiosk"

        const val HOME_URL = "home_url"
        const val PIN_HASH = "pin_hash"
        const val PIN_REQUIRED = "pin_required"
        const val SETUP_COMPLETE = "setup_complete"
        const val LOCKDOWN_ENABLED = "lockdown_enabled"
        const val RESTRICT_NAVIGATION = "restrict_navigation"
        const val ALLOWED_HOSTS = "allowed_hosts"
        const val ALLOW_ZOOM = "allow_zoom"
        const val USER_AGENT = "user_agent"
        const val TRUSTED_CERTS = "trusted_certs"
        const val IDLE_HOME_MINUTES = "idle_home_minutes"
        const val RELOAD_MINUTES = "reload_minutes"
        const val KEEP_SCREEN_ON = "keep_screen_on"
        const val BURN_IN_SHIFT = "burn_in_shift"
        const val SCHEDULE_ENABLED = "schedule_enabled"
        const val SCHEDULE_OFF = "schedule_off"
        const val SCHEDULE_ON = "schedule_on"
        const val SCHEDULE_DAYS = "schedule_days"
        const val WAKE_MINUTES = "wake_minutes"
        const val BRIGHTNESS = "screen_brightness"
        const val BRIGHTNESS_AUTO = "auto"
        const val API_ENABLED = "api_enabled"
        const val API_PORT = "api_port"
        const val API_TOKEN = "api_token"
        const val API_SCREENSHOTS = "api_screenshots"

        const val DEFAULT_API_PORT = 8765
        val DEFAULT_OFF: LocalTime = LocalTime.of(22, 0)
        val DEFAULT_ON: LocalTime = LocalTime.of(7, 0)

        fun parseBrightness(value: String?): Int? = value?.toIntOrNull()?.takeIf { it in 1..100 }

        fun parseTime(value: String?): LocalTime? {
            val parts = value?.split(":")?.mapNotNull { it.toIntOrNull() }
            if (parts?.size != 2) return null
            val (hour, minute) = parts
            return if (hour in 0..23 && minute in 0..59) LocalTime.of(hour, minute) else null
        }
    }
}
