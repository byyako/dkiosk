package com.byyako.dkiosk.settings

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.byyako.dkiosk.databinding.ActivitySettingsBinding
import com.byyako.dkiosk.ui.padForSystemBars

/** Settings, reached from the kiosk with the corner gesture and optional PIN. */
class SettingsActivity : AppCompatActivity() {

    private var awaitingResult = false

    /** Keeps the settings open while a screen they started (a file picker, the scanner) answers. */
    fun awaitResult() {
        awaitingResult = true
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.root.padForSystemBars()
        binding.toolbar.setNavigationOnClickListener { finish() }
    }

    override fun onStart() {
        super.onStart()
        awaitingResult = false
    }

    override fun onStop() {
        super.onStop()
        // These are behind the PIN, so don't leave them open in the background for the next person.
        if (!isChangingConfigurations && !awaitingResult) finish()
    }

    companion object {
        /** Result extra: the page should be reloaded, e.g. because site data was cleared. */
        const val EXTRA_RELOAD = "reload"
        const val EXTRA_EXIT = "exit"
    }
}
