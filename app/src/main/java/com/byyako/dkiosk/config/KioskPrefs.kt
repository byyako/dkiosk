package com.byyako.dkiosk.config

import android.content.Context
import android.os.Build
import androidx.core.content.edit
import com.byyako.dkiosk.screen.ScreenSchedule
import java.time.DayOfWeek
import java.time.LocalDateTime
import java.time.LocalTime
import java.util.UUID

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

    /** Everything stored, for exporting. */
    val all: Map<String, *>
        get() = prefs.all

    /**
     * Replaces the copyable settings with [values] from an import. Settings the import doesn't
     * mention go back to their defaults, so the result matches the kiosk it came from.
     */
    fun applyImport(values: Map<String, Any>) = prefs.edit {
        (SettingsTransfer.KEYS - values.keys).forEach { remove(it) }
        for ((key, value) in values) {
            when (value) {
                is String -> putString(key, value)
                is Boolean -> putBoolean(key, value)
                is Set<*> -> putStringSet(key, value.filterIsInstance<String>().toSet())
            }
        }
    }

    /** Save setup together so an optional PIN doesn't send the next launch back to setup. */
    fun completeSetup(url: String, hash: String?) = prefs.edit {
        putString(HOME_URL, url)
        putString(PIN_HASH, hash)
        putBoolean(PIN_REQUIRED, hash != null)
        putBoolean(SETUP_COMPLETE, true)
    }

    // Page

    val nightPageEnabled: Boolean
        get() = prefs.getBoolean(NIGHT_PAGE_ENABLED, false)

    val nightPageUrl: String?
        get() = prefs.getString(NIGHT_PAGE_URL, null)?.trim()?.ifEmpty { null }

    val nightPageFrom: LocalTime
        get() = parseTime(prefs.getString(NIGHT_PAGE_FROM, null)) ?: DEFAULT_OFF

    val nightPageUntil: LocalTime
        get() = parseTime(prefs.getString(NIGHT_PAGE_UNTIL, null)) ?: DEFAULT_ON

    /** The page to show right now: the night page during its hours, otherwise the home page. */
    val activeHomeUrl: String?
        get() = activeHome(
            homeUrl, nightPageUrl.takeIf { nightPageEnabled }, nightPageFrom, nightPageUntil, LocalDateTime.now(),
        )

    val restrictNavigation: Boolean
        get() = prefs.getBoolean(RESTRICT_NAVIGATION, true)

    /** Extra sites the kiosk may open, one per line. */
    val allowedHosts: List<String>
        get() = prefs.getString(ALLOWED_HOSTS, "").orEmpty().lines().map { it.trim() }.filter { it.isNotEmpty() }

    val allowZoom: Boolean
        get() = prefs.getBoolean(ALLOW_ZOOM, false)

    val userAgent: String?
        get() = prefs.getString(USER_AGENT, null)?.trim()?.ifEmpty { null }

    /** Pages may start video and audio with sound without a tap. */
    val allowAutoplay: Boolean
        get() = prefs.getBoolean(ALLOW_AUTOPLAY, false)

    /** Remembered camera and microphone answers, see SitePermissions. */
    var sitePermissions: Set<String>
        get() = prefs.getStringSet(SITE_PERMISSIONS, null)?.toSet().orEmpty()
        set(value) = prefs.edit { putStringSet(SITE_PERMISSIONS, value) }

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

    /** Start the screensaver after this long without a touch or motion; 0 turns it off. */
    val screensaverMinutes: Int
        get() = prefs.getString(SCREENSAVER_MINUTES, "0")?.toIntOrNull() ?: 0

    /** "clock", "dim" or "page". */
    val screensaverMode: String
        get() = prefs.getString(SCREENSAVER_MODE, "clock") ?: "clock"

    val screensaverUrl: String?
        get() = prefs.getString(SCREENSAVER_URL, null)?.trim()?.ifEmpty { null }

    /** Turn the screen off after this long without a touch or motion; 0 turns it off. */
    val screenOffMinutes: Int
        get() = prefs.getString(SCREEN_OFF_MINUTES, "0")?.toIntOrNull() ?: 0

    val wakeOnProximity: Boolean
        get() = prefs.getBoolean(WAKE_PROXIMITY, false)

    val wakeOnMotion: Boolean
        get() = prefs.getBoolean(WAKE_MOTION, false)

    /** "low", "medium" or "high". */
    val motionSensitivity: String
        get() = prefs.getString(MOTION_SENSITIVITY, "medium") ?: "medium"

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

    // Home Assistant (MQTT)

    val mqttEnabled: Boolean
        get() = prefs.getBoolean(MQTT_ENABLED, false)

    /** The broker's host name or address, without a scheme or port. */
    val mqttHost: String?
        get() = cleanMqttHost(prefs.getString(MQTT_HOST, null))

    val mqttTls: Boolean
        get() = prefs.getBoolean(MQTT_TLS, false)

    val mqttPort: Int
        get() = prefs.getString(MQTT_PORT, null)?.toIntOrNull()?.takeIf { it in 1..65535 }
            ?: if (mqttTls) 8883 else 1883

    val mqttUsername: String?
        get() = prefs.getString(MQTT_USERNAME, null)?.trim()?.ifEmpty { null }

    val mqttPassword: String?
        get() = prefs.getString(MQTT_PASSWORD, null)?.ifEmpty { null }

    /** What Home Assistant calls this device. */
    val mqttName: String
        get() = prefs.getString(MQTT_NAME, null)?.trim()?.ifEmpty { null } ?: "dKiosk ${Build.MODEL}"

    /** Random and stable, so renaming the device keeps its entities and history. */
    val mqttDeviceId: String
        get() = prefs.getString(MQTT_DEVICE_ID, null) ?: UUID.randomUUID().toString()
            .replace("-", "").take(12).also { id -> prefs.edit { putString(MQTT_DEVICE_ID, id) } }

    companion object {
        const val FILE_NAME = "kiosk"

        const val HOME_URL = "home_url"
        const val PIN_HASH = "pin_hash"
        const val PIN_REQUIRED = "pin_required"
        const val SETUP_COMPLETE = "setup_complete"
        const val LOCKDOWN_ENABLED = "lockdown_enabled"
        const val RESTRICT_NAVIGATION = "restrict_navigation"
        const val NIGHT_PAGE_ENABLED = "night_page_enabled"
        const val NIGHT_PAGE_URL = "night_page_url"
        const val NIGHT_PAGE_FROM = "night_page_from"
        const val NIGHT_PAGE_UNTIL = "night_page_until"
        const val ALLOWED_HOSTS = "allowed_hosts"
        const val ALLOW_ZOOM = "allow_zoom"
        const val USER_AGENT = "user_agent"
        const val TRUSTED_CERTS = "trusted_certs"
        const val ALLOW_AUTOPLAY = "allow_autoplay"
        const val SITE_PERMISSIONS = "site_permissions"
        const val IDLE_HOME_MINUTES = "idle_home_minutes"
        const val RELOAD_MINUTES = "reload_minutes"
        const val KEEP_SCREEN_ON = "keep_screen_on"
        const val BURN_IN_SHIFT = "burn_in_shift"
        const val SCHEDULE_ENABLED = "schedule_enabled"
        const val SCHEDULE_OFF = "schedule_off"
        const val SCHEDULE_ON = "schedule_on"
        const val SCHEDULE_DAYS = "schedule_days"
        const val WAKE_MINUTES = "wake_minutes"
        const val SCREENSAVER_MINUTES = "screensaver_minutes"
        const val SCREENSAVER_MODE = "screensaver_mode"
        const val SCREENSAVER_URL = "screensaver_url"
        const val SCREEN_OFF_MINUTES = "screen_off_minutes"
        const val WAKE_PROXIMITY = "wake_proximity"
        const val WAKE_MOTION = "wake_motion"
        const val MOTION_SENSITIVITY = "motion_sensitivity"
        const val BRIGHTNESS = "screen_brightness"
        const val BRIGHTNESS_AUTO = "auto"
        const val API_ENABLED = "api_enabled"
        const val API_PORT = "api_port"
        const val API_TOKEN = "api_token"
        const val API_SCREENSHOTS = "api_screenshots"
        const val MQTT_ENABLED = "mqtt_enabled"
        const val MQTT_HOST = "mqtt_host"
        const val MQTT_PORT = "mqtt_port"
        const val MQTT_TLS = "mqtt_tls"
        const val MQTT_USERNAME = "mqtt_username"
        const val MQTT_PASSWORD = "mqtt_password"
        const val MQTT_NAME = "mqtt_name"
        const val MQTT_DEVICE_ID = "mqtt_device_id"

        const val DEFAULT_API_PORT = 8765
        val DEFAULT_OFF: LocalTime = LocalTime.of(22, 0)
        val DEFAULT_ON: LocalTime = LocalTime.of(7, 0)

        /** [night] between [from] and [until] (which may cross midnight), otherwise [home]. */
        fun activeHome(home: String?, night: String?, from: LocalTime, until: LocalTime, now: LocalDateTime): String? {
            if (night == null) return home
            val inNight = ScreenSchedule(from, until, DayOfWeek.entries.toSet()).isOffPeriod(now)
            return if (inNight) night else home
        }

        /** "mqtt://broker.local:1883/" becomes "broker.local"; the port has its own setting. */
        fun cleanMqttHost(value: String?): String? {
            val host = value?.trim()?.substringAfter("://")?.substringBefore('/')?.ifEmpty { null } ?: return null
            // An IPv6 address keeps its colons; otherwise anything after a colon is a port.
            val withoutPort = if (host.startsWith("[")) host.substringBefore(']').removePrefix("[") else host.substringBefore(':')
            return withoutPort.ifEmpty { null }
        }

        fun parseBrightness(value: String?): Int? = value?.toIntOrNull()?.takeIf { it in 1..100 }

        fun parseTime(value: String?): LocalTime? {
            val parts = value?.split(":")?.mapNotNull { it.toIntOrNull() }
            if (parts?.size != 2) return null
            val (hour, minute) = parts
            return if (hour in 0..23 && minute in 0..59) LocalTime.of(hour, minute) else null
        }
    }
}
