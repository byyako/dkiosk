package com.tyllad.dkiosk.screen

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.DayOfWeek
import java.time.DayOfWeek.FRIDAY
import java.time.DayOfWeek.MONDAY
import java.time.DayOfWeek.SATURDAY
import java.time.DayOfWeek.SUNDAY
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime

class ScreenScheduleTest {

    private val everyDay = DayOfWeek.entries.toSet()
    private val weekdays = DayOfWeek.entries.toSet() - SATURDAY - SUNDAY

    // 2026-09-21 is a Monday.
    private fun at(day: DayOfWeek, time: String): LocalDateTime =
        LocalDateTime.of(LocalDate.of(2026, 9, 21).plusDays(day.value - 1L), LocalTime.parse(time))

    private fun schedule(off: String, on: String, days: Set<DayOfWeek> = everyDay) =
        ScreenSchedule(LocalTime.parse(off), LocalTime.parse(on), days)

    @Test
    fun overnightPeriodSpansMidnight() {
        val night = schedule("22:00", "07:00")
        assertFalse(night.isOffPeriod(at(MONDAY, "21:59")))
        assertTrue(night.isOffPeriod(at(MONDAY, "22:00")))
        assertTrue(night.isOffPeriod(at(MONDAY, "23:59")))
        assertTrue(night.isOffPeriod(at(MONDAY, "00:00")))
        assertTrue(night.isOffPeriod(at(MONDAY, "06:59")))
        assertFalse(night.isOffPeriod(at(MONDAY, "07:00")))
        assertFalse(night.isOffPeriod(at(MONDAY, "12:00")))
    }

    @Test
    fun sameDayPeriod() {
        val workHours = schedule("09:00", "17:00")
        assertFalse(workHours.isOffPeriod(at(MONDAY, "08:59")))
        assertTrue(workHours.isOffPeriod(at(MONDAY, "09:00")))
        assertTrue(workHours.isOffPeriod(at(MONDAY, "16:59")))
        assertFalse(workHours.isOffPeriod(at(MONDAY, "17:00")))
    }

    @Test
    fun overnightPeriodBelongsToTheDayItStarts() {
        val weeknights = schedule("22:00", "07:00", weekdays)
        // Friday night still counts through Saturday morning...
        assertTrue(weeknights.isOffPeriod(at(FRIDAY, "23:00")))
        assertTrue(weeknights.isOffPeriod(at(SATURDAY, "06:00")))
        // ...but Saturday night doesn't start one, and Sunday night does not either.
        assertFalse(weeknights.isOffPeriod(at(SATURDAY, "23:00")))
        assertFalse(weeknights.isOffPeriod(at(SUNDAY, "06:00")))
        assertFalse(weeknights.isOffPeriod(at(SUNDAY, "23:00")))
        // Monday morning is the tail of Sunday night, which isn't scheduled.
        assertFalse(weeknights.isOffPeriod(at(MONDAY, "06:00")))
        assertTrue(weeknights.isOffPeriod(at(MONDAY, "22:30")))
    }

    @Test
    fun sameDayPeriodOnlyOnChosenDays() {
        val weekdayHours = schedule("09:00", "17:00", weekdays)
        assertTrue(weekdayHours.isOffPeriod(at(FRIDAY, "10:00")))
        assertFalse(weekdayHours.isOffPeriod(at(SATURDAY, "10:00")))
    }

    @Test
    fun equalTimesOrNoDaysMeanNeverOff() {
        assertFalse(schedule("08:00", "08:00").isOffPeriod(at(MONDAY, "08:00")))
        assertFalse(schedule("22:00", "07:00", emptySet()).isOffPeriod(at(MONDAY, "23:00")))
    }
}
