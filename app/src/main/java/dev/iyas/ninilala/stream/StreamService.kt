package dev.iyas.ninilala.stream

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.lifecycle.LifecycleService
import dev.iyas.ninilala.MainActivity
import dev.iyas.ninilala.R
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.runBlocking

class StreamService : LifecycleService() {
    private val tag = "StreamService"

    private var wakeLock: PowerManager.WakeLock? = null
    private var server: StreamServer? = null
    private var advertiser: NsdAdvertiser? = null
    private var encoder: CameraEncoder? = null
    private var viewerStateThread: Thread? = null

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
        if (server != null) return

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

        val streamServer = StreamServer().also { server = it }
        streamServer.start()
        viewerStateThread = Thread({
            runBlocking {
                streamServer.viewerConnected.collect { hasViewer ->
                    _viewerConnected.value = hasViewer
                }
            }
        }, "sg-viewer-state").also { it.isDaemon = true; it.start() }
        val nsd = NsdAdvertiser(this).also { advertiser = it }
        nsd.register(streamServer.port)

        try {
            encoder = CameraEncoder(this, useFront, streamServer).also { it.start() }
            Log.i(tag, "streaming on port ${streamServer.port}")
        } catch (e: Exception) {
            Log.e(tag, "failed to start camera encoder", e)
            stopStreaming()
            stopSelf()
        }
    }

    private fun stopStreaming() {
        runCatching { encoder?.stop() }
        encoder = null
        runCatching { advertiser?.unregister() }
        advertiser = null
        viewerStateThread?.interrupt()
        viewerStateThread = null
        runCatching { server?.stop() }
        server = null
        _viewerConnected.value = false
        wakeLock?.let { lock ->
            if (lock.isHeld) lock.release()
        }
        wakeLock = null
        stopForeground(STOP_FOREGROUND_REMOVE)
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

    companion object {
        const val ACTION_START = "dev.iyas.ninilala.action.START"
        const val ACTION_STOP = "dev.iyas.ninilala.action.STOP"
        const val EXTRA_FRONT_CAMERA = "front_camera"
        private const val CHANNEL_ID = "stream"
        private const val NOTIFICATION_ID = 1

        private val isRunning = MutableStateFlow(false)
        val running: StateFlow<Boolean> = isRunning.asStateFlow()

        private val _viewerConnected = MutableStateFlow(false)
        val viewerConnected: StateFlow<Boolean> = _viewerConnected.asStateFlow()

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
