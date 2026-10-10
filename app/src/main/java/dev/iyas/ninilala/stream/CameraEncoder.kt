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
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.util.Size
import android.view.Surface
import androidx.annotation.RequiresPermission
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.min

class CameraEncoder(
    private val context: Context,
    private val useFrontCamera: Boolean,
    private val server: StreamServer,
) {
    private val tag = "CameraEncoder"
    private val cameraManager = context.getSystemService(CameraManager::class.java)

    private var cameraThread: HandlerThread? = null
    private var cameraHandler: Handler? = null
    private var codecThread: HandlerThread? = null
    private var codecHandler: Handler? = null

    private var cameraDevice: CameraDevice? = null
    private var captureSession: CameraCaptureSession? = null
    private var encoder: MediaCodec? = null
    private var inputSurface: Surface? = null
    private val running = AtomicBoolean(false)

    @RequiresPermission(Manifest.permission.CAMERA)
    fun start() {
        if (!running.compareAndSet(false, true)) return

        val camThread = HandlerThread("sg-camera").also {
            it.start()
            cameraThread = it
            cameraHandler = Handler(it.looper)
        }
        val encThread = HandlerThread("sg-encoder").also {
            it.start()
            codecThread = it
            codecHandler = Handler(it.looper)
        }

        val cameraId = findCameraId()
            ?: throw IllegalStateException("No ${if (useFrontCamera) "front" else "back"} camera")
        val characteristics = cameraManager.getCameraCharacteristics(cameraId)
        val size = pickStreamSize(characteristics)
        val fps = pickFps(characteristics)
        val bitrate = bitrateFor(size, fps)

        Log.i(tag, "opening camera $cameraId ${size.width}x${size.height}@$fps bitrate=$bitrate")

        val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, size.width, size.height).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            setInteger(MediaFormat.KEY_BIT_RATE, bitrate)
            setInteger(MediaFormat.KEY_FRAME_RATE, fps)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
            setInteger(MediaFormat.KEY_BITRATE_MODE, MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR)
            val orientation = characteristics.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 0
            setInteger(MediaFormat.KEY_ROTATION, orientation)
        }

        val codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC).also { encoder = it }
        codec.setCallback(object : MediaCodec.Callback() {
            override fun onInputBufferAvailable(codec: MediaCodec, index: Int) = Unit

            override fun onOutputBufferAvailable(
                codec: MediaCodec,
                index: Int,
                info: MediaCodec.BufferInfo,
            ) {
                val buffer = codec.getOutputBuffer(index) ?: run {
                    codec.releaseOutputBuffer(index, false)
                    return
                }
                if (info.size > 0) {
                    val bytes = ByteArray(info.size)
                    buffer.position(info.offset)
                    buffer.limit(info.offset + info.size)
                    buffer.get(bytes)
                    if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) {
                        server.setCodecConfig(bytes)
                    } else {
                        val isKey = info.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME != 0
                        server.sendAccessUnit(bytes, 0, bytes.size, isKey)
                    }
                }
                codec.releaseOutputBuffer(index, false)
            }

            override fun onError(codec: MediaCodec, e: MediaCodec.CodecException) {
                Log.e(tag, "encoder error", e)
            }

            override fun onOutputFormatChanged(codec: MediaCodec, format: MediaFormat) {
                Log.i(tag, "encoder format $format")
            }
        }, codecHandler)

        codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        val surface = codec.createInputSurface().also { inputSurface = it }
        codec.start()

        cameraManager.openCamera(cameraId, object : CameraDevice.StateCallback() {
            override fun onOpened(camera: CameraDevice) {
                cameraDevice = camera
                createSession(camera, surface, fps)
            }

            override fun onDisconnected(camera: CameraDevice) {
                camera.close()
                cameraDevice = null
            }

            override fun onError(camera: CameraDevice, error: Int) {
                Log.e(tag, "camera error $error")
                camera.close()
                cameraDevice = null
            }
        }, cameraHandler)
    }

    private fun createSession(camera: CameraDevice, surface: Surface, fps: Int) {
        val handler = cameraHandler ?: return
        val executor = Executor { handler.post(it) }
        val sessionConfig = SessionConfiguration(
            SessionConfiguration.SESSION_REGULAR,
            listOf(OutputConfiguration(surface)),
            executor,
            object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(session: CameraCaptureSession) {
                    if (!running.get()) {
                        session.close()
                        return
                    }
                    captureSession = session
                    val fpsRange = android.util.Range(fps, fps)
                    val request = camera.createCaptureRequest(CameraDevice.TEMPLATE_RECORD).apply {
                        addTarget(surface)
                        set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO)
                        set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, fpsRange)
                    }.build()
                    try {
                        session.setRepeatingRequest(request, null, cameraHandler)
                    } catch (e: Exception) {
                        Log.w(tag, "exact fps $fps failed, retrying without lock", e)
                        val fallback = camera.createCaptureRequest(CameraDevice.TEMPLATE_RECORD).apply {
                            addTarget(surface)
                            set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO)
                        }.build()
                        session.setRepeatingRequest(fallback, null, cameraHandler)
                    }
                }

                override fun onConfigureFailed(session: CameraCaptureSession) {
                    Log.e(tag, "capture session configure failed")
                }
            },
        )
        camera.createCaptureSession(sessionConfig)
    }

    fun stop() {
        if (!running.compareAndSet(true, false)) return
        runCatching { captureSession?.stopRepeating() }
        runCatching { captureSession?.close() }
        captureSession = null
        runCatching { cameraDevice?.close() }
        cameraDevice = null
        runCatching { encoder?.stop() }
        runCatching { encoder?.release() }
        encoder = null
        runCatching { inputSurface?.release() }
        inputSurface = null
        cameraThread?.quitSafely()
        codecThread?.quitSafely()
        cameraThread = null
        codecThread = null
        cameraHandler = null
        codecHandler = null
    }

    private fun findCameraId(): String? {
        val facing = if (useFrontCamera) {
            CameraCharacteristics.LENS_FACING_FRONT
        } else {
            CameraCharacteristics.LENS_FACING_BACK
        }
        return cameraManager.cameraIdList.firstOrNull { id ->
            cameraManager.getCameraCharacteristics(id)
                .get(CameraCharacteristics.LENS_FACING) == facing
        }
    }

    private fun pickStreamSize(characteristics: CameraCharacteristics): Size {
        val map = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
            ?: throw IllegalStateException("No stream configuration map")
        val cameraSizes = map.getOutputSizes(Surface::class.java)?.toList().orEmpty()
        val probe = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
        val encoderCaps = probe.codecInfo
            .getCapabilitiesForType(MediaFormat.MIMETYPE_VIDEO_AVC)
            .videoCapabilities
        probe.release()

        val supported = cameraSizes.filter { size ->
            encoderCaps?.isSizeSupported(size.width, size.height) == true
        }.sortedByDescending { it.width.toLong() * it.height.toLong() }

        // Prefer a 16:9 size near the top end for typical phone cameras.
        val preferred = supported.firstOrNull { size ->
            val ratio = size.width.toFloat() / size.height.toFloat()
            ratio in 1.7f..1.8f && size.width >= 1280
        }
        return preferred ?: supported.firstOrNull()
            ?: Size(1280, 720)
    }

    private fun pickFps(characteristics: CameraCharacteristics): Int {
        val ranges = characteristics.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES)
            ?: return 30
        val supports30 = ranges.any { it.lower <= 30 && it.upper >= 30 }
        val supports60 = ranges.any { it.lower <= 60 && it.upper >= 60 }
        // Prefer 30 for battery/thermal; only use 60 if 30 is missing.
        return when {
            supports30 -> 30
            supports60 -> 60
            else -> ranges.maxOfOrNull { it.upper }?.coerceAtMost(30) ?: 30
        }
    }

    private fun bitrateFor(size: Size, fps: Int): Int {
        val raw = (0.12 * size.width * size.height * fps).toInt()
        return min(raw, 12_000_000).coerceAtLeast(2_000_000)
    }
}
