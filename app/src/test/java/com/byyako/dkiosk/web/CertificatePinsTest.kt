package com.byyako.dkiosk.web

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CertificatePinsTest {

    private var stored = emptySet<String>()
    private val pins = CertificatePins(load = { stored }, save = { stored = it })

    @Test
    fun fingerprintIsColonSeparatedUppercaseSha256() {
        assertEquals(
            "BA:78:16:BF:8F:01:CF:EA:41:41:40:DE:5D:AE:22:23:B0:03:61:A3:96:17:7A:9C:B4:10:FF:61:F2:00:15:AD",
            sha256Fingerprint("abc".toByteArray()),
        )
    }

    @Test
    fun nothingIsPinnedInitially() {
        assertFalse(pins.isPinned("nas.lan", "AA:BB"))
        assertFalse(pins.hasPinFor("nas.lan"))
    }

    @Test
    fun pinsExactHostAndFingerprint() {
        pins.pin("NAS.lan", "aa:bb")
        assertTrue(pins.isPinned("nas.lan", "AA:BB"))
        assertFalse(pins.isPinned("nas.lan", "AA:CC"))
        assertFalse(pins.isPinned("other.lan", "AA:BB"))
        assertTrue(pins.hasPinFor("nas.lan"))
    }

    @Test
    fun newPinReplacesTheOldOneForThatHostOnly() {
        pins.pin("nas.lan", "AA:BB")
        pins.pin("router.lan", "11:22")
        pins.pin("nas.lan", "CC:DD")
        assertFalse(pins.isPinned("nas.lan", "AA:BB"))
        assertTrue(pins.isPinned("nas.lan", "CC:DD"))
        assertTrue(pins.isPinned("router.lan", "11:22"))
        assertEquals(2, stored.size)
    }

    @Test
    fun hostPrefixDoesNotLeakAcrossSimilarHosts() {
        pins.pin("nas.lan", "AA:BB")
        assertFalse(pins.hasPinFor("nas.la"))
        assertFalse(pins.hasPinFor("as.lan"))
    }
}
