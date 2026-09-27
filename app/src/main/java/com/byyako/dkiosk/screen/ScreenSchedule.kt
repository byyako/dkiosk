package com.byyako.dkiosk.screen

import java.time.DayOfWeek
import java.time.LocalDateTime
import java.time.LocalTime

/**
 * A daily period when the screen should be off, such as 22:00 to 07:00. [days] are the days the
 * period starts on, so with an overnight period Friday's night ends on Saturday morning.
 */
class ScreenSchedule(
    private val offAt: LocalTime,
    private val onAt: LocalTime,
    private val days: Set<DayOfWeek>,
) {
    fun isOffPeriod(now: LocalDateTime): Boolean {
        if (offAt == onAt) return false
        val time = now.toLocalTime()
        val today = now.dayOfWeek

        if (offAt < onAt) return today in days && time >= offAt && time < onAt
        // Overnight: either the period started this evening or it started yesterday evening.
        return (today in days && time >= offAt) || (today.minus(1) in days && time < onAt)
    }
}
