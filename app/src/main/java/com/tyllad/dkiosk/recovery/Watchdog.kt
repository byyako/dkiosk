package com.tyllad.dkiosk.recovery

import android.os.Handler
import android.os.Looper
import android.webkit.WebView

/**
 * Checks every 30 seconds that the page's JavaScript still answers. A renderer stuck in an endless
 * loop never replies, and nothing else in WebView reports that when nobody is touching the screen.
 * [isPaused] skips checks while the page is legitimately blocked, e.g. by an alert().
 */
class Watchdog(
    private val webView: () -> WebView,
    private val isPaused: () -> Boolean,
    private val onUnresponsive: () -> Unit,
) {

    private val handler = Handler(Looper.getMainLooper())

    // Bumped on every ping and on stop(), so a late reply from an old ping or old WebView is ignored.
    private var pingId = 0
    private var answered = true

    fun start() {
        stop()
        handler.postDelayed(ping, INTERVAL_MS)
    }

    fun stop() {
        pingId++
        answered = true
        handler.removeCallbacks(ping)
        handler.removeCallbacks(check)
    }

    private val ping: Runnable = Runnable {
        if (isPaused()) {
            handler.postDelayed(ping, INTERVAL_MS)
            return@Runnable
        }
        val id = ++pingId
        answered = false
        webView().evaluateJavascript("1") { if (id == pingId) answered = true }
        handler.postDelayed(check, TIMEOUT_MS)
    }

    private val check: Runnable = Runnable {
        handler.postDelayed(ping, INTERVAL_MS - TIMEOUT_MS)
        if (!answered) onUnresponsive()
    }

    private companion object {
        const val INTERVAL_MS = 30_000L
        const val TIMEOUT_MS = 15_000L
    }
}
