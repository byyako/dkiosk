package com.byyako.dkiosk.settings

import android.app.TimePickerDialog
import android.content.Context
import android.content.res.TypedArray
import android.text.format.DateFormat
import android.util.AttributeSet
import androidx.preference.Preference
import com.byyako.dkiosk.config.KioskPrefs
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

/** A time of day, stored as "HH:mm" and picked with the system time picker. AndroidX has no built-in one. */
class TimePreference(context: Context, attrs: AttributeSet?) : Preference(context, attrs) {

    init {
        summaryProvider = SummaryProvider<TimePreference> { pref ->
            pref.time?.format(DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT))
        }
    }

    private val time: LocalTime?
        get() = KioskPrefs.parseTime(getPersistedString(null))

    override fun onGetDefaultValue(a: TypedArray, index: Int): Any? = a.getString(index)

    override fun onSetInitialValue(defaultValue: Any?) {
        if (getPersistedString(null) == null && defaultValue is String) persistString(defaultValue)
    }

    override fun onClick() {
        val current = time ?: LocalTime.MIDNIGHT
        TimePickerDialog(
            context,
            { _, hour, minute ->
                val value = "%02d:%02d".format(hour, minute)
                if (callChangeListener(value)) {
                    persistString(value)
                    notifyChanged()
                }
            },
            current.hour,
            current.minute,
            DateFormat.is24HourFormat(context),
        ).show()
    }
}
