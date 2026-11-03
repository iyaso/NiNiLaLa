package dev.iyas.ninilala.stream

import android.Manifest
import android.content.Context
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.params.OutputConfiguration
import android.hardware.camera2.params.SessionConfiguration
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.util.Size
import android.view.Surface
import androidx.annotation.RequiresPermission
import java.util.concurrent.Executor
import kotlin.math.abs

data class PreviewProfile(
    val bufferSize: Size,
    /** Width / height for the on-screen view after sensor orientation. */
    val displayAspectRatio: Float,
)

class PreviewCamera(private val context: Context) {
    private val tag = "PreviewCamera"
    private val cameraManager = context.getSystemService(CameraManager::class.java)

    private var thread: HandlerThread? = null
    private var handler: Handler? = null
    private var cameraDevice: CameraDevice? = null
    private var session: CameraCaptureSession? = null
    private var opened = false

    fun resolveProfile(useFront: Boolean): PreviewProfile? {
        val cameraId = findCameraId(useFront) ?: return null
        val characteristics = cameraManager.getCameraCharacteristics(cameraId)
        val bufferSize = pickPreviewSize(characteristics)
        val orientation = characteristics.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 0
        val swapped = orientation % 180 != 0
        val displayAspect = if (swapped) {
            bufferSize.height.toFloat() / bufferSize.width.toFloat()
        } else {
            bufferSize.width.toFloat() / bufferSize.height.toFloat()
        }
        return PreviewProfile(bufferSize, displayAspect)
    }

    @RequiresPermission(Manifest.permission.CAMERA)
    fun start(useFront: Boolean, surface: Surface, bufferSize: Size) {
        stop()
        HandlerThread("sg-preview").also {
            it.start()
            thread = it
            handler = Handler(it.looper)
        }
        opened = true
        val cameraId = findCameraId(useFront) ?: run {
            Log.e(tag, "no camera")
            return
        }

        cameraManager.openCamera(cameraId, object : CameraDevice.StateCallback() {
            override fun onOpened(camera: CameraDevice) {
                if (!opened) {
                    camera.close()
                    return
                }
                cameraDevice = camera
                val h = handler ?: return
                val executor = Executor { h.post(it) }
                val config = SessionConfiguration(
                    SessionConfiguration.SESSION_REGULAR,
                    listOf(OutputConfiguration(surface)),
                    executor,
                    object : CameraCaptureSession.StateCallback() {
                        override fun onConfigured(captureSession: CameraCaptureSession) {
                            if (!opened) {
                                captureSession.close()
                                return
                            }
                            session = captureSession
                            val request = camera.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
                                addTarget(surface)
                                set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO)
                            }.build()
                            captureSession.setRepeatingRequest(request, null, handler)
                            Log.i(tag, "preview ${bufferSize.width}x${bufferSize.height}")
                        }

                        override fun onConfigureFailed(session: CameraCaptureSession) {
                            Log.e(tag, "preview configure failed")
                        }
                    },
                )
                camera.createCaptureSession(config)
            }

            override fun onDisconnected(camera: CameraDevice) {
                camera.close()
                cameraDevice = null
            }

            override fun onError(camera: CameraDevice, error: Int) {
                Log.e(tag, "preview camera error $error")
                camera.close()
                cameraDevice = null
            }
        }, handler)
    }

    fun stop() {
        opened = false
        runCatching { session?.stopRepeating() }
        runCatching { session?.close() }
        session = null
        runCatching { cameraDevice?.close() }
        cameraDevice = null
        thread?.quitSafely()
        thread = null
        handler = null
    }

    private fun findCameraId(useFront: Boolean): String? {
        val facing = if (useFront) {
            CameraCharacteristics.LENS_FACING_FRONT
        } else {
            CameraCharacteristics.LENS_FACING_BACK
        }
        return cameraManager.cameraIdList.firstOrNull { id ->
            cameraManager.getCameraCharacteristics(id)
                .get(CameraCharacteristics.LENS_FACING) == facing
        }
    }

    private fun pickPreviewSize(characteristics: CameraCharacteristics): Size {
        val map = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
            ?: return Size(1280, 720)
        val sizes = map.getOutputSizes(Surface::class.java)?.toList().orEmpty()
        if (sizes.isEmpty()) return Size(1280, 720)

        // Prefer a 16:9 size around 1080p — sharp enough, light for preview.
        val preferred = sizes
            .filter { it.width * it.height <= 1920 * 1080 }
            .minByOrNull { size ->
                val ratio = size.width.toFloat() / size.height.toFloat()
                abs(ratio - (16f / 9f)) * 1000 + abs(size.width - 1280)
            }
        return preferred ?: sizes.maxBy { it.width * it.height }
    }
}
