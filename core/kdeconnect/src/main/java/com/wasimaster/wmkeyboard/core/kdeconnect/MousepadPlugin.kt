package com.wasimaster.wmkeyboard.core.kdeconnect

import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.put

/**
 * `kdeconnect.mousepad.*`, both directions.
 *
 * **Out**: this phone as the PC's mouse and keyboard — pointer deltas, scroll,
 * clicks, a drag's hold and release, text, special keys, modifier chords.
 *
 * **In**: the PC's keyboard typing into the phone. The desktop sends the very
 * same `mousepad.request` packet for that, which is why one plugin owns both:
 * a packet with `key` or `specialKey` is a keystroke *for us*, anything else
 * is pointer traffic we have no pointer to apply to.
 *
 * Two things make the desktop's "remote keyboard" box work at all, neither of
 * them obvious from the packet list:
 * - it only **appears** once we have sent `keyboardstate {state: true}`;
 * - it only **echoes what was typed** if our `mousepad.echo` carries both
 *   `isAck` and `key` — kdeconnect-kde drops an echo missing either, so a
 *   special-key echo still has to say `"key": ""`.
 */
class MousepadPlugin internal constructor(host: KdePluginHost) : KdePlugin(host) {
    override val incoming = setOf(KdeTypes.MOUSEPAD_REQUEST, KdeTypes.MOUSEPAD_KEYBOARDSTATE)
    override val outgoing = setOf(KdeTypes.MOUSEPAD_REQUEST, KdeTypes.MOUSEPAD_ECHO, KdeTypes.MOUSEPAD_KEYBOARDSTATE)

    @Volatile private var keyboardReady = false

    // ---- out: pointer ----

    fun move(deviceId: String, dx: Double, dy: Double): Boolean {
        if (dx == 0.0 && dy == 0.0) return true
        return host.send(deviceId, MousepadPackets.move(dx, dy))
    }

    fun scroll(deviceId: String, dx: Double, dy: Double): Boolean {
        if (dx == 0.0 && dy == 0.0) return true
        return host.send(deviceId, request { put("scroll", true); put("dx", dx); put("dy", dy) })
    }

    fun click(deviceId: String, click: KdeClick): Boolean = host.send(
        deviceId,
        request {
            put(
                when (click) {
                    KdeClick.LEFT -> "singleclick"
                    KdeClick.RIGHT -> "rightclick"
                    KdeClick.MIDDLE -> "middleclick"
                    KdeClick.DOUBLE -> "doubleclick"
                },
                true,
            )
        },
    )

    /** The left button held ([down]) or let go: the two halves of a drag. */
    fun hold(deviceId: String, down: Boolean): Boolean =
        host.send(deviceId, request { put(if (down) "singlehold" else "singlerelease", true) })

    // ---- out: keys ----

    /** Text of any length in one packet; the desktop types it as written. */
    fun text(deviceId: String, text: String): Boolean {
        val clean = text.replace(NUL, "")
        if (clean.isEmpty()) return true
        return host.send(deviceId, request { put("key", clean) })
    }

    /** One character with modifiers held around it: Ctrl+C, Alt+Tab's cousin Alt+x. */
    fun chord(deviceId: String, key: String, modifiers: KdeModifiers): Boolean {
        if (key.isEmpty()) return true
        // Shift alone is not a chord: the character already carries it.
        if (!modifiers.ctrl && !modifiers.alt && !modifiers.meta) return text(deviceId, key)
        // A chord names the key, not the character shift would make of it.
        return host.send(deviceId, request { put("key", key.lowercase()); modifiers(modifiers) })
    }

    fun special(deviceId: String, key: KdeSpecialKey, modifiers: KdeModifiers = KdeModifiers.None, times: Int = 1): Boolean {
        var ok = true
        repeat(times.coerceIn(1, MAX_REPEAT)) {
            ok = host.send(deviceId, request { put("specialKey", key.code); modifiers(modifiers) }) && ok
        }
        return ok
    }

    // ---- in: the PC's keyboard ----

    /**
     * Tells every connected desktop whether keystrokes have somewhere to go —
     * the keyboard is on screen over a field. Their remote-keyboard box shows
     * and hides with it.
     */
    fun setKeyboardReady(ready: Boolean) {
        val effective = ready && host.config.remoteTyping
        if (keyboardReady == effective) return
        keyboardReady = effective
        for (id in host.connectedIds()) sendKeyboardState(id)
    }

    override fun onConnected(deviceId: String) {
        sendKeyboardState(deviceId)
    }

    private fun sendKeyboardState(deviceId: String) {
        val ready = keyboardReady && host.device(deviceId)?.disabled?.contains(KdePluginKeys.REMOTE_TYPING) != true
        host.send(deviceId, kdePacket(KdeTypes.MOUSEPAD_KEYBOARDSTATE) { put("state", ready) })
    }

    override fun onPacket(deviceId: String, packet: KdePacket) {
        if (packet.type == KdeTypes.MOUSEPAD_KEYBOARDSTATE) {
            val accepts = packet.boolOrNull("state") ?: true
            host.update(deviceId) { it.copy(acceptsKeys = accepts) }
            return
        }
        if (!packet.has("key") && !packet.has("specialKey")) return
        if (!keyboardReady || host.device(deviceId)?.disabled?.contains(KdePluginKeys.REMOTE_TYPING) == true) return

        // kdeconnect-android sends a lone NUL ahead of some special keys.
        val text = packet.string("key").orEmpty().replace(NUL, "")
        val special = KdeSpecialKey.fromCode(packet.int("specialKey"))
        if (text.isEmpty() && special == null) return

        host.emit(
            KdeEvent.RemoteKey(
                deviceId = deviceId,
                text = if (special == null) text else "",
                special = special,
                shift = packet.bool("shift"),
                ctrl = packet.bool("ctrl"),
                alt = packet.bool("alt"),
            ),
        )
        if (packet.bool("sendAck")) {
            host.send(
                deviceId,
                kdePacket(KdeTypes.MOUSEPAD_ECHO) {
                    put("key", packet.string("key").orEmpty().replace(NUL, ""))
                    packet.int("specialKey")?.let { put("specialKey", it) }
                    for (flag in ECHOED_FLAGS) if (packet.has(flag)) put(flag, packet.bool(flag))
                    put("isAck", true)
                },
            )
        }
    }

    private inline fun request(build: JsonObjectBuilder.() -> Unit): KdePacket = kdePacket(KdeTypes.MOUSEPAD_REQUEST, build)

    private fun JsonObjectBuilder.modifiers(m: KdeModifiers) {
        if (m.shift) put("shift", true)
        if (m.ctrl) put("ctrl", true)
        if (m.alt) put("alt", true)
        if (m.meta) put("super", true)
    }

    private companion object {
        /** Spelt as a code point so no tool ever has to carry the escape for it. */
        val NUL = Char(0).toString()
        const val MAX_REPEAT = 200
        val ECHOED_FLAGS = listOf("shift", "ctrl", "alt", "super")
    }
}

/** The shapes of pointer packets the link's writer needs to recognise. */
internal object MousepadPackets {
    fun move(dx: Double, dy: Double): KdePacket =
        kdePacket(KdeTypes.MOUSEPAD_REQUEST) { put("dx", dx); put("dy", dy) }

    /** A packet that does nothing but move the pointer: exactly `dx` and `dy`. */
    fun isPureMove(packet: KdePacket): Boolean =
        packet.type == KdeTypes.MOUSEPAD_REQUEST && packet.body.size == 2 &&
            packet.body["dx"] is JsonPrimitive && packet.body["dy"] is JsonPrimitive

    /**
     * [a] then [b] as one movement, or null when either is anything else.
     * Scroll packets are not merged: a desktop turns each into wheel events,
     * and their count is part of how it feels.
     */
    fun mergeMoves(a: KdePacket, b: KdePacket): KdePacket? {
        if (!isPureMove(a) || !isPureMove(b)) return null
        return move(
            (a.double("dx") ?: 0.0) + (b.double("dx") ?: 0.0),
            (a.double("dy") ?: 0.0) + (b.double("dy") ?: 0.0),
        )
    }
}
