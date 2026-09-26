package com.tyllad.dkiosk

import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.graphics.Color
import android.net.ConnectivityManager
import android.net.Network
import android.os.BatteryManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.WindowManager
import android.webkit.CookieManager
import android.webkit.HttpAuthHandler
import android.webkit.SslErrorHandler
import android.webkit.WebView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
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
import com.tyllad.dkiosk.remote.ApiServer
import com.tyllad.dkiosk.remote.KioskApi
import com.tyllad.dkiosk.remote.KioskControl
import com.tyllad.dkiosk.screen.PixelShift
import com.tyllad.dkiosk.screen.ScreenDimmer
import com.tyllad.dkiosk.settings.PinPrompt
import com.tyllad.dkiosk.settings.SettingsActivity
import com.tyllad.dkiosk.settings.TapSequence
import com.tyllad.dkiosk.setup.SetupActivity
import com.tyllad.dkiosk.web.CertificatePins
import com.tyllad.dkiosk.web.KioskChromeClient
import com.tyllad.dkiosk.web.KioskWebViewClient
import com.tyllad.dkiosk.web.NavigationPolicy
import com.tyllad.dkiosk.web.PagePrompts
import com.tyllad.dkiosk.web.Verdict
import com.tyllad.dkiosk.web.hostOf
import com.tyllad.dkiosk.web.isSamePage
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit

class KioskActivity : AppCompatActivity(), KioskWebViewClient.Callbacks {

    private lateinit var binding: ActivityKioskBinding
    private lateinit var prefs: KioskPrefs
    private lateinit var webView: WebView
    private lateinit var prompts: PagePrompts
    private lateinit var chromeClient: KioskChromeClient
    private lateinit var pinPrompt: PinPrompt
    private lateinit var errorScreen: ErrorScreen
    private lateinit var watchdog: Watchdog
    private lateinit var idleTimer: IdleTimer
    private lateinit var dimmer: ScreenDimmer
    private lateinit var pixelShift: PixelShift

    private val handler = Handler(Looper.getMainLooper())
    private val settingsTaps = TapSequence()
    private val pageBackoff = Backoff()
    private val rendererBackoff = Backoff()
    private var lastRendererLossAt = 0L
    private var loadingUrl: String? = null
    private var failedUrl: String? = null
    private var failure: String? = null
    private var loadedHomeUrl: String? = null
    private var appliedUserAgent: String? = null
    private var swallowGesture = false
    private var apiServer: ApiServer? = null
    private var apiServerToken: String? = null

    private val homeUrl: String
        get() = prefs.homeUrl.orEmpty()

    private val openSettings = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.data?.getBooleanExtra(SettingsActivity.EXTRA_RELOAD, false) == true) loadHome()
    }

    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            runOnUiThread { if (errorScreen.isShowing) errorScreen.retryNow() }
        }
    }

    // The loading page stays underneath; if it does finish before the retry, onPageLoaded hides this.
    private val loadTimeout = Runnable {
        onPageFailed(loadingUrl ?: homeUrl, getString(R.string.error_timeout))
    }

    private val periodicReload = object : Runnable {
        override fun run() {
            if (SystemClock.uptimeMillis() - idleTimer.lastTouchAt < 60_000) {
                // Someone is using the screen; don't reload under their finger.
                handler.postDelayed(this, 60_000)
                return
            }
            if (!errorScreen.isShowing) reloadPage()
            scheduleReload()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = KioskPrefs(this)
        if (!prefs.isSetUp) {
            startActivity(Intent(this, SetupActivity::class.java))
            finish()
            return
        }

        binding = ActivityKioskBinding.inflate(layoutInflater)
        setContentView(binding.root)

        if (applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0) {
            // Lets debug builds be inspected and scripted from chrome://inspect.
            WebView.setWebContentsDebuggingEnabled(true)
        }

        pinPrompt = PinPrompt(this, prefs)
        val certPins = CertificatePins(load = { prefs.trustedCerts }, save = { prefs.trustedCerts = it })
        prompts = PagePrompts(this, certPins, ::navigationPolicy, pinPrompt::ask)
        chromeClient = KioskChromeClient(this)
        errorScreen = ErrorScreen(binding.errorScreen) { load(failedUrl ?: homeUrl) }
        watchdog = Watchdog({ webView }, { chromeClient.isDialogOpen }, ::onPageUnresponsive)
        idleTimer = IdleTimer(::onIdle)
        dimmer = ScreenDimmer(binding.blackout, window, prefs)
        pixelShift = PixelShift(binding.webContainer)
        webView = createWebView()
        appliedUserAgent = prefs.userAgent

        padForKeyboard(binding.root)
        enterImmersiveMode()
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                // Back walks page history only; on the first page it does nothing.
                if (webView.canGoBack()) webView.goBack()
            }
        })

        loadHome()
        applySettings()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        // In home-screen mode the Home button lands here: treat it as "back to the home page".
        if (intent.hasCategory(Intent.CATEGORY_HOME)) loadHome()
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
        dimmer.start()
    }

    override fun onPause() {
        webView.onPause()
        watchdog.stop()
        dimmer.stop()
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
        apiServer?.stop()
        if (::webView.isInitialized) {
            idleTimer.stop()
            watchdog.stop()
            dimmer.stop()
            pixelShift.setEnabled(false)
            binding.webContainer.removeView(webView)
            webView.destroy()
        }
        super.onDestroy()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        // Dialogs, the keyboard and edge swipes can bring the system bars back; hide them again.
        if (hasFocus) enterImmersiveMode()
    }

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_DOWN) {
            idleTimer.onTouch()
            // The touch that wakes a dark screen shouldn't also press something on the page.
            swallowGesture = dimmer.isDark
            dimmer.onTouch()
            val corner = resources.getDimension(R.dimen.settings_corner)
            val inCorner = event.x > binding.root.width - corner && event.y < corner
            if (inCorner && settingsTaps.onTap(event.eventTime)) {
                pinPrompt.ask({ openSettings.launch(Intent(this, SettingsActivity::class.java)) })
            }
        }
        if (swallowGesture) return true
        // Corner taps still reach the page, so buttons up there keep working.
        return super.dispatchTouchEvent(event)
    }

    // Page callbacks

    override fun navigationPolicy() = NavigationPolicy(homeUrl, prefs.allowedHosts, prefs.restrictNavigation)

    override fun onNavigationBlocked(verdict: Verdict, pageLost: Boolean) {
        val message = when (verdict) {
            is Verdict.BlockedHost -> getString(R.string.blocked_host, verdict.host)
            is Verdict.BlockedScheme -> getString(R.string.blocked_scheme, verdict.scheme)
            Verdict.Allow -> return
        }
        if (pageLost) {
            // Typically the home page redirected to a login on another site: say which one to allow.
            val reason = when (verdict) {
                is Verdict.BlockedHost -> getString(R.string.error_redirect_blocked, verdict.host)
                else -> message
            }
            onPageFailed(loadingUrl ?: homeUrl, reason)
        } else {
            Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
        }
    }

    override fun onPageStarted(url: String) {
        loadingUrl = url
        restartLoadTimeout()
    }

    override fun onPageLoaded(url: String) {
        handler.removeCallbacks(loadTimeout)
        pageBackoff.reset()
        failedUrl = null
        failure = null
        errorScreen.hide()
    }

    override fun onPageFailed(url: String, reason: String) {
        handler.removeCallbacks(loadTimeout)
        failedUrl = url
        failure = reason
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
        loadedHomeUrl = homeUrl
        load(homeUrl)
    }

    /** Every load the kiosk starts itself is timed from the start, so a server that never answers is caught too. */
    private fun load(url: String) {
        loadingUrl = url
        restartLoadTimeout()
        webView.loadUrl(url)
    }

    private fun reloadPage() {
        loadingUrl = webView.url
        restartLoadTimeout()
        webView.reload()
    }

    private fun restartLoadTimeout() {
        handler.removeCallbacks(loadTimeout)
        handler.postDelayed(loadTimeout, LOAD_TIMEOUT_MS)
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
        view.setBackgroundColor(Color.BLACK)
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
        view.webChromeClient = chromeClient
        view.setDownloadListener { _, _, _, _, _ ->
            Toast.makeText(this, R.string.download_blocked, Toast.LENGTH_SHORT).show()
        }
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
        if (prefs.homeUrl != loadedHomeUrl) {
            loadHome()
        } else if (prefs.userAgent != appliedUserAgent) {
            // A new user agent only takes effect on the next load.
            reloadPage()
        }
        appliedUserAgent = prefs.userAgent

        if (prefs.keepScreenOn) {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
        idleTimer.timeoutMs = prefs.idleHomeMinutes * 60_000L
        pixelShift.setEnabled(prefs.burnInShift)
        scheduleReload()
        applyApiSettings()
    }

    private fun applyApiSettings() {
        if (prefs.apiEnabled && prefs.apiToken == null) prefs.apiToken = KioskApi.newToken()
        val token = prefs.apiToken?.takeIf { prefs.apiEnabled }

        val running = apiServer
        if (running != null && (token != apiServerToken || running.port != prefs.apiPort)) {
            running.stop()
            apiServer = null
        }
        if (token == null || apiServer != null) return

        val server = ApiServer(prefs.apiPort, KioskApi(token, remoteControl))
        try {
            server.start()
            apiServer = server
            apiServerToken = token
        } catch (e: IOException) {
            Log.w(TAG, "Can't start the API on port ${prefs.apiPort}", e)
            Toast.makeText(this, getString(R.string.api_port_busy, prefs.apiPort), Toast.LENGTH_LONG).show()
        }
    }

    // Remote control

    private val remoteControl = object : KioskControl {
        override fun status() = onMainThread {
            val battery = getSystemService(BatteryManager::class.java)
            JSONObject()
                .put("url", webView.url)
                .put("title", webView.title)
                .put("homeUrl", homeUrl)
                .put("screen", if (dimmer.isDark) "off" else "on")
                .put("error", failure)
                .put("battery", battery.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY))
                .put("charging", battery.isCharging)
                .put("version", packageManager.getPackageInfo(packageName, 0).versionName)
        }

        override fun reload() = onMainThread { reloadPage() }

        override fun goHome() = onMainThread { loadHome() }

        override fun open(url: String, makeHome: Boolean): String? = onMainThread {
            val host = hostOf(url).orEmpty()
            when {
                makeHome -> {
                    prefs.homeUrl = url
                    loadHome()
                    null
                }
                !navigationPolicy().allowsHost(host) -> getString(R.string.blocked_host, host)
                else -> {
                    load(url)
                    null
                }
            }
        }

        override fun setScreen(on: Boolean) = onMainThread { dimmer.turn(on) }
    }

    /** API requests arrive on worker threads; this runs [block] on the main thread and waits for it. */
    private fun <T> onMainThread(block: () -> T): T {
        val task = FutureTask(block)
        handler.post(task)
        return task.get(5, TimeUnit.SECONDS)
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

    private companion object {
        const val TAG = "KioskActivity"
        const val LOAD_TIMEOUT_MS = 60_000L
    }
}
