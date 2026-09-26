package com.tyllad.dkiosk.web

/** Outcome of checking a URL against the kiosk's navigation rules. */
sealed interface Verdict {
    data object Allow : Verdict
    data class BlockedScheme(val scheme: String) : Verdict
    data class BlockedHost(val host: String) : Verdict
}

/**
 * Decides where the kiosk may navigate. Only web schemes are ever opened (tel:, intent:, market:
 * and friends would leave the kiosk). With [restrict] on, top-level pages must also be on the home
 * URL's host or one of [extraHosts].
 *
 * Hosts compare case-insensitively and ignore ports and a leading "www."; an entry like
 * "*.example.com" matches example.com and all of its subdomains.
 */
class NavigationPolicy(
    homeUrl: String,
    extraHosts: Collection<String>,
    private val restrict: Boolean,
) {
    private val patterns: List<String> = (listOf(homeUrl) + extraHosts).mapNotNull(::allowedHostPattern)

    fun check(url: String, isMainFrame: Boolean = true): Verdict {
        val scheme = schemeOf(url) ?: return Verdict.BlockedScheme("unknown")
        if (scheme in INTERNAL_SCHEMES) return Verdict.Allow
        if (scheme !in WEB_SCHEMES) return Verdict.BlockedScheme(scheme)

        // Frames inside an allowed page (embedded charts, video players) may come from anywhere.
        if (!restrict || !isMainFrame) return Verdict.Allow

        val host = hostOf(url) ?: return Verdict.BlockedHost("")
        return if (allowsHost(host)) Verdict.Allow else Verdict.BlockedHost(host)
    }

    fun allowsHost(host: String): Boolean = !restrict || patterns.any { matches(it, canonicalHost(host)) }

    private fun matches(pattern: String, host: String): Boolean {
        if (!pattern.startsWith("*.")) return host == pattern
        val base = pattern.removePrefix("*.")
        return host == base || host.endsWith(".$base")
    }

    private companion object {
        val WEB_SCHEMES = setOf("http", "https")

        // Stay inside the current page, so they can't escape the allowed sites.
        val INTERNAL_SCHEMES = setOf("about", "blob")
    }
}

/**
 * Turns an allowlist entry as a user might type it ("Auth.Example.com", "https://sso.example.com/login",
 * "*.example.com") into a canonical host pattern, or null if it has no host.
 */
fun allowedHostPattern(entry: String): String? {
    val trimmed = entry.trim()
    val wildcard = trimmed.startsWith("*.")
    val rest = trimmed.removePrefix("*.")
    val host = hostOf(if ("://" in rest) rest else "https://$rest") ?: return null
    val canonical = canonicalHost(host).ifEmpty { return null }
    if (!isHostName(canonical)) return null
    return if (wildcard) "*.$canonical" else canonical
}

/** Letters, digits, dots, hyphens and underscores, or a bracketed IPv6 address. */
private fun isHostName(host: String): Boolean {
    if (host.startsWith("[")) return host.endsWith("]") && host.drop(1).dropLast(1).all { it.isLetterOrDigit() || it in ":." }
    return host.all { it.isLetterOrDigit() || it in ".-_" }
}
