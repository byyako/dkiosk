package com.byyako.dkiosk.config

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SetupCodesTest {

    @Test
    fun roundTripThroughAPaddedFrame() {
        val settings = SettingsTransfer.export(mapOf(KioskPrefs.HOME_URL to "https://ha.example.com/lovelace/0"))
        val matrix = SetupCodes.encode(settings)!!

        // Draw it like a camera would see it: 4 pixels per module on a grey background, rows padded.
        val scale = 4
        val size = matrix.width * scale + 40
        val stride = size + 24
        val luma = ByteArray(stride * size) { 0xB0.toByte() }
        for (y in 0 until matrix.height * scale) {
            for (x in 0 until matrix.width * scale) {
                val dark = matrix.get(x / scale, y / scale)
                luma[(y + 20) * stride + x + 20] = (if (dark) 0x10 else 0xF0).toByte()
            }
        }
        assertEquals(settings, SetupCodes.decode(luma, size, size, stride))
    }

    @Test
    fun nothingToFind() {
        assertNull(SetupCodes.decode(ByteArray(64 * 48) { 0x80.toByte() }, 64, 48, 64))
    }

    @Test
    fun tooMuchForOneCode() {
        assertNull(SetupCodes.encode("x".repeat(5000)))
    }
}
