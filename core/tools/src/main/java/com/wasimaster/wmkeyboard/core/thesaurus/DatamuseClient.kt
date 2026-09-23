package com.wasimaster.wmkeyboard.core.thesaurus

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

/** One word of a Datamuse answer, with the tags `md=p` asks for (`syn`, `n`, `adj`…). */
data class DatamuseWord(val word: String, val tags: List<String> = emptyList())

/**
 * The Datamuse API (datamuse.com/api): free, no key, no account. Asked with
 * `ml=` ("means like") rather than `rel_syn=`, because the first ranks the
 * same synonyms by how well they stand in for the word ("happy" leads with
 * pleased, glad, content where `rel_syn` leads with halcyon) and tags each
 * one `syn`, so one request serves both [SynonymSource.DATAMUSE] and
 * [SynonymSource.SIMILAR_WORDS].
 *
 * The last answer is kept, so the second of those two sources, asked right
 * after the first came up empty, costs nothing.
 */
object DatamuseClient {

    const val TAG_SYNONYM = "syn"
    private const val MAX_RESULTS = 100
    private const val MAX_CHARS = 256 * 1024

    /** Datamuse's part-of-speech tags, as the rest of the keyboard names them. */
    val POS = mapOf("n" to "noun", "v" to "verb", "adj" to "adjective", "adv" to "adverb")

    private val json = Json { ignoreUnknownKeys = true }

    @Volatile
    private var last: Pair<String, List<DatamuseWord>>? = null

    fun url(word: String): String =
        ServiceEndpoints.base(ServiceEndpoint.DATAMUSE) + "/words?ml=" +
            URLEncoder.encode(word.trim().lowercase(Locale.ROOT), "UTF-8") + "&md=p&max=$MAX_RESULTS"

    /** Blocking; call on an IO dispatcher. Empty when Datamuse knows nothing like the word; throws when it could not be asked. */
    fun meansLike(word: String, netSource: NetSource = NetSource.SYNONYMS): List<DatamuseWord> {
        val url = url(word)
        last?.let { (cachedUrl, words) -> if (cachedUrl == url) return words }
        val connection = URL(url).openConnection() as HttpURLConnection
        val netCall = NetLog.call(netSource, "GET", url, route = "/words")
        try {
            connection.connectTimeout = 8_000
            connection.readTimeout = 8_000
            netCall.status = connection.responseCode
            val status = connection.responseCode
            if (status != HttpURLConnection.HTTP_OK) throw IOException("datamuse HTTP $status")
            val body = netCall.countIn(connection.inputStream).bufferedReader().use { reader ->
                val text = StringBuilder()
                val buffer = CharArray(16 * 1024)
                while (true) {
                    val read = reader.read(buffer)
                    if (read < 0) break
                    text.appendRange(buffer, 0, read)
                    if (text.length > MAX_CHARS) throw IOException("datamuse answer too large")
                }
                text.toString()
            }
            return parse(body).also { last = url to it }
        } catch (t: Throwable) {
            netCall.fail(t)
            throw t
        } finally {
            connection.disconnect()
            netCall.end()
        }
    }

    /** `[{"word": "glad", "score": 1, "tags": ["syn", "adj"]}, …]`; anything else reads as no words. */
    fun parse(body: String): List<DatamuseWord> {
        val root = runCatching { json.parseToJsonElement(body) }.getOrNull() as? JsonArray ?: return emptyList()
        return root.mapNotNull { element ->
            val item = element as? JsonObject ?: return@mapNotNull null
            val word = (item["word"] as? JsonPrimitive)?.takeIf { it.isString }?.content?.trim()
                ?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
            val tags = (item["tags"] as? JsonArray).orEmpty()
                .mapNotNull { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content }
            DatamuseWord(word, tags)
        }
    }
}
