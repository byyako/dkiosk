package com.byyako.dkiosk.recovery

import android.os.SystemClock
import java.time.LocalTime
import java.time.format.DateTimeFormatter

/**
 * What the kiosk had to recover from since the app started, for the diagnostics screen. Kept in
 * memory only: it's for an administrator looking at the device, not a log that grows forever.
 */
object Diagnostics {

    val startedAt: Long = SystemClock.elapsedRealtime()

    @Volatile var loadErrors = 0
        private set

    @Volatile var rendererLosses = 0
        private set

    @Volatile var freezes = 0
        private set

    private val events = ArrayDeque<String>()

    @Synchronized
    fun loadFailed(host: String, reason: String) {
        loadErrors++
        add("Couldn't load $host: $reason")
    }

    @Synchronized
    fun rendererGone(crashed: Boolean) {
        rendererLosses++
        add(if (crashed) "Page renderer crashed, restarted it" else "Android stopped the page renderer, restarted it")
    }

    @Synchronized
    fun froze() {
        freezes++
        add("Page stopped responding, restarted it")
    }

    @Synchronized
    fun note(event: String) = add(event)

    /** Most recent first. */
    @Synchronized
    fun recent(): List<String> = events.reversed()

    private fun add(event: String) {
        events.addLast("${LocalTime.now().format(TIME)}  $event")
        while (events.size > MAX_EVENTS) events.removeFirst()
    }

    private const val MAX_EVENTS = 20
    private val TIME = DateTimeFormatter.ofPattern("HH:mm:ss")
}
