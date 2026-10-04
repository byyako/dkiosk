package com.byyako.dkiosk.web

/**
 * A page shown for a while, like a doorbell camera, before the kiosk goes back to [returnUrl].
 * Someone still using the screen when the time is up keeps it until they've stopped touching it
 * for [quietMs].
 */
class TemporaryPage(val returnUrl: String?, private val returnAtMs: Long, private val quietMs: Long = QUIET_MS) {

    /** Milliseconds until it's time to go back, or 0 if it's time now. */
    fun delayUntilReturn(nowMs: Long, lastTouchMs: Long): Long {
        val due = maxOf(returnAtMs, lastTouchMs + quietMs)
        return (due - nowMs).coerceAtLeast(0)
    }

    /** Another temporary page replacing this one still returns to the page from before the first. */
    fun extendTo(returnAtMs: Long) = TemporaryPage(returnUrl, returnAtMs, quietMs)

    companion object {
        const val QUIET_MS = 15_000L
    }
}
