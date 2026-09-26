package com.tyllad.dkiosk.recovery

import org.junit.Assert.assertEquals
import org.junit.Test

class BackoffTest {

    @Test
    fun doublesUntilTheCap() {
        val backoff = Backoff(firstMs = 5_000, maxMs = 60_000)
        val delays = List(7) { backoff.nextDelayMs() }
        assertEquals(listOf(5_000L, 10_000L, 20_000L, 40_000L, 60_000L, 60_000L, 60_000L), delays)
    }

    @Test
    fun resetStartsOver() {
        val backoff = Backoff(firstMs = 1_000, maxMs = 10_000)
        repeat(3) { backoff.nextDelayMs() }
        backoff.reset()
        assertEquals(1_000L, backoff.nextDelayMs())
    }

    @Test
    fun manyFailuresDontOverflow() {
        val backoff = Backoff(firstMs = 5_000, maxMs = 60_000)
        repeat(1_000) { backoff.nextDelayMs() }
        assertEquals(60_000L, backoff.nextDelayMs())
    }
}
