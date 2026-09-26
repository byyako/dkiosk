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

    companion object {
        const val FILE_NAME = "kiosk"
        private const val KEY_HOME_URL = "home_url"
    }
}
