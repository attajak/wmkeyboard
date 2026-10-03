package com.wasimaster.wmkeyboard.core.tools

import android.util.Base64
import com.wasimaster.wmkeyboard.core.netlog.NetSource
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * The scan text tool's online engine (issue #469): the photo goes to a model
 * that reads images, through the OpenAI-compatible chat API, and the model
 * writes out the text it sees.
 *
 * One shape covers nearly every service, because nearly every service speaks
 * it: OpenAI, OpenRouter, Gemini's compatible endpoint, Mistral, a local
 * Ollama or LM Studio. The user names the address, the model and the key.
 * Nothing here runs until they do, and no language data is needed: the model
 * reads whatever script the picture holds.
 */
object VisionOcrClient {

    private const val PATH = "/chat/completions"

    /**
     * What the model is asked. Plain about the output, since the panel splits
     * the answer into words to pick from, and anything the model adds around
     * the text ("Here is the text:") would be picked with it.
     */
    internal const val PROMPT =
        "Transcribe all of the text in this image exactly as written, keeping its line breaks. " +
            "Output only the text, with no commentary, quotation marks or formatting. " +
            "If the image has no text, output nothing."

    /** Blocking; call on an IO dispatcher. Throws on failure. */
    fun read(baseUrl: String, model: String, apiKey: String, jpeg: ByteArray): String {
        val url = endpoint(baseUrl)
        val body = requestBody(model, Base64.encodeToString(jpeg, Base64.NO_WRAP))
        val headers = buildMap {
            put("Accept", "application/json")
            if (apiKey.isNotBlank()) put("Authorization", "Bearer ${apiKey.trim()}")
        }
        val response = ToolHttp.postJson(
            url = url,
            body = body,
            timeoutMs = 90_000,
            headers = headers,
            source = NetSource.OCR_ONLINE,
            route = PATH,
        )
        return parse(response)
    }

    /**
     * The chat endpoint from what the user pasted: a base ending in `/v1`, or
     * the full `/chat/completions` address, with or without a trailing slash.
     */
    internal fun endpoint(baseUrl: String): String {
        val trimmed = baseUrl.trim().trimEnd('/')
        require(trimmed.isNotEmpty()) { "no address for the online text reader" }
        val withScheme = if ("://" in trimmed) trimmed else "https://$trimmed"
        return if (withScheme.endsWith(PATH)) withScheme else withScheme + PATH
    }

    internal fun requestBody(model: String, jpegBase64: String): String = buildJsonObject {
        put("model", model.trim())
        put("temperature", 0)
        putJsonArray("messages") {
            addJsonObject {
                put("role", "user")
                putJsonArray("content") {
                    addJsonObject {
                        put("type", "text")
                        put("text", PROMPT)
                    }
                    addJsonObject {
                        put("type", "image_url")
                        putJsonObject("image_url") {
                            put("url", "data:image/jpeg;base64,$jpegBase64")
                        }
                    }
                }
            }
        }
    }.toString()

    /**
     * The model's text: `choices[0].message.content`, a string, or a list of
     * parts on the services that answer that way. A wrapping code fence some
     * models add anyway is taken off.
     */
    internal fun parse(body: String): String {
        val message = Json.parseToJsonElement(body).jsonObject["choices"]?.jsonArray
            ?.firstOrNull()?.jsonObject?.get("message") as? JsonObject
            ?: throw IllegalStateException("the text reader sent no answer")
        val text = when (val content = message["content"]) {
            is JsonPrimitive -> content.takeIf { it.isString }?.content.orEmpty()
            is JsonArray -> content.joinToString("") { part ->
                ((part as? JsonObject)?.get("text") as? JsonPrimitive)?.content.orEmpty()
            }
            else -> ""
        }
        return FENCE.matchEntire(text.trim())?.groupValues?.get(1)?.trim() ?: text.trim()
    }

    /** A whole answer wrapped in a Markdown code fence, with or without a language. */
    private val FENCE = Regex("^```[A-Za-z]*\\n?([\\s\\S]*?)\\n?```$")
}
