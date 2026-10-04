package com.byyako.dkiosk.web

/** Camera and microphone access a page can ask for. */
enum class SiteFeature(val key: String) {
    CAMERA("camera"),
    MICROPHONE("microphone"),
}

/**
 * Remembered answers to pages asking for the camera or microphone, per host and feature. Stored as
 * "host|feature=allow" or "host|feature=block" strings. Undecided means the kiosk asks.
 */
class SitePermissions(
    private val load: () -> Set<String>,
    private val save: (Set<String>) -> Unit,
) {
    enum class Decision { ALLOW, BLOCK }

    fun decision(host: String, feature: SiteFeature): Decision? {
        val prefix = prefix(host, feature)
        val stored = load().firstOrNull { it.startsWith(prefix) } ?: return null
        return when (stored.removePrefix(prefix)) {
            "allow" -> Decision.ALLOW
            "block" -> Decision.BLOCK
            else -> null
        }
    }

    fun remember(host: String, features: Collection<SiteFeature>, decision: Decision) {
        val prefixes = features.map { prefix(host, it) }
        val kept = load().filterNot { entry -> prefixes.any { entry.startsWith(it) } }
        val value = if (decision == Decision.ALLOW) "allow" else "block"
        save(kept.toSet() + prefixes.map { it + value })
    }

    /** How many sites have something allowed, and how many have something blocked. */
    fun counts(): Pair<Int, Int> {
        val entries = load()
        fun hosts(value: String) = entries.filter { it.endsWith("=$value") }.map { it.substringBefore('|') }.toSet().size
        return hosts("allow") to hosts("block")
    }

    private fun prefix(host: String, feature: SiteFeature) = "${canonicalHost(host)}|${feature.key}="
}
