package com.wasimaster.wmkeyboard.core.vocab

import com.wasimaster.wmkeyboard.core.endpoints.ServiceEndpoint
import com.wasimaster.wmkeyboard.core.endpoints.ServiceEndpoints
import com.wasimaster.wmkeyboard.core.netlog.NetLog
import com.wasimaster.wmkeyboard.core.netlog.NetSource
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.Locale
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject

/**
 * Wiktionary's own definition endpoint, the last fallback when neither
 * kaikki nor the free dictionary API answered. It gives definitions and
 * examples by part of speech and nothing else, as HTML snippets that are
 * stripped to text here.
 */
object WiktionaryRestClient : VocabAutofill.Source {

    private const val USER_AGENT = "WMKeyboard vocabulary (+https://github.com/wasi-master/WMKeyboard)"
    private const val MAX_SENSES_PER_POS = 3
    private val json = Json { ignoreUnknownKeys = true }

    fun url(word: String): String =
        ServiceEndpoints.base(ServiceEndpoint.WIKTIONARY) + "/api/rest_v1/page/definition/" +
            URLEncoder.encode(word.trim(), "UTF-8").replace("+", "%20") + "?redirect=true"

    override fun lookup(lemma: String, translationCodes: List<String>): VocabWord? {
        val connection = URL(url(lemma)).openConnection() as HttpURLConnection
        val netCall = NetLog.call(NetSource.VOCABULARY, "GET", url(lemma), route = "/api/rest_v1/page/definition")
        try {
            connection.connectTimeout = 8_000
            connection.readTimeout = 12_000
            connection.instanceFollowRedirects = true
            connection.setRequestProperty("User-Agent", USER_AGENT)
            connection.setRequestProperty("Accept", "application/json")
            netCall.status = connection.responseCode
            val status = connection.responseCode
            if (status == HttpURLConnection.HTTP_NOT_FOUND) return null
            if (status != HttpURLConnection.HTTP_OK) throw IOException("wiktionary HTTP $status")
            val body = netCall.countIn(connection.inputStream).bufferedReader().use { it.readText() }
            return parse(body, lemma)
        } catch (t: Throwable) {
            netCall.fail(t)
            throw t
        } finally {
            connection.disconnect()
            netCall.end()
        }
    }

    fun parse(body: String, lemma: String): VocabWord? {
        val root = runCatching { json.parseToJsonElement(body).jsonObject }.getOrNull() ?: return null
        val english = root["en"] as? JsonArray ?: return null
        val pos = ArrayList<String>()
        val senses = ArrayList<VocabSense>()
        for (element in english) {
            val block = element as? JsonObject ?: continue
            val partOfSpeech = block.str("partOfSpeech")?.lowercase(Locale.ROOT).orEmpty()
            if (partOfSpeech.isNotEmpty() && partOfSpeech !in pos) pos += partOfSpeech
            var kept = 0
            for (definitionElement in block["definitions"] as? JsonArray ?: JsonArray(emptyList())) {
                if (kept >= MAX_SENSES_PER_POS) break
                val definition = definitionElement as? JsonObject ?: continue
                val text = definition.str("definition")?.let(::stripHtml)?.takeIf { it.isNotEmpty() } ?: continue
                val example = (definition["examples"] as? JsonArray)
                    ?.firstNotNullOfOrNull { (it as? JsonPrimitive)?.content?.let(::stripHtml)?.takeIf { s -> s.isNotEmpty() } }
                senses += VocabSense(pos = partOfSpeech, definition = text, example = example)
                kept++
            }
        }
        if (senses.isEmpty()) return null
        return VocabWord(word = lemma, pos = pos, senses = senses)
    }

    private val TAGS = Regex("<[^>]+>")
    private val SPACES = Regex("\\s+")

    internal fun stripHtml(html: String): String =
        html.replace(TAGS, "")
            .replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">")
            .replace("&quot;", "\"").replace("&#39;", "'").replace("&nbsp;", " ")
            .replace(SPACES, " ")
            .trim()

    private fun JsonObject.str(key: String): String? =
        (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content?.takeIf { it.isNotBlank() }
}
