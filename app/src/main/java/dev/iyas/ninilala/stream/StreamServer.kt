package dev.iyas.ninilala.stream

import android.util.Log
import dev.iyas.ninilala.LanProtocol
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.BufferedOutputStream
import java.io.IOException
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread

class StreamServer {
    private val tag = "StreamServer"
    private var serverSocket: ServerSocket? = null
    private val clientOut = AtomicReference<BufferedOutputStream?>(null)
    private var acceptThread: Thread? = null
    @Volatile private var running = false
    @Volatile private var codecConfig: ByteArray? = null

    private val _viewerConnected = MutableStateFlow(false)
    val viewerConnected: StateFlow<Boolean> = _viewerConnected.asStateFlow()

    val port: Int
        get() = serverSocket?.localPort ?: -1

    fun start() {
        if (running) return
        running = true
        val server = ServerSocket(0).also { serverSocket = it }
        acceptThread = thread(name = "sg-accept", isDaemon = true) {
            while (running) {
                try {
                    val socket = server.accept()
                    onClient(socket)
                } catch (_: SocketException) {
                    break
                } catch (e: IOException) {
                    if (running) Log.w(tag, "accept failed", e)
                }
            }
        }
    }

    private fun onClient(socket: Socket) {
        val existing = clientOut.get()
        if (existing != null) {
            Log.i(tag, "refusing second viewer")
            runCatching { socket.close() }
            return
        }
        socket.tcpNoDelay = true
        val out = BufferedOutputStream(socket.getOutputStream(), 64 * 1024)
        if (!clientOut.compareAndSet(null, out)) {
            runCatching { socket.close() }
            return
        }
        Log.i(tag, "viewer connected from ${socket.inetAddress.hostAddress}")
        _viewerConnected.value = true
        codecConfig?.let { writePacket(out, it) }
        thread(name = "sg-client-watch", isDaemon = true) {
            try {
                socket.getInputStream().read()
            } catch (_: IOException) {
            } finally {
                dropClient(out, socket)
            }
        }
    }

    fun setCodecConfig(data: ByteArray) {
        codecConfig = data.copyOf()
        clientOut.get()?.let { writePacket(it, data) }
    }

    fun sendAccessUnit(data: ByteArray, offset: Int, length: Int, isKeyFrame: Boolean) {
        val out = clientOut.get() ?: return
        val config = codecConfig
        if (isKeyFrame && config != null) {
            writePacket(out, config)
        }
        writePacket(out, data, offset, length)
    }

    private fun writePacket(out: BufferedOutputStream, data: ByteArray) {
        writePacket(out, data, 0, data.size)
    }

    private fun writePacket(out: BufferedOutputStream, data: ByteArray, offset: Int, length: Int) {
        if (length <= 0 || length > LanProtocol.MAX_ACCESS_UNIT_BYTES) return
        try {
            synchronized(out) {
                out.write((length ushr 24) and 0xff)
                out.write((length ushr 16) and 0xff)
                out.write((length ushr 8) and 0xff)
                out.write(length and 0xff)
                out.write(data, offset, length)
                out.flush()
            }
        } catch (e: IOException) {
            Log.i(tag, "viewer write failed: ${e.message}")
            dropClient(out, null)
        }
    }

    private fun dropClient(out: BufferedOutputStream, socket: Socket?) {
        if (clientOut.compareAndSet(out, null)) {
            _viewerConnected.value = false
            runCatching { out.close() }
            runCatching { socket?.close() }
        }
    }

    fun stop() {
        running = false
        _viewerConnected.value = false
        runCatching { serverSocket?.close() }
        serverSocket = null
        clientOut.getAndSet(null)?.let { runCatching { it.close() } }
        acceptThread?.join(500)
        acceptThread = null
        codecConfig = null
    }
}
