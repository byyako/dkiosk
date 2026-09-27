package com.byyako.dkiosk.recovery

/** Retry delays that double after each failure (5s, 10s, 20s, 40s) and then stay at [maxMs]. */
class Backoff(private val firstMs: Long = 5_000, private val maxMs: Long = 60_000) {

    private var failures = 0

    fun nextDelayMs(): Long {
        val delay = (firstMs shl failures.coerceAtMost(20)).coerceAtMost(maxMs)
        failures++
        return delay
    }

    fun reset() {
        failures = 0
    }
}
