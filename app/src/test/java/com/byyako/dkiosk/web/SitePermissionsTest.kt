package com.byyako.dkiosk.web

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SitePermissionsTest {

    private var stored = emptySet<String>()
    private val permissions = SitePermissions(load = { stored }, save = { stored = it })

    @Test
    fun undecidedUntilRemembered() {
        assertNull(permissions.decision("ha.example.com", SiteFeature.CAMERA))
        permissions.remember("ha.example.com", listOf(SiteFeature.CAMERA, SiteFeature.MICROPHONE), SitePermissions.Decision.ALLOW)
        assertEquals(SitePermissions.Decision.ALLOW, permissions.decision("ha.example.com", SiteFeature.CAMERA))
        assertEquals(SitePermissions.Decision.ALLOW, permissions.decision("HA.example.com", SiteFeature.MICROPHONE))
        assertNull(permissions.decision("other.example.com", SiteFeature.CAMERA))
    }

    @Test
    fun wwwIsTheSameSite() {
        permissions.remember("www.example.com", listOf(SiteFeature.MICROPHONE), SitePermissions.Decision.BLOCK)
        assertEquals(SitePermissions.Decision.BLOCK, permissions.decision("example.com", SiteFeature.MICROPHONE))
    }

    @Test
    fun aNewAnswerReplacesTheOldOne() {
        permissions.remember("a.com", listOf(SiteFeature.CAMERA), SitePermissions.Decision.BLOCK)
        permissions.remember("a.com", listOf(SiteFeature.CAMERA), SitePermissions.Decision.ALLOW)
        assertEquals(SitePermissions.Decision.ALLOW, permissions.decision("a.com", SiteFeature.CAMERA))
        assertEquals(setOf("a.com|camera=allow"), stored)
    }

    @Test
    fun counts() {
        permissions.remember("a.com", listOf(SiteFeature.CAMERA, SiteFeature.MICROPHONE), SitePermissions.Decision.ALLOW)
        permissions.remember("b.com", listOf(SiteFeature.CAMERA), SitePermissions.Decision.BLOCK)
        permissions.remember("c.com", listOf(SiteFeature.MICROPHONE), SitePermissions.Decision.BLOCK)
        assertEquals(1 to 2, permissions.counts())
    }
}
