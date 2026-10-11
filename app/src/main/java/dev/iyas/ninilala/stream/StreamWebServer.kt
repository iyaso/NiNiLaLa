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
 * Serves the browser player and a length-prefixed H.264 + AAC stream.
 * jMuxer on the page turns those frames into MSE media; no MPEG-TS muxer.
 */
class StreamWebServer(private val context: Context) : NanoHTTPD("0.0.0.0", WEB_PORT) {

    private val running = AtomicBoolean(false)
    private val clients = ConcurrentHashMap<Long, Client>()
    private val nextClientId = AtomicLong(1)

    var onViewerCountChanged: ((Int) -> Unit)? = null

    @Volatile var audioEnabled: Boolean = false
    @Volatile private var sampleRate: Int = 44100
    @Volatile private var stereo: Boolean = false
    @Volatile private var avcHeader: ByteArray = ByteArray(0)

    private val indexHtml: ByteArray by lazy {
        context.assets.open("web/index.html").use { it.readBytes() }
    }
    private val jmuxerJs: ByteArray by lazy {
        context.assets.open("web/jmuxer.min.js").use { it.readBytes() }
    }

    fun startServer(): Boolean = try {
        start(SOCKET_READ_TIMEOUT, false)
        running.set(true)
        Log.i(TAG, "web viewer on port $WEB_PORT")
        true
    } catch (e: Exception) {
        Log.e(TAG, "web viewer failed to start", e)
        false
    }

    fun stopServer() {
        running.set(false)
        clients.values.forEach { it.dead.set(true) }
        runCatching { stop() }
        clients.clear()
        onViewerCountChanged?.invoke(0)
    }

    fun viewerCount(): Int = clients.size

    fun setAudio(sampleRate: Int, stereo: Boolean) {
        this.sampleRate = sampleRate
        this.stereo = stereo
        audioEnabled = true
    }

    fun setVideoHeader(header: ByteArray) {
        avcHeader = header
    }

    fun publishVideo(buffer: ByteBuffer, info: MediaCodec.BufferInfo) {
        if (!running.get() || info.size <= 0) return
        if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) return
        var data = copy(buffer, info)
        val header = avcHeader
        if (info.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME != 0 && header.isNotEmpty()) {
            data = header + data
        }
        broadcast(VIDEO, data)
    }

    fun publishAudio(buffer: ByteBuffer, info: MediaCodec.BufferInfo) {
        if (!running.get() || !audioEnabled || info.size <= 0) return
        val aac = copy(buffer, info)
        broadcast(AUDIO, adts(aac) + aac)
    }

    override fun serve(session: IHTTPSession): Response = when (session.uri) {
        "/", "/index.html" -> bytes(indexHtml, "text/html; charset=utf-8", cache = false)
        "/jmuxer.min.js" -> bytes(jmuxerJs, "application/javascript", cache = true)
        "/status" -> newFixedLengthResponse(
            Response.Status.OK,
            "application/json",
            """{"audio":$audioEnabled}""",
        )
        "/stream" -> serveStream()
        else -> newFixedLengthResponse(Response.Status.NOT_FOUND, MIME_PLAINTEXT, "not found")
    }

    private fun bytes(body: ByteArray, mime: String, cache: Boolean): Response =
        newFixedLengthResponse(Response.Status.OK, mime, ByteArrayInputStream(body), body.size.toLong()).apply {
            addHeader("Cache-Control", if (cache) "public, max-age=86400" else "no-store")
        }

    private fun serveStream(): Response {
        val client = Client(nextClientId.getAndIncrement())
        clients[client.id] = client
        onViewerCountChanged?.invoke(clients.size)
        Log.i(TAG, "viewer #${client.id} connected (${clients.size})")
        return newChunkedResponse(Response.Status.OK, "application/octet-stream", ClientStream(client)).apply {
            addHeader("Cache-Control", "no-store")
        }
    }

    private fun broadcast(type: Byte, payload: ByteArray) {
        val frame = ByteArray(5 + payload.size)
        frame[0] = type
        val n = payload.size
        frame[1] = (n ushr 24).toByte()
        frame[2] = (n ushr 16).toByte()
        frame[3] = (n ushr 8).toByte()
        frame[4] = n.toByte()
        payload.copyInto(frame, 5)
        for (client in clients.values) {
            if (client.dead.get()) continue
            if (!client.queue.offer(frame)) {
                client.dead.set(true)
                Log.i(TAG, "dropped slow viewer #${client.id}")
            }
        }
    }

    private fun copy(buffer: ByteBuffer, info: MediaCodec.BufferInfo): ByteArray {
        val dup = buffer.duplicate()
        dup.position(info.offset)
        dup.limit(info.offset + info.size)
        return ByteArray(dup.remaining()).also { dup.get(it) }
    }

    private fun adts(aac: ByteArray): ByteArray {
        val freq = when (sampleRate) {
            96000 -> 0; 88200 -> 1; 64000 -> 2; 48000 -> 3
            44100 -> 4; 32000 -> 5; 24000 -> 6; 22050 -> 7
            16000 -> 8; 12000 -> 9; 11025 -> 10; 8000 -> 11
            else -> 4
        }
        val channels = if (stereo) 2 else 1
        val len = aac.size + 7
        return byteArrayOf(
            0xFF.toByte(),
            0xF1.toByte(),
            ((1 shl 6) or (freq shl 2) or (channels shr 2)).toByte(),
            (((channels and 3) shl 6) or ((len shr 11) and 3)).toByte(),
            ((len shr 3) and 0xFF).toByte(),
            (((len and 7) shl 5) or 0x1F).toByte(),
            0xFC.toByte(),
        )
    }

    private fun unregister(client: Client) {
        if (clients.remove(client.id) != null) {
            Log.i(TAG, "viewer #${client.id} left (${clients.size})")
            onViewerCountChanged?.invoke(clients.size)
        }
    }

    private class Client(val id: Long) {
        val queue = ArrayBlockingQueue<ByteArray>(QUEUE_CAPACITY)
        val dead = AtomicBoolean(false)
    }

    private inner class ClientStream(private val client: Client) : InputStream() {
        private var current: ByteArray? = null
        private var pos = 0

        override fun read(buffer: ByteArray, off: Int, len: Int): Int {
            if (len == 0) return 0
            while (true) {
                if (client.dead.get() || !running.get()) {
                    unregister(client)
                    return -1
                }
                var cur = current
                if (cur == null) {
                    cur = try {
                        client.queue.poll(1, TimeUnit.SECONDS) ?: continue
                    } catch (e: InterruptedException) {
                        Thread.currentThread().interrupt()
                        unregister(client)
                        return -1
                    }
                    current = cur
                    pos = 0
                }
                val n = minOf(cur.size - pos, len)
                System.arraycopy(cur, pos, buffer, off, n)
                pos += n
                if (pos >= cur.size) current = null
                return n
            }
        }

        override fun read(): Int {
            val one = ByteArray(1)
            return if (read(one, 0, 1) == -1) -1 else one[0].toInt() and 0xff
        }

        override fun close() {
            client.dead.set(true)
            unregister(client)
        }
    }

    companion object {
        private const val TAG = "StreamWebServer"
        const val WEB_PORT = 8080
        private const val QUEUE_CAPACITY = 90
        private const val SOCKET_READ_TIMEOUT = 10_000
        private const val VIDEO: Byte = 1
        private const val AUDIO: Byte = 2

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
    }
}

internal fun annexB(buffer: ByteBuffer?): ByteArray {
    if (buffer == null) return ByteArray(0)
    val dup = buffer.duplicate()
    dup.rewind()
    if (!dup.hasRemaining()) return ByteArray(0)
    val raw = ByteArray(dup.remaining())
    dup.get(raw)
    val hasStart = raw.size >= 3 && raw[0] == 0.toByte() && raw[1] == 0.toByte() &&
        (raw[2] == 1.toByte() || (raw.size >= 4 && raw[2] == 0.toByte() && raw[3] == 1.toByte()))
    return if (hasStart) raw else byteArrayOf(0, 0, 0, 1) + raw
}
