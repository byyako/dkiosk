package com.byyako.dkiosk.settings

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.byyako.dkiosk.databinding.ActivitySettingsBinding
import com.byyako.dkiosk.ui.padForSystemBars

/** Settings, reached from the kiosk with the corner taps and the PIN. */
class SettingsActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.root.padForSystemBars()
        binding.toolbar.setNavigationOnClickListener { finish() }
    }

    override fun onStop() {
        super.onStop()
        // These are behind the PIN, so don't leave them open in the background for the next person.
        if (!isChangingConfigurations) finish()
    }

    companion object {
        /** Result extra: the page should be reloaded, e.g. because site data was cleared. */
        const val EXTRA_RELOAD = "reload"
    }
}
