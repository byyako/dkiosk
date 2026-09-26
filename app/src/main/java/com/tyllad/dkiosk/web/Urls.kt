package com.tyllad.dkiosk.web

import java.net.URI
import java.net.URISyntaxException

private val WEB_SCHEMES = setOf("http", "https")

/**
 * Turns what a user typed into a loadable URL, or null if it isn't one.
 * Input without a scheme ("192.168.1.5:8123") is assumed to be https.
 */
fun normalizeHomeUrl(input: String): String? {
    val trimmed = input.trim()
    if (trimmed.isEmpty()) return null

    val withScheme = if ("://" in trimmed) trimmed else "https://$trimmed"
    val uri = try {
        URI(withScheme)
    } catch (_: URISyntaxException) {
        return null
    }

    if (uri.scheme?.lowercase() !in WEB_SCHEMES) return null
    if (uri.host.isNullOrEmpty()) return null
    return uri.toString()
}
