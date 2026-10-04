package com.byyako.dkiosk.web

import android.app.Activity
import android.view.LayoutInflater
import android.webkit.HttpAuthHandler
import android.webkit.SslErrorHandler
import android.webkit.WebViewDatabase
import androidx.appcompat.app.AlertDialog
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.byyako.dkiosk.R
import com.byyako.dkiosk.databinding.DialogLoginBinding
import com.byyako.dkiosk.ui.panForKeyboard

/**
 * Dialogs a page can trigger: trusting a self-signed certificate and HTTP basic auth logins.
 * Only the kiosk's own sites may ask; for anything else the request is cancelled silently.
 */
class PagePrompts(
    private val activity: Activity,
    private val certPins: CertificatePins,
    private val policy: () -> NavigationPolicy,
    private val askForPin: (onSuccess: () -> Unit, onCancel: () -> Unit) -> Unit,
) {
    // Several requests can hit the same certificate or login at once; they share one dialog.
    private val pendingCerts = mutableMapOf<String, MutableList<SslErrorHandler>>()
    private val pendingLogins = mutableMapOf<String, MutableList<HttpAuthHandler>>()
    private val dialogs = mutableSetOf<AlertDialog>()
    private var generation = 0

    /** Resolve handlers before destroying their WebView and invalidate delayed PIN callbacks. */
    fun dismiss() {
        generation++
        pendingCerts.values.flatten().forEach { it.cancel() }
        pendingLogins.values.flatten().forEach { it.cancel() }
        pendingCerts.clear()
        pendingLogins.clear()
        dialogs.toList().forEach { it.dismiss() }
        dialogs.clear()
    }

    private fun show(dialog: AlertDialog) {
        dialogs.add(dialog)
        dialog.setOnDismissListener { dialogs.remove(dialog) }
        dialog.show()
    }

    private val authStore: WebViewDatabase
        get() = WebViewDatabase.getInstance(activity)

    fun onUntrustedCertificate(host: String, fingerprint: String, handler: SslErrorHandler) {
        when {
            !policy().allowsHost(host) -> handler.cancel()
            certPins.isPinned(host, fingerprint) -> handler.proceed()
            else -> askToTrust(host, fingerprint, handler)
        }
    }

    fun onLoginRequest(host: String, realm: String, handler: HttpAuthHandler) {
        if (!policy().allowsHost(host)) {
            handler.cancel()
            return
        }
        val saved = authStore.getHttpAuthUsernamePassword(host, realm)
        val username = saved?.getOrNull(0)
        val password = saved?.getOrNull(1)
        // useHttpAuthUsernamePassword() turns false once the server has rejected these credentials.
        if (username != null && password != null && handler.useHttpAuthUsernamePassword()) {
            handler.proceed(username, password)
            return
        }
        askForLogin(host, realm, username, handler)
    }

    private fun askToTrust(host: String, fingerprint: String, handler: SslErrorHandler) {
        val key = "$host|$fingerprint"
        if (queue(pendingCerts, key, handler)) return
        val requestedAt = generation

        val changed = certPins.hasPinFor(host)
        val dialog = MaterialAlertDialogBuilder(activity)
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
                // Trusting a certificate is an admin decision, not something a passer-by should do.
                askForPin(
                    {
                        if (requestedAt == generation) {
                            val waiting = pendingCerts.remove(key).orEmpty()
                            if (policy().allowsHost(host)) {
                                certPins.pin(host, fingerprint)
                                waiting.forEach { it.proceed() }
                            } else waiting.forEach { it.cancel() }
                        }
                    },
                    { if (requestedAt == generation) pendingCerts.remove(key)?.forEach { it.cancel() } },
                )
            }
            .setNegativeButton(android.R.string.cancel) { _, _ ->
                pendingCerts.remove(key)?.forEach { it.cancel() }
            }
            .create()
        show(dialog)
    }

    private fun askForLogin(host: String, realm: String, lastUsername: String?, handler: HttpAuthHandler) {
        val key = "$host|$realm"
        if (queue(pendingLogins, key, handler)) return
        val requestedAt = generation

        val binding = DialogLoginBinding.inflate(LayoutInflater.from(activity))
        binding.username.setText(lastUsername)
        val message = if (realm.isBlank()) {
            activity.getString(R.string.login_message, host)
        } else {
            activity.getString(R.string.login_message_realm, host, realm)
        }

        val dialog = MaterialAlertDialogBuilder(activity)
            .setTitle(R.string.login_title)
            .setMessage(message)
            .setView(binding.root)
            .setCancelable(false)
            .setPositiveButton(R.string.login_sign_in) { _, _ ->
                if (requestedAt != generation) return@setPositiveButton
                val username = binding.username.text.toString()
                val password = binding.password.text.toString()
                val waiting = pendingLogins.remove(key).orEmpty()
                if (policy().allowsHost(host)) {
                    authStore.setHttpAuthUsernamePassword(host, realm, username, password)
                    waiting.forEach { it.proceed(username, password) }
                } else waiting.forEach { it.cancel() }
            }
            .setNegativeButton(android.R.string.cancel) { _, _ ->
                pendingLogins.remove(key)?.forEach { it.cancel() }
            }
            .create()
            .apply { panForKeyboard() }
        show(dialog)
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
