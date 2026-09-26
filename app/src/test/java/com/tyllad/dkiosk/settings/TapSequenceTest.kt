package com.tyllad.dkiosk.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TapSequenceTest {

    @Test
    fun fiveQuickTapsTrigger() {
        val sequence = TapSequence(taps = 5, windowMs = 3_000)
        val results = listOf(0L, 300L, 600L, 900L, 1_200L).map(sequence::onTap)
        assertEquals(listOf(false, false, false, false, true), results)
    }

    @Test
    fun slowTapsDoNot() {
        val sequence = TapSequence(taps = 5, windowMs = 3_000)
        val results = listOf(0L, 1_000L, 2_000L, 3_000L, 4_000L).map(sequence::onTap)
        assertFalse(results.any { it })
    }

    @Test
    fun oldTapsFallOutOfTheWindow() {
        val sequence = TapSequence(taps = 3, windowMs = 1_000)
        sequence.onTap(0)
        sequence.onTap(100)
        // The first two are too old by now; three fresh taps are needed.
        assertFalse(sequence.onTap(2_000))
        assertFalse(sequence.onTap(2_100))
        assertTrue(sequence.onTap(2_200))
    }

    @Test
    fun startsOverAfterTriggering() {
        val sequence = TapSequence(taps = 2, windowMs = 1_000)
        sequence.onTap(0)
        assertTrue(sequence.onTap(100))
        assertFalse(sequence.onTap(200))
        assertTrue(sequence.onTap(300))
    }
}
