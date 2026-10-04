package com.byyako.dkiosk.settings

import android.content.Context
import android.os.BatteryManager
import android.os.Build
import android.os.SystemClock
import android.webkit.WebView
import com.byyako.dkiosk.config.KioskPrefs
import com.byyako.dkiosk.lockdown.Lockdown
import com.byyako.dkiosk.recovery.Diagnostics
import com.byyako.dkiosk.remote.MqttStatus
import com.byyako.dkiosk.remote.localIpAddress
import com.byyako.dkiosk.web.hostOf

/** A plain-text summary for someone troubleshooting a kiosk, easy to copy into a bug report. */
object DiagnosticsReport {

    fun build(context: Context, prefs: KioskPrefs): String {
        val packageInfo = context.packageManager.getPackageInfo(context.packageName, 0)
        val webView = WebView.getCurrentWebViewPackage()
        val battery = context.getSystemService(BatteryManager::class.java)
        val uptimeMinutes = (SystemClock.elapsedRealtime() - Diagnostics.startedAt) / 60_000

        val lines = mutableListOf(
            "dKiosk ${packageInfo.versionName}",
            "Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT}), ${Build.MANUFACTURER} ${Build.MODEL}",
            "WebView ${webView?.packageName ?: "unknown"} ${webView?.versionName.orEmpty()}".trim(),
            "Running for ${uptimeMinutes / 60} h ${uptimeMinutes % 60} min",
            "",
            "Home site: ${prefs.homeUrl?.let(::hostOf) ?: "not set"}",
            "Network address: ${localIpAddress(context) ?: "offline"}",
            "Battery: ${battery.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)}%" +
                if (battery.isCharging) ", charging" else "",
            "HTTP API: ${if (prefs.apiEnabled) "on, port ${prefs.apiPort}" else "off"}",
            "Home Assistant: ${if (prefs.mqttEnabled) MqttStatus.text ?: "not connected yet" else "off"}",
            "Managed lockdown: ${lockdownState(context, prefs)}",
            "",
            "Since start: load errors ${Diagnostics.loadErrors}, renderer restarts ${Diagnostics.rendererLosses}, " +
                "frozen pages ${Diagnostics.freezes}",
        )
        val events = Diagnostics.recent()
        lines += ""
        lines += if (events.isEmpty()) "No problems recorded." else "Recent events:"
        lines += events
        return lines.joinToString("\n")
    }

    private fun lockdownState(context: Context, prefs: KioskPrefs): String {
        val lockdown = Lockdown(context)
        return when {
            !prefs.lockdownEnabled -> "off"
            lockdown.isLocked -> "on, locked"
            lockdown.isAvailable -> "on, not locked right now"
            else -> "on, but not allowed by device management"
        }
    }
}
