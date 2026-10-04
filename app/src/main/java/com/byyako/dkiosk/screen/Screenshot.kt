package com.byyako.dkiosk.screen

import android.graphics.Bitmap
import android.os.Handler
import android.os.HandlerThread
import android.view.PixelCopy
import android.view.Window
import androidx.core.graphics.createBitmap
import androidx.core.graphics.scale
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

/**
 * Captures what the window shows, WebView included (drawing the view hierarchy would miss the
 * page's hardware layers). Results arrive on a background thread, so the main thread never waits.
 */
object Screenshot {

    private val thread by lazy { HandlerThread("screenshot").apply { start() } }

    /** Call on the main thread; the future completes on a background thread. */
    fun request(window: Window): CompletableFuture<Bitmap> {
        val result = CompletableFuture<Bitmap>()
        val view = window.decorView
        if (view.width == 0 || view.height == 0) {
            result.completeExceptionally(IOException("The window isn't laid out"))
            return result
        }
        val bitmap = createBitmap(view.width, view.height)
        PixelCopy.request(window, bitmap, { code ->
            if (code == PixelCopy.SUCCESS) {
                result.complete(bitmap)
            } else {
                bitmap.recycle()
                result.completeExceptionally(IOException("Screen capture failed ($code)"))
            }
        }, Handler(thread.looper))
        return result
    }

    /** Waits for [capture] and encodes it, scaled down so the longest side is at most [maxSide]. */
    fun toJpeg(capture: CompletableFuture<Bitmap>, maxSide: Int = 1280, quality: Int = 80): ByteArray {
        val bitmap = capture.get(5, TimeUnit.SECONDS)
        val scale = maxSide.toFloat() / maxOf(bitmap.width, bitmap.height)
        val sized = if (scale < 1f) {
            bitmap.scale((bitmap.width * scale).toInt(), (bitmap.height * scale).toInt())
        } else bitmap
        try {
            return ByteArrayOutputStream().use {
                sized.compress(Bitmap.CompressFormat.JPEG, quality, it)
                it.toByteArray()
            }
        } finally {
            if (sized !== bitmap) sized.recycle()
            bitmap.recycle()
        }
    }
}
