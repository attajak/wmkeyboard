package com.wasimaster.wmkeyboard.core.theme

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.net.URLEncoder

/**
 * The Rboard community's theme repository, as a list of packs.
 *
 * Rboard publishes its index as `list.json` at the root of the
 * `GboardThemes/PackRepoBeta` repository on GitHub: an array of packs, each with
 * a `url` relative to the repository root, the SHA-256 of the file, its size,
 * its author and the names of the themes inside. The Rboard Theme Manager app
 * reads the same file. It has not changed shape since 2021, but it is somebody
 * else's file, so [parse] reads only the fields it needs, skips an entry it
 * cannot use rather than failing the list, and answers null for a document that
 * is not that shape at all — the screen then says the list could not be read.
 */
object RboardCatalog {

    /** Where pack paths in the index are relative to. */
    const val REPOSITORY = "https://raw.githubusercontent.com/GboardThemes/PackRepoBeta/main/"

    const val INDEX_URL = REPOSITORY + "list.json"

    /** The index is 23 KB today; this is room for it to grow tenfold and then some. */
    const val MAX_INDEX_BYTES = 2 * 1024 * 1024

    /** The biggest pack is 8 MB. Past this a pack is refused before it is read. */
    const val MAX_PACK_BYTES = 32L * 1024 * 1024

    /** Fewer than 50 packs today. */
    private const val MAX_PACKS = 500

    /** The list, or null when [text] is not Rboard's index. */
    fun parse(text: String): List<RboardPack>? {
        if (text.length > MAX_INDEX_BYTES) return null
        val root = runCatching { json.parseToJsonElement(text) }.getOrNull() as? JsonArray ?: return null
        val packs = root.asSequence()
            .filterIsInstance<JsonObject>()
            .mapNotNull(::pack)
            .take(MAX_PACKS)
            .toList()
        // An array of something else entirely is not an index with no packs.
        return packs.takeIf { it.isNotEmpty() || root.isEmpty() }
    }

    private fun pack(entry: JsonObject): RboardPack? {
        val path = entry.string("url") ?: return null
        val url = absoluteUrl(path) ?: return null
        val hash = entry.string("hash")?.lowercase()?.takeIf { it.length == SHA256_HEX && it.all { c -> c in HEX } }
        val themes = (entry["themes"] as? JsonArray)
            ?.mapNotNull { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content }
            .orEmpty()
        val tags = (entry["tags"] as? JsonArray)
            ?.mapNotNull { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content?.trim() }
            ?.filter { it.isNotEmpty() }
            .orEmpty()
        val name = entry.string("name")?.trim()?.takeIf { it.isNotEmpty() }
            ?: path.substringAfterLast('/').substringBeforeLast('.').replace('_', ' ')
        return RboardPack(
            name = name,
            author = entry.string("author")?.trim().orEmpty(),
            url = url,
            sha256 = hash,
            sizeBytes = (entry["size"] as? JsonPrimitive)?.content?.toLongOrNull()?.takeIf { it > 0 },
            themes = themes,
            tags = tags,
            description = entry.string("description")?.trim().orEmpty(),
        )
    }

    /**
     * An index path as a URL to download, or null when it cannot be one.
     *
     * Relative paths resolve against [REPOSITORY], with each segment
     * percent-encoded: pack files are named like `Animal Black.zip`, and a raw
     * space in a request path is refused. An absolute link must be https; the
     * pack is checked against its SHA-256 anyway, but a plain-http link would
     * still say who is downloading what to anyone on the network.
     */
    fun absoluteUrl(path: String): String? {
        val trimmed = path.trim()
        if (trimmed.isEmpty()) return null
        if (trimmed.startsWith("https://")) return trimmed.takeIf { ' ' !in it }
        if ("://" in trimmed || trimmed.startsWith("/")) return null
        val segments = trimmed.split('/')
        if (segments.any { it.isEmpty() || it == "." || it == ".." }) return null
        return REPOSITORY + segments.joinToString("/") { segment ->
            URLEncoder.encode(segment, "UTF-8").replace("+", "%20")
        }
    }

    private fun JsonObject.string(key: String): String? =
        (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content

    private val json = Json { isLenient = true; ignoreUnknownKeys = true }
    private const val SHA256_HEX = 64
    private const val HEX = "0123456789abcdef"
}

/** One pack in Rboard's index. */
data class RboardPack(
    val name: String,
    val author: String,
    /** Ready to download. */
    val url: String,
    /** Lowercase hex, or null when the index gives none that is well formed. */
    val sha256: String?,
    val sizeBytes: Long?,
    /** The theme names the index lists. The pack itself is the authority. */
    val themes: List<String>,
    val tags: List<String>,
    val description: String,
)
