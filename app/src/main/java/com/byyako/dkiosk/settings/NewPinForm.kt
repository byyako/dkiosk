package com.byyako.dkiosk.settings

import com.byyako.dkiosk.R
import com.byyako.dkiosk.databinding.ViewNewPinBinding

/** Returns the entered PIN if it's valid and both fields match, otherwise shows what's wrong and returns null. */
fun ViewNewPinBinding.readNewPin(): String? {
    val context = root.context
    val pin = pin.text.toString()
    pinLayout.error = null
    confirmLayout.error = null

    if (!Pin.isValid(pin)) {
        pinLayout.error = context.getString(R.string.pin_invalid, Pin.MIN_LENGTH, Pin.MAX_LENGTH)
        return null
    }
    if (confirm.text.toString() != pin) {
        confirmLayout.error = context.getString(R.string.pin_mismatch)
        return null
    }
    return pin
}
