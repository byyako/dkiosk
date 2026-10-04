package com.byyako.dkiosk.setup

import android.Manifest
import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.ImageFormat
import android.graphics.Matrix
import android.graphics.RectF
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.media.ImageReader
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.util.Size
import android.view.Surface
import android.view.TextureView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.byyako.dkiosk.R
import com.byyako.dkiosk.config.SetupCodes
import com.byyako.dkiosk.databinding.ActivityScanBinding
import com.byyako.dkiosk.ui.padForSystemBars

/** Scans a setup code with the camera and returns its text as [EXTRA_TEXT]. */
class ScanActivity : AppCompatActivity() {

    private lateinit var binding: ActivityScanBinding
    private val manager by lazy { getSystemService(CameraManager::class.java) }
    private var thread: HandlerThread? = null
    private var handler: Handler? = null
    private var camera: CameraDevice? = null
    private var session: CameraCaptureSession? = null
    private var reader: ImageReader? = null
    private var previewSize = Size(640, 480)
    private var sensorOrientation = 90

    @Volatile private var found = false

    private val requestCamera = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) startWhenReady() else {
            Toast.makeText(this, R.string.scan_no_camera, Toast.LENGTH_LONG).show()
            finish()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityScanBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.root.padForSystemBars()
        binding.cancel.setOnClickListener { finish() }
    }

    override fun onResume() {
        super.onResume()
        if (checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            startWhenReady()
        } else requestCamera.launch(Manifest.permission.CAMERA)
    }

    override fun onPause() {
        stopCamera()
        super.onPause()
    }

    private fun startWhenReady() {
        if (thread != null) return
        val preview = binding.preview
        if (preview.isAvailable) {
            startCamera(preview.surfaceTexture ?: return)
        } else preview.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
            override fun onSurfaceTextureAvailable(texture: SurfaceTexture, width: Int, height: Int) = startCamera(texture)

            override fun onSurfaceTextureSizeChanged(texture: SurfaceTexture, width: Int, height: Int) = fitPreview()

            override fun onSurfaceTextureDestroyed(texture: SurfaceTexture) = true

            override fun onSurfaceTextureUpdated(texture: SurfaceTexture) = Unit
        }
    }

    @SuppressLint("MissingPermission") // Checked in onResume.
    private fun startCamera(texture: SurfaceTexture) {
        if (thread != null || isFinishing) return
        val id = pickCamera() ?: return fail()
        val characteristics = manager.getCameraCharacteristics(id)
        sensorOrientation = characteristics.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 90
        val sizes = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
            ?.getOutputSizes(ImageFormat.YUV_420_888).orEmpty()
        // About VGA: sharp enough for a code held up to the camera, quick enough to decode.
        previewSize = sizes.filter { it.width <= 1280 && it.height <= 960 }.maxByOrNull { it.width * it.height }
            ?: sizes.minByOrNull { it.width * it.height } ?: Size(640, 480)
        texture.setDefaultBufferSize(previewSize.width, previewSize.height)
        fitPreview()

        val worker = HandlerThread("scan").apply { start() }
        thread = worker
        val handler = Handler(worker.looper)
        this.handler = handler
        val images = ImageReader.newInstance(previewSize.width, previewSize.height, ImageFormat.YUV_420_888, 2)
        reader = images
        images.setOnImageAvailableListener({ decode(it) }, handler)
        val previewSurface = Surface(texture)
        try {
            manager.openCamera(id, object : CameraDevice.StateCallback() {
                override fun onOpened(device: CameraDevice) {
                    camera = device
                    @Suppress("DEPRECATION") // SessionConfiguration needs API 28.
                    device.createCaptureSession(
                        listOf(previewSurface, images.surface),
                        object : CameraCaptureSession.StateCallback() {
                            override fun onConfigured(capture: CameraCaptureSession) {
                                session = capture
                                val request = device.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
                                    addTarget(previewSurface)
                                    addTarget(images.surface)
                                }
                                runCatching { capture.setRepeatingRequest(request.build(), null, handler) }
                            }

                            override fun onConfigureFailed(capture: CameraCaptureSession) = fail()
                        },
                        handler,
                    )
                }

                override fun onDisconnected(device: CameraDevice) = device.close()

                override fun onError(device: CameraDevice, error: Int) = fail()
            }, handler)
        } catch (e: Exception) {
            Log.w(TAG, "Couldn't open the camera", e)
            fail()
        }
    }

    private fun decode(images: ImageReader) {
        val image = images.acquireLatestImage() ?: return
        try {
            if (found) return
            val plane = image.planes[0]
            val luma = ByteArray(plane.buffer.remaining()).also { plane.buffer.get(it) }
            val text = SetupCodes.decode(luma, image.width, image.height, plane.rowStride) ?: return
            found = true
            runOnUiThread {
                setResult(RESULT_OK, Intent().putExtra(EXTRA_TEXT, text))
                finish()
            }
        } finally {
            image.close()
        }
    }

    /** Rotates and crops the camera picture to fill the screen without stretching it. */
    private fun fitPreview() {
        val view = binding.preview
        if (view.width == 0 || view.height == 0) return
        @Suppress("DEPRECATION") // Activity.display needs API 30.
        val rotation = windowManager.defaultDisplay.rotation
        val degrees = when (rotation) {
            Surface.ROTATION_90 -> 90
            Surface.ROTATION_180 -> 180
            Surface.ROTATION_270 -> 270
            else -> 0
        }
        // How the picture is turned relative to the screen; at 90 or 270 its sides swap.
        val turned = (sensorOrientation - degrees + 360) % 180 != 0
        val pictureWidth = if (turned) previewSize.height else previewSize.width
        val pictureHeight = if (turned) previewSize.width else previewSize.height

        val matrix = Matrix()
        val viewRect = RectF(0f, 0f, view.width.toFloat(), view.height.toFloat())
        val centerX = viewRect.centerX()
        val centerY = viewRect.centerY()
        if (rotation == Surface.ROTATION_90 || rotation == Surface.ROTATION_270) {
            val bufferRect = RectF(0f, 0f, previewSize.height.toFloat(), previewSize.width.toFloat())
            bufferRect.offset(centerX - bufferRect.centerX(), centerY - bufferRect.centerY())
            matrix.setRectToRect(viewRect, bufferRect, Matrix.ScaleToFit.FILL)
            val scale = maxOf(view.height.toFloat() / previewSize.height, view.width.toFloat() / previewSize.width)
            matrix.postScale(scale, scale, centerX, centerY)
            matrix.postRotate(90f * (rotation - 2), centerX, centerY)
        } else {
            // The camera already turns the picture upright; undo the stretch to the view's shape.
            val viewAspect = view.width.toFloat() / view.height
            val pictureAspect = pictureWidth.toFloat() / pictureHeight
            if (viewAspect > pictureAspect) {
                matrix.setScale(1f, viewAspect / pictureAspect, centerX, centerY)
            } else matrix.setScale(pictureAspect / viewAspect, 1f, centerX, centerY)
            if (rotation == Surface.ROTATION_180) matrix.postRotate(180f, centerX, centerY)
        }
        view.setTransform(matrix)
    }

    private fun fail() {
        runOnUiThread {
            Toast.makeText(this, R.string.scan_no_camera, Toast.LENGTH_LONG).show()
            finish()
        }
    }

    private fun stopCamera() {
        val worker = thread ?: return
        val handler = handler
        thread = null
        this.handler = null
        handler?.post {
            runCatching { session?.close() }
            runCatching { camera?.close() }
            runCatching { reader?.close() }
            session = null
            camera = null
            reader = null
            worker.quitSafely()
        }
    }

    /** The back camera if there is one: it's the one pointed away from the screen. */
    private fun pickCamera(): String? = try {
        val ids = manager.cameraIdList
        ids.firstOrNull {
            manager.getCameraCharacteristics(it).get(CameraCharacteristics.LENS_FACING) ==
                CameraCharacteristics.LENS_FACING_BACK
        } ?: ids.firstOrNull()
    } catch (e: Exception) {
        null
    }

    companion object {
        const val EXTRA_TEXT = "text"
        private const val TAG = "ScanActivity"
    }
}
