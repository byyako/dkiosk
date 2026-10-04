package com.byyako.dkiosk.settings

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import androidx.core.graphics.createBitmap
import androidx.core.graphics.set
import com.byyako.dkiosk.R
import com.byyako.dkiosk.config.KioskPrefs
import com.byyako.dkiosk.config.SettingsTransfer
import com.google.zxing.common.BitMatrix
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream

/** Copying settings between kiosks, shared by the first-run and settings screens. */
object Backup {

    private const val MAX_FILE_BYTES = 256 * 1024

    /**
     * Applies settings exported from another kiosk and returns notes (string resources) for the
     * administrator.
     * Throws [SettingsTransfer.ImportException] if [text] isn't usable.
     */
    fun import(context: Context, prefs: KioskPrefs, text: String): List<Int> {
        val values = SettingsTransfer.import(text).toMutableMap()
        val notes = mutableListOf<Int>()
        val camera = context.checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
        if (values[KioskPrefs.WAKE_MOTION] == true && !camera) {
            values[KioskPrefs.WAKE_MOTION] = false
            notes += R.string.import_motion_off
        }
        prefs.applyImport(values)
        if (prefs.mqttEnabled && prefs.mqttUsername != null && prefs.mqttPassword == null) {
            notes += R.string.import_mqtt_password
        }
        return notes
    }

    fun read(context: Context, uri: Uri): String {
        val stream = context.contentResolver.openInputStream(uri) ?: throw IOException("Can't open the file")
        val bytes = stream.use { it.readNBytesCompat(MAX_FILE_BYTES + 1) }
        if (bytes.size > MAX_FILE_BYTES) throw SettingsTransfer.ImportException("The file is too large")
        return bytes.toString(Charsets.UTF_8)
    }

    fun write(context: Context, uri: Uri, text: String) {
        val stream = context.contentResolver.openOutputStream(uri, "wt") ?: throw IOException("Can't write the file")
        stream.use { it.write(text.toByteArray(Charsets.UTF_8)) }
    }

    fun bitmap(matrix: BitMatrix, moduleSize: Int = 8): Bitmap {
        val bitmap = createBitmap(matrix.width * moduleSize, matrix.height * moduleSize)
        for (y in 0 until bitmap.height) {
            for (x in 0 until bitmap.width) {
                bitmap[x, y] = if (matrix.get(x / moduleSize, y / moduleSize)) Color.BLACK else Color.WHITE
            }
        }
        return bitmap
    }

    /** InputStream.readNBytes needs API 33. */
    private fun InputStream.readNBytesCompat(limit: Int): ByteArray {
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        while (out.size() < limit) {
            val count = read(buffer, 0, minOf(buffer.size, limit - out.size()))
            if (count < 0) break
            out.write(buffer, 0, count)
        }
        return out.toByteArray()
    }
}
