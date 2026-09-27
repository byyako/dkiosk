package com.byyako.dkiosk.settings

import android.app.Activity
import android.os.SystemClock
import android.view.LayoutInflater
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.byyako.dkiosk.R
import com.byyako.dkiosk.config.KioskPrefs
import com.byyako.dkiosk.databinding.DialogPinBinding
import com.byyako.dkiosk.ui.panForKeyboard

/** Asks for the settings PIN. Five wrong tries lock it for 30 seconds, doubling after each further miss. */
class PinPrompt(private val activity: Activity, private val prefs: KioskPrefs) {

    fun ask(onSuccess: () -> Unit, onCancel: () -> Unit = {}) {
        val stored = prefs.pinHash ?: return onSuccess()

        val secondsLocked = (lockedUntil - SystemClock.elapsedRealtime() + 999) / 1000
        if (secondsLocked > 0) {
            Toast.makeText(activity, activity.getString(R.string.pin_locked, secondsLocked), Toast.LENGTH_LONG).show()
            onCancel()
            return
        }

        val binding = DialogPinBinding.inflate(LayoutInflater.from(activity))
        val dialog = MaterialAlertDialogBuilder(activity)
            .setTitle(R.string.pin_title)
            .setView(binding.root)
            .setPositiveButton(R.string.pin_unlock, null)
            .setNegativeButton(android.R.string.cancel) { _, _ -> onCancel() }
            .setOnCancelListener { onCancel() }
            .create()

        fun check() {
            val pin = binding.pin.text.toString()
            if (pin.isEmpty()) return
            if (Pin.matches(pin, stored)) {
                failures = 0
                dialog.dismiss()
                onSuccess()
                return
            }
            failures++
            binding.pin.text = null
            if (failures >= MAX_FAILURES) {
                lockedUntil = SystemClock.elapsedRealtime() + (LOCKOUT_MS shl (failures - MAX_FAILURES).coerceAtMost(6))
                dialog.dismiss()
                ask(onSuccess, onCancel) // shows the lockout message
            } else {
                binding.pinLayout.error = activity.getString(R.string.pin_wrong)
            }
        }

        binding.pin.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE) check()
            true
        }
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener { check() }
            binding.pin.requestFocus()
        }
        dialog.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE)
        dialog.panForKeyboard()
        dialog.show()
    }

    // Kept for the whole process so closing and reopening the kiosk doesn't reset the lockout.
    private companion object {
        const val MAX_FAILURES = 5
        const val LOCKOUT_MS = 30_000L
        var failures = 0
        var lockedUntil = 0L
    }
}
