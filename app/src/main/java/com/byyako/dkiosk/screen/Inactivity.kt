package com.byyako.dkiosk.screen

/**
 * What the screen does when nobody is using it: the screensaver after [screensaverMs], then off after
 * [offMs], both counted from the last touch or motion. 0 turns either off.
 */
class Inactivity(private val screensaverMs: Long, private val offMs: Long) {

    enum class Mode { ACTIVE, SCREENSAVER, OFF }

    fun mode(nowMs: Long, lastActivityMs: Long): Mode {
        val idle = nowMs - lastActivityMs
        return when {
            offMs > 0 && idle >= offMs -> Mode.OFF
            screensaverMs > 0 && idle >= screensaverMs -> Mode.SCREENSAVER
            else -> Mode.ACTIVE
        }
    }

    /** Milliseconds until the mode next changes without any activity, or null if it never will. */
    fun nextChangeIn(nowMs: Long, lastActivityMs: Long): Long? {
        val idle = nowMs - lastActivityMs
        return listOf(screensaverMs, offMs).filter { it > 0 && it > idle }.minOrNull()?.minus(idle)
    }
}
