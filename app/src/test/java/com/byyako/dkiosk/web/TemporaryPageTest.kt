package com.byyako.dkiosk.web

import org.junit.Assert.assertEquals
import org.junit.Test

class TemporaryPageTest {

    @Test
    fun returnsWhenTheTimeIsUp() {
        val page = TemporaryPage("https://home", returnAtMs = 30_000, quietMs = 15_000)
        assertEquals(30_000, page.delayUntilReturn(nowMs = 0, lastTouchMs = -100_000))
        assertEquals(0, page.delayUntilReturn(nowMs = 30_000, lastTouchMs = -100_000))
        assertEquals(0, page.delayUntilReturn(nowMs = 40_000, lastTouchMs = -100_000))
    }

    @Test
    fun waitsWhileSomeoneIsTouchingTheScreen() {
        val page = TemporaryPage("https://home", returnAtMs = 30_000, quietMs = 15_000)
        assertEquals(10_000, page.delayUntilReturn(nowMs = 30_000, lastTouchMs = 25_000))
        assertEquals(0, page.delayUntilReturn(nowMs = 40_000, lastTouchMs = 25_000))
    }

    @Test
    fun replacementKeepsTheOriginalReturnPage() {
        val page = TemporaryPage("https://home", returnAtMs = 30_000).extendTo(90_000)
        assertEquals("https://home", page.returnUrl)
        assertEquals(60_000, page.delayUntilReturn(nowMs = 30_000, lastTouchMs = -100_000))
    }
}
