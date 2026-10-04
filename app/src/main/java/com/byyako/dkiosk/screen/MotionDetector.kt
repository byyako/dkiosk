package com.byyako.dkiosk.screen

import kotlin.math.abs

/**
 * Spots movement between camera frames. Each frame's brightness is averaged over a coarse grid and
 * compared with the previous one after removing the overall brightness change, so the screen
 * lighting up the room, or the camera adjusting its exposure, doesn't count as motion.
 */
class MotionDetector(private val sensitivity: Sensitivity = Sensitivity.MEDIUM) {

    /** [cellChange] is how much a cell must change (0-255); [area] the share of cells that must. */
    enum class Sensitivity(val cellChange: Int, val area: Float) {
        LOW(22, 0.06f),
        MEDIUM(14, 0.03f),
        HIGH(9, 0.015f),
    }

    private var previous: IntArray? = null

    /**
     * Takes the luminance plane of a frame ([rowStride] bytes per row) and returns true if it shows
     * motion compared with the last one.
     */
    fun onFrame(luma: ByteArray, width: Int, height: Int, rowStride: Int): Boolean {
        val grid = average(luma, width, height, rowStride)
        val last = previous
        previous = grid
        if (last == null) return false

        val shift = grid.average() - last.average()
        val changed = grid.indices.count { abs(grid[it] - last[it] - shift) > sensitivity.cellChange }
        return changed >= grid.size * sensitivity.area
    }

    /** Forget the last frame, e.g. after the screen changed brightness. */
    fun reset() {
        previous = null
    }

    private fun average(luma: ByteArray, width: Int, height: Int, rowStride: Int): IntArray {
        val cells = IntArray(COLUMNS * ROWS)
        val cellWidth = width / COLUMNS
        val cellHeight = height / ROWS
        for (row in 0 until ROWS) {
            for (column in 0 until COLUMNS) {
                var sum = 0
                var count = 0
                // Every other pixel each way is plenty for an average and quarters the work.
                var y = row * cellHeight
                while (y < (row + 1) * cellHeight) {
                    var x = column * cellWidth
                    val offset = y * rowStride
                    while (x < (column + 1) * cellWidth) {
                        sum += luma[offset + x].toInt() and 0xFF
                        count++
                        x += 2
                    }
                    y += 2
                }
                cells[row * COLUMNS + column] = if (count == 0) 0 else sum / count
            }
        }
        return cells
    }

    companion object {
        const val COLUMNS = 16
        const val ROWS = 12
    }
}
