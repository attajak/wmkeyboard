package com.wasimaster.wmkeyboard.core.selection

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/**
 * Pretty-print a minified JSON selection, or minify a pretty one.
 *
 * Only an object or an array counts: a bare number or string is text that
 * happens to parse. The parsed tree keeps key order and every number literal
 * as written (`1.0` stays `1.0`), which is what makes the round trip lossless.
 */
object JsonReformat {

    enum class Shape { NONE, MINIFIED, PRETTY }

    /** Past this a selection is a file, not a value anyone reformats on a phone. */
    const val MAX_LENGTH = 4000

    @OptIn(ExperimentalSerializationApi::class)
    private val pretty = Json {
        prettyPrint = true
        prettyPrintIndent = "  "
    }

    fun shape(text: String): Shape {
        val trimmed = text.trim()
        if (trimmed.length > MAX_LENGTH || !looksStructured(trimmed)) return Shape.NONE
        parse(trimmed) ?: return Shape.NONE
        return if (trimmed.contains('\n')) Shape.PRETTY else Shape.MINIFIED
    }

    /** The other shape of [text], or null when it is not JSON or already there. */
    fun toggle(text: String): String? {
        val trimmed = text.trim()
        if (trimmed.length > MAX_LENGTH || !looksStructured(trimmed)) return null
        val element = parse(trimmed) ?: return null
        val result = if (trimmed.contains('\n')) {
            Json.encodeToString(JsonElement.serializer(), element)
        } else {
            pretty.encodeToString(JsonElement.serializer(), element)
        }
        return result.takeIf { it != trimmed }
    }

    /** The cheap gate before any parsing: braces or brackets at both ends. */
    fun looksStructured(trimmed: String): Boolean =
        trimmed.length >= 2 &&
            ((trimmed.first() == '{' && trimmed.last() == '}') || (trimmed.first() == '[' && trimmed.last() == ']'))

    private fun parse(trimmed: String): JsonElement? {
        val element = runCatching { Json.parseToJsonElement(trimmed) }.getOrNull() ?: return null
        return element.takeIf { it is JsonObject || it is JsonArray }
    }
}
