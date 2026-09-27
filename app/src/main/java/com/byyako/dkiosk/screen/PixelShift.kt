package com.byyako.dkiosk.screen

import android.os.Handler
import android.os.Looper
import android.view.View
import kotlin.random.Random

/**
 * Burn-in protection for OLED screens showing a static page all day: every couple of minutes the page
 * moves by a few pixels, so no pixel shows the same thing forever.
 */
class PixelShift(private val target: View) {

    private val handler = Handler(Looper.getMainLooper())
    private val maxShiftPx = 4 * target.resources.displayMetrics.density

    private val shift = object : Runnable {
        override fun run() {
            target.translationX = Random.nextFloat() * 2 * maxShiftPx - maxShiftPx
            target.translationY = Random.nextFloat() * 2 * maxShiftPx - maxShiftPx
            handler.postDelayed(this, INTERVAL_MS)
        }
    }

    fun setEnabled(enabled: Boolean) {
        handler.removeCallbacks(shift)
        if (enabled) {
            handler.postDelayed(shift, INTERVAL_MS)
        } else {
            target.translationX = 0f
            target.translationY = 0f
        }
    }

    private companion object {
        const val INTERVAL_MS = 120_000L
    }
}
