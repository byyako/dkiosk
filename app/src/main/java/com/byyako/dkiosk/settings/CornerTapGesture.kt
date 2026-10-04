package com.byyako.dkiosk.settings

/** Counts completed, short taps; dragging, cancellation and multitouch break the sequence. */
class CornerTapGesture(private val touchSlop: Float, private val sequence: TapSequence = TapSequence()) {
    private var downAt: Long? = null
    private var downX = 0f
    private var downY = 0f

    val windowMs: Long get() = sequence.windowMs

    data class Result(val progress: Int, val complete: Boolean)

    fun down(x: Float, y: Float, timeMs: Long) {
        downX = x
        downY = y
        downAt = timeMs
    }

    fun move(x: Float, y: Float) {
        val dx = x - downX
        val dy = y - downY
        if (dx * dx + dy * dy > touchSlop * touchSlop) reset()
    }

    fun up(x: Float, y: Float, timeMs: Long): Result {
        move(x, y)
        val started = downAt
        downAt = null
        if (started == null || timeMs - started !in 0..MAX_TAP_MS) {
            reset()
            return Result(0, false)
        }
        val complete = sequence.onTap(timeMs)
        return Result(sequence.progress, complete)
    }

    fun reset() {
        downAt = null
        sequence.reset()
    }

    private companion object {
        const val MAX_TAP_MS = 500L
    }
}
