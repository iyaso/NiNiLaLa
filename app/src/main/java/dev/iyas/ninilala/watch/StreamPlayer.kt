package dev.iyas.ninilala.watch

import android.media.MediaCodec
import android.media.MediaFormat
import android.util.Log
import android.view.Surface
import dev.iyas.ninilala.LanProtocol
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.DataInputStream
import java.io.EOFException
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

enum class PlayerConnectionState { Idle, Connecting, Connected, Reconnecting, Failed }

class StreamPlayer {
    private val tag = "StreamPlayer"
    private val running = AtomicBoolean(false)
    private var readerThread: Thread? = null
    private var decoder: MediaCodec? = null
    private var socket: Socket? = null

    private val _connectionState = MutableStateFlow(PlayerConnectionState.Idle)
    val connectionState: StateFlow<PlayerConnectionState> = _connectionState.asStateFlow()

    fun start(host: String, port: Int, surface: Surface) {
        if (!running.compareAndSet(false, true)) return
        _connectionState.value = PlayerConnectionState.Connecting
        readerThread = thread(name = "sg-player", isDaemon = true) {
            try {
                playWithRetry(host, port, surface)
            } catch (e: Exception) {
                if (running.get()) Log.e(tag, "playback failed", e)
                _connectionState.value = PlayerConnectionState.Failed
            } finally {
                cleanupCodec()
                runCatching { socket?.close() }
                socket = null
                running.set(false)
            }
        }
    }

    private fun playWithRetry(host: String, port: Int, surface: Surface) {
        var attempt = 0
        val maxDelayMs = 10_000L

        while (running.get()) {
            try {
                if (attempt > 0) {
                    _connectionState.value = PlayerConnectionState.Reconnecting
                    val delayMs = minOf(1000L * (1 shl (attempt - 1)), maxDelayMs)
                    Log.i(tag, "retry attempt $attempt in ${delayMs}ms")
                    Thread.sleep(delayMs)
                    if (!running.get()) break
                }

                play(host, port, surface)
                if (!running.get()) break

                attempt++
            } catch (e: IOException) {
                if (!running.get()) break
                Log.w(tag, "connection lost: ${e.message}")
                cleanupCodec()
                runCatching { socket?.close() }
                socket = null
                attempt++
            } catch (e: Exception) {
                if (!running.get()) break
                Log.e(tag, "unexpected error", e)
                cleanupCodec()
                runCatching { socket?.close() }
                socket = null
                attempt++
            }
        }
    }

    private fun play(host: String, port: Int, surface: Surface) {
        val sock = Socket().also { socket = it }
        sock.tcpNoDelay = true
        sock.connect(InetSocketAddress(host, port), 5_000)
        val input = DataInputStream(sock.getInputStream())

        _connectionState.value = PlayerConnectionState.Connected

        var codec: MediaCodec? = null
        var configured = false

        while (running.get()) {
            val length = try {
                input.readInt()
            } catch (_: EOFException) {
                break
            }
            if (length <= 0 || length > LanProtocol.MAX_ACCESS_UNIT_BYTES) {
                throw IOException("invalid access unit length $length")
            }
            val data = ByteArray(length)
            input.readFully(data)

            if (!configured) {
                val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, 1920, 1080)
                codec = MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_VIDEO_AVC).also {
                    decoder = it
                    it.configure(format, surface, null, 0)
                    it.start()
                }
                configured = true
            }

            val mediaCodec = codec ?: break
            queueInput(mediaCodec, data)
            drainOutput(mediaCodec)
        }
    }

    private fun queueInput(codec: MediaCodec, data: ByteArray) {
        val index = codec.dequeueInputBuffer(10_000)
        if (index < 0) return
        val buffer = codec.getInputBuffer(index) ?: return
        buffer.clear()
        buffer.put(data)
        val flags = if (isCodecConfig(data)) MediaCodec.BUFFER_FLAG_CODEC_CONFIG else 0
        codec.queueInputBuffer(index, 0, data.size, System.nanoTime() / 1000, flags)
    }

    private fun drainOutput(codec: MediaCodec) {
        val info = MediaCodec.BufferInfo()
        while (true) {
            val index = codec.dequeueOutputBuffer(info, 0)
            when {
                index >= 0 -> codec.releaseOutputBuffer(index, true)
                index == MediaCodec.INFO_TRY_AGAIN_LATER -> return
                index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED ->
                    Log.i(tag, "decoder format ${codec.outputFormat}")
                else -> return
            }
        }
    }

    private fun isCodecConfig(data: ByteArray): Boolean {
        var i = 0
        while (i + 4 < data.size) {
            val start = if (data[i] == 0.toByte() && data[i + 1] == 0.toByte()) {
                when {
                    data[i + 2] == 1.toByte() -> i + 3
                    data[i + 2] == 0.toByte() && data[i + 3] == 1.toByte() -> i + 4
                    else -> -1
                }
            } else {
                -1
            }
            if (start < 0) {
                i++
                continue
            }
            val nalType = data[start].toInt() and 0x1f
            if (nalType == 7 || nalType == 8) return true
            i = start
        }
        return false
    }

    fun stop() {
        running.set(false)
        _connectionState.value = PlayerConnectionState.Idle
        runCatching { socket?.close() }
        readerThread?.join(1_000)
        readerThread = null
        cleanupCodec()
    }

    private fun cleanupCodec() {
        runCatching { decoder?.stop() }
        runCatching { decoder?.release() }
        decoder = null
    }
}
