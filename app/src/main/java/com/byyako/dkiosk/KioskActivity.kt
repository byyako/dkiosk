package com.byyako.dkiosk

import android.annotation.SuppressLint
import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ApplicationInfo
import android.graphics.Color
import android.graphics.Rect
import android.media.AudioManager
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
import androidx.core.view.isVisible
import androidx.core.view.updatePadding
import androidx.lifecycle.Lifecycle
import com.byyako.dkiosk.config.KioskPrefs
import com.byyako.dkiosk.databinding.ActivityKioskBinding
import com.byyako.dkiosk.lockdown.Lockdown
import com.byyako.dkiosk.media.SoundPlayer
import com.byyako.dkiosk.media.Speaker
import com.byyako.dkiosk.recovery.Backoff
import com.byyako.dkiosk.recovery.ErrorScreen
import com.byyako.dkiosk.recovery.IdleTimer
import com.byyako.dkiosk.recovery.Watchdog
import com.byyako.dkiosk.remote.ApiServer
import com.byyako.dkiosk.remote.KioskApi
import com.byyako.dkiosk.remote.HomeAssistant
import com.byyako.dkiosk.remote.KioskControl
import com.byyako.dkiosk.remote.MqttBridge
import com.byyako.dkiosk.screen.PixelShift
import com.byyako.dkiosk.screen.CameraMotion
import com.byyako.dkiosk.screen.Inactivity
import com.byyako.dkiosk.screen.MotionDetector
import com.byyako.dkiosk.screen.ProximityWake
import com.byyako.dkiosk.screen.ScreenDimmer
import com.byyako.dkiosk.screen.Screensaver
import com.byyako.dkiosk.screen.Screenshot
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
import com.byyako.dkiosk.web.ScreensaverWebViewClient
import com.byyako.dkiosk.web.SiteFeature
import com.byyako.dkiosk.web.SitePermissions
import com.byyako.dkiosk.web.TemporaryPage
import com.byyako.dkiosk.web.Verdict
import com.byyako.dkiosk.web.hostOf
import com.byyako.dkiosk.web.isSamePage
import com.byyako.dkiosk.web.isSameRoute
import org.json.JSONObject
import java.io.IOException
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit
import kotlin.math.roundToInt

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
    private lateinit var speaker: Speaker
    private lateinit var certPins: CertificatePins
    private lateinit var screensaver: Screensaver
    private var proximity: ProximityWake? = null
    private var cameraMotion: CameraMotion? = null
    private val soundPlayer = SoundPlayer()

    private val handler = Handler(Looper.getMainLooper())
    private val cornerGesture by lazy { CornerTapGesture(ViewConfiguration.get(this).scaledTouchSlop.toFloat()) }
    private val cornerBounds = Rect()
    private var cutouts: List<Rect> = emptyList()
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
    private var mqttBridge: MqttBridge? = null
    private var lastTouchReportAt = 0L

    // Wall-clock time of the last touch, kept as is so Home Assistant sees it change only on a touch.
    private var lastTouchTime: Instant = Instant.now().truncatedTo(ChronoUnit.SECONDS)

    /** The page the kiosk returns to: the home page, or the night page during its hours. */
    private val homeUrl: String
        get() = prefs.activeHomeUrl.orEmpty()

    // Switches between the home and night pages when their hours change, once nobody is using it.
    private val homeSwitch = object : Runnable {
        override fun run() {
            val busy = SystemClock.uptimeMillis() - idleTimer.lastTouchAt < 60_000 || temporaryPage != null
            if (homeUrl != loadedHomeUrl && !busy) loadHome()
            handler.postDelayed(this, 60_000)
        }
    }

    private val openSettings = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.data?.getBooleanExtra(SettingsActivity.EXTRA_EXIT, false) == true) {
            exitKiosk()
        } else if (result.data?.getBooleanExtra(SettingsActivity.EXTRA_RELOAD, false) == true) loadHome()
    }

    // Android's camera/microphone permission, asked for when an administrator allows a page to use them.
    private var androidPermissionDone: ((Boolean) -> Unit)? = null
    private val requestAndroidPermissions =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { results ->
            val granted = results.isNotEmpty() && results.values.all { it }
            if (!granted) Toast.makeText(this, R.string.access_android_denied, Toast.LENGTH_LONG).show()
            androidPermissionDone?.invoke(granted)
            androidPermissionDone = null
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
    private val hideMessage = Runnable { binding.message.isVisible = false }

    // Touches and motion: the screensaver and the inactivity screen-off count from the last of them.
    private var lastActivityAt = SystemClock.uptimeMillis()
    private val inactivityCheck = Runnable { updateInactivity() }
    private var motionSeen = false
    private val motionOver = Runnable {
        motionSeen = false
        mqttBridge?.stateChanged()
    }

    // A page the API opened for a while, such as a doorbell camera.
    private var temporaryPage: TemporaryPage? = null
    private val endTemporaryPage = object : Runnable {
        override fun run() {
            val page = temporaryPage ?: return
            val delay = page.delayUntilReturn(SystemClock.uptimeMillis(), idleTimer.lastTouchAt)
            if (delay > 0) {
                handler.postDelayed(this, delay)
                return
            }
            temporaryPage = null
            val back = page.returnUrl
            if (back == null || isSameRoute(back, homeUrl)) loadHome() else load(back)
        }
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
        certPins = CertificatePins(load = { prefs.trustedCerts }, save = { prefs.trustedCerts = it })
        val sitePermissions = SitePermissions(load = { prefs.sitePermissions }, save = { prefs.sitePermissions = it })
        prompts = PagePrompts(this, certPins, sitePermissions, ::navigationPolicy, pinPrompt::ask, ::askAndroid)
        chromeClient = KioskChromeClient(
            this,
            isCurrent = { candidate -> dashboardActive && !destroyed && ::webView.isInitialized && webView === candidate },
            onPermission = { request, canceled ->
                when {
                    canceled -> prompts.onPermissionRequestCanceled(request)
                    dashboardActive -> prompts.onPermissionRequest(request)
                    else -> request.deny()
                }
            },
        )
        errorScreen = ErrorScreen(binding.errorScreen) { load(failedUrl ?: homeUrl) }
        watchdog = Watchdog({ webView }, { chromeClient.isDialogOpen }, ::onPageUnresponsive)
        idleTimer = IdleTimer(::onIdle)
        dimmer = ScreenDimmer(binding.blackout, window, prefs)
        dimmer.onChange = {
            // The screen lighting up or going dark changes what the camera sees; that isn't motion.
            cameraMotion?.pause(SCREEN_CHANGE_SETTLE_MS)
            mqttBridge?.stateChanged()
        }
        screensaver = Screensaver(binding.screensaver, ::createScreensaverPage)
        pixelShift = PixelShift(binding.webContainer)
        speaker = Speaker(this)
        binding.message.setOnClickListener { hideMessage.run() }
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
        startMqtt()
        startWakeSensors()
        onUserActivity() // Coming back, e.g. from the settings, counts as using it.
        handler.removeCallbacks(homeSwitch)
        handler.postDelayed(homeSwitch, 60_000)
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
        mqttBridge?.stop()
        mqttBridge = null
        if (::webView.isInitialized) {
            resetCornerGesture()
            pinPrompt.dismiss()
            prompts.dismiss()
            chromeClient.dismiss()
            webView.onPause()
            watchdog.stop()
            dimmer.stop()
            speaker.stop()
            soundPlayer.stop()
            stopWakeSensors()
            handler.removeCallbacks(inactivityCheck)
            handler.removeCallbacks(homeSwitch)
            screensaver.hide()
            dimmer.dimmed = false
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
        mqttBridge?.stop()
        if (::webView.isInitialized) {
            errorScreen.hide()
            pinPrompt.dismiss()
            prompts.dismiss()
            chromeClient.dismiss()
            idleTimer.stop()
            watchdog.stop()
            dimmer.stop()
            speaker.shutdown()
            soundPlayer.stop()
            stopWakeSensors()
            screensaver.hide()
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
            lastTouchTime = Instant.now().truncatedTo(ChronoUnit.SECONDS)
            reportTouch()
            // The touch that wakes a dark screen or ends the screensaver shouldn't also press something.
            swallowGesture = dimmer.isDark || screensaver.isShowing
            dimmer.onTouch()
            onUserActivity()
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

    /** A touch, motion, or a command meant to be seen: the screen is in use. */
    private fun onUserActivity() {
        lastActivityAt = SystemClock.uptimeMillis()
        updateInactivity()
    }

    private fun updateInactivity() {
        handler.removeCallbacks(inactivityCheck)
        if (!dashboardActive) return
        val inactivity = Inactivity(prefs.screensaverMinutes * 60_000L, prefs.screenOffMinutes * 60_000L)
        val now = SystemClock.uptimeMillis()
        val mode = inactivity.mode(now, lastActivityAt)
        val wasShowing = screensaver.isShowing
        val wasIdleOff = dimmer.idleOff
        if (mode == Inactivity.Mode.SCREENSAVER) {
            val url = prefs.screensaverUrl
            var saverMode = Screensaver.Mode.of(prefs.screensaverMode)
            if (saverMode == Screensaver.Mode.PAGE && url == null) saverMode = Screensaver.Mode.CLOCK
            screensaver.show(saverMode, url)
            dimmer.dimmed = saverMode == Screensaver.Mode.DIM
        } else {
            screensaver.hide()
            dimmer.dimmed = false
        }
        dimmer.idleOff = mode == Inactivity.Mode.OFF
        if (wasShowing != screensaver.isShowing || wasIdleOff != dimmer.idleOff) {
            cameraMotion?.pause(SCREEN_CHANGE_SETTLE_MS)
            mqttBridge?.stateChanged()
        }
        inactivity.nextChangeIn(now, lastActivityAt)?.let { handler.postDelayed(inactivityCheck, it) }
    }

    private fun startWakeSensors() {
        if (prefs.wakeOnProximity && proximity == null) {
            proximity = ProximityWake(this) { onMotion() }.also { it.start() }
        }
        if (prefs.wakeOnMotion && cameraMotion == null) {
            val sensitivity = when (prefs.motionSensitivity) {
                "low" -> MotionDetector.Sensitivity.LOW
                "high" -> MotionDetector.Sensitivity.HIGH
                else -> MotionDetector.Sensitivity.MEDIUM
            }
            cameraMotion = CameraMotion(this, sensitivity) { runOnUiThread(::onMotion) }.also { it.start() }
        }
    }

    private fun stopWakeSensors() {
        proximity?.stop()
        proximity = null
        cameraMotion?.stop()
        cameraMotion = null
    }

    /** Someone is near: wake the screen like a touch would, without pressing anything. */
    private fun onMotion() {
        if (destroyed || !dashboardActive) return
        dimmer.onTouch()
        onUserActivity()
        if (!motionSeen) {
            motionSeen = true
            mqttBridge?.stateChanged()
        }
        handler.removeCallbacks(motionOver)
        handler.postDelayed(motionOver, MOTION_HOLD_MS)
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun createScreensaverPage(): WebView = WebView(this).apply {
        setBackgroundColor(Color.BLACK)
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        settings.useWideViewPort = true
        settings.loadWithOverviewMode = true
        settings.userAgentString = prefs.userAgent
        webViewClient = ScreensaverWebViewClient(::navigationPolicy, certPins) {
            // Its renderer is gone; a fresh one is made the next time the screensaver starts.
            handler.post { if (screensaver.mode == Screensaver.Mode.PAGE) screensaver.hide() }
        }
    }

    /** Home Assistant's "Last touch" sensor, updated at most every 30 seconds while someone is using it. */
    private fun reportTouch() {
        val now = SystemClock.uptimeMillis()
        if (now - lastTouchReportAt < 30_000) return
        lastTouchReportAt = now
        mqttBridge?.stateChanged()
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

    // The allowed sites follow the home page itself; the night page has to be on one of them.
    override fun navigationPolicy() =
        NavigationPolicy(prefs.homeUrl.orEmpty(), prefs.allowedHosts, prefs.restrictNavigation)

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
        mqttBridge?.stateChanged()
    }

    override fun onPageFailed(url: String, reason: String) {
        if (!isSamePage(loadingUrl, url)) return
        handler.removeCallbacks(loadTimeout)
        kioskLoad = null
        failedUrl = url
        failure = reason
        errorScreen.show(hostOf(url) ?: url, reason, pageBackoff.nextDelayMs())
        mqttBridge?.stateChanged()
    }

    override fun onUntrustedCertificate(host: String, fingerprint: String, handler: SslErrorHandler) {
        if (dashboardActive) prompts.onUntrustedCertificate(host, fingerprint, handler) else handler.cancel()
    }

    override fun onLoginRequest(host: String, realm: String, handler: HttpAuthHandler) {
        if (dashboardActive) prompts.onLoginRequest(host, realm, handler) else handler.cancel()
    }

    private fun askAndroid(features: Set<SiteFeature>, done: (Boolean) -> Unit) {
        val needed = features.map {
            when (it) {
                SiteFeature.CAMERA -> Manifest.permission.CAMERA
                SiteFeature.MICROPHONE -> Manifest.permission.RECORD_AUDIO
            }
        }.filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        when {
            needed.isEmpty() -> done(true)
            // One Android dialog at a time; a second page request waits for the page to ask again.
            androidPermissionDone != null -> done(false)
            else -> {
                androidPermissionDone = done
                requestAndroidPermissions.launch(needed.toTypedArray())
            }
        }
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
        // A temporary page goes back by itself, to where it was opened from.
        if (temporaryPage == null && !isSameRoute(webView.url, homeUrl)) loadHome()
    }

    // WebView

    private fun loadHome() {
        endTemporaryPageEarly()
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
            mediaPlaybackRequiresUserGesture = !prefs.allowAutoplay
            setSupportZoom(prefs.allowZoom)
            builtInZoomControls = prefs.allowZoom
            userAgentString = prefs.userAgent
        }
    }

    // Settings

    private fun applySettings() {
        applyWebSettings(webView)
        if (homeUrl != loadedHomeUrl) {
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
        dimmer.refresh()
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

    /** Runs only while the dashboard is in front, like the HTTP API. */
    private fun startMqtt() {
        if (!prefs.mqttEnabled || mqttBridge != null) return
        val host = prefs.mqttHost ?: return
        val deviceId = prefs.mqttDeviceId
        val homeAssistant = HomeAssistant(deviceId, prefs.mqttName, Build.MODEL, versionName())
        val settings = MqttBridge.Settings(
            host = host,
            port = prefs.mqttPort,
            tls = prefs.mqttTls,
            username = prefs.mqttUsername,
            password = prefs.mqttPassword,
            clientId = "dkiosk-$deviceId",
        )
        mqttBridge = MqttBridge(
            settings, homeAssistant, remoteControl,
            screenshotsAllowed = { prefs.apiScreenshots },
            motionEnabled = { prefs.wakeOnMotion || prefs.wakeOnProximity },
        ).also { it.start() }
    }

    private fun versionName(): String = packageManager.getPackageInfo(packageName, 0).versionName.orEmpty()

    // Remote control

    private val remoteControl = object : KioskControl {
        override fun status() = onMainThread {
            val battery = getSystemService(BatteryManager::class.java)
            JSONObject()
                .put("url", webView.url ?: JSONObject.NULL)
                .put("title", webView.title ?: JSONObject.NULL)
                .put("homeUrl", homeUrl)
                .put("screen", if (dimmer.isDark) "off" else "on")
                .put("error", failure ?: JSONObject.NULL)
                .put("battery", battery.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY))
                .put("charging", battery.isCharging)
                .put("brightness", prefs.brightness ?: KioskPrefs.BRIGHTNESS_AUTO)
                .put("volume", volumePercent())
                .put("temporaryPage", temporaryPage != null)
                .put("screensaver", screensaver.isShowing)
                .put("motion", motionSeen)
                .put("lastTouch", lastTouchTime.toString())
                .put("version", versionName())
        }

        override fun reload() = onMainThread { reloadPage() }

        override fun goHome() = onMainThread { loadHome() }

        override fun open(url: String, makeHome: Boolean, seconds: Int?): String? = onMainThread {
            val host = hostOf(url).orEmpty()
            when {
                makeHome -> {
                    prefs.homeUrl = url
                    loadHome()
                    null
                }
                !navigationPolicy().allowsHost(host) -> getString(R.string.blocked_host, host)
                seconds != null -> {
                    showTemporaryPage(url, seconds * 1000L)
                    null
                }
                else -> {
                    endTemporaryPageEarly()
                    load(url)
                    null
                }
            }
        }

        override fun setScreen(on: Boolean) = onMainThread {
            dimmer.turn(on)
            if (on) onUserActivity()
        }

        override fun setBrightness(percent: Int?) = onMainThread {
            prefs.brightness = percent
            dimmer.refresh()
        }

        override fun setVolume(percent: Int) = onMainThread {
            val audio = getSystemService(AudioManager::class.java)
            val max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
            audio.setStreamVolume(AudioManager.STREAM_MUSIC, (percent * max / 100f).roundToInt(), 0)
        }

        override fun speak(text: String, language: String?) = onMainThread { speaker.speak(text, language) }

        override fun playSound(url: String?) = onMainThread {
            if (url == null) soundPlayer.stop() else soundPlayer.play(url)
        }

        override fun showMessage(text: String, seconds: Int) = onMainThread {
            binding.message.text = text
            binding.message.isVisible = true
            handler.removeCallbacks(hideMessage)
            if (seconds > 0) handler.postDelayed(hideMessage, seconds * 1000L)
            onUserActivity()
            // A message nobody can see is no use, so it lights a dark screen while it's up.
            dimmer.wakeFor(if (seconds > 0) seconds * 1000L else prefs.wakeMinutes * 60_000L)
        }

        override fun screenshot(): ByteArray? {
            if (!prefs.apiScreenshots) return null
            // Capturing finishes on a background thread; only starting it needs the main thread.
            return Screenshot.toJpeg(onMainThread { Screenshot.request(window) })
        }
    }

    private fun showTemporaryPage(url: String, durationMs: Long) {
        val returnAt = SystemClock.uptimeMillis() + durationMs
        temporaryPage = temporaryPage?.extendTo(returnAt) ?: TemporaryPage(webView.url, returnAt)
        load(url)
        dimmer.wakeFor(durationMs)
        onUserActivity()
        handler.removeCallbacks(endTemporaryPage)
        handler.postDelayed(endTemporaryPage, durationMs)
    }

    /** Something else replaces the temporary page, so there's nothing to go back from. */
    private fun endTemporaryPageEarly() {
        temporaryPage = null
        handler.removeCallbacks(endTemporaryPage)
    }

    private fun volumePercent(): Int {
        val audio = getSystemService(AudioManager::class.java)
        val max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
        return (audio.getStreamVolume(AudioManager.STREAM_MUSIC) * 100f / max).roundToInt()
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
            cutouts = insets.displayCutout?.boundingRects.orEmpty()
            placeAdminCorner()
            insets
        }
        root.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> placeAdminCorner() }
    }

    /**
     * Keeps the settings target flush in the top-right corner, where people expect it. Taps in the
     * system gesture areas still reach the app (only swipes are taken), so only a camera cutout
     * overlapping the corner moves the target down.
     */
    private fun placeAdminCorner() {
        val width = binding.root.width
        if (width == 0) return
        val size = resources.getDimensionPixelSize(R.dimen.settings_corner)
        val corner = Rect(width - size, 0, width, size)
        val top = cutouts.filter { Rect.intersects(it, corner) }.maxOfOrNull { it.bottom } ?: 0
        val params = binding.adminCorner.layoutParams as FrameLayout.LayoutParams
        if (params.topMargin != top || params.rightMargin != 0) {
            params.topMargin = top
            params.rightMargin = 0
            binding.adminCorner.layoutParams = params
        }
    }

    private companion object {
        const val TAG = "KioskActivity"
        const val LOAD_TIMEOUT_MS = 60_000L
        const val LOCK_TASK_STARTED = "lock_task_started"
        const val SCREEN_CHANGE_SETTLE_MS = 3_000L
        const val MOTION_HOLD_MS = 30_000L
    }
}
