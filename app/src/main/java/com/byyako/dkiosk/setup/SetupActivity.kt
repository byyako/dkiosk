package com.byyako.dkiosk.setup

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.byyako.dkiosk.KioskActivity
import com.byyako.dkiosk.R
import com.byyako.dkiosk.config.KioskPrefs
import com.byyako.dkiosk.databinding.ActivitySetupBinding
import com.byyako.dkiosk.settings.Pin
import com.byyako.dkiosk.settings.readNewPin
import com.byyako.dkiosk.ui.padForSystemBars
import com.byyako.dkiosk.web.normalizeHomeUrl

/** First run: pick the page and a PIN for the settings. Everything else has working defaults. */
class SetupActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySetupBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySetupBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.root.padForSystemBars()

        val prefs = KioskPrefs(this)
        prefs.homeUrl?.let { binding.url.setText(it) }
        binding.url.setSelection(binding.url.length())
        binding.start.setOnClickListener { finishSetup(prefs) }
    }

    private fun finishSetup(prefs: KioskPrefs) {
        binding.urlLayout.error = null
        val url = normalizeHomeUrl(binding.url.text.toString())
        if (url == null) {
            binding.urlLayout.error = getString(R.string.setup_invalid_url)
            return
        }
        val pin = binding.newPin.readNewPin() ?: return

        prefs.homeUrl = url
        prefs.pinHash = Pin.hash(pin)
        startActivity(Intent(this, KioskActivity::class.java))
        finish()
    }
}
