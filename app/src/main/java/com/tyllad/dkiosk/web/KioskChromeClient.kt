package com.tyllad.dkiosk.web

import android.app.Activity
import android.os.Handler
import android.os.Looper
import android.webkit.JsPromptResult
import android.webkit.JsResult
import android.webkit.WebChromeClient
import android.webkit.WebView
import com.google.android.material.dialog.MaterialAlertDialogBuilder

/**
 * Page alerts and confirms. While one is open the page's JavaScript is paused, which would look like a
 * hang and would freeze a dashboard nobody is watching, so they close themselves after a minute.
 * "Leave this page?" prompts are always answered yes so they can't trap the kiosk.
 */
class KioskChromeClient(private val activity: Activity) : WebChromeClient() {

    private val handler = Handler(Looper.getMainLooper())

    var isDialogOpen = false
        private set

    override fun onJsAlert(view: WebView, url: String, message: String, result: JsResult): Boolean =
        show(message, result, withCancel = false)

    override fun onJsConfirm(view: WebView, url: String, message: String, result: JsResult): Boolean =
        show(message, result, withCancel = true)

    // Text prompts aren't supported; the page sees the prompt cancelled.
    override fun onJsPrompt(
        view: WebView,
        url: String,
        message: String,
        defaultValue: String?,
        result: JsPromptResult,
    ): Boolean {
        result.cancel()
        return true
    }

    override fun onJsBeforeUnload(view: WebView, url: String, message: String, result: JsResult): Boolean {
        result.confirm()
        return true
    }

    private fun show(message: String, result: JsResult, withCancel: Boolean): Boolean {
        val builder = MaterialAlertDialogBuilder(activity)
            .setMessage(message)
            .setCancelable(false)
            .setPositiveButton(android.R.string.ok) { _, _ -> result.confirm() }
        if (withCancel) builder.setNegativeButton(android.R.string.cancel) { _, _ -> result.cancel() }

        val dialog = builder.create()
        val autoClose = Runnable {
            if (withCancel) result.cancel() else result.confirm()
            dialog.dismiss()
        }
        dialog.setOnDismissListener {
            isDialogOpen = false
            handler.removeCallbacks(autoClose)
        }
        isDialogOpen = true
        dialog.show()
        handler.postDelayed(autoClose, AUTO_CLOSE_MS)
        return true
    }

    private companion object {
        const val AUTO_CLOSE_MS = 60_000L
    }
}
