package com.tyllad.dkiosk.screen

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ScreenStateTest {

    private val state = ScreenState()
    private val minute = 60_000L

    @Test
    fun followsTheScheduleByDefault() {
        assertFalse(state.isDark(0, scheduleSaysOff = false))
        assertTrue(state.isDark(0, scheduleSaysOff = true))
    }

    @Test
    fun touchWakesForAWhile() {
        state.wake(nowMs = 0, forMs = 5 * minute)
        assertFalse(state.isDark(4 * minute, scheduleSaysOff = true))
        assertTrue(state.isDark(5 * minute, scheduleSaysOff = true))
    }

    @Test
    fun laterTouchesExtendButNeverShortenTheWake() {
        state.wake(nowMs = 0, forMs = 15 * minute)
        state.wake(nowMs = minute, forMs = 5 * minute)
        assertFalse(state.isDark(10 * minute, scheduleSaysOff = true))
    }

    @Test
    fun forcedOffLastsUntilTheScheduleChanges() {
        state.force(on = false, scheduleSaysOff = false)
        assertTrue(state.isDark(0, scheduleSaysOff = false))
        // The evening off period starts: still dark, and the override is gone...
        assertTrue(state.isDark(minute, scheduleSaysOff = true))
        // ...so in the morning the screen comes back on with the schedule.
        assertFalse(state.isDark(2 * minute, scheduleSaysOff = false))
    }

    @Test
    fun forcedOnLastsUntilTheScheduleChanges() {
        state.force(on = true, scheduleSaysOff = true)
        assertFalse(state.isDark(0, scheduleSaysOff = true))
        assertFalse(state.isDark(minute, scheduleSaysOff = false))
        // Next evening the schedule is back in charge.
        assertTrue(state.isDark(2 * minute, scheduleSaysOff = true))
    }

    @Test
    fun touchPeeksThroughAForcedOff() {
        state.force(on = false, scheduleSaysOff = false)
        state.wake(nowMs = 0, forMs = minute)
        assertFalse(state.isDark(30_000, scheduleSaysOff = false))
        assertTrue(state.isDark(minute, scheduleSaysOff = false))
    }

    @Test
    fun forcingClearsAnEarlierWake() {
        state.wake(nowMs = 0, forMs = 5 * minute)
        state.force(on = false, scheduleSaysOff = false)
        assertTrue(state.isDark(minute, scheduleSaysOff = false))
    }
}
