package com.wasimaster.wmkeyboard.core.kdeconnect

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

/**
 * One KDE Connect packet: a line of JSON on the wire.
 *
 * ```
 * {"id":1758500000000,"type":"kdeconnect.ping","body":{"message":"hi"}}\n
 * ```
 *
 * A packet that announces a payload also carries `payloadSize` and
 * `payloadTransferInfo.port`; the bytes themselves travel on a second
 * connection to that port (see [PayloadTransfer]). [payloadSize] is 0 for "no
 * payload" and -1 for a stream whose length the sender does not know.
 *
 * Reading is deliberately forgiving and writing deliberately strict. The
 * clients in the wild disagree on small things — `id` has been seen as a
 * string, kdeconnect-android sends `targetProtocolVersion` as `"8"`, its
 * telephony plugin sends `isCancel` as `"true"` — so every accessor here takes
 * a number or boolean in either spelling, while [serialize] only ever writes
 * the canonical one.
 */
data class KdePacket(
    val type: String,
    val body: JsonObject = EMPTY_BODY,
    val payloadSize: Long = 0,
    val payloadPort: Int = 0,
) {
    val hasPayload: Boolean get() = payloadSize != 0L

    fun has(key: String): Boolean = body.containsKey(key)

    fun string(key: String): String? =
        (body[key] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.content

    fun long(key: String): Long? {
        val p = body[key] as? JsonPrimitive ?: return null
        if (p is JsonNull) return null
        return p.longOrNull ?: p.doubleOrNull?.toLong() ?: p.content.trim().toLongOrNull()
    }

    fun int(key: String): Int? = long(key)?.coerceIn(Int.MIN_VALUE.toLong(), Int.MAX_VALUE.toLong())?.toInt()

    fun double(key: String): Double? {
        val p = body[key] as? JsonPrimitive ?: return null
        if (p is JsonNull) return null
        return p.doubleOrNull ?: p.content.trim().toDoubleOrNull()
    }

    /** True only for a real `true` or the string `"true"`; absent and null are false. */
    fun bool(key: String): Boolean {
        val p = body[key] as? JsonPrimitive ?: return false
        if (p is JsonNull) return false
        return p.booleanOrNull ?: p.content.equals("true", ignoreCase = true)
    }

    /** [bool], but null when the key is absent — for fields whose absence means "unchanged". */
    fun boolOrNull(key: String): Boolean? = if (has(key) && body[key] !is JsonNull) bool(key) else null

    fun strings(key: String): List<String> =
        (body[key] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.takeIf { p -> p !is JsonNull }?.content }.orEmpty()

    fun obj(key: String): JsonObject? = body[key] as? JsonObject

    fun array(key: String): JsonArray? = body[key] as? JsonArray

    /** The line that goes on the wire, newline included. */
    fun serialize(nowMs: Long = System.currentTimeMillis()): String {
        val root = buildJsonObject {
            put("id", nowMs)
            put("type", type)
            put("body", body)
            if (hasPayload) {
                put("payloadSize", payloadSize)
                putJsonObject("payloadTransferInfo") { put("port", payloadPort) }
            }
        }
        return WireJson.encodeToString(JsonObject.serializer(), root) + "\n"
    }

    companion object {
        val EMPTY_BODY = JsonObject(emptyMap())

        /**
         * Parses one line, or null when it is not a packet. Never throws: a
         * malformed line from the network is an event, not an exception.
         */
        fun parse(line: String): KdePacket? {
            val text = line.trim()
            if (text.isEmpty()) return null
            val root = runCatching { WireJson.parseToJsonElement(text) }.getOrNull() as? JsonObject ?: return null
            val type = (root["type"] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: return null
            val body = root["body"] as? JsonObject ?: EMPTY_BODY
            val sizeElement = root["payloadSize"] as? JsonPrimitive
            val size = sizeElement?.let { it.longOrNull ?: it.content.toLongOrNull() } ?: 0L
            val port = if (size != 0L) {
                val info = root["payloadTransferInfo"] as? JsonObject
                (info?.get("port") as? JsonPrimitive)?.let { it.longOrNull ?: it.content.toLongOrNull() }?.toInt() ?: 0
            } else {
                0
            }
            return KdePacket(type, body, size, port)
        }
    }
}

/** A packet built in place: `kdePacket(KdeTypes.PING) { put("message", text) }`. */
inline fun kdePacket(type: String, build: JsonObjectBuilder.() -> Unit = {}): KdePacket =
    KdePacket(type, buildJsonObject(build))

/**
 * The one Json the wire uses. Compact, and lenient on the way in. kotlinx does
 * not escape `/`, which matters: Qt's writer does not either, and the Android
 * client goes out of its way to match it.
 */
internal val WireJson = Json {
    ignoreUnknownKeys = true
    isLenient = true
    encodeDefaults = true
}

internal fun JsonElement?.asStringOrNull(): String? =
    (this as? JsonPrimitive)?.takeIf { it !is JsonNull }?.content

/** Every packet type this engine sends or understands. */
object KdeTypes {
    const val IDENTITY = "kdeconnect.identity"
    const val PAIR = "kdeconnect.pair"

    const val PING = "kdeconnect.ping"
    const val BATTERY = "kdeconnect.battery"
    const val CLIPBOARD = "kdeconnect.clipboard"
    const val CLIPBOARD_CONNECT = "kdeconnect.clipboard.connect"
    const val MOUSEPAD_REQUEST = "kdeconnect.mousepad.request"
    const val MOUSEPAD_ECHO = "kdeconnect.mousepad.echo"
    const val MOUSEPAD_KEYBOARDSTATE = "kdeconnect.mousepad.keyboardstate"
    const val MPRIS = "kdeconnect.mpris"
    const val MPRIS_REQUEST = "kdeconnect.mpris.request"
    const val SYSTEMVOLUME = "kdeconnect.systemvolume"
    const val SYSTEMVOLUME_REQUEST = "kdeconnect.systemvolume.request"
    const val RUNCOMMAND = "kdeconnect.runcommand"
    const val RUNCOMMAND_REQUEST = "kdeconnect.runcommand.request"
    const val RUNCOMMAND_OUTPUT = "kdeconnect.runcommand.output"
    const val PRESENTER = "kdeconnect.presenter"
    const val SHARE_REQUEST = "kdeconnect.share.request"
    const val SHARE_REQUEST_UPDATE = "kdeconnect.share.request.update"
    const val FINDMYPHONE_REQUEST = "kdeconnect.findmyphone.request"
    const val LOCK = "kdeconnect.lock"
    const val LOCK_REQUEST = "kdeconnect.lock.request"

    /** The two types every peer accepts whatever its capability lists say. */
    val PROTOCOL = setOf(IDENTITY, PAIR)
}
