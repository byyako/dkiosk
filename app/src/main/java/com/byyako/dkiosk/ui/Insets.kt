package com.byyako.dkiosk.ui

import android.app.Dialog
import android.view.View
import android.view.WindowManager
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding

/**
 * Apps targeting Android 15+ are always drawn edge to edge, so screens with normal system bars pad
 * themselves for the bars, the notch and the keyboard.
 */
fun View.padForSystemBars() {
    ViewCompat.setOnApplyWindowInsetsListener(this) { view, insets ->
        val types = WindowInsetsCompat.Type.systemBars() or
            WindowInsetsCompat.Type.displayCutout() or
            WindowInsetsCompat.Type.ime()
        val bars = insets.getInsets(types)
        view.updatePadding(left = bars.left, top = bars.top, right = bars.right, bottom = bars.bottom)
        WindowInsetsCompat.CONSUMED
    }
}

/**
 * In landscape on a phone the keyboard leaves too little height for a dialog, and resizing squeezes
 * its text fields to nothing. Panning keeps the dialog whole and scrolls the focused field into view.
 */
fun Dialog.panForKeyboard() {
    val window = window ?: return
    val keepState = window.attributes.softInputMode and WindowManager.LayoutParams.SOFT_INPUT_MASK_ADJUST.inv()
    window.setSoftInputMode(keepState or WindowManager.LayoutParams.SOFT_INPUT_ADJUST_PAN)
}
