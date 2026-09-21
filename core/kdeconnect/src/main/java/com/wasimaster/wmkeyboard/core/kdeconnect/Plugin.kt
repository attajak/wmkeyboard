package com.wasimaster.wmkeyboard.core.kdeconnect

import java.io.InputStream
import java.io.OutputStream

/**
 * One KDE Connect plugin: a family of packet types and what to do with them.
 *
 * [incoming] and [outgoing] are not documentation. The engine builds the
 * identity packet's capability lists from them, and a peer **silently drops**
 * any packet type missing from those lists — so a type handled in [onPacket]
 * but absent from [incoming] is a feature that never works and never says why.
 */
abstract class KdePlugin internal constructor(internal val host: KdePluginHost) {
    abstract val incoming: Set<String>
    abstract val outgoing: Set<String>

    /** The per-device switch that silences this plugin, or null if it has none. */
    internal open val key: String? = null

    /** A paired device became reachable. Runs on the link's reader thread. */
    internal open fun onConnected(deviceId: String) {}

    internal open fun onDisconnected(deviceId: String) {}

    /** A packet of one of [incoming] from a paired device. */
    internal abstract fun onPacket(deviceId: String, packet: KdePacket)

    protected fun enabledFor(deviceId: String): Boolean {
        val k = key ?: return true
        return host.device(deviceId)?.disabled?.contains(k) != true
    }
}

/** What a plugin may ask of the engine. */
internal interface KdePluginHost {
    val config: KdeEngineConfig
    fun now(): Long

    /**
     * Sends to a paired, reachable device that accepts [packet]'s type. False
     * otherwise — an unpaired peer answers any stray packet by unpairing, so
     * the check is here once rather than in every caller.
     */
    fun send(deviceId: String, packet: KdePacket): Boolean

    fun device(deviceId: String): KdeDevice?
    fun update(deviceId: String, change: (KdeDevice) -> KdeDevice)
    fun emit(event: KdeEvent)
    fun connectedIds(): List<String>

    /**
     * Announces [header] with a payload and streams [source] to the peer when
     * it fetches. Blocks until the transfer ends; call from [launch].
     */
    fun upload(deviceId: String, header: KdePacket, size: Long, source: InputStream, cancelled: () -> Boolean, onProgress: (Long) -> Unit): Boolean

    /** Fetches [packet]'s payload into [sink]. Blocks; call from [launch]. */
    fun download(deviceId: String, packet: KdePacket, sink: OutputStream, cancelled: () -> Boolean, onProgress: (Long) -> Unit): Boolean

    fun launch(block: suspend () -> Unit)
    val fileSink: KdeFileSink?
}
