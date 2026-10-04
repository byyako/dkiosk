package com.byyako.dkiosk.web

import android.annotation.SuppressLint
import android.net.http.SslError
import android.webkit.HttpAuthHandler
import android.webkit.RenderProcessGoneDetail
import android.webkit.SslErrorHandler
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.webkit.WebViewDatabase

/**
 * For a screensaver page: the same site rules as the dashboard, but it never prompts, because
 * nobody is there to answer. Certificates and logins the administrator already approved still work.
 */
class ScreensaverWebViewClient(
    private val policy: () -> NavigationPolicy,
    private val certPins: CertificatePins,
    private val onGone: () -> Unit,
) : WebViewClient() {

    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean =
        policy().check(request.url.toString(), request.isForMainFrame) != Verdict.Allow

    // Proceeds only for a certificate the administrator already trusted for this host.
    @SuppressLint("WebViewClientOnReceivedSslError")
    override fun onReceivedSslError(view: WebView, handler: SslErrorHandler, error: SslError) {
        val host = hostOf(error.url)
        val der = error.certificate.derBytes()
        if (host != null && der != null && policy().allowsHost(host) && certPins.isPinned(host, sha256Fingerprint(der))) {
            handler.proceed()
        } else handler.cancel()
    }

    override fun onReceivedHttpAuthRequest(view: WebView, handler: HttpAuthHandler, host: String, realm: String) {
        val saved = WebViewDatabase.getInstance(view.context).getHttpAuthUsernamePassword(host, realm)
        val username = saved?.getOrNull(0)
        val password = saved?.getOrNull(1)
        if (policy().allowsHost(host) && username != null && password != null && handler.useHttpAuthUsernamePassword()) {
            handler.proceed(username, password)
        } else handler.cancel()
    }

    override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
        onGone()
        return true
    }
}
