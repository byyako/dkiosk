package com.byyako.dkiosk.web

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RouteTest {
    @Test fun hashDashboardRoutesAreDifferentDestinations() {
        assertTrue(isSamePage("https://example.com/#home", "https://example.com/#settings"))
        assertFalse(isSameRoute("https://example.com/#home", "https://example.com/#settings"))
    }

    @Test fun matchingHomeAndEmptyFragmentAreAccepted() {
        assertTrue(isSameRoute("https://example.com/#home", "https://example.com#home"))
        assertTrue(isSameRoute("https://example.com/#", "https://example.com/"))
        assertFalse(isSameRoute(null, "https://example.com/"))
    }
}
