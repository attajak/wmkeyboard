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
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject

/**
 * One word's Wiktionary entry from kaikki.org, the same per-word JSONL the
 * pack builder reads, folded into a [VocabWord] the same way. It is the
 * first place the list editor asks online: the record it gives matches the
 * packs' (senses with quotations, both accents, the family, the etymology,
 * translations), where the free dictionary API gives a few definitions.
 *
 * kaikki is a static site, one file per word; a word it does not have is a
 * plain 404, which [lookup] reports as null.
 */
object KaikkiClient : VocabAutofill.Source {

    private const val USER_AGENT = "WMKeyboard vocabulary (+https://github.com/wasi-master/WMKeyboard)"
    private const val MAX_SENSES_PER_POS = 3
    private const val MAX_RELATED = 12
    private const val MAX_QUOTATIONS = 2
    private const val MAX_TRANSLATIONS_PER_LANGUAGE = 3
    private const val MAX_BYTES = 4L * 1024 * 1024

    private val json = Json { ignoreUnknownKeys = true }

    private val POS_MAP = mapOf(
        "adj" to "adjective", "adv" to "adverb", "intj" to "interjection", "prep" to "preposition",
        "conj" to "conjunction", "det" to "determiner", "pron" to "pronoun", "num" to "numeral",
        "prep_phrase" to "phrase", "adv_phrase" to "phrase",
    )
    private val POS_DROP = setOf("name", "prefix", "suffix", "character", "symbol", "proverb", "infix", "affix")
    private val FORM_SKIP_TAGS = setOf("table-tags", "inflection-template", "class", "canonical", "romanization")
    private val KEPT_TAGS = setOf(
        "transitive", "intransitive", "formal", "informal", "figurative", "archaic", "dated",
        "rare", "slang", "colloquial", "literary", "obsolete", "countable", "uncountable", "reflexive",
    )

    fun url(word: String): String {
        val w = word.lowercase(Locale.ROOT)
        fun q(s: String) = URLEncoder.encode(s, "UTF-8").replace("+", "%20")
        return ServiceEndpoints.base(ServiceEndpoint.KAIKKI) +
            "/dictionary/English/meaning/${q(w.take(1))}/${q(w.take(2))}/${q(w)}.jsonl"
    }

    /** Blocking; call on an IO dispatcher. Null when kaikki has no page for the word; throws when it could not be asked. */
    override fun lookup(lemma: String, translationCodes: List<String>): VocabWord? {
        val connection = URL(url(lemma)).openConnection() as HttpURLConnection
        val netCall = NetLog.call(NetSource.VOCABULARY, "GET", url(lemma), route = "/dictionary")
        try {
            connection.connectTimeout = 8_000
            connection.readTimeout = 12_000
            connection.instanceFollowRedirects = true
            connection.setRequestProperty("User-Agent", USER_AGENT)
            netCall.status = connection.responseCode
            val status = connection.responseCode
            if (status == HttpURLConnection.HTTP_NOT_FOUND) return null
            if (status != HttpURLConnection.HTTP_OK) throw IOException("kaikki HTTP $status")
            val body = netCall.countIn(connection.inputStream).bufferedReader().use { reader ->
                val text = StringBuilder()
                val buffer = CharArray(16 * 1024)
                while (true) {
                    val read = reader.read(buffer)
                    if (read < 0) break
                    text.appendRange(buffer, 0, read)
                    if (text.length > MAX_BYTES) throw IOException("kaikki entry too large")
                }
                text.toString()
            }
            return parse(body, lemma, translationCodes)
        } catch (t: Throwable) {
            netCall.fail(t)
            throw t
        } finally {
            connection.disconnect()
            netCall.end()
        }
    }

    /** Everything the entries of one page add up to, before it becomes a [VocabWord]. */
    private class Draft(val lemma: String, val translationCodes: List<String>) {
        val pos = ArrayList<String>()
        val senses = ArrayList<VocabSense>()
        val synonyms = LinkedHashSet<String>()
        val antonyms = LinkedHashSet<String>()
        val derived = LinkedHashSet<String>()
        val related = LinkedHashSet<String>()
        val hypernyms = LinkedHashSet<String>()
        val hyponyms = LinkedHashSet<String>()
        val forms = LinkedHashSet<String>()
        val ipa = HashMap<String, String>()
        val audio = HashMap<String, String>()
        var rhymes: String? = null
        var etymology: String? = null
        var hyphenation: List<String> = emptyList()
        var wikipedia: String? = null
        val translations = LinkedHashMap<String, Pair<ArrayList<String>, ArrayList<String>>>()

        fun toWord(): VocabWord? {
            if (senses.isEmpty()) return null
            fun trim(set: Set<String>) = set.filter { it.isNotEmpty() && it != lemma }.take(MAX_RELATED)
            return VocabWord(
                word = lemma,
                pos = pos,
                ipa = ipa,
                audio = audio,
                senses = senses,
                synonyms = trim(synonyms),
                antonyms = trim(antonyms),
                family = if (derived.isEmpty() && related.isEmpty()) null else VocabFamily(trim(derived), trim(related)),
                hypernyms = trim(hypernyms),
                hyponyms = trim(hyponyms),
                forms = forms.toList(),
                hyphenation = hyphenation,
                rhymes = rhymes,
                etymology = etymology,
                attested = etymology?.let { ATTESTED.find(it)?.groupValues?.get(1) },
                wikipedia = wikipedia,
                translations = translations.mapValues { (_, slot) ->
                    VocabTranslation(w = slot.first, r = if (slot.second.any { it.isNotEmpty() }) slot.second else emptyList())
                },
            )
        }
    }

    /** The JSONL page folded into one record, or null when it holds no usable English entry. */
    fun parse(body: String, lemma: String, translationCodes: List<String> = emptyList()): VocabWord? {
        val entries = body.lineSequence()
            .map { it.trim() }
            .filter { it.startsWith("{") }
            .mapNotNull { runCatching { json.parseToJsonElement(it).jsonObject }.getOrNull() }
            .filter { (it.str("lang_code") ?: "en") == "en" }
            .toList()
        if (entries.isEmpty()) return null
        val draft = Draft(lemma, translationCodes)
        for (entry in entries) addEntry(draft, entry)
        return draft.toWord()
    }

    private fun addEntry(draft: Draft, entry: JsonObject) {
        val rawPos = entry.str("pos")?.lowercase(Locale.ROOT) ?: return
        if (rawPos in POS_DROP) return
        val partOfSpeech = POS_MAP[rawPos] ?: rawPos
        if (partOfSpeech !in draft.pos) draft.pos += partOfSpeech
        addSenses(draft, entry, partOfSpeech)
        draft.synonyms += entry.arr("synonyms").words()
        draft.antonyms += entry.arr("antonyms").words()
        draft.derived += entry.arr("derived").words()
        draft.related += entry.arr("related").words()
        draft.hypernyms += entry.arr("hypernyms").words()
        draft.hyponyms += entry.arr("hyponyms").words()
        addForms(draft, entry)
        addSounds(draft, entry)
        if (draft.etymology == null) draft.etymology = entry.str("etymology_text")?.trim()?.takeIf { it.isNotEmpty() }
        if (draft.hyphenation.isEmpty()) {
            draft.hyphenation = entry.strings("hyphenation").ifEmpty {
                entry.arr("hyphenations").firstNotNullOfOrNull { (it as? JsonObject)?.strings("parts") }.orEmpty()
            }
        }
        if (draft.wikipedia == null) draft.wikipedia = entry.strings("wikipedia").firstOrNull()
        if (draft.translationCodes.isNotEmpty()) addTranslations(draft, entry)
    }

    private fun addSenses(draft: Draft, entry: JsonObject, partOfSpeech: String) {
        var kept = 0
        for (senseElement in entry.arr("senses")) {
            if (kept >= MAX_SENSES_PER_POS) break
            val sense = senseOf(senseElement as? JsonObject ?: continue, partOfSpeech) ?: continue
            draft.synonyms += sense.synonyms
            draft.antonyms += sense.antonyms
            draft.senses += sense
            kept++
        }
    }

    private fun senseOf(sense: JsonObject, partOfSpeech: String): VocabSense? {
        val definition = sense.strings("glosses").lastOrNull()?.trim().orEmpty()
        if (definition.isEmpty()) return null
        val examples = sense.arr("examples").mapNotNull { it as? JsonObject }
        val example = examples.firstOrNull { it.str("type") != "quotation" && it.str("ref") == null }?.str("text")
        val quotations = examples
            .mapNotNull { quote ->
                val text = quote.str("text") ?: return@mapNotNull null
                val ref = quote.str("ref") ?: return@mapNotNull null
                VocabQuotation(text = text, ref = ref.trimEnd(':'))
            }
            .take(MAX_QUOTATIONS)
        return VocabSense(
            pos = partOfSpeech,
            definition = definition,
            example = example?.trim()?.takeIf { it.isNotEmpty() },
            quotations = quotations,
            synonyms = sense.arr("synonyms").words(),
            antonyms = sense.arr("antonyms").words(),
            tags = sense.strings("tags").filter { it in KEPT_TAGS },
            topics = sense.strings("topics").take(3),
        )
    }

    private fun addForms(draft: Draft, entry: JsonObject) {
        for (formElement in entry.arr("forms")) {
            val form = formElement as? JsonObject ?: continue
            if (form.strings("tags").any { it in FORM_SKIP_TAGS }) continue
            val text = form.str("form")?.trim() ?: continue
            if (isPlainForm(text, draft.lemma)) draft.forms += text
        }
    }

    private fun isPlainForm(text: String, lemma: String): Boolean {
        if (text.isEmpty() || text == lemma) return false
        return text.all { it.isLetter() || it == ' ' || it == '-' || it == '\'' }
    }

    private fun addSounds(draft: Draft, entry: JsonObject) {
        for (soundElement in entry.arr("sounds")) {
            val sound = soundElement as? JsonObject ?: continue
            val accent = accentOf(sound.strings("tags"))
            sound.str("ipa")?.let { value -> putAccented(draft.ipa, accent, value) }
            sound.str("mp3_url")?.let { value -> putAccented(draft.audio, accent, value) }
            if (draft.rhymes == null) draft.rhymes = sound.str("rhymes")
        }
    }

    /** Keyed by accent when the sound names one; an unlabelled sound fills the map only while it is empty. */
    private fun putAccented(map: HashMap<String, String>, accent: String?, value: String) {
        if (accent != null) {
            map.putIfAbsent(accent, value)
        } else if (map.isEmpty()) {
            map[VocabAccent.US.key] = value
        }
    }

    private fun accentOf(tags: List<String>): String? = when {
        tags.any { it in US_TAGS } -> VocabAccent.US.key
        tags.any { it in UK_TAGS } -> VocabAccent.UK.key
        else -> null
    }

    private fun addTranslations(draft: Draft, entry: JsonObject) {
        entry.arr("translations").forEach { element -> (element as? JsonObject)?.let { addTranslation(draft, it) } }
    }

    private fun addTranslation(draft: Draft, tr: JsonObject) {
        val code = tr.str("code") ?: tr.str("lang_code") ?: return
        if (code !in draft.translationCodes) return
        val word = tr.str("word")?.trim()?.takeIf { it.isNotEmpty() } ?: return
        val slot = draft.translations.getOrPut(code) { ArrayList<String>() to ArrayList() }
        if (word in slot.first || slot.first.size >= MAX_TRANSLATIONS_PER_LANGUAGE) return
        slot.first += word
        slot.second += tr.str("roman")?.trim().orEmpty()
    }

    private val US_TAGS = setOf("US", "General-American", "GA")
    private val UK_TAGS = setOf("UK", "Received-Pronunciation", "RP")

    private val ATTESTED = Regex("""[Ff]irst attested (?:in|around|from)\s+(?:the\s+)?(\d{4})""")

    private fun JsonObject.str(key: String): String? =
        (this[key] as? JsonPrimitive)?.takeIf { it !is JsonNull && it.isString }?.content?.takeIf { it.isNotBlank() }

    private fun JsonObject.arr(key: String): JsonArray =
        (this[key] as? JsonArray) ?: JsonArray(emptyList())

    private fun JsonObject.strings(key: String): List<String> =
        arr(key).mapNotNull { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content?.takeIf { s -> s.isNotBlank() } }

    /** `[{"word": "hate"}, …]` → the words. */
    private fun JsonArray.words(): List<String> =
        mapNotNull { (it as? JsonObject)?.str("word")?.trim()?.takeIf { w -> w.isNotEmpty() } }
}
