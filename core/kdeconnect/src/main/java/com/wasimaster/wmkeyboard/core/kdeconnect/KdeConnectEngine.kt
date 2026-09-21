package com.wasimaster.wmkeyboard.core.kdeconnect

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.json.put
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.security.cert.X509Certificate
import javax.net.ssl.SSLSocket

/**
 * Finds `_kdeconnect._udp` services. The engine has no mDNS of its own — on
 * Android that is `NsdManager`, which is not something a JVM module can name —
 * so the host supplies one, or none, in which case UDP announcements carry
 * discovery alone (which is how the protocol worked for its first decade).
 */
interface KdeMdns {
    /**
     * Announce ourselves and start browsing. The service instance name must be
     * [deviceId] and the TXT records `id`, `name`, `type`, `protocol` —
     * kdeconnect-kde ignores a record without `protocol`.
     */
    fun start(deviceId: String, name: String, type: String, protocolVersion: Int, tcpPort: Int, onFound: (Found) -> Unit)
    fun stop()

    data class Found(val deviceId: String, val address: InetAddress, val port: Int, val protocolVersion: Int)
}

/**
 * A KDE Connect device, complete: discovery, encrypted links, pairing, and the
 * plugins, behind one [state] to draw and one [events] stream to react to.
 *
 * ## Who gets a link
 *
 * - A **paired** device is dialled whenever it is heard, if [KdeEngineConfig.autoConnect].
 * - An **unpaired** device gets a link only while [setDiscovering] is on — the
 *   user is looking at the list of nearby devices, which is also the only time
 *   one could be paired. The reference clients link to every device on the LAN
 *   all the time; a keyboard has no business holding sockets open to strangers'
 *   laptops on a café network while its owner types a message.
 *
 * ## Threads
 *
 * Everything here may be called from any thread. Sockets live on
 * `Dispatchers.IO`; the per-device bookkeeping is guarded by one lock; state is
 * a `MutableStateFlow` updated atomically. [events] are delivered on whatever
 * thread produced them — collectors hop to their own.
 */
class KdeConnectEngine(
    /** Where the identity and the paired devices are kept; null keeps them in memory only. */
    private val dir: File?,
    private val ports: KdePorts = KdePorts(),
    private val mdns: KdeMdns? = null,
    private val fileSink: KdeFileSink? = null,
    fetchArt: ((String) -> ByteArray?)? = null,
    /** Loopback peers are ignored in production, as kdeconnect-kde does; tests are nothing but. */
    private val allowLoopback: Boolean = false,
    private val tlsProtocols: List<String> = KdeTls.DEFAULT_PROTOCOLS,
    private val now: () -> Long = System::currentTimeMillis,
    private val log: (String) -> Unit = {},
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lock = Any()

    private val _state = MutableStateFlow(KdeState())
    val state: StateFlow<KdeState> = _state.asStateFlow()

    private val _events = MutableSharedFlow<KdeEvent>(extraBufferCapacity = 128)
    val events: SharedFlow<KdeEvent> = _events.asSharedFlow()

    @Volatile private var config = KdeEngineConfig()
    @Volatile private var discovering = false
    @Volatile private var running = false

    private val trust = KdeTrustStore(dir)
    private var identity: KdeLocalIdentity? = null
    private var tls: KdeTls? = null
    private var transport: LanTransport? = null
    private var payloads: PayloadTransfer? = null
    private var announcer: Job? = null

    private class Record(var info: KdeDeviceInfo, paired: Boolean) {
        val pairing = KdePairing(paired) { info.protocolVersion }
        var link: KdeLink? = null
        var linkedAtMs: Long = 0
        var timer: Job? = null
    }

    private val records = HashMap<String, Record>()

    /** Paired devices the user asked for by hand while auto-connect is off. */
    private val wanted = HashSet<String>()

    private val host = Host()

    val mousepad = MousepadPlugin(host)
    val clipboard = ClipboardPlugin(host)
    val mpris = MprisRemotePlugin(host, fetchArt)
    val phoneMedia = MprisHostPlugin(host)
    val volume = SystemVolumePlugin(host)
    val commands = RunCommandPlugin(host)
    val presenter = PresenterPlugin(host)
    val share = SharePlugin(host)
    val ping = PingPlugin(host)
    val battery = BatteryPlugin(host)
    val findDevice = FindDevicePlugin(host)
    val lockDevice = LockPlugin(host)

    private val plugins: List<KdePlugin> = listOf(
        mousepad, clipboard, mpris, phoneMedia, volume, commands, presenter, share, ping, battery, findDevice, lockDevice,
    )
    private val byIncoming: Map<String, List<KdePlugin>> =
        plugins.flatMap { p -> p.incoming.map { it to p } }.groupBy({ it.first }, { it.second })

    /** What the identity packet announces: the union of what the plugins handle. */
    val capabilities = KdeCapabilities(
        incoming = plugins.flatMapTo(sortedSetOf()) { it.incoming },
        outgoing = plugins.flatMapTo(sortedSetOf()) { it.outgoing },
    )

    val isRunning: Boolean get() = running

    /** True once something has been paired — the host's reason to run at all outside discovery. */
    fun hasPairedDevices(): Boolean = trust.all().isNotEmpty()

    fun configure(next: KdeEngineConfig) {
        val previous = config
        config = next.copy(deviceName = KdeDeviceNames.sanitize(next.deviceName).ifEmpty { KdeEngineConfig().deviceName })
        if (!running) return
        _state.update { it.copy(selfName = config.deviceName) }
        if (previous.remoteTyping != next.remoteTyping) mousepad.setKeyboardReady(keyboardShown)
        if (previous.deviceName != config.deviceName || previous.hosts != next.hosts) announce()
    }

    /**
     * Brings the device onto the network. Idempotent. False when no TCP port
     * in the protocol's range could be bound.
     */
    @Synchronized
    fun start(): Boolean {
        if (running) return true
        val loaded = KdeIdentityStore(dir).loadOrCreate(java.util.Date(now()))
        // A new certificate is a new device as far as every peer is concerned.
        if (loaded.created) trust.clear()
        val me = loaded.identity
        val secure = KdeTls(me, tlsProtocols)
        identity = me
        tls = secure
        payloads = PayloadTransfer(secure, ports)
        val lan = LanTransport(
            scope = scope,
            self = ::selfInfo,
            tls = secure,
            trust = trust,
            ports = ports,
            allowLoopback = allowLoopback,
            now = now,
            log = log,
            wantsDial = ::wantsDial,
            onLink = ::onLink,
        )
        if (!lan.start()) return false
        transport = lan
        running = true
        synchronized(lock) {
            for (device in trust.all()) recordFor(device.id, device.toInfo(), paired = true)
        }
        _state.value = KdeState(
            running = true,
            selfId = me.deviceId,
            selfName = config.deviceName,
            selfFingerprint = DerCertificate.fingerprint(me.certificate),
            listening = lan.listening,
            devices = trust.all().map { it.toDevice() },
        )
        runCatching {
            mdns?.start(me.deviceId, config.deviceName, config.deviceType.wire, KDE_PROTOCOL_VERSION, lan.tcpPort) { found ->
                if (running) lan.dial(found.address, found.port, found.deviceId, found.protocolVersion)
            }
        }
        startAnnouncer()
        return true
    }

    @Synchronized
    fun stop() {
        if (!running) return
        running = false
        announcer?.cancel()
        announcer = null
        runCatching { mdns?.stop() }
        transport?.stop()
        transport = null
        val open = synchronized(lock) {
            val links = records.values.mapNotNull { it.link }
            records.values.forEach { it.timer?.cancel() }
            records.clear()
            wanted.clear()
            links
        }
        open.forEach { it.close() }
        _state.value = KdeState()
    }

    /** [stop], and the coroutines with it. The engine is unusable afterwards. */
    fun close() {
        stop()
        scope.cancel()
    }

    /**
     * The user is looking at the list of nearby devices (or not any more).
     * While on, unpaired devices are linked so they can be listed and paired,
     * and announcements go out every few seconds.
     */
    fun setDiscovering(on: Boolean) {
        if (discovering == on) return
        discovering = on
        if (!running) return
        if (on) {
            announce()
            startAnnouncer()
        } else {
            val strangers = synchronized(lock) {
                records.values.filter { it.pairing.state == KdePairState.NOT_PAIRED }.mapNotNull { it.link }
            }
            strangers.forEach { it.close() }
        }
    }

    /** Announce now: the keyboard just came up, the network changed, the user pressed Refresh. */
    fun announce() {
        transport?.announce(config.hosts)
    }

    /** [announce], when the last thing heard from a paired device is old enough to doubt the link. */
    fun probe(staleAfterMs: Long = STALE_LINK_MS) {
        if (!running) return
        val t = now()
        val doubtful = synchronized(lock) {
            records.values.any { r ->
                r.pairing.state == KdePairState.PAIRED && (r.link?.let { t - it.lastReceivedMs > staleAfterMs } ?: true)
            }
        }
        if (doubtful) announce()
    }

    /** Announce to one host directly — "add a device by address". */
    fun announceTo(host: String, port: Int = ports.udpTarget) {
        val lan = transport ?: return
        scope.launch {
            runCatching { lan.announceTo(InetSocketAddress(InetAddress.getByName(host.trim()), port)) }
        }
    }

    /** Where our announcements are heard and where we accept connections. For tests and diagnostics. */
    val udpPort: Int get() = transport?.udpPort ?: 0
    val tcpPort: Int get() = transport?.tcpPort ?: 0

    // ---- pairing ----

    fun requestPair(deviceId: String) {
        val record = synchronized(lock) { records[deviceId] }
        if (record?.link?.isOpen != true) {
            update(deviceId) { it.copy(pairFailure = KdePairFailure.NOT_REACHABLE) }
            _events.tryEmit(KdeEvent.PairFailed(deviceId, KdePairFailure.NOT_REACHABLE))
            return
        }
        apply(deviceId, record) { it.request(now()) }
    }

    fun acceptPair(deviceId: String) = withRecord(deviceId) { r -> apply(deviceId, r) { it.accept() } }

    /** Reject their request, or withdraw ours. */
    fun cancelPair(deviceId: String) = withRecord(deviceId) { r -> apply(deviceId, r) { it.cancel() } }

    fun unpair(deviceId: String) {
        val record = synchronized(lock) { records[deviceId] }
        if (record == null) {
            trust.remove(deviceId)
            _state.update { s -> s.copy(devices = s.devices.filter { it.id != deviceId }) }
            return
        }
        apply(deviceId, record) { it.unpair(reachable = record.link?.isOpen == true) }
    }

    /** Dial a paired device by hand. Only meaningful with auto-connect off. */
    fun connect(deviceId: String) {
        synchronized(lock) { wanted += deviceId }
        announce()
    }

    fun setPluginEnabled(deviceId: String, pluginKey: String, enabled: Boolean) {
        trust.update(deviceId) { d -> d.copy(disabled = if (enabled) d.disabled - pluginKey else d.disabled + pluginKey) }
        val disabled = trust.get(deviceId)?.disabled ?: return
        update(deviceId) { it.copy(disabled = disabled) }
        if (pluginKey == KdePluginKeys.REMOTE_TYPING) {
            // Re-announce the keyboard's state under the new rule.
            mousepad.setKeyboardReady(false)
            mousepad.setKeyboardReady(keyboardShown)
        }
    }

    @Volatile private var keyboardShown = false

    /** The keyboard is on screen over a field, or it is not: what PC typing is gated on. */
    fun setKeyboardShown(shown: Boolean) {
        keyboardShown = shown
        mousepad.setKeyboardReady(shown)
    }

    // ---- links ----

    private fun selfInfo(): KdeDeviceInfo = KdeDeviceInfo(
        id = identity?.deviceId.orEmpty(),
        name = config.deviceName,
        type = config.deviceType,
        protocolVersion = KDE_PROTOCOL_VERSION,
        incoming = capabilities.incoming,
        outgoing = capabilities.outgoing,
    )

    private fun wantsDial(deviceId: String): Boolean {
        if (!running) return false
        synchronized(lock) {
            val record = records[deviceId]
            // Two copies of one announcement (one per interface) are one event.
            if (record?.link?.isOpen == true && now() - record.linkedAtMs < DUPLICATE_WINDOW_MS) return false
            return if (trust.isTrusted(deviceId)) config.autoConnect || deviceId in wanted else discovering
        }
    }

    private fun onLink(info: KdeDeviceInfo, certificate: X509Certificate, socket: SSLSocket) {
        if (!running) {
            runCatching { socket.close() }
            return
        }
        val paired = trust.isTrusted(info.id)
        var replaced: KdeLink? = null
        val link: KdeLink = synchronized(lock) {
            if (!paired) {
                val strangers = records.values.count { it.pairing.state != KdePairState.PAIRED && it.link?.isOpen == true }
                if (!discovering || strangers >= MAX_UNPAIRED_LINKS) {
                    runCatching { socket.close() }
                    return
                }
            }
            val record = recordFor(info.id, info, paired)
            val existing = record.link
            // A second socket for a device we already hold must be the same
            // device. TLS pinned it if paired; this covers the unpaired case.
            if (existing != null && existing.isOpen && !existing.certificate.encoded.contentEquals(certificate.encoded)) {
                runCatching { socket.close() }
                return
            }
            replaced = existing
            val created = KdeLink(info, certificate, socket, scope, now, ::onPacket, ::onLinkClosed)
            record.info = info
            record.link = created
            record.linkedAtMs = now()
            created
        }
        replaced?.close()
        val address = link.address.hostAddress.orEmpty()
        if (paired) {
            trust.update(info.id) {
                it.copy(
                    name = info.name,
                    type = info.type.wire,
                    lastAddress = address,
                    lastSeenMs = now(),
                    protocolVersion = maxOf(it.protocolVersion, info.protocolVersion),
                )
            }
        }
        val fingerprint = DerCertificate.fingerprint(certificate)
        _state.update { s ->
            val fresh = KdeDevice(info.id, info.name, info.type)
            val base = s.devices.firstOrNull { it.id == info.id } ?: fresh
            val next = base.copy(
                name = info.name,
                type = info.type,
                paired = paired,
                reachable = true,
                pairState = if (paired) KdePairState.PAIRED else base.pairState,
                address = address,
                fingerprint = fingerprint,
                accepts = info.incoming,
                sends = info.outgoing,
                disabled = trust.get(info.id)?.disabled.orEmpty(),
            )
            s.copy(devices = (s.devices.filter { it.id != info.id } + next).sortedForDisplay())
        }
        link.start()
        if (paired) plugins.forEach { p -> runCatching { p.onConnected(info.id) } }
    }

    private fun onLinkClosed(link: KdeLink) {
        val id = link.info.id
        val record: Record = synchronized(lock) {
            val found = records[id]
            // An old connection must not take its replacement down with it.
            if (found == null || found.link !== link) return
            found.link = null
            found
        }
        val wasPaired = record.pairing.state == KdePairState.PAIRED
        runEffects(id, record, record.pairing.onDisconnected())
        if (wasPaired) {
            plugins.forEach { p -> runCatching { p.onDisconnected(id) } }
            update(id) { it.copy(reachable = false, battery = null) }
        } else {
            synchronized(lock) { records.remove(id)?.timer?.cancel() }
            _state.update { s -> s.copy(devices = s.devices.filter { it.id != id }) }
        }
    }

    private fun onPacket(link: KdeLink, packet: KdePacket) {
        val id = link.info.id
        val record = synchronized(lock) { records[id] } ?: return
        if (packet.type == KdeTypes.PAIR) {
            apply(id, record) { it.onPacket(packet, now()) }
            return
        }
        if (packet.type == KdeTypes.IDENTITY) return
        if (record.pairing.state != KdePairState.PAIRED) {
            // Both reference clients answer a stray packet from an unpaired
            // device this way: it is how a device that was unpaired while out
            // of reach finds out.
            link.send(kdePacket(KdeTypes.PAIR) { put("pair", false) })
            return
        }
        byIncoming[packet.type]?.forEach { p -> runCatching { p.onPacket(id, packet) } }
    }

    // ---- pairing effects ----

    private inline fun apply(deviceId: String, record: Record, step: (KdePairing) -> List<KdePairing.Effect>) {
        val effects = synchronized(record.pairing) { step(record.pairing) }
        runEffects(deviceId, record, effects)
    }

    private fun runEffects(deviceId: String, record: Record, effects: List<KdePairing.Effect>) {
        for (effect in effects) {
            when (effect) {
                is KdePairing.Effect.Send -> record.link?.send(effect.packet)
                is KdePairing.Effect.StartTimer -> {
                    record.timer?.cancel()
                    record.timer = scope.launch {
                        delay(effect.millis)
                        apply(deviceId, record) { it.onTimeout() }
                    }
                    update(deviceId) { it.copy(pairDeadlineMs = now() + effect.millis) }
                }
                KdePairing.Effect.StopTimer -> {
                    record.timer?.cancel()
                    record.timer = null
                }
                KdePairing.Effect.Trust -> {
                    val link = record.link ?: continue
                    trust.put(
                        KdeTrustedDevice(
                            id = deviceId,
                            name = link.info.name,
                            type = link.info.type.wire,
                            certificate = KdeTrustStore.encode(link.certificate),
                            protocolVersion = link.info.protocolVersion,
                            lastAddress = link.address.hostAddress.orEmpty(),
                            lastSeenMs = now(),
                        ),
                    )
                    // One update, so no observer ever sees "paired" with the
                    // pairing code still on screen.
                    update(deviceId) {
                        it.copy(
                            paired = true,
                            pairState = KdePairState.PAIRED,
                            verificationKey = null,
                            pairDeadlineMs = 0,
                            pairFailure = null,
                        )
                    }
                    _events.tryEmit(KdeEvent.Paired(deviceId, link.info.name))
                    plugins.forEach { p -> runCatching { p.onConnected(deviceId) } }
                }
                KdePairing.Effect.Distrust -> {
                    trust.remove(deviceId)
                    plugins.forEach { p -> runCatching { p.onDisconnected(deviceId) } }
                    update(deviceId) { it.copy(paired = false, pairState = record.pairing.state) }
                    _events.tryEmit(KdeEvent.Unpaired(deviceId))
                    // No longer ours to hold a socket to, unless the user is browsing.
                    if (!discovering) record.link?.close()
                    if (record.link == null) {
                        synchronized(lock) { records.remove(deviceId) }
                        _state.update { s -> s.copy(devices = s.devices.filter { it.id != deviceId }) }
                    }
                }
                is KdePairing.Effect.Failed -> {
                    update(deviceId) { it.copy(pairFailure = effect.reason) }
                    _events.tryEmit(KdeEvent.PairFailed(deviceId, effect.reason))
                }
                KdePairing.Effect.AskUser -> {
                    val key = verificationKey(record).orEmpty()
                    _events.tryEmit(KdeEvent.PairRequested(deviceId, record.info.name, key))
                }
            }
        }
        val pairing = record.pairing
        update(deviceId) {
            it.copy(
                pairState = pairing.state,
                verificationKey = if (pairing.inProgress) verificationKey(record) else null,
                pairDeadlineMs = if (pairing.inProgress) it.pairDeadlineMs else 0,
                pairFailure = if (pairing.inProgress) null else it.pairFailure,
            )
        }
    }

    private fun verificationKey(record: Record): String? {
        val mine = identity?.certificate ?: return null
        val theirs = record.link?.certificate ?: return null
        val stamp = if (record.info.protocolVersion >= 8) record.pairing.timestampSeconds ?: return null else null
        return KdeVerificationKey.of(mine, theirs, stamp)
    }

    // ---- bookkeeping ----

    /** Must be called with [lock] held. */
    private fun recordFor(id: String, info: KdeDeviceInfo, paired: Boolean): Record =
        records.getOrPut(id) { Record(info, paired) }

    private inline fun withRecord(deviceId: String, block: (Record) -> Unit) {
        val record = synchronized(lock) { records[deviceId] } ?: return
        block(record)
    }

    private fun update(deviceId: String, change: (KdeDevice) -> KdeDevice) {
        _state.update { s ->
            var touched = false
            val devices = s.devices.map {
                if (it.id == deviceId) {
                    val next = change(it)
                    if (next != it) touched = true
                    next
                } else {
                    it
                }
            }
            if (touched) s.copy(devices = devices) else s
        }
    }

    private fun startAnnouncer() {
        announcer?.cancel()
        announcer = scope.launch {
            var round = 0
            while (isActive && running) {
                val lonely = discovering || synchronized(lock) {
                    records.values.any { it.pairing.state == KdePairState.PAIRED && it.link?.isOpen != true }
                }
                if (lonely) announce()
                // Eager at first — a keyboard is on screen for seconds, not
                // hours — then a heartbeat that costs one datagram.
                delay(if (round < ANNOUNCE_RAMP_MS.size) ANNOUNCE_RAMP_MS[round] else if (discovering) 5_000L else 20_000L)
                round++
            }
        }
    }

    private fun KdeTrustedDevice.toInfo() =
        KdeDeviceInfo(id, name, KdeDeviceType.fromWire(type), protocolVersion)

    private fun KdeTrustedDevice.toDevice() = KdeDevice(
        id = id,
        name = name,
        type = KdeDeviceType.fromWire(type),
        paired = true,
        pairState = KdePairState.PAIRED,
        address = lastAddress,
        fingerprint = x509()?.let(DerCertificate::fingerprint).orEmpty(),
        disabled = disabled,
    )

    private fun List<KdeDevice>.sortedForDisplay(): List<KdeDevice> =
        sortedWith(compareByDescending<KdeDevice> { it.paired }.thenByDescending { it.reachable }.thenBy { it.name.lowercase() })

    private inner class Host : KdePluginHost {
        override val config: KdeEngineConfig get() = this@KdeConnectEngine.config
        override val fileSink: KdeFileSink? get() = this@KdeConnectEngine.fileSink
        override fun now(): Long = this@KdeConnectEngine.now()

        override fun send(deviceId: String, packet: KdePacket): Boolean {
            val record = synchronized(lock) { records[deviceId] } ?: return false
            if (record.pairing.state != KdePairState.PAIRED) return false
            val link = record.link ?: return false
            if (packet.type !in link.info.incoming) return false
            return link.send(packet)
        }

        override fun device(deviceId: String): KdeDevice? = _state.value.device(deviceId)
        override fun update(deviceId: String, change: (KdeDevice) -> KdeDevice) = this@KdeConnectEngine.update(deviceId, change)
        override fun emit(event: KdeEvent) {
            _events.tryEmit(event)
        }

        override fun connectedIds(): List<String> = synchronized(lock) {
            records.filter { (_, r) -> r.pairing.state == KdePairState.PAIRED && r.link?.isOpen == true }.keys.toList()
        }

        override fun upload(
            deviceId: String,
            header: KdePacket,
            size: Long,
            source: InputStream,
            cancelled: () -> Boolean,
            onProgress: (Long) -> Unit,
        ): Boolean {
            val record = synchronized(lock) { records[deviceId] } ?: return false
            val link = record.link?.takeIf { record.pairing.state == KdePairState.PAIRED } ?: return false
            val transfer = payloads ?: return false
            val server = transfer.listen() ?: return false
            // 0 means "no payload" on the wire, so an empty file says -1 and
            // closes at once; the desktop reads that as a zero-byte stream.
            val announced = if (size == 0L) -1L else size
            if (!link.send(header.copy(payloadSize = announced, payloadPort = server.localPort))) {
                runCatching { server.close() }
                return false
            }
            return runCatching { transfer.serve(server, link.certificate, source, cancelled, onProgress) }
                .onFailure { log("upload to $deviceId failed: ${it.brief()}") }
                .isSuccess
        }

        override fun download(
            deviceId: String,
            packet: KdePacket,
            sink: OutputStream,
            cancelled: () -> Boolean,
            onProgress: (Long) -> Unit,
        ): Boolean {
            val record = synchronized(lock) { records[deviceId] } ?: return false
            val link = record.link?.takeIf { record.pairing.state == KdePairState.PAIRED } ?: return false
            val transfer = payloads ?: return false
            if (packet.payloadPort !in 1..65535) return false
            return runCatching {
                transfer.fetch(link.address, packet.payloadPort, link.certificate, packet.payloadSize, sink, cancelled, onProgress)
            }.onFailure { log("download from $deviceId failed: ${it.brief()}") }.isSuccess
        }

        override fun launch(block: suspend () -> Unit) {
            scope.launch { block() }
        }
    }

    private companion object {
        const val MAX_UNPAIRED_LINKS = 42
        const val DUPLICATE_WINDOW_MS = 3_000L
        const val STALE_LINK_MS = 45_000L
        val ANNOUNCE_RAMP_MS = longArrayOf(1_500L, 3_500L, 5_000L)
    }
}
