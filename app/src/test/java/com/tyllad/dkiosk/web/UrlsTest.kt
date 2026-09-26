package com.tyllad.dkiosk.web

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class UrlsTest {

    @Test
    fun keepsFullUrls() {
        assertEquals("https://example.com/dash?x=1", normalizeHomeUrl("https://example.com/dash?x=1"))
        assertEquals("http://192.168.1.5:8123", normalizeHomeUrl("http://192.168.1.5:8123"))
    }

    @Test
    fun trimsWhitespace() {
        assertEquals("https://example.com", normalizeHomeUrl("  https://example.com \n"))
    }

    @Test
    fun defaultsToHttpsWithoutScheme() {
        assertEquals("https://homeassistant.local:8123", normalizeHomeUrl("homeassistant.local:8123"))
    }

    @Test
    fun rejectsNonWebSchemes() {
        assertNull(normalizeHomeUrl("file:///sdcard/index.html"))
        assertNull(normalizeHomeUrl("javascript://alert(1)"))
        assertNull(normalizeHomeUrl("ftp://example.com"))
    }

    @Test
    fun rejectsJunk() {
        assertNull(normalizeHomeUrl(""))
        assertNull(normalizeHomeUrl("   "))
        assertNull(normalizeHomeUrl("https://"))
        assertNull(normalizeHomeUrl("not a url"))
    }
}
