package com.byyako.dkiosk.web

import android.app.Activity
import android.os.Handler
import android.os.Looper
import android.webkit.JsPromptResult
import android.webkit.JsResult
import android.webkit.WebChromeClient
import android.webkit.WebView
import androidx.appcompat.app.AlertDialog
import com.google.android.material.dialog.MaterialAlertDialogBuilder

/**
 * Page alerts and confirms. While one is open the page's JavaScript is paused, which would look like a
 * hang and would freeze a dashboard nobody is watching, so they close themselves after a minute.
 * "Leave this page?" prompts are always answered yes so they can't trap the kiosk.
 */
class KioskChromeClient(
    private val activity: Activity,
    private val isCurrent: (WebView) -> Boolean = { true },
) : WebChromeClient() {

    private val handler = Handler(Looper.getMainLooper())
    private var activeDialog: AlertDialog? = null

    fun dismiss() {
        activeDialog?.dismiss()
        handler.removeCallbacksAndMessages(null)
    }

    var isDialogOpen = false
        private set

    override fun onJsAlert(view: WebView, url: String, message: String, result: JsResult): Boolean {
        if (isCurrent(view)) show(message, result, withCancel = false) else result.cancel()
        return true
    }

    override fun onJsConfirm(view: WebView, url: String, message: String, result: JsResult): Boolean {
        if (isCurrent(view)) show(message, result, withCancel = true) else result.cancel()
        return true
    }

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
        dismiss()
        var resolved = false
        fun resolve(confirm: Boolean) {
            if (resolved) return
            resolved = true
            if (confirm) result.confirm() else result.cancel()
        }
        val builder = MaterialAlertDialogBuilder(activity)
            .setMessage(message)
            .setCancelable(false)
            .setPositiveButton(android.R.string.ok) { _, _ -> resolve(true) }
        if (withCancel) builder.setNegativeButton(android.R.string.cancel) { _, _ -> resolve(false) }

        val dialog = builder.create()
        val autoClose = Runnable {
            resolve(!withCancel)
            dialog.dismiss()
        }
        dialog.setOnDismissListener {
            resolve(false)
            if (activeDialog === dialog) activeDialog = null
            isDialogOpen = false
            handler.removeCallbacks(autoClose)
        }
        isDialogOpen = true
        activeDialog = dialog
        dialog.show()
        handler.postDelayed(autoClose, AUTO_CLOSE_MS)
        return true
    }

    private companion object {
        const val AUTO_CLOSE_MS = 60_000L
    }
}
