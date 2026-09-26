package com.tyllad.dkiosk.web

import android.app.Activity
import android.view.LayoutInflater
import android.webkit.HttpAuthHandler
import android.webkit.SslErrorHandler
import android.webkit.WebViewDatabase
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.tyllad.dkiosk.R
import com.tyllad.dkiosk.databinding.DialogLoginBinding

/**
 * Dialogs a page can trigger: trusting a self-signed certificate and HTTP basic auth logins.
 * Only the kiosk's own sites may ask; for anything else the request is cancelled silently.
 */
class PagePrompts(
    private val activity: Activity,
    private val certPins: CertificatePins,
    private val policy: () -> NavigationPolicy,
) {
    // Several requests can hit the same certificate or login at once; they share one dialog.
    private val pendingCerts = mutableMapOf<String, MutableList<SslErrorHandler>>()
    private val pendingLogins = mutableMapOf<String, MutableList<HttpAuthHandler>>()

    private val authStore: WebViewDatabase
        get() = WebViewDatabase.getInstance(activity)

    fun onUntrustedCertificate(host: String, fingerprint: String, handler: SslErrorHandler) {
        when {
            certPins.isPinned(host, fingerprint) -> handler.proceed()
            !policy().allowsHost(host) -> handler.cancel()
            else -> askToTrust(host, fingerprint, handler)
        }
    }

    fun onLoginRequest(host: String, realm: String, handler: HttpAuthHandler) {
        val saved = authStore.getHttpAuthUsernamePassword(host, realm)
        // useHttpAuthUsernamePassword() turns false once the server has rejected these credentials.
        if (saved != null && handler.useHttpAuthUsernamePassword()) {
            handler.proceed(saved[0], saved[1])
            return
        }
        if (!policy().allowsHost(host)) {
            handler.cancel()
            return
        }
        askForLogin(host, realm, saved?.getOrNull(0), handler)
    }

    private fun askToTrust(host: String, fingerprint: String, handler: SslErrorHandler) {
        val key = "$host|$fingerprint"
        if (queue(pendingCerts, key, handler)) return

        val changed = certPins.hasPinFor(host)
        MaterialAlertDialogBuilder(activity)
            .setTitle(if (changed) R.string.cert_changed_title else R.string.cert_untrusted_title)
            .setMessage(
                activity.getString(
                    if (changed) R.string.cert_changed_message else R.string.cert_untrusted_message,
                    host,
                    fingerprint,
                ),
            )
            .setCancelable(false)
            .setPositiveButton(R.string.cert_trust) { _, _ ->
                certPins.pin(host, fingerprint)
                pendingCerts.remove(key)?.forEach { it.proceed() }
            }
            .setNegativeButton(android.R.string.cancel) { _, _ ->
                pendingCerts.remove(key)?.forEach { it.cancel() }
            }
            .show()
    }

    private fun askForLogin(host: String, realm: String, lastUsername: String?, handler: HttpAuthHandler) {
        val key = "$host|$realm"
        if (queue(pendingLogins, key, handler)) return

        val binding = DialogLoginBinding.inflate(LayoutInflater.from(activity))
        binding.username.setText(lastUsername)
        val message = if (realm.isBlank()) {
            activity.getString(R.string.login_message, host)
        } else {
            activity.getString(R.string.login_message_realm, host, realm)
        }

        MaterialAlertDialogBuilder(activity)
            .setTitle(R.string.login_title)
            .setMessage(message)
            .setView(binding.root)
            .setCancelable(false)
            .setPositiveButton(R.string.login_sign_in) { _, _ ->
                val username = binding.username.text.toString()
                val password = binding.password.text.toString()
                authStore.setHttpAuthUsernamePassword(host, realm, username, password)
                pendingLogins.remove(key)?.forEach { it.proceed(username, password) }
            }
            .setNegativeButton(android.R.string.cancel) { _, _ ->
                pendingLogins.remove(key)?.forEach { it.cancel() }
            }
            .show()
    }

    /** Adds [handler] to the waiting list for [key]; returns true if a dialog for it is already open. */
    private fun <T> queue(pending: MutableMap<String, MutableList<T>>, key: String, handler: T): Boolean {
        val waiting = pending[key]
        if (waiting != null) {
            waiting += handler
            return true
        }
        pending[key] = mutableListOf(handler)
        return false
    }
}
