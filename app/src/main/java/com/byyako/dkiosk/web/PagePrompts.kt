package com.byyako.dkiosk.web

import android.app.Activity
import android.view.LayoutInflater
import android.util.Log
import android.webkit.HttpAuthHandler
import android.webkit.PermissionRequest
import android.webkit.SslErrorHandler
import android.webkit.WebViewDatabase
import androidx.appcompat.app.AlertDialog
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.byyako.dkiosk.R
import com.byyako.dkiosk.databinding.DialogLoginBinding
import com.byyako.dkiosk.ui.panForKeyboard

/**
 * Dialogs a page can trigger: trusting a self-signed certificate, HTTP basic auth logins and camera
 * or microphone access. Only the kiosk's own sites may ask; for anything else the request is
 * cancelled silently.
 */
class PagePrompts(
    private val activity: Activity,
    private val certPins: CertificatePins,
    private val sitePermissions: SitePermissions,
    private val policy: () -> NavigationPolicy,
    private val askForPin: (onSuccess: () -> Unit, onCancel: () -> Unit) -> Unit,
    /** Gets Android's own permission for [features] if dKiosk doesn't have it yet. */
    private val askAndroid: (features: Set<SiteFeature>, done: (granted: Boolean) -> Unit) -> Unit,
) {
    // Several requests can hit the same certificate or login at once; they share one dialog.
    private val pendingCerts = mutableMapOf<String, MutableList<SslErrorHandler>>()
    private val pendingLogins = mutableMapOf<String, MutableList<HttpAuthHandler>>()
    private val pendingAccess = mutableMapOf<String, MutableList<PermissionRequest>>()
    private val accessDialogs = mutableMapOf<String, AlertDialog>()
    private val dialogs = mutableSetOf<AlertDialog>()
    private var generation = 0

    /** Resolve handlers before destroying their WebView and invalidate delayed PIN callbacks. */
    fun dismiss() {
        generation++
        pendingCerts.values.flatten().forEach { it.cancel() }
        pendingLogins.values.flatten().forEach { it.cancel() }
        pendingAccess.values.flatten().forEach { deny(it) }
        pendingCerts.clear()
        pendingLogins.clear()
        pendingAccess.clear()
        accessDialogs.clear()
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

    fun onPermissionRequest(request: PermissionRequest) {
        val host = request.origin.host
        val features = request.resources.mapNotNull { FEATURES[it] }.toSet()
        // Other kinds (protected media IDs, MIDI) aren't something a kiosk page needs.
        if (host == null || features.isEmpty() || !policy().allowsHost(host)) {
            deny(request)
            return
        }
        val decisions = features.map { sitePermissions.decision(host, it) }
        when {
            SitePermissions.Decision.BLOCK in decisions -> deny(request)
            decisions.all { it == SitePermissions.Decision.ALLOW } -> grantWithAndroid(request, features)
            else -> askForAccess(host, features, request)
        }
    }

    fun onPermissionRequestCanceled(request: PermissionRequest) {
        val key = pendingAccess.entries.firstOrNull { request in it.value }?.key ?: return
        val waiting = pendingAccess.getValue(key)
        waiting.remove(request)
        if (waiting.isEmpty()) {
            pendingAccess.remove(key)
            accessDialogs.remove(key)?.dismiss()
        }
    }

    private fun askForAccess(host: String, features: Set<SiteFeature>, request: PermissionRequest) {
        val key = "$host|${features.sorted().joinToString(",")}"
        if (queue(pendingAccess, key, request)) return
        val requestedAt = generation

        val what = when (features) {
            setOf(SiteFeature.CAMERA) -> R.string.access_camera
            setOf(SiteFeature.MICROPHONE) -> R.string.access_microphone
            else -> R.string.access_camera_microphone
        }
        val dialog = MaterialAlertDialogBuilder(activity)
            .setTitle(R.string.access_title)
            .setMessage(activity.getString(R.string.access_message, host, activity.getString(what)))
            .setCancelable(false)
            .setPositiveButton(R.string.access_allow) { _, _ ->
                // Like trusting a certificate, this is for the administrator to decide.
                askForPin(
                    {
                        if (requestedAt != generation) return@askForPin
                        val waiting = pendingAccess.remove(key).orEmpty()
                        if (policy().allowsHost(host)) {
                            sitePermissions.remember(host, features, SitePermissions.Decision.ALLOW)
                            waiting.forEach { grantWithAndroid(it, features) }
                        } else waiting.forEach { deny(it) }
                    },
                    { if (requestedAt == generation) pendingAccess.remove(key)?.forEach { deny(it) } },
                )
            }
            .setNegativeButton(R.string.access_block) { _, _ ->
                sitePermissions.remember(host, features, SitePermissions.Decision.BLOCK)
                pendingAccess.remove(key)?.forEach { deny(it) }
            }
            .setNeutralButton(R.string.access_not_now) { _, _ ->
                pendingAccess.remove(key)?.forEach { deny(it) }
            }
            .create()
        accessDialogs[key] = dialog
        dialog.setOnDismissListener {
            dialogs.remove(dialog)
            if (accessDialogs[key] === dialog) accessDialogs.remove(key)
        }
        dialogs.add(dialog)
        dialog.show()
    }

    /**
     * Android's own permission dialog pauses the kiosk, which dismisses this class's prompts, so
     * the request is held here rather than in the pending lists until Android answers.
     */
    private fun grantWithAndroid(request: PermissionRequest, features: Set<SiteFeature>) {
        askAndroid(features) { granted ->
            val resources = request.resources.filter { FEATURES[it] in features }.toTypedArray()
            try {
                if (granted) request.grant(resources) else request.deny()
            } catch (e: IllegalStateException) {
                Log.w(TAG, "Page permission request ended before it was answered", e)
            }
        }
    }

    private fun deny(request: PermissionRequest) {
        try {
            request.deny()
        } catch (e: IllegalStateException) {
            Log.w(TAG, "Page permission request ended before it was answered", e)
        }
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

    private companion object {
        const val TAG = "PagePrompts"
        val FEATURES = mapOf(
            PermissionRequest.RESOURCE_VIDEO_CAPTURE to SiteFeature.CAMERA,
            PermissionRequest.RESOURCE_AUDIO_CAPTURE to SiteFeature.MICROPHONE,
        )
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
