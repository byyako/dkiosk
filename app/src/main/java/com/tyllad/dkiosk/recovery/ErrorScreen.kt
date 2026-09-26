package com.tyllad.dkiosk.recovery

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.core.view.isVisible
import com.tyllad.dkiosk.R
import com.tyllad.dkiosk.databinding.ViewErrorBinding

/** Full-screen "can't load the page" message that counts down and retries on its own. */
class ErrorScreen(private val binding: ViewErrorBinding, private val onRetry: () -> Unit) {

    private val handler = Handler(Looper.getMainLooper())
    private val context = binding.root.context
    private var retryAt = 0L

    val isShowing: Boolean
        get() = binding.root.isVisible

    init {
        binding.retryNow.setOnClickListener { retryNow() }
    }

    fun show(host: String, reason: String, retryInMs: Long) {
        binding.title.text = context.getString(R.string.error_title, host)
        binding.reason.text = reason
        binding.root.isVisible = true
        retryAt = SystemClock.uptimeMillis() + retryInMs
        handler.removeCallbacks(countdown)
        countdown.run()
    }

    fun hide() {
        handler.removeCallbacks(countdown)
        binding.root.isVisible = false
    }

    fun retryNow() {
        handler.removeCallbacks(countdown)
        binding.countdown.setText(R.string.error_retrying)
        onRetry()
    }

    private val countdown = object : Runnable {
        override fun run() {
            val secondsLeft = ((retryAt - SystemClock.uptimeMillis() + 999) / 1000).toInt()
            if (secondsLeft <= 0) {
                retryNow()
                return
            }
            binding.countdown.text = context.getString(R.string.error_countdown, secondsLeft)
            handler.postDelayed(this, 1000)
        }
    }
}
