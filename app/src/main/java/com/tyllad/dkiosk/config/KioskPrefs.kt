package com.tyllad.dkiosk.config

import android.content.Context
import androidx.core.content.edit

/** Persistent kiosk settings. Nothing is hardcoded so every install is configured by its owner. */
class KioskPrefs(context: Context) {

    private val prefs = context.getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE)

    /** The page the kiosk shows; null until first-run setup completes. */
    var homeUrl: String?
        get() = prefs.getString(KEY_HOME_URL, null)
        set(value) = prefs.edit { putString(KEY_HOME_URL, value) }

    /** When on, top-level pages must be on the home URL's host or in [allowedHosts]. */
    var restrictNavigation: Boolean
        get() = prefs.getBoolean(KEY_RESTRICT_NAVIGATION, true)
        set(value) = prefs.edit { putBoolean(KEY_RESTRICT_NAVIGATION, value) }

    /** Sites allowed besides the home URL's host, e.g. an SSO login domain. Supports "*.example.com". */
    var allowedHosts: Set<String>
        get() = prefs.getStringSet(KEY_ALLOWED_HOSTS, null)?.toSet().orEmpty()
        set(value) = prefs.edit { putStringSet(KEY_ALLOWED_HOSTS, value) }

    var allowZoom: Boolean
        get() = prefs.getBoolean(KEY_ALLOW_ZOOM, false)
        set(value) = prefs.edit { putBoolean(KEY_ALLOW_ZOOM, value) }

    /** Replaces WebView's user agent when set; null keeps the default. */
    var userAgent: String?
        get() = prefs.getString(KEY_USER_AGENT, null)?.ifBlank { null }
        set(value) = prefs.edit { putString(KEY_USER_AGENT, value) }

    /** Trust-on-first-use certificate pins; see [com.tyllad.dkiosk.web.CertificatePins]. */
    var trustedCerts: Set<String>
        get() = prefs.getStringSet(KEY_TRUSTED_CERTS, null)?.toSet().orEmpty()
        set(value) = prefs.edit { putStringSet(KEY_TRUSTED_CERTS, value) }

    companion object {
        const val FILE_NAME = "kiosk"
        private const val KEY_HOME_URL = "home_url"
        private const val KEY_RESTRICT_NAVIGATION = "restrict_navigation"
        private const val KEY_ALLOWED_HOSTS = "allowed_hosts"
        private const val KEY_ALLOW_ZOOM = "allow_zoom"
        private const val KEY_USER_AGENT = "user_agent"
        private const val KEY_TRUSTED_CERTS = "trusted_certs"
    }
}
