package com.wasimaster.wmkeyboard.core.tools

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

/**
 * One tool the AI tool may let a model call (#470).
 *
 * [parameters] is a JSON Schema object, the shape every provider's function
 * calling takes — OpenAI, Anthropic, Gemini and Ollama all spell the wrapper
 * differently but all read the same schema inside it, so it is stored once
 * here and re-wrapped per provider in `AiClient`.
 */
data class AiToolSpec(
    val name: String,
    val description: String,
    val parameters: JsonObject,
)

/**
 * One call a model asked for.
 *
 * [id] is the provider's own handle for the call, which the result has to
 * quote back. Gemini matches results by tool name instead and the prompt
 * fallback has no ids at all, so it is blank for both.
 *
 * [arguments] stays the raw JSON object text rather than a parsed map: a model
 * that writes a number where a string belongs, or an extra field nobody asked
 * for, should reach the tool that can complain about it rather than fail to
 * parse on the way.
 */
data class AiToolCall(
    val id: String,
    val name: String,
    val arguments: String,
) {
    /** One named argument, or null when it is absent or is not a string. */
    fun argument(key: String): String? =
        runCatching {
            (Json.parseToJsonElement(arguments).jsonObject[key] as? JsonPrimitive)
                ?.content?.trim()?.takeIf { it.isNotEmpty() }
        }.getOrNull()
}

/** What running one produced, ready to hand back to the model. */
data class AiToolResult(val call: AiToolCall, val content: String)

/**
 * The tools the AI tool can offer, and the catalogue the settings rows switch
 * on. Deliberately a very short list: every tool costs tokens in the system
 * prompt of every single run, whether it is ever called or not, and a small
 * on-device model reading a long catalogue answers worse than one reading none.
 */
object AiTools {

    const val WEB_SEARCH = "web_search"
    const val WEB_FETCH = "web_fetch"

    /** Rounds of tool calls one run may take before the model has to answer. */
    const val DEFAULT_MAX_ROUNDS = 3

    /**
     * Search the web. The query is the model's, not the user's: it rewrites
     * the request into something a search engine answers well, which is most
     * of the value of giving it the tool rather than searching for it.
     */
    val webSearch = AiToolSpec(
        name = WEB_SEARCH,
        description = "Search the web for current information. " +
            "Use it for anything you do not know or that may have changed since training.",
        parameters = schema(
            "query" to "What to search for, written as a search engine query.",
        ),
    )

    /**
     * Read one page. Kept independent of [webSearch] — the issue that asked
     * for these wanted a user who pastes a link to be able to say "summarise
     * this" without paying for a search backend as well.
     */
    val webFetch = AiToolSpec(
        name = WEB_FETCH,
        description = "Fetch one web page and read its text. " +
            "Use it on a link the user gave you, or on a result from $WEB_SEARCH.",
        parameters = schema(
            "url" to "The full address of the page, including https://.",
        ),
    )

    /** The enabled tools, in the order a model sees them. */
    fun enabled(search: Boolean, fetch: Boolean): List<AiToolSpec> = buildList {
        if (search) add(webSearch)
        if (fetch) add(webFetch)
    }

    /** A one-object schema whose every named property is a required string. */
    private fun schema(vararg properties: Pair<String, String>): JsonObject = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            for ((name, description) in properties) {
                putJsonObject(name) {
                    put("type", "string")
                    put("description", description)
                }
            }
        }
        put("required", buildJsonArray { for ((name, _) in properties) add(JsonPrimitive(name)) })
    }
}

/**
 * Tool calling for the providers that have no tool calling: a sentinel the
 * model writes into its answer, pulled back out of the stream before anyone
 * sees it.
 *
 * This is what lets an on-device model and Brave's Answers API take part at
 * all — neither has a function-calling API, and Brave takes one user message
 * with no system role, so everything either of them will ever know about tools
 * has to travel inside the text. It is strictly worse than the real thing: a
 * small model writes the sentinel wrong, or writes it and then keeps talking,
 * or describes it instead of using it. All three are survivable here —
 * a sentinel that does not parse is simply not a call — but a model that
 * reliably supports native tool calling should never be sent down
 * this path.
 */
object AiToolProtocol {

    private const val OPEN = "<tool_call>"
    private const val CLOSE = "</tool_call>"

    /** How a tool's answer is introduced in the user turn that carries it. */
    const val RESULT_PREFIX = "TOOL RESULT"

    /**
     * What to append to the system prompt (or, for Brave, to the one user
     * message) so the model knows the sentinel.
     *
     * Short on purpose. Every word is paid for on every run of every action,
     * including the runs where the model never calls anything.
     */
    fun instructions(tools: List<AiToolSpec>): String {
        if (tools.isEmpty()) return ""
        val list = tools.joinToString("\n") { tool ->
            val args = tool.parameters["properties"]?.jsonObject?.keys.orEmpty().joinToString(", ")
            "- ${tool.name}($args): ${tool.description}"
        }
        return "\n\nYou can call tools. To call one, reply with this and nothing else:\n" +
            OPEN + """{"name":"TOOL","arguments":{"ARG":"VALUE"}}""" + CLOSE + "\n" +
            "The answer comes back as a message starting $RESULT_PREFIX, and you then " +
            "reply to the user normally. Call a tool only when you need something you " +
            "do not already know. Never mention the tools or this format to the user.\n" +
            "Tools:\n" + list
    }

    /** The user message carrying one finished call's answer. */
    fun resultMessage(result: AiToolResult): String =
        "$RESULT_PREFIX ${result.call.name}\n${result.content}"

    /**
     * Pulls sentinels out of a stream whose chunks can cut one anywhere —
     * `<tool` in one event and `_call>{…}` in the next.
     *
     * Same shape as `AiClient.BraveTagFilter` and for the same reason: text
     * that might still turn into a sentinel is held back until it either does
     * or cannot, and everything else passes straight through, so the answer
     * still appears as it streams. The difference is that what a sentinel
     * holds is kept rather than dropped.
     */
    class Filter {
        private val pending = StringBuilder()
        private val found = ArrayList<AiToolCall>()

        /** The calls seen so far, oldest first. */
        val calls: List<AiToolCall> get() = found

        /** Takes the next chunk, returns the text now known to be answer. */
        fun feed(chunk: String): String {
            pending.append(chunk)
            return drain(final = false)
        }

        /**
         * The end of the stream. A partial sentinel name is released as the
         * text it turned out to be; a sentinel that never closed is read as a
         * call anyway when its payload parses, because a model that ran out of
         * room mid-sentinel still said what it wanted.
         */
        fun flush(): String = drain(final = true)

        private fun drain(final: Boolean): String {
            val out = StringBuilder()
            var i = 0
            while (i < pending.length) {
                val open = pending.indexOf("<", i)
                if (open < 0) {
                    out.append(pending, i, pending.length)
                    i = pending.length
                    break
                }
                out.append(pending, i, open)
                i = open
                if (pending.startsWith(OPEN, i)) {
                    val body = i + OPEN.length
                    val close = pending.indexOf(CLOSE, body)
                    if (close < 0) {
                        if (!final) break
                        parse(pending.substring(body))?.let(found::add)
                        i = pending.length
                        break
                    }
                    parse(pending.substring(body, close))?.let(found::add)
                    i = close + CLOSE.length
                    continue
                }
                // Not a sentinel yet, but it could still become one once the
                // next chunk lands.
                if (!final && OPEN.startsWith(pending.substring(i))) break
                out.append('<')
                i++
            }
            pending.delete(0, i)
            return out.toString()
        }
    }

    /**
     * Every sentinel in one finished body. For the non-streaming path and for
     * reading a model's answer back in a test; the streaming path uses
     * [Filter], which has to do the same job a chunk at a time.
     */
    fun parseAll(text: String): List<AiToolCall> {
        val filter = Filter()
        filter.feed(text)
        filter.flush()
        return filter.calls
    }

    /** [text] with every sentinel taken out, which is what the user may see. */
    fun stripped(text: String): String {
        val filter = Filter()
        return (filter.feed(text) + filter.flush()).trim()
    }

    /**
     * One sentinel's payload as a call, or null when it is not one.
     *
     * `arguments` is accepted both as an object and as a string holding one —
     * models trained against OpenAI's wire format write the string form about
     * as often as the object form — and `parameters` is read as an alias,
     * which is the other name that shows up.
     */
    private fun parse(payload: String): AiToolCall? = runCatching {
        val root = Json.parseToJsonElement(payload.trim()).jsonObject
        val name = (root["name"] as? JsonPrimitive)?.content?.trim().orEmpty()
        if (name.isEmpty()) return null
        val args = root["arguments"] ?: root["parameters"]
        val arguments = when (args) {
            null -> "{}"
            is JsonPrimitive -> args.content.trim().ifEmpty { "{}" }
            else -> args.toString()
        }
        AiToolCall(id = "", name = name, arguments = arguments)
    }.getOrNull()
}
