package com.wasimaster.wmkeyboard.core.kdeconnect

import kotlinx.serialization.json.put

/**
 * `kdeconnect.clipboard` and `kdeconnect.clipboard.connect`.
 *
 * Two packets, because there are two questions. `clipboard` says "this was
 * just copied" and is always applied. `clipboard.connect` is sent once when a
 * link comes up and says "this is what I have, as of then" — applied only when
 * it is *newer* than what this side has, so whichever device was copied on last
 * wins, and reconnecting does not clobber a fresh copy with a stale one.
 *
 * ## Why this does not echo
 *
 * Setting the phone's clipboard fires the phone's own clipboard listener, which
 * would report the text right back to the PC, which would set it again. The
 * rule that stops it is ordering: [onPacket] records the text as the current
 * content **before** the host is told to write it, so when the listener's
 * report arrives in [localChanged] it is already known and goes nowhere. The
 * desktops do exactly the same.
 *
 * Nothing on the wire marks a clip as sensitive. That is decided here, locally:
 * a sensitive clip is remembered (so a later duplicate is still recognised)
 * but never sent on its own — only [push], the user's explicit "send this".
 */
class ClipboardPlugin internal constructor(host: KdePluginHost) : KdePlugin(host) {
    override val incoming = setOf(KdeTypes.CLIPBOARD, KdeTypes.CLIPBOARD_CONNECT)
    override val outgoing = setOf(KdeTypes.CLIPBOARD, KdeTypes.CLIPBOARD_CONNECT)
    override val key = KdePluginKeys.CLIPBOARD

    private val lock = Any()
    private var content: String = ""
    private var sensitive: Boolean = false

    /** 0 means "never seen a copy", which peers read as "ignore my connect packet". */
    private var timestampMs: Long = 0

    /**
     * The phone's clipboard changed to [text]. Sent to every connected device
     * unless it is what we already hold, empty, [isSensitive], or sending is
     * off. Returns the number of devices it went to.
     */
    fun localChanged(text: String, isSensitive: Boolean): Int {
        synchronized(lock) {
            if (text == content) return 0
            content = text
            sensitive = isSensitive
            timestampMs = nextTimestamp()
        }
        if (text.isEmpty() || isSensitive || !host.config.clipboardSend) return 0
        return broadcast(text)
    }

    /** The user asked for [text] to be sent now, sensitive or not. */
    fun push(text: String, deviceId: String? = null): Int {
        if (text.isEmpty()) return 0
        synchronized(lock) {
            if (text != content) {
                content = text
                sensitive = false
                timestampMs = nextTimestamp()
            }
        }
        return if (deviceId == null) {
            broadcast(text)
        } else if (enabledFor(deviceId) && host.send(deviceId, kdePacket(KdeTypes.CLIPBOARD) { put("content", text) })) {
            1
        } else {
            0
        }
    }

    override fun onConnected(deviceId: String) {
        if (!host.config.clipboardSend || !enabledFor(deviceId)) return
        val (text, stamp) = synchronized(lock) {
            if (sensitive || content.isEmpty()) return
            content to timestampMs
        }
        host.send(deviceId, kdePacket(KdeTypes.CLIPBOARD_CONNECT) { put("content", text); put("timestamp", stamp) })
    }

    override fun onPacket(deviceId: String, packet: KdePacket) {
        if (!host.config.clipboardReceive || !enabledFor(deviceId)) return
        val text = packet.string("content") ?: return
        if (text.isEmpty()) return
        synchronized(lock) {
            if (packet.type == KdeTypes.CLIPBOARD_CONNECT) {
                val theirs = packet.long("timestamp") ?: 0
                if (theirs <= 0 || theirs < timestampMs) return
            }
            if (text == content) return
            // Before the host writes it: see the class comment.
            content = text
            sensitive = false
            timestampMs = nextTimestamp()
        }
        host.emit(KdeEvent.ClipboardReceived(deviceId, host.device(deviceId)?.name.orEmpty(), text))
    }

    private fun broadcast(text: String): Int {
        var sent = 0
        for (id in host.connectedIds()) {
            if (!enabledFor(id)) continue
            if (host.send(id, kdePacket(KdeTypes.CLIPBOARD) { put("content", text) })) sent++
        }
        return sent
    }

    /**
     * Strictly increasing, even if the wall clock steps back: kdeconnect-kde
     * accepts an equal timestamp and GSConnect rejects one, so equal is the one
     * value never to produce.
     */
    private fun nextTimestamp(): Long = maxOf(host.now(), timestampMs + 1)
}
