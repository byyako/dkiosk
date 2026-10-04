package com.byyako.dkiosk.setup

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import com.byyako.dkiosk.KioskActivity
import com.byyako.dkiosk.R
import com.byyako.dkiosk.config.KioskPrefs
import com.byyako.dkiosk.config.SettingsTransfer
import com.byyako.dkiosk.settings.Backup
import com.byyako.dkiosk.databinding.ActivitySetupBinding
import com.byyako.dkiosk.settings.Pin
import com.byyako.dkiosk.settings.readNewPin
import com.byyako.dkiosk.ui.padForSystemBars
import com.byyako.dkiosk.web.normalizeHomeUrl

/** First run: pick the page and optional administrator PIN. Everything else has working defaults. */
class SetupActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySetupBinding
    private val prefs by lazy { KioskPrefs(this) }

    private val restoreFile = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@registerForActivityResult
        try {
            restore(Backup.read(this, uri))
        } catch (e: Exception) {
            toast(getString(R.string.import_settings_failed, e.message.orEmpty()))
        }
    }

    private val scanCode = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        result.data?.getStringExtra(ScanActivity.EXTRA_TEXT)?.let(::restore)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySetupBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.root.padForSystemBars()

        prefs.homeUrl?.let { binding.url.setText(it) }
        binding.url.setSelection(binding.url.length())
        binding.requirePin.setOnCheckedChangeListener { _, required ->
            binding.newPin.root.isVisible = required
        }
        binding.start.setOnClickListener { finishSetup(prefs) }
        binding.scan.setOnClickListener { scanCode.launch(Intent(this, ScanActivity::class.java)) }
        binding.restore.setOnClickListener {
            restoreFile.launch(arrayOf("application/json", "text/plain", "application/octet-stream"))
        }
    }

    /** Copies another kiosk's settings; the PIN is still chosen here, since it's never exported. */
    private fun restore(text: String) {
        try {
            val notes = Backup.import(this, prefs, text)
            prefs.homeUrl?.let {
                binding.url.setText(it)
                binding.url.setSelection(binding.url.length())
            }
            toast((listOf(getString(R.string.import_settings_done)) + notes.map(::getString)).joinToString(" "))
        } catch (e: SettingsTransfer.ImportException) {
            toast(getString(R.string.import_settings_failed, e.message.orEmpty()))
        }
    }

    private fun toast(text: String) = Toast.makeText(this, text, Toast.LENGTH_LONG).show()

    private fun finishSetup(prefs: KioskPrefs) {
        binding.urlLayout.error = null
        val url = normalizeHomeUrl(binding.url.text.toString())
        if (url == null) {
            binding.urlLayout.error = getString(R.string.setup_invalid_url)
            return
        }
        val hash = if (binding.requirePin.isChecked) {
            Pin.hash(binding.newPin.readNewPin() ?: return)
        } else null
        prefs.completeSetup(url, hash)
        startActivity(Intent(this, KioskActivity::class.java))
        finish()
    }
}
