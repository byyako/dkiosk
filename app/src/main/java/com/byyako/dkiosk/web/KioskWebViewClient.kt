package com.byyako.dkiosk.web

import android.graphics.Bitmap
import android.net.http.SslError
import android.webkit.HttpAuthHandler
import android.webkit.RenderProcessGoneDetail
import android.webkit.SslErrorHandler
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient

/**
 * Enforces the [NavigationPolicy], reports whether each top-level page loaded or failed, and hands
 * certificate and login prompts to the activity.
 */
class KioskWebViewClient(
    private val callbacks: Callbacks,
    private val isCurrent: (WebView) -> Boolean = { true },
) : WebViewClient() {

    interface Callbacks {
        fun navigationPolicy(): NavigationPolicy

        /**
         * [pageLost] is true when there was no earlier page to go back to, so nothing is showing.
         * Returns true if an error screen now covers the page, so there's no need to go back.
         */
        fun onNavigationBlocked(verdict: Verdict, pageLost: Boolean): Boolean

        fun onPageStarted(url: String)

        fun onPageLoaded(url: String)

        fun onPageFailed(url: String, reason: String)

        /** Must eventually call proceed() or cancel() on [handler]. */
        fun onUntrustedCertificate(host: String, fingerprint: String, handler: SslErrorHandler)

        /** Must eventually call proceed() or cancel() on [handler]. */
        fun onLoginRequest(host: String, realm: String, handler: HttpAuthHandler)

        fun onRendererGone(crashed: Boolean)
    }

    // Errors can arrive before onPageStarted (HTTP errors come with the response headers), so they are
    // matched to the page by URL in onPageFinished instead of being reset when a page starts.
    private var failedUrl: String? = null
    private var failure: String? = null

    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
        if (!isCurrent(view)) return true
        val verdict = callbacks.navigationPolicy().check(request.url.toString(), request.isForMainFrame)
        if (verdict == Verdict.Allow) return false
        callbacks.onNavigationBlocked(verdict, pageLost = false)
        return true
    }

    override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
        if (!isCurrent(view)) return
        // shouldOverrideUrlLoading never sees form POSTs or redirects of loadUrl(), so check again here.
        val verdict = callbacks.navigationPolicy().check(url)
        if (verdict != Verdict.Allow) {
            view.stopLoading()
            val canGoBack = view.canGoBack()
            val covered = callbacks.onNavigationBlocked(verdict, pageLost = !canGoBack)
            // Going home instead could loop forever if the home page itself redirects somewhere blocked.
            if (canGoBack && !covered) view.goBack() else view.loadUrl(BLANK)
            return
        }
        // The blank page that replaces a blocked one isn't a page load.
        if (url == BLANK) return
        if (!isSamePage(failedUrl, url)) {
            failedUrl = null
            failure = null
        }
        callbacks.onPageStarted(url)
    }

    override fun onPageFinished(view: WebView, url: String) {
        // The blank page shown after a blocked redirect isn't a successful load.
        if (!isCurrent(view) || url == BLANK) return
        val error = failure?.takeIf { isSamePage(failedUrl, url) }
        if (error != null) {
            failedUrl = null
            failure = null
        }
        if (error == null) callbacks.onPageLoaded(url) else callbacks.onPageFailed(url, error)
    }

    override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
        if (isCurrent(view) && request.isForMainFrame) recordFailure(request, error.description.toString())
    }

    override fun onReceivedHttpError(view: WebView, request: WebResourceRequest, response: WebResourceResponse) {
        // 5xx usually means the server is restarting or a proxy can't reach it, so it's worth retrying.
        // A 4xx is a real answer and the page is left as the server sent it.
        if (isCurrent(view) && request.isForMainFrame && response.statusCode >= 500) {
            recordFailure(request, "HTTP ${response.statusCode} ${response.reasonPhrase.orEmpty()}".trim())
        }
    }

    private fun recordFailure(request: WebResourceRequest, reason: String) {
        failedUrl = request.url.toString()
        failure = reason
    }

    override fun onReceivedSslError(view: WebView, handler: SslErrorHandler, error: SslError) {
        if (!isCurrent(view)) {
            handler.cancel()
            return
        }
        val host = hostOf(error.url)
        val der = error.certificate.derBytes()
        if (host == null || der == null) {
            handler.cancel()
            return
        }
        callbacks.onUntrustedCertificate(host, sha256Fingerprint(der), handler)
    }

    override fun onReceivedHttpAuthRequest(view: WebView, handler: HttpAuthHandler, host: String, realm: String) {
        if (isCurrent(view)) callbacks.onLoginRequest(host, realm, handler) else handler.cancel()
    }

    override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
        if (isCurrent(view)) callbacks.onRendererGone(detail.didCrash())
        // Returning true keeps the app alive; the activity replaces this WebView, which is now unusable.
        return true
    }

    private companion object {
        const val BLANK = "about:blank"
    }
}
