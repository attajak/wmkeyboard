package com.wasimaster.wmkeyboard.core.kdeconnect

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.put

/** `kdeconnect.ping`, both ways. A ping with no message is still a ping. */
class PingPlugin internal constructor(host: KdePluginHost) : KdePlugin(host) {
    override val incoming = setOf(KdeTypes.PING)
    override val outgoing = setOf(KdeTypes.PING)

    fun ping(deviceId: String, message: String = ""): Boolean =
        host.send(deviceId, kdePacket(KdeTypes.PING) { if (message.isNotBlank()) put("message", message.trim()) })

    override fun onPacket(deviceId: String, packet: KdePacket) {
        host.emit(KdeEvent.Ping(deviceId, host.device(deviceId)?.name.orEmpty(), packet.string("message").orEmpty()))
    }
}

/**
 * `kdeconnect.battery`, both ways: the PC's charge for the device chip, and
 * the phone's for the desktop's tray. Sent only when something changed — the
 * sticky battery broadcast fires far more often than the numbers move.
 */
class BatteryPlugin internal constructor(host: KdePluginHost) : KdePlugin(host) {
    override val incoming = setOf(KdeTypes.BATTERY)
    override val outgoing = setOf(KdeTypes.BATTERY)
    override val key = KdePluginKeys.BATTERY

    @Volatile private var local: KdeBattery? = null

    fun setLocal(charge: Int, charging: Boolean, low: Boolean) {
        val next = KdeBattery(charge.coerceIn(-1, 100), charging, low)
        if (next == local) return
        local = next
        for (id in host.connectedIds()) send(id, next)
    }

    override fun onConnected(deviceId: String) {
        local?.let { send(deviceId, it) }
    }

    override fun onDisconnected(deviceId: String) {
        host.update(deviceId) { it.copy(battery = null) }
    }

    override fun onPacket(deviceId: String, packet: KdePacket) {
        val charge = packet.int("currentCharge") ?: return
        // -1 is a desktop with no battery at all.
        val battery = if (charge < 0) null else KdeBattery(charge.coerceIn(0, 100), packet.bool("isCharging"), packet.int("thresholdEvent") == 1)
        host.update(deviceId) { it.copy(battery = battery) }
    }

    private fun send(deviceId: String, battery: KdeBattery) {
        if (!host.config.batteryReport || !enabledFor(deviceId)) return
        host.send(
            deviceId,
            kdePacket(KdeTypes.BATTERY) {
                put("currentCharge", battery.charge)
                put("isCharging", battery.charging)
                put("thresholdEvent", if (battery.low) 1 else 0)
            },
        )
    }
}

/** Makes the PC ring. The protocol names the packet for phones; desktops answer it too. */
class FindDevicePlugin internal constructor(host: KdePluginHost) : KdePlugin(host) {
    override val incoming = emptySet<String>()
    override val outgoing = setOf(KdeTypes.FINDMYPHONE_REQUEST)

    fun ring(deviceId: String): Boolean = host.send(deviceId, kdePacket(KdeTypes.FINDMYPHONE_REQUEST))

    override fun onPacket(deviceId: String, packet: KdePacket) = Unit
}

/**
 * Locks and unlocks the PC's session (`kdeconnect.lock.request` out,
 * `kdeconnect.lock` in). Only the request goes out from here: advertising
 * `lock.request` as *incoming* would offer the phone up as something to lock.
 *
 * The desktop's own opening packet is `{"requestLocked": null}`, so nothing
 * here may test these keys for truth — only for presence.
 */
class LockPlugin internal constructor(host: KdePluginHost) : KdePlugin(host) {
    override val incoming = setOf(KdeTypes.LOCK)
    override val outgoing = setOf(KdeTypes.LOCK_REQUEST)

    fun setLocked(deviceId: String, locked: Boolean): Boolean =
        host.send(deviceId, kdePacket(KdeTypes.LOCK_REQUEST) { put("setLocked", locked) })

    fun refresh(deviceId: String): Boolean =
        host.send(deviceId, kdePacket(KdeTypes.LOCK_REQUEST) { put("requestLocked", true) })

    override fun onConnected(deviceId: String) {
        refresh(deviceId)
    }

    override fun onDisconnected(deviceId: String) {
        host.update(deviceId) { it.copy(lock = KdeLock()) }
    }

    override fun onPacket(deviceId: String, packet: KdePacket) {
        if (packet.has("isLocked")) {
            val locked = packet.bool("isLocked")
            host.update(deviceId) { it.copy(lock = KdeLock(known = true, locked = locked)) }
        }
        if (packet.has("lockResult")) host.emit(KdeEvent.LockResult(deviceId, packet.bool("lockResult")))
    }
}

/**
 * The slideshow pointer. `dx`/`dy` are **fractions of the PC's screen**, not
 * pixels, and the desktop removes its red dot after half a second without a
 * packet — so a pointer held still has to keep saying so.
 */
class PresenterPlugin internal constructor(host: KdePluginHost) : KdePlugin(host) {
    override val incoming = emptySet<String>()
    override val outgoing = setOf(KdeTypes.PRESENTER)

    fun move(deviceId: String, dxFraction: Double, dyFraction: Double): Boolean =
        host.send(deviceId, kdePacket(KdeTypes.PRESENTER) { put("dx", dxFraction); put("dy", dyFraction) })

    fun stop(deviceId: String): Boolean = host.send(deviceId, kdePacket(KdeTypes.PRESENTER) { put("stop", true) })

    override fun onPacket(deviceId: String, packet: KdePacket) = Unit
}

/**
 * The commands the user defined on the PC, run from here.
 *
 * `commandList` arrives as a **string of JSON inside the JSON** — an object
 * keyed by command id — not as an object. `runcommand.output` and the `setup`
 * and `stop` requests are in both reference clients and in no specification.
 */
class RunCommandPlugin internal constructor(host: KdePluginHost) : KdePlugin(host) {
    override val incoming = setOf(KdeTypes.RUNCOMMAND, KdeTypes.RUNCOMMAND_OUTPUT)
    override val outgoing = setOf(KdeTypes.RUNCOMMAND_REQUEST)

    fun refresh(deviceId: String): Boolean =
        host.send(deviceId, kdePacket(KdeTypes.RUNCOMMAND_REQUEST) { put("requestCommandList", true) })

    fun run(deviceId: String, key: String): Boolean {
        val command = host.device(deviceId)?.commands?.list?.firstOrNull { it.key == key }
        // Shown before the desktop confirms, so the press is visibly received.
        host.update(deviceId) {
            it.copy(commands = it.commands.copy(running = KdeCommandRun(id = -1, command = command?.name.orEmpty())))
        }
        return host.send(deviceId, kdePacket(KdeTypes.RUNCOMMAND_REQUEST) { put("key", key) })
    }

    /** Opens the command editor on the PC's screen. */
    fun setup(deviceId: String): Boolean =
        host.send(deviceId, kdePacket(KdeTypes.RUNCOMMAND_REQUEST) { put("setup", true) })

    fun stop(deviceId: String): Boolean =
        host.send(deviceId, kdePacket(KdeTypes.RUNCOMMAND_REQUEST) { put("stop", true) })

    fun dismissOutput(deviceId: String) {
        host.update(deviceId) { it.copy(commands = it.commands.copy(running = null)) }
    }

    override fun onConnected(deviceId: String) {
        refresh(deviceId)
    }

    override fun onDisconnected(deviceId: String) {
        host.update(deviceId) { it.copy(commands = KdeCommands()) }
    }

    override fun onPacket(deviceId: String, packet: KdePacket) {
        if (packet.type == KdeTypes.RUNCOMMAND_OUTPUT) {
            output(deviceId, packet)
            return
        }
        val list = packet.string("commandList")?.let(::parseCommands)
            // A client that sends the object itself is wrong and harmless.
            ?: packet.obj("commandList")?.let(::commandsOf)
        host.update(deviceId) {
            it.copy(
                commands = it.commands.copy(
                    list = list ?: it.commands.list,
                    loaded = true,
                    canAdd = packet.boolOrNull("canAddCommand") ?: it.commands.canAdd,
                ),
            )
        }
    }

    private fun output(deviceId: String, packet: KdePacket) {
        val id = packet.long("id") ?: 0
        host.update(deviceId) { d ->
            val current = d.commands.running
            val run = when {
                packet.bool("commandStarted") ->
                    KdeCommandRun(id, packet.string("command") ?: current?.command.orEmpty())
                current == null -> KdeCommandRun(id, "")
                else -> current.copy(id = id)
            }
            val lines = (run.lines + packet.strings("stdout") + packet.strings("stderr")).takeLast(MAX_OUTPUT_LINES)
            val finished = packet.bool("commandFinished")
            d.copy(
                commands = d.commands.copy(
                    running = run.copy(
                        lines = lines,
                        finished = run.finished || finished,
                        success = if (finished) packet.boolOrNull("success") ?: true else run.success,
                    ),
                ),
            )
        }
    }

    internal companion object {
        const val MAX_OUTPUT_LINES = 400

        fun parseCommands(json: String): List<KdeCommand>? =
            (runCatching { WireJson.parseToJsonElement(json) }.getOrNull() as? JsonObject)?.let(::commandsOf)

        private fun commandsOf(root: JsonObject): List<KdeCommand> =
            root.entries.mapNotNull { (key, value) ->
                val entry = value as? JsonObject ?: return@mapNotNull null
                val name = entry["name"].asStringOrNull()?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                KdeCommand(key, name, entry["command"].asStringOrNull().orEmpty())
            }.sortedBy { it.name.lowercase() }
    }
}

/**
 * The PC's audio outputs. Volumes are in the desktop's raw units against each
 * sink's own `maxVolume`, not percentages, and every request changes exactly
 * one property — the desktops send their updates the same way.
 */
class SystemVolumePlugin internal constructor(host: KdePluginHost) : KdePlugin(host) {
    override val incoming = setOf(KdeTypes.SYSTEMVOLUME)
    override val outgoing = setOf(KdeTypes.SYSTEMVOLUME_REQUEST)

    fun refresh(deviceId: String): Boolean =
        host.send(deviceId, kdePacket(KdeTypes.SYSTEMVOLUME_REQUEST) { put("requestSinks", true) })

    fun setVolume(deviceId: String, sink: String, fraction: Float): Boolean {
        val max = host.device(deviceId)?.volume?.sinks?.firstOrNull { it.name == sink }?.maxVolume ?: return false
        val volume = (fraction.coerceIn(0f, 1f) * max).toInt()
        host.update(deviceId) { it.withSink(sink) { s -> s.copy(volume = volume) } }
        return host.send(deviceId, kdePacket(KdeTypes.SYSTEMVOLUME_REQUEST) { put("name", sink); put("volume", volume) })
    }

    fun setMuted(deviceId: String, sink: String, muted: Boolean): Boolean {
        host.update(deviceId) { it.withSink(sink) { s -> s.copy(muted = muted) } }
        return host.send(deviceId, kdePacket(KdeTypes.SYSTEMVOLUME_REQUEST) { put("name", sink); put("muted", muted) })
    }

    fun makeDefault(deviceId: String, sink: String): Boolean =
        host.send(deviceId, kdePacket(KdeTypes.SYSTEMVOLUME_REQUEST) { put("name", sink); put("enabled", true) })

    override fun onConnected(deviceId: String) {
        refresh(deviceId)
    }

    override fun onDisconnected(deviceId: String) {
        host.update(deviceId) { it.copy(volume = KdeVolume()) }
    }

    override fun onPacket(deviceId: String, packet: KdePacket) {
        val list = packet.array("sinkList")
        if (list != null) {
            val sinks = list.mapNotNull { element ->
                val o = element as? JsonObject ?: return@mapNotNull null
                val p = KdePacket(KdeTypes.SYSTEMVOLUME, o)
                val name = p.string("name") ?: return@mapNotNull null
                KdeSink(
                    name = name,
                    description = p.string("description")?.takeIf { it.isNotBlank() } ?: name,
                    volume = p.int("volume") ?: 0,
                    maxVolume = (p.int("maxVolume") ?: DEFAULT_MAX).coerceAtLeast(1),
                    muted = p.bool("muted"),
                    isDefault = p.bool("enabled"),
                )
            }
            host.update(deviceId) { it.copy(volume = KdeVolume(sinks, loaded = true)) }
            return
        }
        val name = packet.string("name") ?: return
        host.update(deviceId) { d ->
            val makesDefault = packet.boolOrNull("enabled") == true
            val sinks = d.volume.sinks.map { s ->
                when {
                    s.name == name -> s.copy(
                        volume = packet.int("volume") ?: s.volume,
                        muted = packet.boolOrNull("muted") ?: s.muted,
                        isDefault = packet.boolOrNull("enabled") ?: s.isDefault,
                    )
                    makesDefault -> s.copy(isDefault = false)
                    else -> s
                }
            }
            d.copy(volume = d.volume.copy(sinks = sinks))
        }
    }

    private fun KdeDevice.withSink(name: String, change: (KdeSink) -> KdeSink): KdeDevice =
        copy(volume = volume.copy(sinks = volume.sinks.map { if (it.name == name) change(it) else it }))

    private companion object {
        /** PulseAudio's "100 %". */
        const val DEFAULT_MAX = 65536
    }
}
