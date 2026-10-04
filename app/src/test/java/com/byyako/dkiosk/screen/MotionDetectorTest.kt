package com.byyako.dkiosk.screen

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MotionDetectorTest {

    private val width = 160
    private val height = 120
    private val stride = 176 // Rows are often padded.

    private fun frame(base: Int, block: Pair<IntRange, IntRange>? = null, blockValue: Int = 0): ByteArray {
        val luma = ByteArray(stride * height)
        for (y in 0 until height) {
            for (x in 0 until width) {
                val inBlock = block != null && x in block.first && y in block.second
                luma[y * stride + x] = (if (inBlock) blockValue else base + (x + y) % 7).toByte()
            }
        }
        return luma
    }

    @Test
    fun firstFrameAndStillScenesAreNotMotion() {
        val detector = MotionDetector()
        assertFalse(detector.onFrame(frame(100), width, height, stride))
        assertFalse(detector.onFrame(frame(100), width, height, stride))
    }

    @Test
    fun overallBrightnessChangeIsNotMotion() {
        val detector = MotionDetector(MotionDetector.Sensitivity.HIGH)
        detector.onFrame(frame(60), width, height, stride)
        assertFalse(detector.onFrame(frame(140), width, height, stride))
    }

    @Test
    fun somethingMovingIsMotion() {
        val detector = MotionDetector()
        detector.onFrame(frame(100), width, height, stride)
        // A dark shape covering about a tenth of the picture.
        assertTrue(detector.onFrame(frame(100, 40..90 to 30..70, blockValue = 20), width, height, stride))
    }

    @Test
    fun lowSensitivityIgnoresSmallChanges() {
        val small = 0..14 to 0..14 // One cell or so.
        val low = MotionDetector(MotionDetector.Sensitivity.LOW)
        low.onFrame(frame(100), width, height, stride)
        assertFalse(low.onFrame(frame(100, small, blockValue = 0), width, height, stride))
    }

    @Test
    fun resetForgetsTheLastFrame() {
        val detector = MotionDetector()
        detector.onFrame(frame(100), width, height, stride)
        detector.reset()
        assertFalse(detector.onFrame(frame(100, 40..90 to 30..70, blockValue = 20), width, height, stride))
    }
}
