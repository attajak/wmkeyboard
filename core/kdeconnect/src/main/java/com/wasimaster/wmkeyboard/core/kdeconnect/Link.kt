package com.wasimaster.wmkeyboard.core.kdeconnect

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import java.io.BufferedInputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.security.cert.X509Certificate
import java.util.concurrent.atomic.AtomicBoolean
import javax.net.ssl.SSLSocket

/**
 * An established, encrypted connection to one device: newline-delimited JSON
 * both ways until somebody closes it.
 *
 * One coroutine reads and one writes. All writes funnel through one outbox so two
 * callers can never interleave half a packet each, and  * what is queued: a finger dragging across the touchpad makes a packet per
 * frame, and when the socket falls behind, consecutive pure-movement packets
 * are **added together** rather than sent one by one. The pointer lands in the
 * same place, and the backlog that would otherwise play out as a slow-motion
 * replay of the drag never forms.
 */
internal class KdeLink(
    val info: KdeDeviceInfo,
    val certificate: X509Certificate,
    private val socket: SSLSocket,
    private val scope: CoroutineScope,
    private val now: () -> Long,
    private val onPacket: (KdeLink, KdePacket) -> Unit,
    private val onClosed: (KdeLink) -> Unit,
) {
    val address: InetAddress = socket.inetAddress

    @Volatile var lastReceivedMs: Long = now()
        private set

    private val closed = AtomicBoolean(false)
    private val outbox = Channel<KdePacket>(Channel.UNLIMITED)

    val isOpen: Boolean get() = !closed.get()

    /**
     * Starts reading and writing. Separate from construction so the engine can
     * file the link under its device id first: the peer's opening packets
     * arrive within milliseconds, and their handlers look the link up.
     */
    fun start() {
        scope.launch(Dispatchers.IO) { readLoop() }
        scope.launch(Dispatchers.IO) { writeLoop() }
    }

    /** Queues [packet]; false when the link is already closed. Never blocks. */
    fun send(packet: KdePacket): Boolean = !closed.get() && outbox.trySend(packet).isSuccess

    fun close() {
        if (!closed.compareAndSet(false, true)) return
        outbox.close()
        runCatching { socket.close() }
        onClosed(this)
    }

    private fun readLoop() {
        try {
            val input = BufferedInputStream(socket.inputStream, 16 * 1024)
            while (!closed.get()) {
                val line = readLineBounded(input, MAX_PACKET_BYTES) ?: break
                if (line.isBlank()) continue
                lastReceivedMs = now()
                val packet = KdePacket.parse(line) ?: continue
                runCatching { onPacket(this, packet) }
            }
        } catch (_: IOException) {
            // Closed from this side or lost from the other: same outcome.
        } finally {
            close()
        }
    }

    private suspend fun writeLoop() {
        try {
            val output = socket.outputStream
            for (first in outbox) {
                var current = first
                while (true) {
                    val next = outbox.tryReceive().getOrNull() ?: break
                    val merged = MousepadPackets.mergeMoves(current, next)
                    if (merged != null) {
                        current = merged
                    } else {
                        write(output, current)
                        current = next
                    }
                }
                write(output, current)
            }
        } catch (_: IOException) {
            // Falls through to close().
        } finally {
            close()
        }
    }

    private fun write(output: OutputStream, packet: KdePacket) {
        output.write(packet.serialize(now()).toByteArray(Charsets.UTF_8))
        output.flush()
    }

    companion object {
        /** Both reference clients cap a packet at 32 MiB. */
        const val MAX_PACKET_BYTES = 32 * 1024 * 1024
    }
}

/**
 * The second connection a payload travels on.
 *
 * The sender listens on a port in 1739–1764 and names it in the announcing
 * packet's `payloadTransferInfo`; the receiver dials it. TLS here is the
 * ordinary way round — **the listener is the TLS server** — unlike the control
 * link. Both ends pin the certificate of the link the transfer belongs to, so
 * a payload socket can only ever be the paired device.
 *
 * The receiver dials the *link's* address, never one named in the packet: a
 * packet is data from the peer, and an address in it would be the peer telling
 * us where to connect.
 */
internal class PayloadTransfer(
    private val tls: KdeTls,
    private val ports: KdePorts,
) {
    /** A listening socket for one upload. Closed by [serve] or by the caller on failure. */
    fun listen(): ServerSocket? {
        for (port in ports.payloadMin..ports.payloadMax) {
            val socket = runCatching {
                ServerSocket().apply {
                    reuseAddress = true
                    bind(InetSocketAddress(port))
                    soTimeout = ACCEPT_TIMEOUT_MS
                }
            }.getOrNull()
            if (socket != null) return socket
        }
        return null
    }

    /**
     * Waits for the peer to fetch, then streams [source] to it. Returns the
     * bytes sent.
     *
     * @throws IOException on timeout, a wrong certificate, or a broken pipe
     */
    fun serve(
        server: ServerSocket,
        peer: X509Certificate,
        source: InputStream,
        cancelled: () -> Boolean,
        onProgress: (Long) -> Unit,
    ): Long {
        server.use { listening ->
            val raw = listening.accept()
            val ssl = tls.wrap(raw, clientMode = false, pinned = peer)
            ssl.use { secure ->
                secure.soTimeout = IO_TIMEOUT_MS
                return copy(source, secure.outputStream, limit = -1, cancelled, onProgress).also {
                    secure.outputStream.flush()
                }
            }
        }
    }

    /**
     * Fetches a payload of [size] bytes (-1: until the peer closes) from
     * [address]:[port] into [sink]. Returns the bytes received.
     *
     * @throws IOException when the transfer stops short of [size]
     */
    fun fetch(
        address: InetAddress,
        port: Int,
        peer: X509Certificate,
        size: Long,
        sink: OutputStream,
        cancelled: () -> Boolean,
        onProgress: (Long) -> Unit,
    ): Long {
        val raw = Socket()
        raw.connect(InetSocketAddress(address, port), LanTransport.CONNECT_TIMEOUT_MS)
        val ssl = tls.wrap(raw, clientMode = true, pinned = peer)
        ssl.use { secure ->
            secure.soTimeout = IO_TIMEOUT_MS
            val received = copy(secure.inputStream, sink, limit = size, cancelled, onProgress)
            sink.flush()
            // A sender may announce less than it sends, never more than it has.
            if (size >= 0 && received < size) throw IOException("payload ended at $received of $size bytes")
            return received
        }
    }

    private fun copy(
        input: InputStream,
        output: OutputStream,
        limit: Long,
        cancelled: () -> Boolean,
        onProgress: (Long) -> Unit,
    ): Long {
        val buffer = ByteArray(CHUNK)
        var total = 0L
        while (true) {
            if (cancelled()) throw IOException("cancelled")
            val want = if (limit >= 0) minOf(CHUNK.toLong(), limit - total).toInt() else CHUNK
            if (want <= 0) break
            val read = input.read(buffer, 0, want)
            if (read < 0) break
            output.write(buffer, 0, read)
            total += read
            onProgress(total)
        }
        return total
    }

    companion object {
        const val CHUNK = 4096

        /** kdeconnect-kde waits 30 s for the other end to fetch; kdeconnect-android 10. */
        const val ACCEPT_TIMEOUT_MS = 30_000
        const val IO_TIMEOUT_MS = 30_000
    }
}
