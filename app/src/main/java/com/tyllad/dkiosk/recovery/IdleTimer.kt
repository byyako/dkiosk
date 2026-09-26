package com.tyllad.dkiosk.recovery

import android.os.Handler
import android.os.Looper
import android.os.SystemClock

/** Calls [onIdle] once nobody has touched the screen for [timeoutMs]. A timeout of 0 disables it. */
class IdleTimer(private val onIdle: () -> Unit) {

    private val handler = Handler(Looper.getMainLooper())
    private val fire = Runnable { onIdle() }

    var timeoutMs = 0L
        set(value) {
            field = value
            restart()
        }

    var lastTouchAt = SystemClock.uptimeMillis()
        private set

    fun onTouch() {
        lastTouchAt = SystemClock.uptimeMillis()
        restart()
    }

    fun stop() {
        handler.removeCallbacks(fire)
    }

    private fun restart() {
        handler.removeCallbacks(fire)
        if (timeoutMs > 0) handler.postDelayed(fire, timeoutMs)
    }
}
