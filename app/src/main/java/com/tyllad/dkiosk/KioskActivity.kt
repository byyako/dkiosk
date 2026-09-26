package com.tyllad.dkiosk

import android.annotation.SuppressLint
import android.content.pm.ApplicationInfo
import android.net.ConnectivityManager
import android.net.Network
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.text.InputType
import android.util.Log
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.WindowManager
import android.webkit.CookieManager
import android.webkit.HttpAuthHandler
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
import androidx.lifecycle.Lifecycle
import com.tyllad.dkiosk.config.KioskPrefs
import com.tyllad.dkiosk.databinding.ActivityKioskBinding
import com.tyllad.dkiosk.recovery.Backoff
import com.tyllad.dkiosk.recovery.ErrorScreen
import com.tyllad.dkiosk.recovery.IdleTimer
import com.tyllad.dkiosk.recovery.Watchdog
import com.tyllad.dkiosk.web.CertificatePins
import com.tyllad.dkiosk.web.KioskWebViewClient
import com.tyllad.dkiosk.web.NavigationPolicy
import com.tyllad.dkiosk.web.PagePrompts
import com.tyllad.dkiosk.web.Verdict
import com.tyllad.dkiosk.web.hostOf
import com.tyllad.dkiosk.web.isSamePage
import com.tyllad.dkiosk.web.normalizeHomeUrl

class KioskActivity : AppCompatActivity(), KioskWebViewClient.Callbacks {

    private lateinit var binding: ActivityKioskBinding
    private lateinit var prefs: KioskPrefs
    private lateinit var webView: WebView
    private lateinit var prompts: PagePrompts
    private lateinit var errorScreen: ErrorScreen
    private lateinit var watchdog: Watchdog
    private lateinit var idleTimer: IdleTimer

    private val handler = Handler(Looper.getMainLooper())
    private val pageBackoff = Backoff()
    private val rendererBackoff = Backoff()
    private var lastRendererLossAt = 0L
    private var failedUrl: String? = null
    private var appliedUserAgent: String? = null

    private val homeUrl: String
        get() = prefs.homeUrl.orEmpty()

    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            runOnUiThread { if (errorScreen.isShowing) errorScreen.retryNow() }
        }
    }

    // The loading page stays underneath; if it does finish before the retry, onPageLoaded hides this.
    private val loadTimeout = Runnable {
        onPageFailed(webView.url ?: homeUrl, getString(R.string.error_timeout))
    }

    private val periodicReload = object : Runnable {
        override fun run() {
            if (SystemClock.uptimeMillis() - idleTimer.lastTouchAt < 60_000) {
                // Someone is using the screen; don't reload under their finger.
                handler.postDelayed(this, 60_000)
                return
            }
            if (!errorScreen.isShowing) webView.reload()
            scheduleReload()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = KioskPrefs(this)
        binding = ActivityKioskBinding.inflate(layoutInflater)
        setContentView(binding.root)

        if (applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0) {
            // Lets debug builds be inspected and scripted from chrome://inspect.
            WebView.setWebContentsDebuggingEnabled(true)
        }

        val certPins = CertificatePins(load = { prefs.trustedCerts }, save = { prefs.trustedCerts = it })
        prompts = PagePrompts(this, certPins, ::navigationPolicy)
        errorScreen = ErrorScreen(binding.errorScreen) { webView.loadUrl(failedUrl ?: homeUrl) }
        watchdog = Watchdog({ webView }, ::onPageUnresponsive)
        idleTimer = IdleTimer(::onIdle)
        webView = createWebView()

        padForKeyboard(binding.root)
        enterImmersiveMode()
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                // Back walks page history only; on the first page it does nothing.
                if (webView.canGoBack()) webView.goBack()
            }
        })

        applySettings()
        if (prefs.homeUrl == null) promptForHomeUrl() else loadHome()
    }

    override fun onStart() {
        super.onStart()
        getSystemService(ConnectivityManager::class.java).registerDefaultNetworkCallback(networkCallback)
    }

    override fun onResume() {
        super.onResume()
        webView.onResume()
        watchdog.start()
        applySettings()
    }

    override fun onPause() {
        webView.onPause()
        watchdog.stop()
        // Write cookies to disk now so a login survives the process being killed in the background.
        CookieManager.getInstance().flush()
        super.onPause()
    }

    override fun onStop() {
        getSystemService(ConnectivityManager::class.java).unregisterNetworkCallback(networkCallback)
        super.onStop()
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        idleTimer.stop()
        binding.webContainer.removeView(webView)
        webView.destroy()
        super.onDestroy()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        // Dialogs, the keyboard and edge swipes can bring the system bars back; hide them again.
        if (hasFocus) enterImmersiveMode()
    }

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_DOWN) idleTimer.onTouch()
        return super.dispatchTouchEvent(event)
    }

    // Page callbacks

    override fun navigationPolicy() = NavigationPolicy(homeUrl, prefs.allowedHosts, prefs.restrictNavigation)

    override fun onNavigationBlocked(verdict: Verdict) {
        val message = when (verdict) {
            is Verdict.BlockedHost -> getString(R.string.blocked_host, verdict.host)
            is Verdict.BlockedScheme -> getString(R.string.blocked_scheme, verdict.scheme)
            Verdict.Allow -> return
        }
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    override fun onPageStarted(url: String) {
        handler.removeCallbacks(loadTimeout)
        handler.postDelayed(loadTimeout, LOAD_TIMEOUT_MS)
    }

    override fun onPageLoaded(url: String) {
        handler.removeCallbacks(loadTimeout)
        pageBackoff.reset()
        failedUrl = null
        errorScreen.hide()
    }

    override fun onPageFailed(url: String, reason: String) {
        handler.removeCallbacks(loadTimeout)
        failedUrl = url
        errorScreen.show(hostOf(url) ?: url, reason, pageBackoff.nextDelayMs())
    }

    override fun onUntrustedCertificate(host: String, fingerprint: String, handler: SslErrorHandler) =
        prompts.onUntrustedCertificate(host, fingerprint, handler)

    override fun onLoginRequest(host: String, realm: String, handler: HttpAuthHandler) =
        prompts.onLoginRequest(host, realm, handler)

    override fun onRendererGone(crashed: Boolean) {
        Log.w(TAG, "WebView renderer gone (crashed=$crashed), replacing the WebView")
        val now = SystemClock.uptimeMillis()
        // A page that kills the renderer right after loading would otherwise restart in a tight loop.
        val delay = if (now - lastRendererLossAt < 60_000) rendererBackoff.nextDelayMs() else 0L
        if (delay == 0L) rendererBackoff.reset()
        lastRendererLossAt = now

        replaceWebView()
        handler.postDelayed(::loadHome, delay)
    }

    private fun onPageUnresponsive() {
        Log.w(TAG, "Page stopped responding")
        // Killing the renderer leads to onRendererGone, which rebuilds everything.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && webView.webViewRenderProcess?.terminate() == true) return
        replaceWebView()
        loadHome()
    }

    private fun onIdle() {
        if (!isSamePage(webView.url, homeUrl)) loadHome()
    }

    // WebView

    private fun loadHome() {
        if (homeUrl.isNotEmpty()) webView.loadUrl(homeUrl)
    }

    private fun createWebView(): WebView {
        val view = WebView(this)
        configureWebView(view)
        binding.webContainer.addView(view, MATCH_PARENT, MATCH_PARENT)
        return view
    }

    private fun replaceWebView() {
        handler.removeCallbacks(loadTimeout)
        watchdog.stop()
        binding.webContainer.removeView(webView)
        webView.destroy()
        webView = createWebView()
        if (lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) watchdog.start()
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun configureWebView(view: WebView) {
        view.setBackgroundColor(android.graphics.Color.BLACK)
        view.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            // Lay out pages the way Chrome does, including ones without a viewport meta tag.
            useWideViewPort = true
            loadWithOverviewMode = true
            displayZoomControls = false
        }
        applyWebSettings(view)
        CookieManager.getInstance().apply {
            setAcceptCookie(true)
            setAcceptThirdPartyCookies(view, true)
        }
        view.webViewClient = KioskWebViewClient(this)
        view.webChromeClient = WebChromeClient()
    }

    private fun applyWebSettings(view: WebView) {
        view.settings.apply {
            setSupportZoom(prefs.allowZoom)
            builtInZoomControls = prefs.allowZoom
            userAgentString = prefs.userAgent
        }
    }

    // Settings

    private fun applySettings() {
        applyWebSettings(webView)
        if (prefs.userAgent != appliedUserAgent) {
            // The new user agent only takes effect on the next load.
            if (webView.url != null) webView.reload()
            appliedUserAgent = prefs.userAgent
        }

        if (prefs.keepScreenOn) {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
        idleTimer.timeoutMs = prefs.idleHomeMinutes * 60_000L
        scheduleReload()
    }

    private fun scheduleReload() {
        handler.removeCallbacks(periodicReload)
        if (prefs.reloadMinutes > 0) handler.postDelayed(periodicReload, prefs.reloadMinutes * 60_000L)
    }

    // Window

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
                loadHome()
            }
        }
        dialog.show()
    }

    private companion object {
        const val TAG = "KioskActivity"
        const val LOAD_TIMEOUT_MS = 60_000L
    }
}
