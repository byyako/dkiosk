package com.byyako.dkiosk.screen

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.ImageFormat
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.util.Log
import android.util.Range
import android.util.Size

/**
 * Watches the front camera for movement, a couple of frames a second at low resolution. Frames
 * never leave the device or this class. If something else takes the camera (a page using it, say),
 * it waits until Android reports the camera free again rather than taking it back.
 */
class CameraMotion(
    private val context: Context,
    sensitivity: MotionDetector.Sensitivity,
    private val onMotion: () -> Unit,
) {
    private val detector = MotionDetector(sensitivity)
    private val manager = context.getSystemService(CameraManager::class.java)
    private var thread: HandlerThread? = null
    private var handler: Handler? = null
    private var camera: CameraDevice? = null
    private var session: CameraCaptureSession? = null
    private var reader: ImageReader? = null
    private var lastFrameAt = 0L
    private var cameraId: String? = null

    // Set while another user has the camera; it's reopened once Android says it's free.
    private var waitingForCamera = false
    private val availability = object : CameraManager.AvailabilityCallback() {
        override fun onCameraAvailable(id: String) {
            if (running && waitingForCamera && id == cameraId) {
                waitingForCamera = false
                open()
            }
        }
    }

    @Volatile private var ignoreUntil = 0L

    @Volatile private var running = false

    /** False if there's no camera or no permission to use it. */
    val isAvailable: Boolean
        get() = context.checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED &&
            pickCamera() != null

    fun start() {
        if (running || !isAvailable) return
        running = true
        val worker = HandlerThread("camera-motion").apply { start() }
        thread = worker
        handler = Handler(worker.looper).also {
            manager.registerAvailabilityCallback(availability, it)
            it.post(::open)
        }
    }

    fun stop() {
        running = false
        val worker = thread ?: return
        manager.unregisterAvailabilityCallback(availability)
        handler?.post {
            close()
            worker.quitSafely()
        }
        thread = null
        handler = null
    }

    /** Ignore what the camera sees for a moment, e.g. while the screen changes brightness. */
    fun pause(ms: Long) {
        ignoreUntil = SystemClock.uptimeMillis() + ms
    }

    @SuppressLint("MissingPermission") // Checked by isAvailable before starting.
    private fun open() {
        if (!running) return
        val id = pickCamera() ?: return
        cameraId = id
        val handler = handler ?: return
        try {
            manager.openCamera(id, object : CameraDevice.StateCallback() {
                override fun onOpened(device: CameraDevice) {
                    if (!running) {
                        device.close()
                        return
                    }
                    camera = device
                    startCapture(device, id)
                }

                override fun onDisconnected(device: CameraDevice) = waitForCamera("taken by another user")

                override fun onError(device: CameraDevice, error: Int) = when (error) {
                    ERROR_CAMERA_IN_USE, ERROR_MAX_CAMERAS_IN_USE -> waitForCamera("in use")
                    else -> retryLater("error $error")
                }
            }, handler)
        } catch (e: Exception) {
            // CameraAccessException, or SecurityException if the permission was revoked.
            retryLater(e.toString())
        }
    }

    @Suppress("DEPRECATION") // The newer SessionConfiguration needs API 28.
    private fun startCapture(device: CameraDevice, id: String) {
        val handler = handler ?: return
        val size = pickSize(id)
        val images = ImageReader.newInstance(size.width, size.height, ImageFormat.YUV_420_888, 2)
        reader = images
        images.setOnImageAvailableListener({ onImage(it) }, handler)
        try {
            device.createCaptureSession(listOf(images.surface), object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(capture: CameraCaptureSession) {
                    if (!running) return
                    session = capture
                    val request = device.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
                        addTarget(images.surface)
                        lowestFrameRate(id)?.let { set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, it) }
                    }
                    capture.setRepeatingRequest(request.build(), null, handler)
                }

                override fun onConfigureFailed(capture: CameraCaptureSession) = retryLater("capture setup failed")
            }, handler)
        } catch (e: Exception) {
            retryLater(e.toString())
        }
    }

    private fun onImage(images: ImageReader) {
        val image = images.acquireLatestImage() ?: return
        try {
            val now = SystemClock.uptimeMillis()
            if (now - lastFrameAt < FRAME_EVERY_MS) return
            lastFrameAt = now
            val plane = image.planes[0]
            val buffer = plane.buffer
            val luma = ByteArray(buffer.remaining()).also { buffer.get(it) }
            val moved = detector.onFrame(luma, image.width, image.height, plane.rowStride)
            if (now < ignoreUntil) {
                detector.reset()
            } else if (moved) {
                onMotion()
            }
        } finally {
            image.close()
        }
    }

    private fun waitForCamera(reason: String) {
        Log.i(TAG, "Camera unavailable ($reason), waiting until it's free")
        close()
        waitingForCamera = true
    }

    private fun retryLater(reason: String) {
        Log.i(TAG, "Camera unavailable ($reason), trying again in ${RETRY_MS / 1000} s")
        close()
        handler?.postDelayed(::open, RETRY_MS)
    }

    private fun close() {
        runCatching { session?.close() }
        runCatching { camera?.close() }
        runCatching { reader?.close() }
        session = null
        camera = null
        reader = null
        detector.reset()
    }

    private fun pickCamera(): String? = try {
        val ids = manager.cameraIdList
        ids.firstOrNull {
            manager.getCameraCharacteristics(it).get(CameraCharacteristics.LENS_FACING) ==
                CameraCharacteristics.LENS_FACING_FRONT
        } ?: ids.firstOrNull()
    } catch (e: Exception) {
        null
    }

    /** The smallest YUV size at least 160 pixels wide: plenty to see someone walk up. */
    private fun pickSize(id: String): Size {
        val map = manager.getCameraCharacteristics(id).get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
        val sizes = map?.getOutputSizes(ImageFormat.YUV_420_888).orEmpty()
        return sizes.filter { it.width >= 160 && it.height >= 120 }.minByOrNull { it.width * it.height }
            ?: sizes.minByOrNull { it.width * it.height }
            ?: Size(320, 240)
    }

    private fun lowestFrameRate(id: String): Range<Int>? = manager.getCameraCharacteristics(id)
        .get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES)
        ?.minWithOrNull(compareBy<Range<Int>> { it.upper }.thenBy { it.lower })

    private companion object {
        const val TAG = "CameraMotion"
        const val FRAME_EVERY_MS = 500L
        const val RETRY_MS = 30_000L
    }
}
