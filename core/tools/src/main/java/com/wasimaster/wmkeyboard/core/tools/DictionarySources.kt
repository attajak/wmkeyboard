package com.wasimaster.wmkeyboard.core.tools

import androidx.annotation.StringRes
import com.wasimaster.wmkeyboard.core.netlog.NetSource
import com.wasimaster.wmkeyboard.core.vocab.KaikkiClient
import com.wasimaster.wmkeyboard.core.vocab.VocabAccent
import com.wasimaster.wmkeyboard.core.vocab.VocabIndex
import com.wasimaster.wmkeyboard.core.vocab.VocabWord
import com.wasimaster.wmkeyboard.core.vocab.WiktionaryRestClient
import com.wasimaster.wmkeyboard.tools.R
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * A place the Dictionary tool can ask for a word's definitions. The user
 * puts them in order and switches them on and off; a look-up asks each one
 * in turn until one has the word, so a source that is down (the free
 * dictionary API is often down for days) falls through to the next.
 *
 * [id] is what the settings store: never store [name].
 */
enum class DictionarySource(val id: String, val online: Boolean) {
    /** The vocabulary packs on the device. Only knows the packs' words, but costs nothing to ask and works offline. */
    VOCAB_PACKS("vocab_packs", online = false),

    /** Wiktionary's full entry through kaikki.org: senses, examples, both accents' IPA and recordings. */
    WIKTIONARY("wiktionary", online = true),

    /** Wiktionary's own definition endpoint: definitions and examples, no pronunciation. */
    WIKTIONARY_REST("wiktionary_rest", online = true),

    /** The free dictionary API (dictionaryapi.dev). Last by default: it is often down. */
    DICTIONARY_API("dictionary_api", online = true),
    ;

    /** The source's name, for the settings list and the panel's "From …" line. */
    @get:StringRes
    val labelRes: Int
        get() = when (this) {
            VOCAB_PACKS -> R.string.core_tools_dictionary_source_vocab_packs
            WIKTIONARY -> R.string.core_tools_dictionary_source_wiktionary
            WIKTIONARY_REST -> R.string.core_tools_dictionary_source_wiktionary_rest
            DICTIONARY_API -> R.string.core_tools_dictionary_source_dictionary_api
        }

    /** One line on what the source knows, under its name in settings. */
    @get:StringRes
    val descriptionRes: Int
        get() = when (this) {
            VOCAB_PACKS -> R.string.core_tools_dictionary_source_vocab_packs_desc
            WIKTIONARY -> R.string.core_tools_dictionary_source_wiktionary_desc
            WIKTIONARY_REST -> R.string.core_tools_dictionary_source_wiktionary_rest_desc
            DICTIONARY_API -> R.string.core_tools_dictionary_source_dictionary_api_desc
        }

    companion object {
        fun of(id: String): DictionarySource? = entries.firstOrNull { it.id == id }
    }
}

/** One row of the user's source list: the source, and whether it is asked. */
data class DictionarySourceChoice(val source: DictionarySource, val enabled: Boolean = true)

/** The source list as settings store it: ids in order, a `-` in front of one that is off. */
object DictionarySources {

    /** Every source, on: offline first, then the richest, then the least reliable. */
    val DEFAULT: List<DictionarySourceChoice> = DictionarySource.entries.map { DictionarySourceChoice(it) }

    fun encode(choices: List<DictionarySourceChoice>): String =
        choices.joinToString(",") { (if (it.enabled) "" else "-") + it.source.id }

    /**
     * An id this build does not know is dropped. A source this build knows
     * and the stored list does not (added in an update) joins at the end,
     * switched on, so an update adds a backup rather than hiding it.
     */
    fun decode(raw: String): List<DictionarySourceChoice> {
        val seen = LinkedHashMap<DictionarySource, Boolean>()
        for (token in raw.split(',')) {
            val trimmed = token.trim()
            val off = trimmed.startsWith('-')
            val source = DictionarySource.of(trimmed.removePrefix("-")) ?: continue
            seen.putIfAbsent(source, !off)
        }
        return seen.map { (source, enabled) -> DictionarySourceChoice(source, enabled) } +
            DictionarySource.entries.filter { it !in seen }.map { DictionarySourceChoice(it) }
    }
}

/**
 * Asks the user's dictionary sources in their order until one has the word.
 *
 * A source that does not know the word and a source that cannot be asked
 * (offline, down, a page too large) both fall through to the next; only the
 * verdict at the end tells them apart, so the panel can say "no entry" or
 * "could not reach any source" rather than one message for both.
 */
object DictionaryLookup {

    sealed interface Result {
        data class Found(val entries: List<DictEntry>, val source: DictionarySource) : Result

        /** Every source that was asked answered, and none knew the word. */
        data object NotFound : Result

        /** No source could be asked (offline, or every one failed). */
        data object Failed : Result
    }

    /** One source asked for one word: its entries, empty for none; throws when it could not be asked. */
    fun interface Fetch {
        fun entries(source: DictionarySource, word: String): List<DictEntry>
    }

    suspend fun resolve(word: String, sources: List<DictionarySource>, fetch: Fetch): Result {
        val trimmed = word.trim()
        if (trimmed.isEmpty() || sources.isEmpty()) return Result.NotFound
        var answered = false
        for (source in sources) {
            val entries = try {
                withContext(Dispatchers.IO) { fetch.entries(source, trimmed) }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                continue
            }
            answered = true
            if (entries.isNotEmpty()) return Result.Found(entries, source)
        }
        return if (answered) Result.NotFound else Result.Failed
    }

    /**
     * The real sources. [index] is the vocabulary packs' index, or null when
     * none is loaded (the packs source then has nothing).
     */
    fun fetcher(index: () -> VocabIndex?): Fetch = Fetch { source, word ->
        when (source) {
            DictionarySource.VOCAB_PACKS ->
                index()?.lookupAnyForm(word.lowercase(Locale.ROOT))?.let(::fromVocabWord).orEmpty()
            DictionarySource.WIKTIONARY ->
                KaikkiClient.lookup(word, emptyList(), NetSource.DICTIONARY)?.let(::fromVocabWord).orEmpty()
            DictionarySource.WIKTIONARY_REST ->
                WiktionaryRestClient.lookup(word, NetSource.DICTIONARY)?.let(::fromVocabWord).orEmpty()
            DictionarySource.DICTIONARY_API -> try {
                DictionaryClient.lookup(word)
            } catch (_: DictionaryClient.NotFoundException) {
                emptyList()
            }
        }
    }

    /** A pack's or Wiktionary's record as one entry, its senses grouped by part of speech in the record's order. */
    fun fromVocabWord(record: VocabWord): List<DictEntry> {
        val byPos = LinkedHashMap<String, ArrayList<DictDefinition>>()
        for (sense in record.senses) {
            val text = sense.definition.trim()
            if (text.isEmpty()) continue
            byPos.getOrPut(sense.pos.trim().lowercase(Locale.ROOT)) { ArrayList() } += DictDefinition(
                text = text,
                example = sense.example?.trim()?.takeIf { it.isNotEmpty() },
                synonyms = sense.synonyms,
            )
        }
        if (byPos.isEmpty()) return emptyList()
        val single = byPos.size == 1
        val meanings = byPos.map { (pos, definitions) ->
            DictMeaning(
                partOfSpeech = pos,
                definitions = definitions,
                // The record's own lists are not tied to a part of speech;
                // they only belong under one when there is only one.
                synonyms = if (single) record.synonyms else emptyList(),
                antonyms = if (single) record.antonyms else emptyList(),
            )
        }
        return listOf(
            DictEntry(
                word = record.word,
                phonetic = record.ipaFor(VocabAccent.US).orEmpty(),
                audioUrl = record.audioFor(VocabAccent.US)?.takeIf { it.startsWith("http") },
                meanings = meanings,
            ),
        )
    }
}
