package com.byyako.dkiosk.screen

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.View
import android.view.Window
import android.view.WindowManager
import androidx.core.view.isVisible
import com.byyako.dkiosk.config.KioskPrefs
import java.time.LocalDateTime

/**
 * Turns the screen "off" by covering the page with black and dropping the brightness. The page keeps
 * running underneath, and on an OLED screen black pixels are actually off.
 */
class ScreenDimmer(private val blackout: View, private val window: Window, private val prefs: KioskPrefs) {

    private val handler = Handler(Looper.getMainLooper())
    private val state = ScreenState()
    private val update = Runnable { update() }
    private val tick = object : Runnable {
        override fun run() {
            update()
            handler.postDelayed(this, 30_000)
        }
    }

    val isDark: Boolean
        get() = blackout.isVisible

    fun start() {
        handler.removeCallbacks(tick)
        tick.run()
    }

    fun stop() {
        handler.removeCallbacks(tick)
        handler.removeCallbacks(update)
    }

    fun onTouch() = wakeFor(prefs.wakeMinutes * 60_000L)

    /** Lights a dark screen for [ms], e.g. after a touch or for a message sent over the API. */
    fun wakeFor(ms: Long) {
        state.wake(SystemClock.elapsedRealtime(), ms)
        update()
        // Not replacing earlier checks: a short wake mustn't cancel the check for a longer one.
        handler.postDelayed(update, ms)
    }

    /** Remote on/off. It holds until the schedule next switches. */
    fun turn(on: Boolean) {
        state.force(on, scheduleSaysOff())
        update()
    }

    /** Applies a changed brightness setting. */
    fun refresh() = update()

    private fun scheduleSaysOff(): Boolean {
        if (!prefs.scheduleEnabled) return false
        return ScreenSchedule(prefs.screenOffAt, prefs.screenOnAt, prefs.scheduleDays).isOffPeriod(LocalDateTime.now())
    }

    private fun update() {
        val dark = state.isDark(SystemClock.elapsedRealtime(), scheduleSaysOff())
        blackout.isVisible = dark
        val brightness = when {
            dark -> 0f
            else -> prefs.brightness?.let { it / 100f } ?: WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
        }
        if (window.attributes.screenBrightness != brightness) {
            window.attributes = window.attributes.apply { screenBrightness = brightness }
        }
    }
}
