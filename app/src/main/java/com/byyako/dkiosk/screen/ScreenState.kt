package com.byyako.dkiosk.screen

/**
 * Decides whether the screen should be dark. The schedule is the baseline. A remote on/off command
 * overrides it until the schedule next changes, and a touch wakes the screen for a while either way.
 */
class ScreenState {

    private var forcedOn: Boolean? = null
    private var scheduleWhenForced = false
    private var awakeUntil = 0L

    fun isDark(nowMs: Long, scheduleSaysOff: Boolean): Boolean {
        if (forcedOn != null && scheduleSaysOff != scheduleWhenForced) forcedOn = null
        if (nowMs < awakeUntil) return false
        return forcedOn?.not() ?: scheduleSaysOff
    }

    fun wake(nowMs: Long, forMs: Long) {
        awakeUntil = maxOf(awakeUntil, nowMs + forMs)
    }

    fun force(on: Boolean, scheduleSaysOff: Boolean) {
        forcedOn = on
        scheduleWhenForced = scheduleSaysOff
        awakeUntil = 0
    }
}
