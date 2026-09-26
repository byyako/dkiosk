package com.tyllad.dkiosk.web

import android.graphics.Bitmap
import android.net.http.SslCertificate
import android.net.http.SslError
import android.os.Build
import android.webkit.SslErrorHandler
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient

/** Enforces the [NavigationPolicy] and routes untrusted certificates to the host for a decision. */
class KioskWebViewClient(private val callbacks: Callbacks) : WebViewClient() {

    interface Callbacks {
        fun navigationPolicy(): NavigationPolicy

        fun onNavigationBlocked(verdict: Verdict)

        /** Must eventually call [SslErrorHandler.proceed] or [SslErrorHandler.cancel] on [handler]. */
        fun onUntrustedCertificate(host: String, fingerprint: String, handler: SslErrorHandler)
    }

    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
        val verdict = callbacks.navigationPolicy().check(request.url.toString(), request.isForMainFrame)
        if (verdict == Verdict.Allow) return false
        callbacks.onNavigationBlocked(verdict)
        return true
    }

    override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
        super.onPageStarted(view, url, favicon)
        // Backstop for navigations shouldOverrideUrlLoading never sees: form POSTs and
        // redirects of loadUrl() calls.
        val verdict = callbacks.navigationPolicy().check(url)
        if (verdict == Verdict.Allow) return
        view.stopLoading()
        // Blank rather than reload home: if home itself redirects somewhere blocked, reloading loops.
        if (view.canGoBack()) view.goBack() else view.loadUrl("about:blank")
        callbacks.onNavigationBlocked(verdict)
    }

    override fun onReceivedSslError(view: WebView, handler: SslErrorHandler, error: SslError) {
        val host = hostOf(error.url)
        val der = error.certificate.derBytes()
        if (host == null || der == null) {
            handler.cancel()
            return
        }
        callbacks.onUntrustedCertificate(host, sha256Fingerprint(der), handler)
    }

    private fun SslCertificate.derBytes(): ByteArray? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            x509Certificate?.encoded
        } else {
            // Before API 29 the only access to the raw certificate is its saved-state bundle.
            SslCertificate.saveState(this).getByteArray("x509-certificate")
        }
}
