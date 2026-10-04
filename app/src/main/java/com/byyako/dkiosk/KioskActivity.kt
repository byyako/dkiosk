package com.byyako.dkiosk

import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.graphics.Color
import android.graphics.Rect
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
import android.view.ViewConfiguration
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.WindowManager
import android.webkit.CookieManager
import android.webkit.HttpAuthHandler
import android.webkit.SslErrorHandler
import android.webkit.WebView
import android.widget.Toast
import android.widget.FrameLayout
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.core.view.updatePadding
import androidx.lifecycle.Lifecycle
import com.byyako.dkiosk.config.KioskPrefs
import com.byyako.dkiosk.databinding.ActivityKioskBinding
import com.byyako.dkiosk.lockdown.Lockdown
import com.byyako.dkiosk.recovery.Backoff
import com.byyako.dkiosk.recovery.ErrorScreen
import com.byyako.dkiosk.recovery.IdleTimer
import com.byyako.dkiosk.recovery.Watchdog
import com.byyako.dkiosk.remote.ApiServer
import com.byyako.dkiosk.remote.KioskApi
import com.byyako.dkiosk.remote.KioskControl
import com.byyako.dkiosk.screen.PixelShift
import com.byyako.dkiosk.screen.ScreenDimmer
import com.byyako.dkiosk.settings.PinPrompt
import com.byyako.dkiosk.settings.HomeApp
import com.byyako.dkiosk.settings.CornerTapGesture
import com.byyako.dkiosk.settings.SettingsActivity
import com.byyako.dkiosk.setup.SetupActivity
import com.byyako.dkiosk.web.CertificatePins
import com.byyako.dkiosk.web.KioskChromeClient
import com.byyako.dkiosk.web.KioskWebViewClient
import com.byyako.dkiosk.web.NavigationPolicy
import com.byyako.dkiosk.web.PagePrompts
import com.byyako.dkiosk.web.Verdict
import com.byyako.dkiosk.web.hostOf
import com.byyako.dkiosk.web.isSamePage
import com.byyako.dkiosk.web.isSameRoute
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
    private val cornerGesture by lazy { CornerTapGesture(ViewConfiguration.get(this).scaledTouchSlop.toFloat()) }
    private val cornerBounds = Rect()
    private val cornerLocation = IntArray(2)
    private var restoredLockTaskStart = false
    private val lockdown by lazy { Lockdown(this, restoredLockTaskStart) }
    private var cornerCaptured = false
    private var lockdownWarningShown = false
    private var dashboardActive = false
    private var networkRegistered = false
    private var destroyed = false
    private val clearCornerProgress = Runnable {
        cornerGesture.reset()
        binding.adminCorner.text = ""
        binding.adminCorner.setBackgroundColor(Color.TRANSPARENT)
    }
    private val pageBackoff = Backoff()
    private val rendererBackoff = Backoff()
    private var lastRendererLossAt = 0L
    private var loadingUrl: String? = null

    // A load the kiosk started itself (home, retry, reload, API) that hasn't reached a page yet.
    private var kioskLoad: String? = null

    // WebView reports a load that was dropped before the server answered (replaced by a retry, or
    // blocked) as finished. Only a finish after a page has actually started counts as loaded.
    private var pageStarted = false
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
        if (result.data?.getBooleanExtra(SettingsActivity.EXTRA_EXIT, false) == true) {
            exitKiosk()
        } else if (result.data?.getBooleanExtra(SettingsActivity.EXTRA_RELOAD, false) == true) loadHome()
    }

    // Registering the callback reports the network that's already up; only one that comes back counts.
    @Volatile private var offline = false

    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            if (!offline) return
            offline = false
            runOnUiThread { if (!destroyed && ::errorScreen.isInitialized && errorScreen.isShowing) errorScreen.retryNow() }
        }

        override fun onLost(network: Network) {
            offline = true
        }
    }

    // The loading page stays underneath; if it does finish before the retry, onPageLoaded hides this.
    private val loadTimeout = Runnable {
        onPageFailed(loadingUrl ?: homeUrl, getString(R.string.error_timeout))
    }
    private val rendererRetry = Runnable { loadHome() }

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
        restoredLockTaskStart = savedInstanceState?.getBoolean(LOCK_TASK_STARTED, false) == true
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
        chromeClient = KioskChromeClient(this) { candidate ->
            dashboardActive && !destroyed && ::webView.isInitialized && webView === candidate
        }
        errorScreen = ErrorScreen(binding.errorScreen) { load(failedUrl ?: homeUrl) }
        watchdog = Watchdog({ webView }, { chromeClient.isDialogOpen }, ::onPageUnresponsive)
        idleTimer = IdleTimer(::onIdle)
        dimmer = ScreenDimmer(binding.blackout, window, prefs)
        pixelShift = PixelShift(binding.webContainer)
        binding.adminCorner.setOnClickListener { requestSettings() }
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
        if (::webView.isInitialized && intent.hasCategory(Intent.CATEGORY_HOME)) loadHome()
    }

    override fun onStart() {
        super.onStart()
        if (!::webView.isInitialized) return
        val connectivity = getSystemService(ConnectivityManager::class.java)
        offline = connectivity.activeNetwork == null
        connectivity.registerDefaultNetworkCallback(networkCallback)
        networkRegistered = true
    }

    override fun onResume() {
        super.onResume()
        if (!::webView.isInitialized || isFinishing) return
        dashboardActive = true
        webView.onResume()
        watchdog.start()
        applySettings()
        dimmer.start()
        applyApiSettings()
        try {
            if (prefs.lockdownEnabled) {
                if (!lockdown.start(this) && !lockdownWarningShown) {
                    Toast.makeText(this, R.string.lockdown_failed, Toast.LENGTH_LONG).show()
                    lockdownWarningShown = true
                }
            } else if (!lockdown.stop(this) && !lockdownWarningShown) {
                Toast.makeText(this, R.string.lockdown_exit_failed, Toast.LENGTH_LONG).show()
                lockdownWarningShown = true
            }
        } catch (e: SecurityException) {
            Log.w(TAG, "Device policy refused lockdown", e)
            Toast.makeText(this, R.string.lockdown_failed, Toast.LENGTH_LONG).show()
        }
    }

    override fun onPause() {
        dashboardActive = false
        apiServer?.stop()
        apiServer = null
        if (::webView.isInitialized) {
            resetCornerGesture()
            pinPrompt.dismiss()
            prompts.dismiss()
            chromeClient.dismiss()
            webView.onPause()
            watchdog.stop()
            dimmer.stop()
        }
        // Write cookies to disk now so a login survives the process being killed in the background.
        CookieManager.getInstance().flush()
        super.onPause()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        if (::webView.isInitialized) outState.putBoolean(LOCK_TASK_STARTED, lockdown.startedHere)
        super.onSaveInstanceState(outState)
    }

    override fun onStop() {
        if (networkRegistered) {
            getSystemService(ConnectivityManager::class.java).unregisterNetworkCallback(networkCallback)
            networkRegistered = false
        }
        super.onStop()
    }

    override fun onDestroy() {
        destroyed = true
        handler.removeCallbacksAndMessages(null)
        apiServer?.stop()
        if (::webView.isInitialized) {
            errorScreen.hide()
            pinPrompt.dismiss()
            prompts.dismiss()
            chromeClient.dismiss()
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
        if (!::webView.isInitialized) return super.dispatchTouchEvent(event)
        if (event.actionMasked == MotionEvent.ACTION_DOWN) {
            idleTimer.onTouch()
            // The touch that wakes a dark screen shouldn't also press something on the page.
            swallowGesture = dimmer.isDark
            dimmer.onTouch()
            binding.adminCorner.getLocationOnScreen(cornerLocation)
            cornerBounds.set(
                cornerLocation[0], cornerLocation[1],
                cornerLocation[0] + binding.adminCorner.width, cornerLocation[1] + binding.adminCorner.height,
            )
            cornerCaptured = !swallowGesture && cornerBounds.contains(event.rawX.toInt(), event.rawY.toInt())
            if (cornerCaptured) {
                cornerGesture.down(event.rawX, event.rawY, event.eventTime)
            } else resetCornerGesture()
        }
        if (swallowGesture) return true
        if (cornerCaptured) {
            when (event.actionMasked) {
                MotionEvent.ACTION_MOVE -> cornerGesture.move(event.rawX, event.rawY)
                MotionEvent.ACTION_POINTER_DOWN, MotionEvent.ACTION_CANCEL -> resetCornerGesture()
                MotionEvent.ACTION_UP -> {
                    val result = cornerGesture.up(event.rawX, event.rawY, event.eventTime)
                    cornerCaptured = false
                    handler.removeCallbacks(clearCornerProgress)
                    if (result.complete) {
                        requestSettings()
                    } else if (result.progress > 0) {
                        binding.adminCorner.text = getString(R.string.admin_tap_progress, result.progress)
                        binding.adminCorner.setBackgroundColor(0xCC000000.toInt())
                        handler.postDelayed(clearCornerProgress, cornerGesture.windowMs)
                    } else resetCornerGesture()
                }
            }
            return true
        }
        return super.dispatchTouchEvent(event)
    }

    private fun resetCornerGesture() {
        handler.removeCallbacks(clearCornerProgress)
        clearCornerProgress.run()
    }

    private fun requestSettings() {
        resetCornerGesture()
        pinPrompt.ask({ openSettings.launch(Intent(this, SettingsActivity::class.java)) })
    }

    private fun exitKiosk() {
        try {
            if (!lockdown.stop(this)) {
                Toast.makeText(this, R.string.lockdown_exit_failed, Toast.LENGTH_LONG).show()
                return
            }
        } catch (e: SecurityException) {
            Log.w(TAG, "Device policy refused exit", e)
            Toast.makeText(this, R.string.lockdown_exit_failed, Toast.LENGTH_LONG).show()
            return
        }
        prefs.lockdownEnabled = false
        if (HomeApp.isDefault(this)) {
            Toast.makeText(this, R.string.exit_pick_home_app, Toast.LENGTH_LONG).show()
            HomeApp.openHomeSettings(this)
        } else finishAffinity()
    }

    // Page callbacks

    override fun navigationPolicy() = NavigationPolicy(homeUrl, prefs.allowedHosts, prefs.restrictNavigation)

    override fun onNavigationBlocked(verdict: Verdict, pageLost: Boolean): Boolean {
        val message = when (verdict) {
            is Verdict.BlockedHost -> getString(R.string.blocked_host, verdict.host)
            is Verdict.BlockedScheme -> getString(R.string.blocked_scheme, verdict.scheme)
            Verdict.Allow -> return false
        }
        val failedLoad = kioskLoad
        if (!pageLost && failedLoad == null) {
            Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
            return false
        }
        // The kiosk's own load went somewhere it may not go, typically the home page redirecting to a
        // login on another site: say which one to allow rather than leave a blank or stale page.
        val reason = when (verdict) {
            is Verdict.BlockedHost -> getString(R.string.error_redirect_blocked, verdict.host)
            else -> message
        }
        onPageFailed(failedLoad ?: loadingUrl ?: homeUrl, reason)
        return true
    }

    override fun onPageStarted(url: String) {
        kioskLoad = null
        pageStarted = true
        loadingUrl = url
        restartLoadTimeout()
    }

    override fun onPageLoaded(url: String) {
        if (!pageStarted || !isSamePage(loadingUrl, url)) return
        pageStarted = false
        handler.removeCallbacks(loadTimeout)
        pageBackoff.reset()
        failedUrl = null
        failure = null
        errorScreen.hide()
    }

    override fun onPageFailed(url: String, reason: String) {
        if (!isSamePage(loadingUrl, url)) return
        handler.removeCallbacks(loadTimeout)
        kioskLoad = null
        failedUrl = url
        failure = reason
        errorScreen.show(hostOf(url) ?: url, reason, pageBackoff.nextDelayMs())
    }

    override fun onUntrustedCertificate(host: String, fingerprint: String, handler: SslErrorHandler) {
        if (dashboardActive) prompts.onUntrustedCertificate(host, fingerprint, handler) else handler.cancel()
    }

    override fun onLoginRequest(host: String, realm: String, handler: HttpAuthHandler) {
        if (dashboardActive) prompts.onLoginRequest(host, realm, handler) else handler.cancel()
    }

    override fun onRendererGone(crashed: Boolean) {
        Log.w(TAG, "WebView renderer gone (crashed=$crashed), replacing the WebView")
        val now = SystemClock.uptimeMillis()
        // A page that kills the renderer right after loading would otherwise restart in a tight loop.
        val delay = if (now - lastRendererLossAt < 60_000) rendererBackoff.nextDelayMs() else 0L
        if (delay == 0L) rendererBackoff.reset()
        lastRendererLossAt = now

        replaceWebView()
        handler.postDelayed(rendererRetry, delay)
    }

    private fun onPageUnresponsive() {
        Log.w(TAG, "Page stopped responding")
        // Killing the renderer leads to onRendererGone, which rebuilds everything.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && webView.webViewRenderProcess?.terminate() == true) return
        replaceWebView()
        loadHome()
    }

    private fun onIdle() {
        if (!isSameRoute(webView.url, homeUrl)) loadHome()
    }

    // WebView

    private fun loadHome() {
        loadedHomeUrl = homeUrl
        load(homeUrl)
    }

    /** Every load the kiosk starts itself is timed from the start, so a server that never answers is caught too. */
    private fun load(url: String) {
        handler.removeCallbacks(rendererRetry)
        // The current page with another #fragment just scrolls or switches a hash route: no new page
        // starts, but its finish still counts.
        val current = webView.copyBackForwardList().currentItem?.url
        val sameDocument = '#' in url && current?.substringBefore('#') == url.substringBefore('#')
        startKioskLoad(url, sameDocument)
        webView.loadUrl(url)
    }

    private fun reloadPage() {
        handler.removeCallbacks(rendererRetry)
        startKioskLoad(webView.url, sameDocument = false)
        webView.reload()
    }

    private fun startKioskLoad(url: String?, sameDocument: Boolean) {
        kioskLoad = url.takeUnless { sameDocument }
        pageStarted = sameDocument
        loadingUrl = url
        restartLoadTimeout()
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
        handler.removeCallbacks(rendererRetry)
        errorScreen.hide()
        watchdog.stop()
        pinPrompt.dismiss()
        prompts.dismiss()
        chromeClient.dismiss()
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
        view.webViewClient = KioskWebViewClient(this) { candidate ->
            !destroyed && ::webView.isInitialized && webView === candidate
        }
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
        val task = FutureTask {
            check(dashboardActive && !destroyed && !isFinishing) { "Dashboard is not active" }
            block()
        }
        handler.post(task)
        try {
            return task.get(5, TimeUnit.SECONDS)
        } finally {
            // A request that times out or is interrupted must not execute later from the queue.
            task.cancel(false)
            handler.removeCallbacks(task)
        }
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
            val safe = insets.getInsets(WindowInsetsCompat.Type.displayCutout() or WindowInsetsCompat.Type.systemGestures())
            val params = binding.adminCorner.layoutParams as FrameLayout.LayoutParams
            if (params.topMargin != safe.top || params.rightMargin != safe.right) {
                params.topMargin = safe.top
                params.rightMargin = safe.right
                binding.adminCorner.layoutParams = params
            }
            insets
        }
    }

    private companion object {
        const val TAG = "KioskActivity"
        const val LOAD_TIMEOUT_MS = 60_000L
        const val LOCK_TASK_STARTED = "lock_task_started"
    }
}
