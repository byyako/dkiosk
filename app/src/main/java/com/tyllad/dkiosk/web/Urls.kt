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

/** Lowercased scheme of [url] ("https", "tel", "intent"), or null if it has none. */
fun schemeOf(url: String): String? {
    val colon = url.indexOf(':')
    if (colon <= 0) return null
    val scheme = url.substring(0, colon)
    if (!scheme[0].isLetter() || !scheme.all { it.isLetterOrDigit() || it in "+-." }) return null
    return scheme.lowercase()
}

/**
 * Lowercased host of an absolute URL, without userinfo or port; null if there is none.
 * Parsed by hand because WebView hands us URLs that java.net.URI rejects (e.g. "|" in queries).
 */
fun hostOf(url: String): String? {
    val start = url.indexOf("://").takeIf { it > 0 }?.plus(3) ?: return null
    val end = url.indexOfAny(charArrayOf('/', '?', '#'), start).takeIf { it >= 0 } ?: url.length
    val authority = url.substring(start, end).substringAfterLast('@')
    val host = if (authority.startsWith("[")) {
        authority.substringBefore(']') + "]"
    } else {
        authority.substringBefore(':')
    }
    return host.lowercase().ifEmpty { null }
}

/** Host form used for comparisons: lowercase, no trailing dot, no leading "www.". */
fun canonicalHost(host: String): String = host.lowercase().trimEnd('.').removePrefix("www.")
