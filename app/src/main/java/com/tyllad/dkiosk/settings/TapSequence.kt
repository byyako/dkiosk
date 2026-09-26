package com.tyllad.dkiosk.settings

/** Recognizes [taps] taps that all land within [windowMs], like the five quick taps that open the settings. */
class TapSequence(private val taps: Int = 5, private val windowMs: Long = 3_000) {

    private val times = ArrayDeque<Long>()

    /** Records a tap at [timeMs] and returns true when it completes the sequence. */
    fun onTap(timeMs: Long): Boolean {
        times.addLast(timeMs)
        while (timeMs - times.first() > windowMs) times.removeFirst()
        if (times.size < taps) return false
        times.clear()
        return true
    }
}
