package com.tyllad.dkiosk.settings

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.text.InputType
import android.view.LayoutInflater
import android.webkit.CookieManager
import android.webkit.WebStorage
import android.webkit.WebViewDatabase
import android.widget.Toast
import androidx.annotation.StringRes
import androidx.appcompat.app.AlertDialog
import androidx.preference.EditTextPreference
import androidx.preference.MultiSelectListPreference
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import androidx.preference.SwitchPreferenceCompat
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.tyllad.dkiosk.R
import com.tyllad.dkiosk.config.KioskPrefs
import com.tyllad.dkiosk.databinding.ViewNewPinBinding
import com.tyllad.dkiosk.ui.panForKeyboard
import com.tyllad.dkiosk.web.allowedHostPattern
import com.tyllad.dkiosk.web.normalizeHomeUrl
import java.time.DayOfWeek
import java.time.format.TextStyle
import java.util.Locale

class SettingsFragment : PreferenceFragmentCompat() {

    private val prefs by lazy { KioskPrefs(requireContext()) }

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        preferenceManager.sharedPreferencesName = KioskPrefs.FILE_NAME
        setPreferencesFromResource(R.xml.preferences, rootKey)

        setUpHomeUrl()
        setUpAllowedHosts()
        setUpUserAgent()
        setUpScheduleDays()
        setUpHomeScreen()

        onClick("change_pin", ::changePin)
        onClick("forget_certificates", ::forgetCertificates)
        onClick("clear_site_data", ::clearSiteData)
        onClick("choose_home_app") { HomeApp.openHomeSettings(requireContext()) }
        onClick("exit", ::exitKiosk)

        val context = requireContext()
        findPreference<Preference>("version")?.summary =
            context.packageManager.getPackageInfo(context.packageName, 0).versionName
    }

    override fun onResume() {
        super.onResume()
        // Coming back from Android's home app screen, the choice may have changed.
        findPreference<SwitchPreferenceCompat>("home_screen")?.isChecked = HomeApp.isOffered(requireContext())
        findPreference<Preference>("choose_home_app")?.setSummary(
            if (HomeApp.isDefault(requireContext())) R.string.home_app_is_default else R.string.home_app_not_default,
        )
    }

    private fun setUpHomeUrl() {
        val pref = findPreference<EditTextPreference>(KioskPrefs.HOME_URL) ?: return
        pref.setOnBindEditTextListener {
            it.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            it.setSelection(it.length())
        }
        pref.setOnPreferenceChangeListener { _, value ->
            val url = normalizeHomeUrl(value as String)
            when {
                url == null -> {
                    toast(R.string.setup_invalid_url)
                    false
                }
                url != value -> {
                    // Save the cleaned-up form ("example.com" becomes "https://example.com").
                    pref.text = url
                    false
                }
                else -> true
            }
        }
    }

    private fun setUpAllowedHosts() {
        val pref = findPreference<EditTextPreference>(KioskPrefs.ALLOWED_HOSTS) ?: return
        pref.setOnBindEditTextListener {
            it.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or
                InputType.TYPE_TEXT_VARIATION_URI
            it.minLines = 3
        }
        pref.setSummaryProvider {
            prefs.allowedHosts.joinToString(", ").ifEmpty { getString(R.string.allowed_hosts_none) }
        }
        pref.setOnPreferenceChangeListener { _, value ->
            val invalid = (value as String).lines().map { it.trim() }.filter { it.isNotEmpty() && allowedHostPattern(it) == null }
            if (invalid.isNotEmpty()) toast(getString(R.string.allowed_hosts_invalid, invalid.joinToString(", ")))
            invalid.isEmpty()
        }
    }

    private fun setUpUserAgent() {
        findPreference<EditTextPreference>(KioskPrefs.USER_AGENT)?.setSummaryProvider {
            prefs.userAgent ?: getString(R.string.user_agent_default)
        }
    }

    private fun setUpScheduleDays() {
        findPreference<MultiSelectListPreference>(KioskPrefs.SCHEDULE_DAYS)?.setSummaryProvider {
            val days = prefs.scheduleDays
            val weekend = setOf(DayOfWeek.SATURDAY, DayOfWeek.SUNDAY)
            when (days) {
                DayOfWeek.entries.toSet() -> getString(R.string.schedule_every_day)
                DayOfWeek.entries.toSet() - weekend -> getString(R.string.schedule_weekdays)
                weekend -> getString(R.string.schedule_weekends)
                emptySet<DayOfWeek>() -> getString(R.string.schedule_no_days)
                else -> days.sorted().joinToString(", ") { it.getDisplayName(TextStyle.SHORT, Locale.getDefault()) }
            }
        }
    }

    private fun setUpHomeScreen() {
        findPreference<SwitchPreferenceCompat>("home_screen")?.setOnPreferenceChangeListener { _, value ->
            val offered = value as Boolean
            val wasDefault = HomeApp.isDefault(requireContext())
            HomeApp.setOffered(requireContext(), offered)
            if (offered) {
                toast(R.string.home_app_pick)
                HomeApp.openHomeSettings(requireContext())
            } else if (wasDefault) {
                HomeApp.openHomeSettings(requireContext())
            }
            true
        }
    }

    private fun changePin() {
        val form = ViewNewPinBinding.inflate(LayoutInflater.from(requireContext()))
        val padding = resources.getDimensionPixelSize(R.dimen.dialog_padding)
        form.root.setPadding(padding, padding / 3, padding, 0)

        val dialog = MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.change_pin)
            .setView(form.root)
            .setPositiveButton(R.string.save, null)
            .setNegativeButton(android.R.string.cancel, null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val pin = form.readNewPin() ?: return@setOnClickListener
                prefs.pinHash = Pin.hash(pin)
                dialog.dismiss()
                toast(R.string.pin_changed)
            }
        }
        dialog.panForKeyboard()
        dialog.show()
    }

    private fun forgetCertificates() {
        confirm(R.string.forget_certificates, R.string.forget_certificates_confirm) {
            prefs.trustedCerts = emptySet()
            toast(R.string.done)
        }
    }

    private fun clearSiteData() {
        confirm(R.string.clear_site_data, R.string.clear_site_data_confirm) {
            CookieManager.getInstance().removeAllCookies(null)
            CookieManager.getInstance().flush()
            WebStorage.getInstance().deleteAllData()
            WebViewDatabase.getInstance(requireContext()).clearHttpAuthUsernamePassword()
            requireActivity().setResult(Activity.RESULT_OK, Intent().putExtra(SettingsActivity.EXTRA_RELOAD, true))
            toast(R.string.done)
        }
    }

    private fun exitKiosk() {
        if (HomeApp.isDefault(requireContext())) {
            // As the home app, dKiosk would just be started again. Another home app has to be picked first.
            toast(R.string.exit_pick_home_app)
            HomeApp.openHomeSettings(requireContext())
        } else {
            requireActivity().finishAffinity()
        }
    }

    private fun onClick(key: String, action: () -> Unit) {
        findPreference<Preference>(key)?.setOnPreferenceClickListener {
            action()
            true
        }
    }

    private fun confirm(@StringRes title: Int, @StringRes message: Int, action: () -> Unit) {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(title)
            .setMessage(message)
            .setPositiveButton(title) { _, _ -> action() }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun toast(@StringRes text: Int) = toast(getString(text))

    private fun toast(text: String) = Toast.makeText(requireContext(), text, Toast.LENGTH_LONG).show()
}
