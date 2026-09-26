package com.tyllad.dkiosk

import android.annotation.SuppressLint
import android.content.pm.ApplicationInfo
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.text.InputType
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.WindowManager
import android.webkit.CookieManager
import android.webkit.SslErrorHandler
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.core.view.updatePadding
import com.tyllad.dkiosk.config.KioskPrefs
import com.tyllad.dkiosk.web.CertificatePins
import com.tyllad.dkiosk.web.KioskWebViewClient
import com.tyllad.dkiosk.web.NavigationPolicy
import com.tyllad.dkiosk.web.Verdict
import com.tyllad.dkiosk.web.normalizeHomeUrl

class KioskActivity : AppCompatActivity(), KioskWebViewClient.Callbacks {

    private lateinit var prefs: KioskPrefs
    private lateinit var webView: WebView
    private lateinit var certPins: CertificatePins

    /** SSL errors waiting on an open trust dialog, keyed by "host|fingerprint". */
    private val pendingCertDecisions = mutableMapOf<String, MutableList<SslErrorHandler>>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = KioskPrefs(this)
        certPins = CertificatePins(load = { prefs.trustedCerts }, save = { prefs.trustedCerts = it })

        if (applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0) {
            // Lets debug builds be inspected and scripted from chrome://inspect.
            WebView.setWebContentsDebuggingEnabled(true)
        }
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        val root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        webView = WebView(this).also(::configureWebView)
        root.addView(webView, MATCH_PARENT, MATCH_PARENT)
        setContentView(root)

        padForKeyboard(root)
        enterImmersiveMode()

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                // Back walks page history only; on the first page it does nothing.
                if (webView.canGoBack()) webView.goBack()
            }
        })

        val homeUrl = prefs.homeUrl
        if (homeUrl == null) promptForHomeUrl() else webView.loadUrl(homeUrl)
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        // Dialogs, the keyboard and edge swipes can bring the system bars back; hide them again.
        if (hasFocus) enterImmersiveMode()
    }

    override fun onResume() {
        super.onResume()
        webView.onResume()
    }

    override fun onPause() {
        webView.onPause()
        // Write cookies to disk now so a login survives the process being killed in the background.
        CookieManager.getInstance().flush()
        super.onPause()
    }

    override fun onDestroy() {
        (webView.parent as? FrameLayout)?.removeView(webView)
        webView.destroy()
        super.onDestroy()
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun configureWebView(view: WebView) {
        view.setBackgroundColor(Color.BLACK)
        view.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            // Lay out pages the way Chrome does, including ones without a viewport meta tag.
            useWideViewPort = true
            loadWithOverviewMode = true
            setSupportZoom(prefs.allowZoom)
            builtInZoomControls = prefs.allowZoom
            displayZoomControls = false
            userAgentString = prefs.userAgent
        }
        CookieManager.getInstance().apply {
            setAcceptCookie(true)
            setAcceptThirdPartyCookies(view, true)
        }
        view.webViewClient = KioskWebViewClient(this)
        view.webChromeClient = WebChromeClient()
    }

    override fun navigationPolicy() =
        NavigationPolicy(prefs.homeUrl.orEmpty(), prefs.allowedHosts, prefs.restrictNavigation)

    override fun onNavigationBlocked(verdict: Verdict) {
        val message = when (verdict) {
            is Verdict.BlockedHost -> getString(R.string.blocked_host, verdict.host)
            is Verdict.BlockedScheme -> getString(R.string.blocked_scheme, verdict.scheme)
            Verdict.Allow -> return
        }
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    override fun onUntrustedCertificate(host: String, fingerprint: String, handler: SslErrorHandler) {
        when {
            certPins.isPinned(host, fingerprint) -> handler.proceed()
            // Only the kiosk's own sites may ask; a third-party resource with a bad cert just fails.
            !navigationPolicy().allowsHost(host) -> handler.cancel()
            else -> askToTrustCertificate(host, fingerprint, handler)
        }
    }

    private fun askToTrustCertificate(host: String, fingerprint: String, handler: SslErrorHandler) {
        val key = "$host|$fingerprint"
        // Several resources can fail on the same cert at once; they share one dialog.
        pendingCertDecisions[key]?.let {
            it += handler
            return
        }
        pendingCertDecisions[key] = mutableListOf(handler)

        val changed = certPins.hasPinFor(host)
        AlertDialog.Builder(this)
            .setTitle(if (changed) R.string.cert_changed_title else R.string.cert_untrusted_title)
            .setMessage(
                getString(
                    if (changed) R.string.cert_changed_message else R.string.cert_untrusted_message,
                    host,
                    fingerprint,
                ),
            )
            .setCancelable(false)
            .setPositiveButton(R.string.cert_trust) { _, _ ->
                certPins.pin(host, fingerprint)
                pendingCertDecisions.remove(key)?.forEach { it.proceed() }
            }
            .setNegativeButton(R.string.cert_cancel) { _, _ ->
                pendingCertDecisions.remove(key)?.forEach { it.cancel() }
            }
            .show()
    }

    private fun enterImmersiveMode() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            // Draw into the notch area too, so no black strip is left at the top.
            window.attributes = window.attributes.apply {
                layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }
        WindowCompat.getInsetsController(window, window.decorView).apply {
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            hide(WindowInsetsCompat.Type.systemBars())
        }
    }

    /**
     * Edge-to-edge windows aren't resized for the keyboard (adjustResize is a no-op), so pad the
     * bottom by the keyboard height ourselves to keep focused inputs, like login forms, visible.
     */
    private fun padForKeyboard(root: View) {
        ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            view.updatePadding(bottom = insets.getInsets(WindowInsetsCompat.Type.ime()).bottom)
            insets
        }
    }

    private fun promptForHomeUrl() {
        val input = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            setHint(R.string.setup_url_hint)
            setText(R.string.setup_url_prefix)
            setSelection(text.length)
        }
        val padding = (20 * resources.displayMetrics.density).toInt()
        val container = FrameLayout(this).apply {
            setPadding(padding, padding / 2, padding, 0)
            addView(input)
        }

        val dialog = AlertDialog.Builder(this)
            .setTitle(R.string.setup_title)
            .setMessage(R.string.setup_message)
            .setView(container)
            .setCancelable(false)
            .setPositiveButton(R.string.setup_save, null)
            .create()

        // Wire the button after show() so an invalid URL keeps the dialog open.
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val url = normalizeHomeUrl(input.text.toString())
                if (url == null) {
                    input.error = getString(R.string.setup_invalid_url)
                    return@setOnClickListener
                }
                prefs.homeUrl = url
                dialog.dismiss()
                webView.loadUrl(url)
            }
        }
        dialog.show()
    }
}
