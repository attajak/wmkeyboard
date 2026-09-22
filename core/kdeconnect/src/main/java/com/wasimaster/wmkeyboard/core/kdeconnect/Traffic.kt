package com.wasimaster.wmkeyboard.core.kdeconnect

import java.net.InetAddress

/** What kind of connection a [KdeTrafficMeter] is told about. */
enum class KdeTrafficKind {
    /** The control link to a device: packets both ways for as long as it is up. */
    LINK,

    /** A file this device served to a peer. */
    PAYLOAD_SEND,

    /** A file this device fetched from a peer. */
    PAYLOAD_RECEIVE,
}

/**
 * How the engine reports its connections to whoever is counting them — the
 * keyboard's network activity log. An interface rather than a dependency, so
 * this module keeps depending on nothing of the app's.
 */
fun interface KdeTrafficMeter {
    fun open(kind: KdeTrafficKind, address: InetAddress, port: Int): KdeTrafficTap
}

/** One connection being counted. [close] is called exactly once. */
interface KdeTrafficTap {
    fun sent(bytes: Long)
    fun received(bytes: Long)
    fun close(failure: Throwable?)
}
