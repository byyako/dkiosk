package com.byyako.dkiosk.web

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
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
    fun keepsPastedSchemeAfterPrefilledOne() {
        assertEquals("http://localhost:8780/", normalizeHomeUrl("https://http://localhost:8780/"))
        assertEquals("https://example.com", normalizeHomeUrl("HTTPS://https://example.com"))
        assertNull(normalizeHomeUrl("https://file:///sdcard/index.html"))
    }

    @Test
    fun rejectsNonWebSchemes() {
        assertNull(normalizeHomeUrl("file:///sdcard/index.html"))
        assertNull(normalizeHomeUrl("javascript://alert(1)"))
        assertNull(normalizeHomeUrl("ftp://example.com"))
    }

    @Test
    fun schemeOfHandlesOpaqueAndHierarchicalUrls() {
        assertEquals("https", schemeOf("HTTPS://x.com"))
        assertEquals("tel", schemeOf("tel:123"))
        assertEquals("android-app", schemeOf("android-app://com.x/https/y"))
        assertNull(schemeOf("x.com/path"))
        assertNull(schemeOf(":nothing"))
        assertNull(schemeOf("1http://x.com"))
    }

    @Test
    fun hostOfStripsUserinfoPortAndCase() {
        assertEquals("example.com", hostOf("https://user:pw@Example.COM:8443/a?b#c"))
        assertEquals("example.com", hostOf("https://example.com"))
        assertEquals("[::1]", hostOf("http://[::1]:8080/"))
        assertEquals("a.com", hostOf("https://a.com?q=http://b.com"))
        assertNull(hostOf("tel:123"))
        assertNull(hostOf("https:///path"))
    }

    @Test
    fun samePageIgnoresTrailingSlashAndFragment() {
        assertTrue(isSamePage("https://a.com/", "https://a.com"))
        assertTrue(isSamePage("https://a.com/dash#top", "https://a.com/dash"))
        assertFalse(isSamePage("https://a.com/dash", "https://a.com/other"))
        assertFalse(isSamePage("https://a.com/?x=1", "https://a.com/?x=2"))
        assertFalse(isSamePage(null, "https://a.com"))
    }

    @Test
    fun rejectsJunk() {
        assertNull(normalizeHomeUrl(""))
        assertNull(normalizeHomeUrl("   "))
        assertNull(normalizeHomeUrl("https://"))
        assertNull(normalizeHomeUrl("not a url"))
    }
}
