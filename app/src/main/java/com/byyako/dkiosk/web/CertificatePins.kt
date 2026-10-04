package com.byyako.dkiosk.web

import android.net.http.SslCertificate
import android.os.Build
import java.security.MessageDigest

/** The certificate as DER bytes, for fingerprinting. */
fun SslCertificate.derBytes(): ByteArray? =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        x509Certificate?.encoded
    } else {
        // Before API 29 the raw certificate is only reachable through its saved-state bundle.
        SslCertificate.saveState(this).getByteArray("x509-certificate")
    }

/** SHA-256 fingerprint as colon-separated uppercase hex, the format browsers display. */
fun sha256Fingerprint(der: ByteArray): String =
    MessageDigest.getInstance("SHA-256").digest(der).joinToString(":") { "%02X".format(it) }

/**
 * Trust-on-first-use pins for servers whose certificates Android doesn't trust (self-signed, private
 * CA). A pin is one exact host + fingerprint pair, so a changed certificate is never silently
 * accepted. Stored as "host|fingerprint" strings.
 */
class CertificatePins(
    private val load: () -> Set<String>,
    private val save: (Set<String>) -> Unit,
) {
    fun isPinned(host: String, fingerprint: String): Boolean = key(host, fingerprint) in load()

    /** True if some certificate was trusted for [host] before, i.e. a new one means it changed. */
    fun hasPinFor(host: String): Boolean = load().any { it.startsWith(prefix(host)) }

    /** Trusts [fingerprint] for [host], replacing any certificate trusted for it earlier. */
    fun pin(host: String, fingerprint: String) {
        save(load().filterNot { it.startsWith(prefix(host)) }.toSet() + key(host, fingerprint))
    }

    private fun prefix(host: String) = "${host.lowercase()}|"

    private fun key(host: String, fingerprint: String) = prefix(host) + fingerprint.uppercase()
}
