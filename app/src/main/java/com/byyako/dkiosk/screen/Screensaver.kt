package com.byyako.dkiosk.screen

import android.graphics.Color
import android.os.Handler
import android.os.Looper
import android.text.format.DateFormat
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.webkit.WebView
import android.widget.FrameLayout
import androidx.core.view.isVisible
import com.byyako.dkiosk.databinding.ViewScreensaverBinding
import java.util.Locale
import kotlin.random.Random

/**
 * Shown when nobody has used the screen for a while: a clock that drifts so it never burns in, the
 * dashboard dimmed, or another page. A page gets its own WebView so the dashboard keeps its state.
 */
class Screensaver(
    private val binding: ViewScreensaverBinding,
    private val createPageView: () -> WebView,
) {
    enum class Mode(val key: String) {
        CLOCK("clock"),
        DIM("dim"),
        PAGE("page");

        companion object {
            fun of(key: String?) = entries.firstOrNull { it.key == key } ?: CLOCK
        }
    }

    private val handler = Handler(Looper.getMainLooper())
    private var page: WebView? = null
    private val drift = object : Runnable {
        override fun run() {
            moveClock()
            handler.postDelayed(this, DRIFT_EVERY_MS)
        }
    }

    var mode: Mode? = null
        private set

    val isShowing: Boolean
        get() = mode != null

    init {
        val datePattern = DateFormat.getBestDateTimePattern(Locale.getDefault(), "EEEEdMMMM")
        binding.date.format12Hour = datePattern
        binding.date.format24Hour = datePattern
        // Without AM/PM, like most bedside clocks; it would make the time too wide for a portrait phone.
        binding.time.format12Hour = withoutAmPm(DateFormat.getBestDateTimePattern(Locale.getDefault(), "hmm"))
        binding.time.format24Hour = DateFormat.getBestDateTimePattern(Locale.getDefault(), "Hmm")
    }

    fun show(mode: Mode, pageUrl: String?) {
        if (this.mode == mode) return
        hide()
        this.mode = mode
        val root = binding.root
        when (mode) {
            Mode.CLOCK -> {
                root.setBackgroundColor(Color.BLACK)
                binding.clock.isVisible = true
                handler.post(drift)
            }
            Mode.DIM -> {
                root.setBackgroundColor(DIM_COLOR)
                binding.clock.isVisible = false
            }
            Mode.PAGE -> {
                root.setBackgroundColor(Color.BLACK)
                binding.clock.isVisible = false
                val view = createPageView()
                root.addView(view, 0, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
                page = view
                pageUrl?.let(view::loadUrl)
            }
        }
        root.isVisible = true
    }

    fun hide() {
        if (mode == null) return
        mode = null
        handler.removeCallbacks(drift)
        binding.root.isVisible = false
        page?.let {
            binding.root.removeView(it)
            it.destroy()
        }
        page = null
    }

    /** Drops the AM/PM field ("a") from a date pattern, leaving quoted literal text alone. */
    private fun withoutAmPm(pattern: String): String {
        var quoted = false
        return buildString {
            for (char in pattern) {
                if (char == '\'') quoted = !quoted
                if (char != 'a' || quoted) append(char)
            }
        }.trim()
    }

    /** Somewhere new in the free space, so no pixel stays lit for long. */
    private fun moveClock() {
        val root = binding.root
        val clock = binding.clock
        if (root.width == 0 || clock.width == 0) {
            // Not laid out yet; try again once it is.
            clock.post(::moveClock)
            return
        }
        clock.translationX = Random.nextInt((root.width - clock.width).coerceAtLeast(1)).toFloat()
        clock.translationY = Random.nextInt((root.height - clock.height).coerceAtLeast(1)).toFloat()
    }

    private companion object {
        const val DRIFT_EVERY_MS = 60_000L
        val DIM_COLOR = Color.argb(0xB3, 0, 0, 0)
    }
}
