package dev.iyas.ninilala.stream

import android.content.Context
import android.media.MediaCodec
import android.util.Log
import fi.iki.elonen.NanoHTTPD
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.net.Inet4Address
import java.net.NetworkInterface
import java.nio.ByteBuffer
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * Serves the running camera stream to LAN browsers on [WEB_PORT]:
 *  - GET /           → the MSE player page (assets/web/index.html)
 *  - GET /stream.h264 → endless chunked body of [4-byte BE length][Annex-B chunk] messages,
 *    where each chunk is either the config (SPS+PPS) or one complete video frame
 *
 * Frames are tapped from the encoder thread; slow clients get disconnected instead
 * of ever blocking the encoder.
 */
class StreamWebServer(private val context: Context) : NanoHTTPD(WEB_PORT) {

    private val running = AtomicBoolean(false)
    private val clients = ConcurrentHashMap<Long, Client>()
    private val nextClientId = AtomicLong(1)

    var onViewerCountChanged: ((Int) -> Unit)? = null

    @Volatile
    private var spsNal: ByteArray? = null

    @Volatile
    private var ppsNal: ByteArray? = null

    private val indexHtml: ByteArray by lazy {
        context.assets.open("web/index.html").use { it.readBytes() }
    }

    fun startServer(): Boolean = try {
        start()
        running.set(true)
        Log.i(TAG, "web viewer server on port $WEB_PORT")
        true
    } catch (e: Exception) {
        Log.e(TAG, "failed to start web viewer server", e)
        false
    }

    fun stopServer() {
        running.set(false)
        clients.values.forEach { it.dead.set(true) }
        try {
            stop()
        } catch (e: Exception) {
            Log.w(TAG, "web server stop failed", e)
        }
        clients.clear()
        onViewerCountChanged?.invoke(0)
    }

    fun setConfig(sps: ByteBuffer, pps: ByteBuffer?) {
        val spsBytes = stripStartCode(duplicateBytes(sps))
        val ppsBytes = pps?.let { stripStartCode(duplicateBytes(it)) } ?: return
        if (spsBytes.isEmpty() || ppsBytes.isEmpty()) return
        spsNal = spsBytes
        ppsNal = ppsBytes
        val config = configChunk() ?: return
        for (client in clients.values) {
            if (!client.dead.get()) enqueue(client, config)
        }
    }

    fun publish(videoBuffer: ByteBuffer, info: MediaCodec.BufferInfo) {
        if (!running.get() || info.size <= 0) return
        val dup = videoBuffer.duplicate()
        val data = ByteArray(info.size)
        dup.position(info.offset)
        dup.limit(info.offset + info.size)
        dup.get(data)

        var hasIdr = false
        var hasSlice = false
        for (nal in splitNals(data)) {
            when (nal[0].toInt() and 0x1f) {
                NAL_SPS -> spsNal = nal
                NAL_PPS -> ppsNal = nal
                NAL_IDR -> hasIdr = true
                NAL_SLICE -> hasSlice = true
            }
        }
        if (!hasIdr && !hasSlice) return

        for (client in clients.values) {
            if (client.dead.get()) continue
            if (hasIdr) client.sawIdr.set(true)
            if (!hasIdr && !client.sawIdr.get()) continue
            enqueue(client, data)
        }
    }

    override fun serve(session: IHTTPSession): Response = when (session.uri) {
        "/", "/index.html" -> newFixedLengthResponse(
            NanoHTTPD.Response.Status.OK,
            "text/html; charset=utf-8",
            ByteArrayInputStream(indexHtml),
            indexHtml.size.toLong(),
        ).apply {
            addHeader("Cache-Control", "no-store")
        }
        "/stream.h264" -> serveStream()
        else -> newFixedLengthResponse(NanoHTTPD.Response.Status.NOT_FOUND, NanoHTTPD.MIME_PLAINTEXT, "not found")
    }

    private fun serveStream(): Response {
        val client = Client(nextClientId.getAndIncrement())
        register(client)
        Log.i(TAG, "web viewer #${client.id} connected (${clients.size} active)")
        return newChunkedResponse(
            NanoHTTPD.Response.Status.OK,
            "application/octet-stream",
            ClientStream(client),
        ).apply {
            addHeader("Cache-Control", "no-store")
        }
    }

    private fun register(client: Client) {
        clients[client.id] = client
        configChunk()?.let { enqueue(client, it) }
        onViewerCountChanged?.invoke(clients.size)
    }

    private fun configChunk(): ByteArray? {
        val sps = spsNal ?: return null
        val pps = ppsNal ?: return null
        return START_CODE + sps + START_CODE + pps
    }

    private fun unregister(client: Client) {
        if (clients.remove(client.id) != null) {
            Log.i(TAG, "web viewer #${client.id} disconnected (${clients.size} active)")
            onViewerCountChanged?.invoke(clients.size)
        }
    }

    private fun enqueue(client: Client, nal: ByteArray) {
        if (client.dead.get()) return
        if (!client.queue.offer(nal)) {
            client.dead.set(true)
            Log.i(TAG, "dropped slow web viewer #${client.id}")
        }
    }

    private class Client(val id: Long) {
        val queue = ArrayBlockingQueue<ByteArray>(QUEUE_CAPACITY)
        val sawIdr = AtomicBoolean(false)
        val dead = AtomicBoolean(false)
    }

    private inner class ClientStream(private val client: Client) : InputStream() {
        private var current: ByteArray? = null
        private var pos = 0
        private var closed = false

        @Synchronized
        override fun read(buffer: ByteArray, off: Int, len: Int): Int {
            if (len == 0) return 0
            while (true) {
                if (closed || client.dead.get() || !running.get()) {
                    closeQuietly()
                    return -1
                }
                var cur = current
                if (cur == null) {
                    val nal = try {
                        client.queue.poll(1, TimeUnit.SECONDS) ?: continue
                    } catch (e: InterruptedException) {
                        Thread.currentThread().interrupt()
                        closeQuietly()
                        return -1
                    }
                    // wire format: [4-byte BE length][raw NAL] — framed here, off the encoder thread
                    val framed = ByteArray(4 + nal.size)
                    framed[0] = (nal.size ushr 24).toByte()
                    framed[1] = (nal.size ushr 16).toByte()
                    framed[2] = (nal.size ushr 8).toByte()
                    framed[3] = nal.size.toByte()
                    nal.copyInto(framed, 4)
                    cur = framed
                    current = cur
                    pos = 0
                }
                val n = minOf(cur.size - pos, len)
                System.arraycopy(cur, pos, buffer, off, n)
                pos += n
                if (pos >= cur.size) {
                    current = null
                    pos = 0
                }
                return n
            }
        }

        override fun read(): Int {
            val one = ByteArray(1)
            return if (read(one, 0, 1) == -1) -1 else one[0].toInt() and 0xff
        }

        @Synchronized
        override fun close() {
            closeQuietly()
        }

        private fun closeQuietly() {
            if (!closed) {
                closed = true
                unregister(client)
            }
        }
    }

    companion object {
        private const val TAG = "StreamWebServer"
        const val WEB_PORT = 8080
        private const val QUEUE_CAPACITY = 64
        private const val NAL_SPS = 7
        private const val NAL_PPS = 8
        private const val NAL_IDR = 5
        private const val NAL_SLICE = 1
        private const val ZERO: Byte = 0
        private const val ONE: Byte = 1
        private val START_CODE = byteArrayOf(0, 0, 0, 1)

        fun findLanUrl(): String? {
            val wlan = ArrayList<String>()
            val other = ArrayList<String>()
            try {
                val interfaces = NetworkInterface.getNetworkInterfaces() ?: return null
                for (nif in interfaces) {
                    if (!nif.isUp || nif.isLoopback) continue
                    for (addr in nif.inetAddresses) {
                        if (addr !is Inet4Address || addr.isLoopbackAddress) continue
                        val ip = addr.hostAddress ?: continue
                        if (nif.name.lowercase().startsWith("wlan")) wlan.add(ip) else other.add(ip)
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "interface scan failed", e)
            }
            val ip = wlan.firstOrNull()
                ?: other.firstOrNull { it.startsWith("192.168.") || it.startsWith("10.") }
                ?: other.firstOrNull()
            return ip?.let { "http://$it:$WEB_PORT/" }
        }

        private fun duplicateBytes(buffer: ByteBuffer): ByteArray {
            val dup = buffer.duplicate()
            return ByteArray(dup.remaining()).also { dup.get(it) }
        }

        private fun stripStartCode(bytes: ByteArray): ByteArray {
            if (bytes.size >= 4 && bytes[0] == ZERO && bytes[1] == ZERO && bytes[2] == ZERO && bytes[3] == ONE) {
                return bytes.copyOfRange(4, bytes.size)
            }
            if (bytes.size >= 3 && bytes[0] == ZERO && bytes[1] == ZERO && bytes[2] == ONE) {
                return bytes.copyOfRange(3, bytes.size)
            }
            return bytes
        }

        private fun splitNals(data: ByteArray): List<ByteArray> {
            val nals = ArrayList<ByteArray>(4)
            val n = data.size
            var start = -1
            var i = 0
            while (i < n) {
                if (i <= n - 3 && data[i] == ZERO && data[i + 1] == ZERO) {
                    val scLen = when {
                        data[i + 2] == ONE -> 3
                        i <= n - 4 && data[i + 2] == ZERO && data[i + 3] == ONE -> 4
                        else -> 0
                    }
                    if (scLen > 0) {
                        if (start >= 0 && i > start) nals.add(strip(data, start, i))
                        start = i
                        i += scLen
                        continue
                    }
                }
                i++
            }
            if (start >= 0 && n > start) nals.add(strip(data, start, n))
            return nals
        }

        private fun strip(data: ByteArray, from: Int, to: Int): ByteArray {
            val head = if (to - from >= 4 && data[from + 2] == ZERO && data[from + 3] == ONE) 4 else 3
            return data.copyOfRange(from + head, to)
        }
    }
}
