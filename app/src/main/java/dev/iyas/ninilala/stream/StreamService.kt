package dev.iyas.ninilala.stream

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.MediaCodec
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.lifecycle.LifecycleService
import com.pedro.common.AudioCodec
import com.pedro.common.ConnectChecker
import com.pedro.common.VideoCodec
import com.pedro.encoder.input.video.CameraHelper
import com.pedro.library.base.Camera2Base
import com.pedro.rtspserver.server.RtspServer
import com.pedro.rtspserver.util.RtspServerStreamClient
import dev.iyas.ninilala.MainActivity
import dev.iyas.ninilala.R
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.nio.ByteBuffer

class StreamService : LifecycleService() {
    private val tag = "StreamService"

    private var wakeLock: PowerManager.WakeLock? = null
    private var rtspCamera: Camera2Base? = null
    private var webServer: StreamWebServer? = null
    private var advertiser: NsdAdvertiser? = null
    private var isStopping = false

    override fun onCreate() {
        super.onCreate()
        isRunning.value = true
    }

    override fun onBind(intent: Intent): IBinder? {
        super.onBind(intent)
        return null
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        when (intent?.action) {
            ACTION_STOP -> {
                stopStreaming()
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_START -> {
                val useFront = intent.getBooleanExtra(EXTRA_FRONT_CAMERA, false)
                startStreaming(useFront)
            }
        }
        return START_STICKY
    }

    @SuppressLint("MissingPermission")
    private fun startStreaming(useFront: Boolean) {
        if (rtspCamera != null) return

        ensureChannel()
        val notification = buildNotification()
        ServiceCompat.startForeground(
            this,
            NOTIFICATION_ID,
            notification,
            ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA,
        )

        val powerManager = getSystemService(PowerManager::class.java)
        wakeLock = powerManager.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "NiNiLaLa:Stream",
        ).also { it.acquire(6 * 60 * 60 * 1000L) }

        val connectChecker = object : ConnectChecker {
            override fun onConnectionStarted(url: String) {
                Log.i(tag, "RTSP client connected: $url")
                _viewerConnected.value = true
            }

            override fun onConnectionSuccess() {
                Log.i(tag, "RTSP connection established")
            }

            override fun onConnectionFailed(reason: String) {
                Log.e(tag, "RTSP connection failed: $reason")
                _viewerConnected.value = false
            }

            override fun onDisconnect() {
                Log.i(tag, "RTSP client disconnected")
                _viewerConnected.value = false
            }

            override fun onAuthError() {
                Log.e(tag, "RTSP auth error")
            }

            override fun onAuthSuccess() {
                Log.i(tag, "RTSP auth success")
            }
        }

        try {
            val web = StreamWebServer(this).also { webServer = it }
            web.onViewerCountChanged = { count -> _viewerConnected.value = count > 0 }
            if (web.startServer()) {
                _webUrl.value = StreamWebServer.findLanUrl()
            }

            val camera = WebTappedRtspCamera(this, connectChecker, RTSP_PORT, web).apply {
                // 5th arg is rotation: 90 = portrait, 0 = landscape. Wrong value streams rotated video.
                prepareVideo(1280, 720, 30, 2_000_000, CameraHelper.getCameraOrientation(this@StreamService))
                prepareAudio(44100, 64 * 1024, false)
                startStream()
            }

            if (useFront) {
                runCatching { camera.switchCamera() }
            }

            rtspCamera = camera

            val nsd = NsdAdvertiser(this).also { advertiser = it }
            nsd.register(RTSP_PORT)

            Log.i(tag, "RTSP streaming on port $RTSP_PORT")
        } catch (e: Exception) {
            Log.e(tag, "failed to start RTSP streaming", e)
            stopStreaming()
            stopSelf()
        }
    }

    private fun stopStreaming() {
        if (isStopping) return
        isStopping = true

        runCatching { rtspCamera?.stopStream() }
        rtspCamera = null

        runCatching { webServer?.stopServer() }
        webServer = null
        _webUrl.value = null

        runCatching { advertiser?.unregister() }
        advertiser = null
        
        _viewerConnected.value = false
        wakeLock?.let { lock ->
            if (lock.isHeld) lock.release()
        }
        wakeLock = null
        runCatching { stopForeground(STOP_FOREGROUND_REMOVE) }
        isStopping = false
    }

    override fun onDestroy() {
        stopStreaming()
        isRunning.value = false
        super.onDestroy()
    }

    private fun ensureChannel() {
        val manager = getSystemService(NotificationManager::class.java)
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.notification_channel_name),
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = getString(R.string.notification_channel_desc)
            setShowBadge(false)
        }
        manager.createNotificationChannel(channel)
    }

    private fun buildNotification(): Notification {
        val openIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val stopIntent = PendingIntent.getService(
            this,
            1,
            Intent(this, StreamService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(getString(R.string.notification_streaming_title))
            .setContentText(getString(R.string.notification_streaming_text))
            .setContentIntent(openIntent)
            .setOngoing(true)
            .setSilent(true)
            .addAction(0, getString(R.string.action_stop), stopIntent)
            .build()
    }

    // RtspServerCamera2 is final in 1.4.3, so Camera2Base is subclassed directly and the
    // RTSP server wired in — exactly like RtspServerCamera2, plus the web-viewer taps.
    private class WebTappedRtspCamera(
        context: Context,
        connectChecker: ConnectChecker,
        port: Int,
        private val web: StreamWebServer,
    ) : Camera2Base(context) {

        private val rtspServer = RtspServer(connectChecker, port)

        fun startStream() {
            startStream("")
        }

        override fun onAudioInfoImp(isStereo: Boolean, sampleRate: Int) {
            rtspServer.setAudioInfo(sampleRate, isStereo)
        }

        override fun startStreamImp(url: String) {
            rtspServer.startServer()
        }

        override fun stopStreamImp() {
            rtspServer.stopServer()
        }

        override fun getAudioDataImp(audioBuffer: ByteBuffer, info: MediaCodec.BufferInfo) {
            rtspServer.sendAudio(audioBuffer, info)
        }

        override fun onVideoInfoImp(sps: ByteBuffer, pps: ByteBuffer?, vps: ByteBuffer?) {
            rtspServer.setVideoInfo(sps.duplicate(), pps?.duplicate(), vps?.duplicate())
            web.setConfig(sps, pps)
        }

        override fun getVideoDataImp(videoBuffer: ByteBuffer, info: MediaCodec.BufferInfo) {
            rtspServer.sendVideo(videoBuffer, info)
            web.publish(videoBuffer, info)
        }

        override fun getStreamClient(): RtspServerStreamClient = RtspServerStreamClient(rtspServer)

        override fun setVideoCodecImp(codec: VideoCodec) {
            rtspServer.setVideoCodec(codec)
        }

        override fun setAudioCodecImp(codec: AudioCodec) {
            rtspServer.setAudioCodec(codec)
        }
    }

    companion object {
        const val ACTION_START = "dev.iyas.ninilala.action.START"
        const val ACTION_STOP = "dev.iyas.ninilala.action.STOP"
        const val EXTRA_FRONT_CAMERA = "front_camera"
        private const val CHANNEL_ID = "stream"
        private const val NOTIFICATION_ID = 1
        private const val RTSP_PORT = 8554

        private val isRunning = MutableStateFlow(false)
        val running: StateFlow<Boolean> = isRunning.asStateFlow()

        private val _viewerConnected = MutableStateFlow(false)
        val viewerConnected: StateFlow<Boolean> = _viewerConnected.asStateFlow()

        private val _webUrl = MutableStateFlow<String?>(null)
        val webUrl: StateFlow<String?> = _webUrl.asStateFlow()

        fun start(context: Context, useFrontCamera: Boolean) {
            val intent = Intent(context, StreamService::class.java)
                .setAction(ACTION_START)
                .putExtra(EXTRA_FRONT_CAMERA, useFrontCamera)
            context.startForegroundService(intent)
        }

        fun stop(context: Context) {
            val intent = Intent(context, StreamService::class.java).setAction(ACTION_STOP)
            context.startService(intent)
        }
    }
}
