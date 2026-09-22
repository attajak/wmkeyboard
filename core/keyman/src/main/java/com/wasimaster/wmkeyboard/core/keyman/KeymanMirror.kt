package com.wasimaster.wmkeyboard.core.keyman

import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.security.MessageDigest
import java.util.zip.GZIPInputStream
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * The project's own copy of Keyman keyboards' rules, in the data repository's
 * `keyman/` folder, read only when keyman.com cannot serve them.
 *
 * keyman.com stays the source. Its version is always current and it is where
 * the rules are published; the copy exists for the day it is down, or stops
 * serving a keyboard, so a layout that already needs rules can still get them.
 *
 * Each keyboard has a folder holding the `.kmx` gzip-compressed and a
 * `meta.json` naming its upstream version and the SHA-256 of the uncompressed
 * file. The copy is only MIT keyboards, the same licence check keyman.com's
 * answers go through, and the mirror script in the data repository makes it.
 *
 * Reading lives here rather than beside the download so it runs on the JVM
 * with the rest of the format code: the network half is a URL and a byte cap.
 */
object KeymanMirror {

    /** What the mirror says about one keyboard. */
    data class Meta(val version: String, val sha256: String, val bytes: Long)

    /** A keyboard's `meta.json`, relative to the data repository's root. */
    fun metaPath(keyboardId: String): String = "$DIR/$keyboardId/meta.json"

    /** A keyboard's compressed rules, relative to the data repository's root. */
    fun rulesPath(keyboardId: String): String = "$DIR/$keyboardId/$keyboardId.$EXTENSION"

    /**
     * Reads a `meta.json`. Null when it is not one, names another keyboard, or
     * carries no version or checksum: without both there is nothing to record
     * as installed and nothing to check the file against.
     */
    fun parseMeta(text: String, keyboardId: String): Meta? {
        val root = runCatching { json.parseToJsonElement(text) }.getOrNull() as? JsonObject
            ?: return null
        fun field(name: String) = (root[name] as? JsonPrimitive)?.content?.trim().orEmpty()
        if (field("id") != keyboardId) return null
        // The mirror script only copies MIT keyboards; a file saying otherwise
        // was not written by it, and is not installed.
        if (!field("license").equals(LICENSE_MIT, ignoreCase = true)) return null
        val version = field("version").takeIf { it.isNotEmpty() && it.length <= MAX_VERSION_LENGTH }
            ?: return null
        val sha = field("sha256").lowercase().takeIf { it.length == SHA256_HEX && it.all(::isHex) }
            ?: return null
        val bytes = field("bytes").toLongOrNull()?.takeIf { it in 1L..KeymanLimits.MAX_KMX_BYTES.toLong() }
            ?: return null
        return Meta(version, sha, bytes)
    }

    /**
     * The `.kmx` inside a mirrored `.kmx.gz`, or null unless it decompresses to
     * exactly the size and SHA-256 [meta] promises.
     *
     * Decompressed length is counted as it is read and stops at the promised
     * size plus one byte, so a file built to inflate forever runs out of budget
     * rather than memory.
     */
    fun unpack(input: InputStream, meta: Meta): ByteArray? {
        val bytes = runCatching {
            GZIPInputStream(input.buffered()).use { gz ->
                val out = ByteArrayOutputStream(meta.bytes.toInt())
                val buffer = ByteArray(BUFFER)
                var total = 0L
                while (true) {
                    val read = gz.read(buffer)
                    if (read < 0) break
                    total += read
                    if (total > meta.bytes) return null
                    out.write(buffer, 0, read)
                }
                out.toByteArray()
            }
        }.getOrNull() ?: return null
        if (bytes.size.toLong() != meta.bytes) return null
        return bytes.takeIf { sha256Hex(it) == meta.sha256 }
    }

    private fun sha256Hex(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private fun isHex(c: Char): Boolean = c in '0'..'9' || c in 'a'..'f'

    private val json = Json { ignoreUnknownKeys = true }

    private const val DIR = "keyman"
    private const val EXTENSION = "kmx.gz"
    private const val LICENSE_MIT = "mit"
    private const val SHA256_HEX = 64
    private const val MAX_VERSION_LENGTH = 64
    private const val BUFFER = 8192
}
