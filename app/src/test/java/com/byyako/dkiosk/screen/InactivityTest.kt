package com.byyako.dkiosk.screen

import com.byyako.dkiosk.screen.Inactivity.Mode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class InactivityTest {

    private val minute = 60_000L

    @Test
    fun screensaverThenOff() {
        val inactivity = Inactivity(screensaverMs = 2 * minute, offMs = 10 * minute)
        assertEquals(Mode.ACTIVE, inactivity.mode(nowMs = minute, lastActivityMs = 0))
        assertEquals(Mode.SCREENSAVER, inactivity.mode(nowMs = 2 * minute, lastActivityMs = 0))
        assertEquals(Mode.OFF, inactivity.mode(nowMs = 10 * minute, lastActivityMs = 0))
        assertEquals(minute, inactivity.nextChangeIn(nowMs = minute, lastActivityMs = 0))
        assertEquals(8 * minute, inactivity.nextChangeIn(nowMs = 2 * minute, lastActivityMs = 0))
        assertNull(inactivity.nextChangeIn(nowMs = 10 * minute, lastActivityMs = 0))
    }

    @Test
    fun eitherCanBeOff() {
        val screensaverOnly = Inactivity(screensaverMs = minute, offMs = 0)
        assertEquals(Mode.SCREENSAVER, screensaverOnly.mode(nowMs = 100 * minute, lastActivityMs = 0))
        val offOnly = Inactivity(screensaverMs = 0, offMs = minute)
        assertEquals(Mode.ACTIVE, offOnly.mode(nowMs = minute - 1, lastActivityMs = 0))
        assertEquals(Mode.OFF, offOnly.mode(nowMs = minute, lastActivityMs = 0))
        assertNull(Inactivity(0, 0).nextChangeIn(nowMs = 5, lastActivityMs = 0))
    }

    @Test
    fun offBeforeScreensaverSkipsIt() {
        val inactivity = Inactivity(screensaverMs = 10 * minute, offMs = 5 * minute)
        assertEquals(Mode.OFF, inactivity.mode(nowMs = 5 * minute, lastActivityMs = 0))
    }
}
