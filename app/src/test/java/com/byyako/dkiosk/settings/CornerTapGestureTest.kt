package com.byyako.dkiosk.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CornerTapGestureTest {
    private val gesture = CornerTapGesture(touchSlop = 8f)

    private fun tap(at: Long): CornerTapGesture.Result {
        gesture.down(10f, 10f, at)
        return gesture.up(10f, 10f, at + 100)
    }

    @Test fun completedTapsAllowFiveSeconds() {
        for (time in listOf(0L, 1000L, 2000L, 3000L)) assertFalse(tap(time).complete)
        assertTrue(tap(5000).complete)
        assertEquals(1, tap(6000).progress)
    }

    @Test fun oldTapsExpire() {
        tap(0)
        assertEquals(1, tap(5100).progress)
    }

    @Test fun longPressBreaksSequence() {
        tap(0)
        gesture.down(10f, 10f, 500)
        assertEquals(0, gesture.up(10f, 10f, 1001).progress)
        assertEquals(1, tap(1100).progress)
    }

    @Test fun draggingAwayAndBackBreaksSequence() {
        tap(0)
        gesture.down(10f, 10f, 500)
        gesture.move(30f, 10f)
        gesture.move(10f, 10f)
        assertEquals(0, gesture.up(10f, 10f, 600).progress)
        assertEquals(1, tap(700).progress)
    }

    @Test fun cancellationMultitouchOrOutsideTouchResets() {
        tap(0)
        gesture.down(10f, 10f, 500)
        gesture.reset()
        assertEquals(0, gesture.up(10f, 10f, 600).progress)
        assertEquals(1, tap(700).progress)
    }

    @Test fun upWithoutDownIsNotATap() {
        assertEquals(CornerTapGesture.Result(0, false), gesture.up(10f, 10f, 100))
    }

    @Test fun smallFingerMovementIsAllowed() {
        gesture.down(10f, 10f, 0)
        assertEquals(1, gesture.up(14f, 14f, 100).progress)
    }
}
