package com.wasimaster.wmkeyboard.core.kdeconnect

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.security.cert.X509Certificate
import java.util.concurrent.ConcurrentHashMap
import javax.net.ssl.SSLSocket

/**
 * The ports the protocol fixes. Tests pass zeros to get ephemeral ones, so two
 * engines can share a machine — and a CI box that happens to run KDE Connect.
 */
data class KdePorts(
    /** Where identity announcements are listened for. */
    val udp: Int = 1716,
    /** Where announcements are *sent*: always 1716, whatever the peer's TCP port is. */
    val udpTarget: Int = 1716,
    val tcpMin: Int = 1716,
    val tcpMax: Int = 1764,
    val payloadMin: Int = 1739,
    val payloadMax: Int = 1764,
) {
    /** False under test, where a peer's TCP port is whatever the OS handed out. */
    val strict: Boolean get() = tcpMin != 0

    companion object {
        val Ephemeral = KdePorts(udp = 0, udpTarget = 0, tcpMin = 0, tcpMax = 0, payloadMin = 0, payloadMax = 0)
    }
}

/**
 * Finding devices on the LAN and turning them into encrypted links.
 *
 * ## How two devices meet
 *
 * One of them broadcasts its identity packet over UDP to port 1716, with the
 * TCP port it listens on. Whoever hears it **dials that TCP port** and opens
 * with its own identity as one plaintext line. Then TLS starts — with the roles
 * the wrong way round: **the side that dialled is the TLS server**, the side
 * that accepted the connection is the TLS client. (Payload sockets, see
 * [PayloadTransfer], are the normal way round. Both halves are the protocol;
 * neither is a choice made here.)
 *
 * Under protocol 8 both sides then send their identity *again*, encrypted, and
 * that second copy is the one that counts: the plaintext line and the UDP
 * datagram are hints about whom to connect to, nothing more.
 *
 * A link is handed to [onLink] only after all of that, together with the
 * certificate the peer presented. Whether that certificate is *trusted* was
 * already enforced by [KdeTls]: for a paired id the handshake fails unless it
 * is the stored one.
 */
internal class LanTransport(
    private val scope: CoroutineScope,
    private val self: () -> KdeDeviceInfo,
    private val tls: KdeTls,
    private val trust: KdeTrustStore,
    private val ports: KdePorts,
    private val allowLoopback: Boolean,
    private val now: () -> Long,
    private val log: (String) -> Unit,
    /**
     * Whether a fresh connection to this device id is wanted. False while a
     * healthy link to it exists: a desktop re-announces on every network
     * change, and answering each one would rebuild a working link for nothing.
     */
    private val wantsDial: (String) -> Boolean,
    private val onLink: (KdeDeviceInfo, X509Certificate, SSLSocket) -> Unit,
) {
    private var udp: DatagramSocket? = null
    private var server: ServerSocket? = null
    private var jobs: List<Job> = emptyList()

    /** Last dial per device id and per address, for the one-second rule both reference clients keep. */
    private val lastDial = ConcurrentHashMap<String, Long>()

    @Volatile var tcpPort: Int = 0
        private set

    @Volatile var udpPort: Int = 0
        private set

    /** Whether announcements from others can be heard. Sending works either way. */
    @Volatile var listening: Boolean = false
        private set

    @Synchronized
    fun start(): Boolean {
        if (server != null) return true
        val tcp = openServer(ports.tcpMin, ports.tcpMax) ?: run {
            log("no free TCP port in ${ports.tcpMin}..${ports.tcpMax}")
            return false
        }
        server = tcp
        tcpPort = tcp.localPort
        // Another KDE Connect client on this phone may own 1716. Announcing
        // still works from any port, and an announcement is all a desktop
        // needs to dial us — so failing to bind costs discovery of devices
        // that never hear *our* broadcast, not the feature.
        val socket = runCatching {
            DatagramSocket(null).apply {
                reuseAddress = true
                broadcast = true
                bind(InetSocketAddress(ports.udp))
            }
        }.onSuccess { listening = true }.getOrElse {
            log("UDP ${ports.udp} unavailable (${it.brief()}); announce-only")
            listening = false
            DatagramSocket().apply { broadcast = true }
        }
        udp = socket
        udpPort = socket.localPort
        jobs = listOfNotNull(
            scope.launch(Dispatchers.IO) { acceptLoop(tcp) },
            if (listening) scope.launch(Dispatchers.IO) { receiveLoop(socket) } else null,
        )
        return true
    }

    @Synchronized
    fun stop() {
        runCatching { udp?.close() }
        runCatching { server?.close() }
        udp = null
        server = null
        listening = false
        jobs.forEach { it.cancel() }
        jobs = emptyList()
        lastDial.clear()
    }

    /**
     * Says "I am here" to the broadcast address of every interface that has
     * one — the default route alone would miss a hotspot the phone itself is
     * serving — and to each of [hosts] directly. That last part is the whole of
     * "add a device by address": a unicast announcement, which the device then
     * answers by dialling us. It is not a TCP connection from our side.
     */
    fun announce(hosts: Collection<String>) {
        val socket = udp ?: return
        val bytes = self().toPacket(tcpPort = tcpPort).serialize(now()).toByteArray(Charsets.UTF_8)
        scope.launch(Dispatchers.IO) {
            val targets = LinkedHashSet<InetAddress>()
            runCatching { targets += InetAddress.getByName("255.255.255.255") }
            targets += broadcastAddresses()
            for (host in hosts) {
                val name = host.trim()
                if (name.isEmpty() || !HOST.matches(name)) continue
                runCatching { targets += InetAddress.getByName(name) }
            }
            for (target in targets) {
                runCatching { socket.send(DatagramPacket(bytes, bytes.size, target, ports.udpTarget)) }
            }
        }
    }

    /** One announcement to one socket address. What tests use in place of a broadcast. */
    fun announceTo(address: InetSocketAddress) {
        val socket = udp ?: return
        val bytes = self().toPacket(tcpPort = tcpPort).serialize(now()).toByteArray(Charsets.UTF_8)
        scope.launch(Dispatchers.IO) {
            runCatching { socket.send(DatagramPacket(bytes, bytes.size, address)) }
        }
    }

    /**
     * Dials a device found some other way than its UDP announcement (mDNS).
     * The id has to be known beforehand: kdeconnect-kde refuses a connection
     * whose opening line does not name it.
     */
    fun dial(address: InetAddress, port: Int, deviceId: String, protocolVersion: Int) {
        if (deviceId == self().id || !wantsDial(deviceId)) return
        scope.launch(Dispatchers.IO) { dialOut(address, port, deviceId, protocolVersion, announced = null) }
    }

    private fun receiveLoop(socket: DatagramSocket) {
        val buffer = ByteArray(MAX_IDENTITY_BYTES)
        while (!socket.isClosed) {
            val datagram = DatagramPacket(buffer, buffer.size)
            try {
                socket.receive(datagram)
            } catch (_: SocketException) {
                return
            } catch (_: IOException) {
                continue
            }
            onAnnouncement(datagram)
        }
    }

    /** One identity datagram: dial its sender back, if it is someone we want a link to. */
    private fun onAnnouncement(datagram: DatagramPacket) {
        val sender = datagram.address ?: return
        if (sender.isLoopbackAddress && !allowLoopback) return
        val text = String(datagram.data, datagram.offset, datagram.length, Charsets.UTF_8)
        val packet = KdePacket.parse(text) ?: return
        val info = KdeDeviceInfo.fromPacket(packet) ?: return
        if (info.id == self().id) return
        val port = packet.int("tcpPort") ?: return
        val allowed = if (ports.strict) ports.tcpMin..ports.tcpMax else 1..65535
        if (port !in allowed || !wantsDial(info.id)) return
        scope.launch(Dispatchers.IO) { dialOut(sender, port, info.id, info.protocolVersion, announced = info) }
    }

    private fun dialOut(address: InetAddress, port: Int, deviceId: String, version: Int, announced: KdeDeviceInfo?) {
        if (isDowngrade(deviceId, version)) {
            log("refusing protocol downgrade from $deviceId")
            return
        }
        if (!rateLimitAllows(deviceId) || !rateLimitAllows(address.hostAddress.orEmpty())) return
        val socket = Socket()
        try {
            socket.keepAlive = true
            socket.connect(InetSocketAddress(address, port), CONNECT_TIMEOUT_MS)
            socket.soTimeout = HANDSHAKE_TIMEOUT_MS
            val me = self()
            val opening = me.toPacket(targetId = deviceId, targetVersion = version).serialize(now())
            socket.getOutputStream().apply {
                write(opening.toByteArray(Charsets.UTF_8))
                flush()
            }
            // We dialled, so we are the TLS *server*.
            val ssl = tls.wrap(socket, clientMode = false, pinned = trust.get(deviceId)?.x509(), HANDSHAKE_TIMEOUT_MS)
            finishHandshake(ssl, deviceId, version, announced, how = "dialled")
        } catch (e: Exception) {
            log("dial $deviceId@${address.hostAddress}:$port failed: ${e.brief()}")
            runCatching { socket.close() }
        }
    }

    private fun acceptLoop(tcp: ServerSocket) {
        while (!tcp.isClosed) {
            val socket = try {
                tcp.accept()
            } catch (_: SocketException) {
                return
            } catch (_: IOException) {
                continue
            }
            scope.launch(Dispatchers.IO) { answer(socket) }
        }
    }

    private fun answer(socket: Socket) {
        try {
            val sender = socket.inetAddress
            if (sender.isLoopbackAddress && !allowLoopback) {
                socket.close()
                return
            }
            socket.keepAlive = true
            socket.soTimeout = HANDSHAKE_TIMEOUT_MS
            // Byte by byte on purpose: a buffered reader would swallow the
            // start of the TLS stream that follows this one line.
            val line = readLineBounded(socket.getInputStream(), MAX_IDENTITY_BYTES)
            val packet = line?.let(KdePacket::parse)
            val info = packet?.let(KdeDeviceInfo::fromPacket)
            val me = self()
            if (packet == null || info == null || info.id == me.id) {
                socket.close()
                return
            }
            // Protocol 8: the dialler says whom it meant to reach, so an
            // announcement with a forged source address cannot aim someone
            // else's connection at us.
            val target = packet.string("targetDeviceId")
            if (target != null && target != me.id) {
                socket.close()
                return
            }
            if (isDowngrade(info.id, info.protocolVersion)) {
                log("refusing protocol downgrade from ${info.id}")
                socket.close()
                return
            }
            // They dialled, so we are the TLS *client*.
            val ssl = tls.wrap(socket, clientMode = true, pinned = trust.get(info.id)?.x509(), HANDSHAKE_TIMEOUT_MS)
            finishHandshake(ssl, info.id, info.protocolVersion, info, how = "answered")
        } catch (e: Exception) {
            log("incoming connection failed: ${e.brief()}")
            runCatching { socket.close() }
        }
    }

    /**
     * After TLS: the encrypted identity exchange (protocol 8), then the checks
     * that tie the three things a peer has told us together — the id it was
     * contacted under, the id in its identity, and the name on its certificate.
     */
    private fun finishHandshake(ssl: SSLSocket, deviceId: String, version: Int, plaintext: KdeDeviceInfo?, how: String) {
        try {
            val certificate = tls.peerCertificate(ssl) ?: throw IOException("peer presented no certificate")
            val info: KdeDeviceInfo
            if (version >= 8) {
                // Both sides write first and read second; kdeconnect-kde gives
                // up on a peer that has not written within a second.
                ssl.getOutputStream().apply {
                    write(self().toPacket().serialize(now()).toByteArray(Charsets.UTF_8))
                    flush()
                }
                val line = readLineBounded(ssl.getInputStream(), MAX_IDENTITY_BYTES)
                    ?: throw IOException("no encrypted identity")
                info = KdePacket.parse(line)?.let(KdeDeviceInfo::fromPacket)
                    ?: throw IOException("invalid encrypted identity")
                if (info.id != deviceId) throw IOException("device id changed after encryption")
                if (info.protocolVersion != version.coerceAtMost(KDE_PROTOCOL_VERSION)) {
                    throw IOException("protocol version changed after encryption")
                }
            } else {
                info = plaintext ?: throw IOException("protocol 7 peer without an identity")
            }
            val commonName = DerCertificate.commonName(certificate)
            if (commonName == null || normalizeId(commonName) != normalizeId(info.id)) {
                throw IOException("certificate is for '${commonName.orEmpty()}', not '${info.id}'")
            }
            ssl.soTimeout = 0
            log("linked to ${info.id} ($how, ${ssl.session.protocol})")
            onLink(info, certificate, ssl)
        } catch (e: Exception) {
            log("handshake with $deviceId failed: ${e.brief()}")
            runCatching { ssl.close() }
        }
    }

    private fun isDowngrade(deviceId: String, version: Int): Boolean {
        val known = trust.get(deviceId) ?: return false
        return version < known.protocolVersion
    }

    private fun rateLimitAllows(key: String): Boolean {
        if (key.isEmpty()) return true
        val t = now()
        var allowed = false
        lastDial.compute(key) { _, last ->
            if (last == null || t - last >= MIN_DIAL_INTERVAL_MS || t < last) {
                allowed = true
                t
            } else {
                last
            }
        }
        if (lastDial.size > MAX_RATE_ENTRIES) lastDial.clear()
        return allowed
    }

    private fun openServer(min: Int, max: Int): ServerSocket? {
        for (port in min..max) {
            val socket = runCatching {
                ServerSocket().apply {
                    reuseAddress = true
                    bind(InetSocketAddress(port))
                }
            }.getOrNull()
            if (socket != null) return socket
        }
        return null
    }

    private fun broadcastAddresses(): List<InetAddress> = runCatching {
        NetworkInterface.getNetworkInterfaces().asSequence()
            .filter { runCatching { it.isUp && !it.isLoopback }.getOrDefault(false) }
            .flatMap { it.interfaceAddresses.asSequence() }
            .mapNotNull { it.broadcast }
            .toList()
    }.getOrDefault(emptyList())

    companion object {
        /** kdeconnect-kde caps identity packets at 8 KiB; kdeconnect-android at 512 KiB. Read generously. */
        const val MAX_IDENTITY_BYTES = 64 * 1024
        const val CONNECT_TIMEOUT_MS = 10_000
        const val HANDSHAKE_TIMEOUT_MS = 10_000
        const val MIN_DIAL_INTERVAL_MS = 1_000L
        private const val MAX_RATE_ENTRIES = 255
        private val HOST = Regex("^[0-9A-Za-z.:%_-]+$")

        /**
         * kdeconnect-kde rewrites anything outside `[A-Za-z0-9_]` to `_` before
         * comparing an id with a certificate's name (ids become D-Bus paths
         * there), so an old hyphenated id and its certificate still match.
         */
        fun normalizeId(id: String): String = id.replace(Regex("[^A-Za-z0-9_]"), "_")
    }
}

/** Why something failed, for a log line: the message, or the exception's name when it has none. */
internal fun Throwable.brief(): String = message ?: javaClass.simpleName

/**
 * One line from [input], without its `\n`, read a byte at a time so nothing
 * past the newline is consumed. Null at end of stream before any byte, or when
 * the line passes [max] bytes — an oversized identity is an attack or a bug,
 * and either way not something to buffer.
 */
internal fun readLineBounded(input: InputStream, max: Int): String? {
    val out = ByteArrayOutputStream(256)
    while (true) {
        val b = input.read()
        if (b < 0) return if (out.size() == 0) null else out.toString("UTF-8")
        if (b == '\n'.code) return out.toString("UTF-8")
        if (out.size() >= max) return null
        out.write(b)
    }
}
