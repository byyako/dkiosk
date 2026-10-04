package com.byyako.dkiosk.config

import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.EncodeHintType
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.ReaderException
import com.google.zxing.WriterException
import com.google.zxing.common.BitMatrix
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeReader
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel

/** QR codes holding exported settings, so a new kiosk can be set up by pointing its camera at one. */
object SetupCodes {

    /** The QR code for [text], or null if it holds too much for one code. */
    fun encode(text: String): BitMatrix? = try {
        QRCodeWriter().encode(
            text, BarcodeFormat.QR_CODE, 0, 0,
            mapOf(
                EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.L,
                EncodeHintType.CHARACTER_SET to "UTF-8",
                EncodeHintType.MARGIN to 2,
            ),
        )
    } catch (_: WriterException) {
        null
    }

    /** Looks for a QR code in a camera frame's luminance plane ([rowStride] bytes per row). */
    fun decode(luma: ByteArray, width: Int, height: Int, rowStride: Int): String? {
        val packed = if (rowStride == width) luma else ByteArray(width * height).also { out ->
            for (row in 0 until height) System.arraycopy(luma, row * rowStride, out, row * width, width)
        }
        val source = PlanarYUVLuminanceSource(packed, width, height, 0, 0, width, height, false)
        return try {
            QRCodeReader().decode(
                BinaryBitmap(HybridBinarizer(source)),
                mapOf(DecodeHintType.TRY_HARDER to true, DecodeHintType.CHARACTER_SET to "UTF-8"),
            ).text
        } catch (_: ReaderException) {
            null
        }
    }
}
