package com.tyllad.dkiosk.web

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NavigationPolicyTest {

    private fun policy(home: String = "http://dash.example.com:8123/lovelace", vararg extra: String, restrict: Boolean = true) =
        NavigationPolicy(home, extra.toList(), restrict)

    @Test
    fun allowsHomeHostOnAnyPortOrScheme() {
        val p = policy()
        assertEquals(Verdict.Allow, p.check("http://dash.example.com:8123/other"))
        assertEquals(Verdict.Allow, p.check("https://dash.example.com/"))
        assertEquals(Verdict.Allow, p.check("https://DASH.Example.com./x"))
    }

    @Test
    fun treatsWwwAsTheSameSite() {
        assertEquals(Verdict.Allow, policy("https://example.com").check("https://www.example.com/"))
        assertEquals(Verdict.Allow, policy("https://www.example.com").check("https://example.com/"))
    }

    @Test
    fun blocksOtherHosts() {
        val p = policy()
        assertEquals(Verdict.BlockedHost("evil.com"), p.check("https://evil.com/"))
        assertEquals(Verdict.BlockedHost("example.com"), p.check("https://example.com/"))
        assertEquals(Verdict.BlockedHost("auth.example.com"), p.check("https://auth.example.com/"))
        assertEquals(Verdict.BlockedHost("dash.example.com.evil.com"), p.check("https://dash.example.com.evil.com/"))
    }

    @Test
    fun hostTricksDontFoolTheCheck() {
        val p = policy()
        assertEquals(Verdict.BlockedHost("evil.com"), p.check("https://dash.example.com@evil.com/"))
        assertEquals(Verdict.BlockedHost("evil.com"), p.check("https://evil.com#@dash.example.com"))
        assertEquals(Verdict.BlockedHost("evil.com"), p.check("https://evil.com?next=dash.example.com"))
    }

    @Test
    fun extraHostsAcceptPlainHostsAndUrls() {
        val p = policy("https://dash.example.com", "Auth.Example.com", "https://sso.other.org/login")
        assertEquals(Verdict.Allow, p.check("https://auth.example.com/authorize"))
        assertEquals(Verdict.Allow, p.check("https://sso.other.org/cb"))
        assertEquals(Verdict.BlockedHost("other.org"), p.check("https://other.org/"))
    }

    @Test
    fun wildcardMatchesDomainAndSubdomainsOnly() {
        val p = policy("https://dash.example.com", "*.corp.net")
        assertEquals(Verdict.Allow, p.check("https://corp.net/"))
        assertEquals(Verdict.Allow, p.check("https://a.b.corp.net/"))
        assertEquals(Verdict.BlockedHost("evilcorp.net"), p.check("https://evilcorp.net/"))
    }

    @Test
    fun ipAddressesAndIpv6() {
        assertEquals(Verdict.Allow, policy("http://192.168.1.5:8123").check("http://192.168.1.5:3000/"))
        assertEquals(Verdict.BlockedHost("192.168.1.6"), policy("http://192.168.1.5").check("http://192.168.1.6/"))
        assertEquals(Verdict.Allow, policy("http://[fe80::1]:8123/").check("http://[FE80::1]/x"))
    }

    @Test
    fun blocksNonWebSchemesEvenWhenUnrestricted() {
        for (p in listOf(policy(), policy(restrict = false))) {
            assertEquals(Verdict.BlockedScheme("tel"), p.check("tel:5551234"))
            assertEquals(Verdict.BlockedScheme("mailto"), p.check("mailto:a@b.c"))
            assertEquals(Verdict.BlockedScheme("intent"), p.check("intent://scan/#Intent;scheme=zxing;end"))
            assertEquals(Verdict.BlockedScheme("market"), p.check("market://details?id=x"))
            assertEquals(Verdict.BlockedScheme("file"), p.check("file:///sdcard/secret.txt"))
            assertEquals(Verdict.BlockedScheme("unknown"), p.check("no-scheme-here"))
        }
    }

    @Test
    fun allowsInPageSchemes() {
        assertEquals(Verdict.Allow, policy().check("about:blank"))
        assertEquals(Verdict.Allow, policy().check("blob:https://dash.example.com/1234"))
    }

    @Test
    fun unrestrictedAllowsAnyWebHost() {
        assertEquals(Verdict.Allow, policy(restrict = false).check("https://anything.org/"))
        assertTrue(policy(restrict = false).allowsHost("anything.org"))
    }

    @Test
    fun subframesMayComeFromAnyHostButNotAnyScheme() {
        val p = policy()
        assertEquals(Verdict.Allow, p.check("https://grafana.other.net/embed", isMainFrame = false))
        assertEquals(Verdict.BlockedScheme("tel"), p.check("tel:123", isMainFrame = false))
    }

    @Test
    fun allowsHostMatchesTheSameRules() {
        val p = policy("https://dash.example.com", "*.corp.net")
        assertTrue(p.allowsHost("DASH.example.com"))
        assertTrue(p.allowsHost("x.corp.net"))
        assertFalse(p.allowsHost("evil.com"))
    }

    @Test
    fun allowedHostPatternNormalizesEntries() {
        assertEquals("example.com", allowedHostPattern(" WWW.Example.com "))
        assertEquals("sso.example.com", allowedHostPattern("https://sso.example.com:8443/login"))
        assertEquals("*.example.com", allowedHostPattern("*.example.com"))
        assertEquals("example.com", allowedHostPattern("example.com/path"))
        assertEquals("[fe80::1]", allowedHostPattern("http://[FE80::1]:8123"))
        assertNull(allowedHostPattern(""))
        assertNull(allowedHostPattern("https://"))
        assertNull(allowedHostPattern("not a host"))
        assertNull(allowedHostPattern("exa!mple.com"))
    }
}
