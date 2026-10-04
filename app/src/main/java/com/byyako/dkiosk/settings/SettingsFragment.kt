package com.byyako.dkiosk.settings

import android.Manifest
import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.text.InputType
import android.text.format.DateUtils
import android.view.LayoutInflater
import android.webkit.CookieManager
import android.webkit.WebStorage
import android.webkit.WebViewDatabase
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.StringRes
import androidx.appcompat.app.AlertDialog
import androidx.core.net.toUri
import androidx.preference.EditTextPreference
import androidx.preference.ListPreference
import androidx.preference.MultiSelectListPreference
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import androidx.preference.SwitchPreferenceCompat
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.byyako.dkiosk.R
import com.byyako.dkiosk.config.KioskPrefs
import com.byyako.dkiosk.databinding.ViewNewPinBinding
import com.byyako.dkiosk.lockdown.Lockdown
import com.byyako.dkiosk.remote.KioskApi
import com.byyako.dkiosk.remote.MqttStatus
import com.byyako.dkiosk.screen.ProximityWake
import com.byyako.dkiosk.screen.Screensaver
import com.byyako.dkiosk.remote.localIpAddress
import com.byyako.dkiosk.ui.panForKeyboard
import com.byyako.dkiosk.web.NavigationPolicy
import com.byyako.dkiosk.web.SitePermissions
import com.byyako.dkiosk.web.allowedHostPattern
import com.byyako.dkiosk.web.hostOf
import com.byyako.dkiosk.web.normalizeHomeUrl
import java.time.DayOfWeek
import java.time.format.TextStyle
import java.util.Locale

class SettingsFragment : PreferenceFragmentCompat() {

    private val prefs by lazy { KioskPrefs(requireContext()) }

    // Motion detection needs the camera; the switch only turns on once Android allows it.
    private val requestCamera = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) {
            findPreference<SwitchPreferenceCompat>(KioskPrefs.WAKE_MOTION)?.isChecked = true
        } else toast(R.string.wake_motion_denied)
    }

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        preferenceManager.sharedPreferencesName = KioskPrefs.FILE_NAME
        setPreferencesFromResource(R.xml.preferences, rootKey)

        setUpHomeUrl()
        setUpAllowedHosts()
        setUpUserAgent()
        setUpScheduleDays()
        setUpBrightness()
        setUpScreensaver()
        setUpNightPage()
        setUpWakeSensors()
        setUpApi()
        setUpMqtt()
        setUpHomeScreen()
        setUpPinProtection()
        setUpLockdown()

        onClick("api_token", ::showApiToken)
        onClick("api_address") { localIpAddress(requireContext())?.let { copy("http://$it:${prefs.apiPort}") } }
        onClick("change_pin", ::changePin)
        onClick("forget_certificates", ::forgetCertificates)
        onClick("forget_site_permissions", ::forgetSitePermissions)
        onClick("clear_site_data", ::clearSiteData)
        onClick("choose_home_app") { HomeApp.openHomeSettings(requireContext()) }
        onClick("exit", ::exitKiosk)
        onClick("lockdown_help") {
            MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.lockdown_setup)
                .setMessage(R.string.lockdown_help)
                .setPositiveButton(android.R.string.ok, null)
                .show()
        }
        onClick("support") { openLink(getString(R.string.support_url)) }

        val context = requireContext()
        findPreference<Preference>("version")?.summary =
            context.packageManager.getPackageInfo(context.packageName, 0).versionName
    }

    override fun onResume() {
        super.onResume()
        updateApiAddress()
        updateMqttStatus()
        updateSitePermissions()
        // Coming back from Android's home app screen, the choice may have changed.
        findPreference<SwitchPreferenceCompat>("home_screen")?.isChecked = HomeApp.isOffered(requireContext())
        findPreference<Preference>("choose_home_app")?.setSummary(
            if (HomeApp.isDefault(requireContext())) R.string.home_app_is_default else R.string.home_app_not_default,
        )
        val locked = Lockdown(requireContext()).isLocked
        findPreference<Preference>("home_screen")?.isEnabled = !locked
        findPreference<Preference>("choose_home_app")?.isEnabled = !locked && HomeApp.isOffered(requireContext())
        findPreference<Preference>("support")?.isEnabled = !locked
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
            val invalid = (value as String).lines()
                .map { it.trim() }
                .filter { it.isNotEmpty() && allowedHostPattern(it) == null }
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

    private fun setUpScreensaver() {
        val url = findPreference<EditTextPreference>(KioskPrefs.SCREENSAVER_URL) ?: return
        url.isEnabled = prefs.screensaverMode == Screensaver.Mode.PAGE.key
        setUpAllowedUrl(url) { prefs.screensaverUrl ?: getString(R.string.screensaver_url_missing) }
        findPreference<ListPreference>(KioskPrefs.SCREENSAVER_MODE)?.setOnPreferenceChangeListener { _, mode ->
            url.isEnabled = mode == Screensaver.Mode.PAGE.key
            true
        }
    }

    private fun setUpNightPage() {
        val url = findPreference<EditTextPreference>(KioskPrefs.NIGHT_PAGE_URL) ?: return
        setUpAllowedUrl(url) { prefs.nightPageUrl ?: getString(R.string.night_page_url_missing) }
    }

    /** An address field that only accepts pages on the kiosk's allowed sites, saved in its clean form. */
    private fun setUpAllowedUrl(pref: EditTextPreference, summary: () -> String) {
        pref.setOnBindEditTextListener {
            it.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            it.setSelection(it.length())
        }
        pref.setSummaryProvider { summary() }
        pref.setOnPreferenceChangeListener { _, value ->
            val text = (value as String).trim()
            if (text.isEmpty()) return@setOnPreferenceChangeListener true
            val normalized = normalizeHomeUrl(text)
            val host = normalized?.let(::hostOf)
            val policy = NavigationPolicy(prefs.homeUrl.orEmpty(), prefs.allowedHosts, prefs.restrictNavigation)
            when {
                normalized == null || host == null -> {
                    toast(R.string.setup_invalid_url)
                    false
                }
                !policy.allowsHost(host) -> {
                    toast(getString(R.string.screensaver_url_blocked, host))
                    false
                }
                normalized != text -> {
                    pref.text = normalized
                    false
                }
                else -> true
            }
        }
    }

    private fun setUpWakeSensors() {
        findPreference<SwitchPreferenceCompat>(KioskPrefs.WAKE_PROXIMITY)?.apply {
            if (!ProximityWake(requireContext()) {}.isAvailable) {
                isEnabled = false
                setSummary(R.string.wake_proximity_missing)
            }
        }
        findPreference<SwitchPreferenceCompat>(KioskPrefs.WAKE_MOTION)?.setOnPreferenceChangeListener { _, enabled ->
            val granted = requireContext().checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
            if (enabled == true && !granted) {
                requestCamera.launch(Manifest.permission.CAMERA)
                false
            } else true
        }
    }

    private fun setUpBrightness() {
        // The API can set any percentage, not just the ones in the list.
        findPreference<ListPreference>(KioskPrefs.BRIGHTNESS)?.setSummaryProvider {
            prefs.brightness?.let { getString(R.string.brightness_percent, it) }
                ?: resources.getStringArray(R.array.brightness_entries).first()
        }
    }

    private fun setUpApi() {
        findPreference<SwitchPreferenceCompat>(KioskPrefs.API_ENABLED)?.setOnPreferenceChangeListener { _, enabled ->
            if (enabled == true && prefs.apiToken == null) prefs.apiToken = KioskApi.newToken()
            true
        }
        findPreference<EditTextPreference>(KioskPrefs.API_PORT)?.apply {
            setOnBindEditTextListener { it.inputType = InputType.TYPE_CLASS_NUMBER }
            setOnPreferenceChangeListener { _, value ->
                val valid = (value as String).toIntOrNull() in 1024..65535
                if (!valid) toast(R.string.api_port_invalid)
                valid
            }
        }
    }

    private fun setUpMqtt() {
        findPreference<EditTextPreference>(KioskPrefs.MQTT_HOST)?.apply {
            dialogMessage = getString(R.string.mqtt_host_hint)
            setOnBindEditTextListener { it.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI }
            setSummaryProvider { prefs.mqttHost ?: getString(R.string.mqtt_host_missing) }
            setOnPreferenceChangeListener { _, value ->
                val text = (value as String).trim()
                val host = KioskPrefs.cleanMqttHost(text)
                when {
                    text.isEmpty() -> true
                    host == null || host.any { it.isWhitespace() } -> {
                        toast(R.string.mqtt_host_invalid)
                        false
                    }
                    host != text -> {
                        this.text = host // Save it without the scheme or port.
                        false
                    }
                    else -> true
                }
            }
        }
        findPreference<EditTextPreference>(KioskPrefs.MQTT_PORT)?.apply {
            dialogMessage = getString(R.string.mqtt_port_help)
            setOnBindEditTextListener { it.inputType = InputType.TYPE_CLASS_NUMBER }
            setSummaryProvider { prefs.mqttPort.toString() }
            setOnPreferenceChangeListener { _, value ->
                val valid = (value as String).isEmpty() || value.toIntOrNull() in 1..65535
                if (!valid) toast(R.string.mqtt_port_invalid)
                valid
            }
        }
        findPreference<EditTextPreference>(KioskPrefs.MQTT_USERNAME)?.setSummaryProvider {
            prefs.mqttUsername ?: getString(R.string.mqtt_not_set)
        }
        findPreference<EditTextPreference>(KioskPrefs.MQTT_PASSWORD)?.apply {
            setOnBindEditTextListener {
                it.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            }
            setSummaryProvider {
                getString(if (prefs.mqttPassword == null) R.string.mqtt_not_set else R.string.mqtt_password_set)
            }
        }
        findPreference<EditTextPreference>(KioskPrefs.MQTT_NAME)?.apply {
            setOnBindEditTextListener { if (it.text.isEmpty()) it.setText(prefs.mqttName) }
            setSummaryProvider { prefs.mqttName }
        }
        // The TLS switch changes the default port. The new value is saved after this listener, so
        // the port's summary is refreshed (by setting its provider again) once that's done.
        findPreference<SwitchPreferenceCompat>(KioskPrefs.MQTT_TLS)?.setOnPreferenceChangeListener { _, _ ->
            listView.post {
                findPreference<EditTextPreference>(KioskPrefs.MQTT_PORT)?.let { it.summaryProvider = it.summaryProvider }
            }
            true
        }
    }

    private fun updateMqttStatus() {
        val status = MqttStatus.text
        findPreference<Preference>("mqtt_status")?.summary = if (status == null) {
            getString(R.string.mqtt_status_never)
        } else {
            val time = DateUtils.formatDateTime(requireContext(), MqttStatus.at, DateUtils.FORMAT_SHOW_TIME)
            getString(R.string.mqtt_status_at, status, time)
        }
    }

    private fun updateApiAddress() {
        val address = localIpAddress(requireContext())
        findPreference<Preference>("api_address")?.summary =
            if (address == null) getString(R.string.api_address_offline) else "http://$address:${prefs.apiPort}"
    }

    private fun showApiToken() {
        val token = prefs.apiToken ?: KioskApi.newToken().also { prefs.apiToken = it }
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.api_token)
            .setMessage(getString(R.string.api_token_help, token))
            .setPositiveButton(R.string.api_token_copy) { _, _ -> copy(token) }
            .setNeutralButton(R.string.api_token_new) { _, _ ->
                prefs.apiToken = KioskApi.newToken()
                toast(R.string.api_token_replaced)
                showApiToken()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun copy(text: String) {
        val clipboard = requireContext().getSystemService(ClipboardManager::class.java)
        clipboard.setPrimaryClip(ClipData.newPlainText(getString(R.string.app_name), text))
        // Android 13+ confirms copies itself.
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) toast(R.string.copied)
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

    private fun setUpPinProtection() {
        val toggle = findPreference<SwitchPreferenceCompat>(KioskPrefs.PIN_REQUIRED) ?: return
        toggle.isChecked = prefs.pinRequired
        findPreference<Preference>("change_pin")?.isEnabled = prefs.pinRequired
        toggle.setOnPreferenceChangeListener { _, required ->
            if (required == true) {
                changePin()
            } else {
                confirm(R.string.pin_disable_title, R.string.pin_disable_message) {
                    prefs.pinRequired = false
                    toggle.isChecked = false
                    findPreference<Preference>("change_pin")?.isEnabled = false
                }
            }
            false // Save only after the new PIN or confirmation succeeds.
        }
    }

    private fun setUpLockdown() {
        val toggle = findPreference<SwitchPreferenceCompat>(KioskPrefs.LOCKDOWN_ENABLED) ?: return
        val available = Lockdown(requireContext()).isAvailable
        toggle.isEnabled = available || prefs.lockdownEnabled // Allow disabling a stale configuration.
        toggle.setSummary(if (available) R.string.lockdown_ready else R.string.lockdown_unavailable)
        toggle.setOnPreferenceChangeListener { _, enabled ->
            if (enabled == true && !Lockdown(requireContext()).isAvailable) {
                toast(R.string.lockdown_unavailable)
                false
            } else true
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
                prefs.pinRequired = true
                findPreference<SwitchPreferenceCompat>(KioskPrefs.PIN_REQUIRED)?.isChecked = true
                findPreference<Preference>("change_pin")?.isEnabled = true
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

    private fun updateSitePermissions() {
        val (allowed, blocked) = SitePermissions(load = { prefs.sitePermissions }, save = {}).counts()
        findPreference<Preference>("forget_site_permissions")?.summary = if (allowed + blocked == 0) {
            getString(R.string.forget_site_permissions_none)
        } else getString(R.string.forget_site_permissions_summary, allowed, blocked)
    }

    private fun forgetSitePermissions() {
        confirm(R.string.forget_site_permissions, R.string.forget_site_permissions_confirm) {
            prefs.sitePermissions = emptySet()
            updateSitePermissions()
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
        val exit = {
            requireActivity().setResult(Activity.RESULT_OK, Intent().putExtra(SettingsActivity.EXTRA_EXIT, true))
            requireActivity().finish()
        }
        if (prefs.lockdownEnabled || Lockdown(requireContext()).isLocked) {
            confirm(R.string.exit, R.string.lockdown_exit_message, exit)
        } else {
            exit()
        }
    }

    private fun openLink(url: String) {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, url.toUri()))
        } catch (_: ActivityNotFoundException) {
            toast(getString(R.string.no_browser, url))
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
