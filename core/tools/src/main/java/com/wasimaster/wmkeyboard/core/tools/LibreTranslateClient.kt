package com.wasimaster.wmkeyboard.core.tools

import com.wasimaster.wmkeyboard.core.netlog.NetLog
import com.wasimaster.wmkeyboard.core.netlog.NetSource
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * Translation against a LibreTranslate server the user names.
 *
 * The F-Droid build's translator, and the reason it can be one: LibreTranslate
 * is AGPL and self-hostable, so pointing the keyboard at an instance is a
 * configuration choice rather than a dependency on somebody's product. The
 * other channels keep [TranslateClient] and Google's endpoint; nothing here
 * changes for them.
 *
 * There is deliberately no default instance. Every public one either rate-limits
 * anonymous callers hard or wants a key, so a baked-in host would work for the
 * first few users of it and fail for the rest with no way to tell why.
 */
object LibreTranslateClient {

    /** Longest text we send, matching [TranslateClient.MAX_CHARS]. */
    const val MAX_CHARS = TranslateClient.MAX_CHARS

    /**
     * Blocking; call on an IO dispatcher. Throws on failure.
     *
     * [endpoint] is whatever the user typed into settings — see [translateUrl]
     * for how much latitude that allows. [apiKey] is optional: a self-hosted
     * instance usually needs none, a public one usually does.
     */
    fun translate(
        text: String,
        target: String,
        endpoint: String,
        apiKey: String = "",
        source: String = TranslateClient.AUTO,
    ): Translation {
        val trimmed = text.take(MAX_CHARS)
        val payload = buildJsonObject {
            put("q", trimmed)
            // Auto unless the user named the source in the panel; either way
            // the panel reports what came back, exactly as the Google path does.
            put("source", source.ifBlank { TranslateClient.AUTO })
            put("target", target)
            put("format", "text")
            if (apiKey.isNotBlank()) put("api_key", apiKey)
        }
        val body = ToolHttp.postJson(
            url = translateUrl(endpoint),
            body = payload.toString(),
            headers = mapOf("Accept" to "application/json"),
            source = NetSource.TRANSLATE,
            route = NetLog.pathOf(translateUrl(endpoint)),
        )
        val parsed = parse(body)
        return if (parsed.detectedSource.isBlank() && source.isNotBlank() && source != TranslateClient.AUTO) {
            parsed.copy(detectedSource = source)
        } else {
            parsed
        }
    }

    /**
     * The URL to POST to, from whatever the user pasted.
     *
     * People paste a bare host, a host with a scheme, a trailing slash, or the
     * full `/translate` path, and every one of those is a reasonable thing to
     * have copied out of a README. All four have to work, because the failure
     * they otherwise produce is a 404 that looks like the server being broken.
     *
     * A scheme other than http/https is refused rather than coerced: it means
     * the field holds something that is not an endpoint, and guessing at it
     * would send the user's text somewhere they did not name.
     */
    internal fun translateUrl(endpoint: String): String {
        val base = normalizeBase(endpoint)
        return if (base.endsWith("/translate")) base else "$base/translate"
    }

    /** [translateUrl] without the endpoint path, for callers that need the root. */
    internal fun normalizeBase(endpoint: String): String {
        val raw = endpoint.trim()
        require(raw.isNotEmpty()) { "no LibreTranslate instance configured" }
        val withScheme = when {
            raw.startsWith("http://", ignoreCase = true) -> raw
            raw.startsWith("https://", ignoreCase = true) -> raw
            // A bare host is the common paste; assume TLS rather than falling
            // back to cleartext, which would silently downgrade the request.
            !raw.contains("://") -> "https://$raw"
            else -> throw IllegalArgumentException("unsupported scheme in LibreTranslate URL")
        }
        return withScheme.trimEnd('/')
    }

    /**
     * LibreTranslate answers a single `q` with a string and a batch with an
     * array. We only ever send a string, but an instance behind a proxy that
     * normalises everything to a list is not worth a crash, so both shapes are
     * read.
     */
    internal fun parse(body: String): Translation {
        val root = Json.parseToJsonElement(body).jsonObject
        val text = when (val translated = root["translatedText"]) {
            is JsonArray -> translated.firstOrNull()?.jsonPrimitive?.content.orEmpty()
            is JsonPrimitive -> translated.content
            else -> ""
        }
        val detected = when (val language = root["detectedLanguage"]) {
            is JsonArray -> language.firstOrNull()?.jsonObject
            is JsonObject -> language
            else -> null
        }?.get("language")?.jsonPrimitive?.content.orEmpty()
        return Translation(text = text, detectedSource = detected)
    }
}
