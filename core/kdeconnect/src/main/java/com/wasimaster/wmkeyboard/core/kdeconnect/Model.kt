package com.wasimaster.wmkeyboard.core.kdeconnect

import java.io.InputStream
import java.io.OutputStream

/**
 * What the engine is told to be and to allow. Behaviour switches, not
 * capability switches: the capability lists in the identity packet stay the
 * same whatever these say, because changing them means tearing every link down
 * to re-announce, and a switch that reconnects the PC when flipped is a worse
 * switch. A plugin that is off simply drops what arrives and sends nothing.
 */
data class KdeEngineConfig(
    val deviceName: String = "WM Keyboard",
    val deviceType: KdeDeviceType = KdeDeviceType.PHONE,
    /** Hosts announced to directly, for networks that swallow broadcasts. */
    val hosts: Set<String> = emptySet(),
    /** Dial paired devices as they are heard; off, only devices the user picks. */
    val autoConnect: Boolean = true,
    val clipboardReceive: Boolean = true,
    val clipboardSend: Boolean = true,
    val remoteTyping: Boolean = true,
    val batteryReport: Boolean = true,
    val exposeMedia: Boolean = true,
    val receiveFiles: Boolean = true,
)

/** Everything the UI draws, in one immutable value. */
data class KdeState(
    val running: Boolean = false,
    val selfId: String = "",
    val selfName: String = "",
    val selfFingerprint: String = "",
    /** False when UDP 1716 was taken: devices must hear *us*, we cannot hear them. */
    val listening: Boolean = true,
    val devices: List<KdeDevice> = emptyList(),
) {
    fun device(id: String?): KdeDevice? = if (id == null) null else devices.firstOrNull { it.id == id }

    val paired: List<KdeDevice> get() = devices.filter { it.paired }
    val connected: List<KdeDevice> get() = devices.filter { it.paired && it.reachable }
    val nearby: List<KdeDevice> get() = devices.filter { !it.paired && it.reachable }
}

data class KdeDevice(
    val id: String,
    val name: String,
    val type: KdeDeviceType,
    val paired: Boolean = false,
    val reachable: Boolean = false,
    val pairState: KdePairState = KdePairState.NOT_PAIRED,
    /** The code to show while [pairState] is one of the two in-progress states. */
    val verificationKey: String? = null,
    /** When the pending request lapses, as an engine-clock timestamp, for the countdown. */
    val pairDeadlineMs: Long = 0,
    val pairFailure: KdePairFailure? = null,
    val address: String = "",
    val fingerprint: String = "",
    /** Packet types the device accepts: what may be sent to it. */
    val accepts: Set<String> = emptySet(),
    /** Packet types the device sends: what we may expect from it. */
    val sends: Set<String> = emptySet(),
    val disabled: Set<String> = emptySet(),
    val battery: KdeBattery? = null,
    /** The device said it can take keystrokes (`mousepad.keyboardstate`). */
    val acceptsKeys: Boolean = true,
    val mpris: KdeMpris = KdeMpris(),
    val volume: KdeVolume = KdeVolume(),
    val commands: KdeCommands = KdeCommands(),
    val lock: KdeLock = KdeLock(),
    val transfers: List<KdeTransfer> = emptyList(),
) {
    fun canSend(type: String): Boolean = paired && reachable && type in accepts

    val canMouse: Boolean get() = canSend(KdeTypes.MOUSEPAD_REQUEST)
    val canMedia: Boolean get() = canSend(KdeTypes.MPRIS_REQUEST)
    val canVolume: Boolean get() = canSend(KdeTypes.SYSTEMVOLUME_REQUEST)
    val canRun: Boolean get() = canSend(KdeTypes.RUNCOMMAND_REQUEST)
    val canPresent: Boolean get() = canSend(KdeTypes.PRESENTER)
    val canShare: Boolean get() = canSend(KdeTypes.SHARE_REQUEST)
    val canRing: Boolean get() = canSend(KdeTypes.FINDMYPHONE_REQUEST)
    val canLock: Boolean get() = canSend(KdeTypes.LOCK_REQUEST)
    val canPing: Boolean get() = canSend(KdeTypes.PING)
    val canClipboard: Boolean get() = canSend(KdeTypes.CLIPBOARD)
}

data class KdeBattery(val charge: Int, val charging: Boolean, val low: Boolean)

data class KdeMpris(
    val players: List<String> = emptyList(),
    val selected: String? = null,
    val status: Map<String, KdePlayer> = emptyMap(),
    val supportsArtTransfer: Boolean = false,
    /** Bumped when artwork bytes arrive, so observers know to ask [MprisRemotePlugin.art] again. */
    val artRevision: Int = 0,
) {
    val current: KdePlayer? get() = selected?.let(status::get)
}

data class KdePlayer(
    val name: String,
    val title: String = "",
    val artist: String = "",
    val album: String = "",
    val albumArtUrl: String = "",
    val playing: Boolean = false,
    val positionMs: Long = 0,
    /** Engine-clock time [positionMs] was true at, so the UI can extrapolate. */
    val positionAtMs: Long = 0,
    val lengthMs: Long = -1,
    val volume: Int = -1,
    val canPlay: Boolean = true,
    val canPause: Boolean = true,
    val canNext: Boolean = true,
    val canPrevious: Boolean = true,
    val canSeek: Boolean = true,
    /** Null until the player says it has one: that is how support is announced. */
    val loop: KdeLoop? = null,
    val shuffle: Boolean? = null,
)

enum class KdeLoop(val wire: String) {
    NONE("None"), TRACK("Track"), PLAYLIST("Playlist");

    companion object {
        fun fromWire(value: String?): KdeLoop? = entries.firstOrNull { it.wire.equals(value, ignoreCase = true) }
    }
}

enum class KdeMediaAction(val wire: String) {
    PLAY("Play"), PAUSE("Pause"), PLAY_PAUSE("PlayPause"), STOP("Stop"), NEXT("Next"), PREVIOUS("Previous");

    companion object {
        fun fromWire(value: String?): KdeMediaAction? = entries.firstOrNull { it.wire.equals(value, ignoreCase = true) }
    }
}

data class KdeVolume(val sinks: List<KdeSink> = emptyList(), val loaded: Boolean = false)

/** [volume] is in the PC's own units, 0..[maxVolume] — not a percentage. */
data class KdeSink(
    val name: String,
    val description: String,
    val volume: Int,
    val maxVolume: Int,
    val muted: Boolean,
    val isDefault: Boolean,
) {
    val fraction: Float get() = if (maxVolume > 0) (volume.toFloat() / maxVolume).coerceIn(0f, 1.5f) else 0f
}

data class KdeCommands(
    val list: List<KdeCommand> = emptyList(),
    val loaded: Boolean = false,
    /** The desktop can open its command editor for us (`setup`). */
    val canAdd: Boolean = false,
    val running: KdeCommandRun? = null,
)

data class KdeCommand(val key: String, val name: String, val command: String)

data class KdeCommandRun(
    val id: Long,
    val command: String,
    val lines: List<String> = emptyList(),
    val finished: Boolean = false,
    val success: Boolean = true,
)

data class KdeLock(val known: Boolean = false, val locked: Boolean = false)

enum class KdeTransferState { WAITING, RUNNING, DONE, FAILED, CANCELLED }

data class KdeTransfer(
    val id: Long,
    val fileName: String,
    val outgoing: Boolean,
    val size: Long,
    val done: Long = 0,
    val state: KdeTransferState = KdeTransferState.WAITING,
    /** Where a received file ended up, as the host app's own reference (a URI string). */
    val location: String = "",
)

/** A file offered for sending. [open] is called once, on an IO thread, when the peer fetches. */
class KdeOutgoingFile(
    val name: String,
    /** -1 when the provider does not know. */
    val size: Long,
    val lastModifiedMs: Long,
    val open: () -> InputStream,
)

/**
 * Where received files go. The engine knows nothing about storage: the host
 * opens a destination and is told how it ended.
 */
interface KdeFileSink {
    /** Null refuses the file. Called on an IO thread. */
    fun create(deviceName: String, fileName: String, size: Long): KdeIncomingFile?
}

interface KdeIncomingFile {
    val stream: OutputStream

    /** Closes [stream]. Returns the location to remember on success, or null. */
    fun finish(success: Boolean): String?
}

/** What the phone is playing, offered to the PC as one MPRIS player. */
data class KdePhoneMedia(
    val player: String,
    val title: String,
    val artist: String,
    val album: String,
    val playing: Boolean,
    val positionMs: Long,
    val lengthMs: Long,
    /** 0–100. */
    val volume: Int,
    val canNext: Boolean,
    val canPrevious: Boolean,
    val canSeek: Boolean,
)

/** Things that happen *to* the host, as opposed to state it can poll. */
sealed interface KdeEvent {
    val deviceId: String

    /** The PC typed. Exactly one of [text] and [special] is meaningful. */
    data class RemoteKey(
        override val deviceId: String,
        val text: String,
        val special: KdeSpecialKey?,
        val shift: Boolean,
        val ctrl: Boolean,
        val alt: Boolean,
    ) : KdeEvent

    data class ClipboardReceived(override val deviceId: String, val deviceName: String, val text: String) : KdeEvent
    data class TextReceived(override val deviceId: String, val deviceName: String, val text: String) : KdeEvent
    data class UrlReceived(override val deviceId: String, val deviceName: String, val url: String) : KdeEvent
    data class FileReceived(override val deviceId: String, val deviceName: String, val fileName: String, val location: String) : KdeEvent
    data class Ping(override val deviceId: String, val deviceName: String, val message: String) : KdeEvent
    data class PairRequested(override val deviceId: String, val deviceName: String, val verificationKey: String) : KdeEvent
    data class Paired(override val deviceId: String, val deviceName: String) : KdeEvent
    data class PairFailed(override val deviceId: String, val reason: KdePairFailure) : KdeEvent
    data class Unpaired(override val deviceId: String) : KdeEvent
    data class LockResult(override val deviceId: String, val success: Boolean) : KdeEvent

    /** The PC pressed a transport button on the phone's media. */
    data class PhoneMediaCommand(
        override val deviceId: String,
        val action: KdeMediaAction? = null,
        val seekToMs: Long? = null,
        val seekByMs: Long? = null,
        val volume: Int? = null,
    ) : KdeEvent
}

/**
 * The keys `specialKey` can name. The numbers are the protocol's; 3 and 17–20
 * exist in the table but no client both sends and honours them, so they are
 * left out — modifiers travel as the four booleans instead.
 */
enum class KdeSpecialKey(val code: Int) {
    BACKSPACE(1), TAB(2), LEFT(4), UP(5), RIGHT(6), DOWN(7), PAGE_UP(8), PAGE_DOWN(9),
    HOME(10), END(11), ENTER(12), DELETE(13), ESCAPE(14), SYS_RQ(15), SCROLL_LOCK(16),
    F1(21), F2(22), F3(23), F4(24), F5(25), F6(26), F7(27), F8(28), F9(29), F10(30), F11(31), F12(32);

    companion object {
        private val byCode = entries.associateBy { it.code }
        fun fromCode(code: Int?): KdeSpecialKey? = code?.let(byCode::get)
    }
}

data class KdeModifiers(
    val shift: Boolean = false,
    val ctrl: Boolean = false,
    val alt: Boolean = false,
    val meta: Boolean = false,
) {
    val any: Boolean get() = shift || ctrl || alt || meta

    companion object { val None = KdeModifiers() }
}

enum class KdeClick { LEFT, RIGHT, MIDDLE, DOUBLE }
