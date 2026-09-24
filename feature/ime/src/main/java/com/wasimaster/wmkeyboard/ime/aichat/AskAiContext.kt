package com.wasimaster.wmkeyboard.ime.aichat

import com.wasimaster.wmkeyboard.core.grammar.GrammarLint
import com.wasimaster.wmkeyboard.core.tools.DictEntry
import com.wasimaster.wmkeyboard.core.tools.WikipediaClient
import com.wasimaster.wmkeyboard.core.vocab.VocabWord

/**
 * What a tool hands the AI chat when the user asks about it (#352): the text
 * the model reads, framed so it knows where the text came from.
 *
 * It travels as the message's attachment, which the chat sends inside a
 * `<text>` block after the question, so the question stays the user's own
 * words and the material stays data. These frames are written for the model,
 * not the user, which is why they are plain English like the system prompts
 * in `AiPrompts` rather than string resources.
 *
 * Pure functions over the tools' own models, so the tests can read them
 * without a device.
 */
object AskAiContext {

    /** The most a selection's surrounding block adds to it; past this it is the selection alone. */
    const val MAX_SURROUNDING = 4_000

    /**
     * A passage selected out of a longer text: the passage, where it came
     * from, and the paragraph around it when there is more of that to see.
     * A model asked about a sentence reads it far better in its paragraph.
     */
    fun selection(selected: String, source: String, block: String): String = buildString {
        append("A passage selected from ").append(source).append(":\n\n")
        append('"').append(selected.trim()).append('"')
        val around = block.trim()
        if (around.length > selected.trim().length && around.length <= MAX_SURROUNDING) {
            append("\n\nThe paragraph it is in:\n").append(around)
        }
    }

    /** A whole Wikipedia article: the full text when it has been loaded, else the summary. */
    fun wikipedia(summary: WikipediaClient.Summary, fullText: String?): String = buildString {
        append("Wikipedia article: ").append(summary.title).append('\n')
        if (summary.description.isNotBlank()) append(summary.description.trim()).append('\n')
        if (summary.url.isNotBlank()) append("URL: ").append(summary.url).append('\n')
        val body = fullText?.takeIf { it.isNotBlank() } ?: summary.extract
        if (body.isNotBlank()) append('\n').append(body.trim())
    }

    /** What the Wikipedia panel calls an article in a selection's frame. */
    fun wikipediaSource(title: String): String = "the Wikipedia article \"$title\""

    /** Dictionary entries for one word, every meaning numbered as the panel shows it. */
    fun dictionary(entries: List<DictEntry>): String = buildString {
        for ((index, entry) in entries.withIndex()) {
            if (index > 0) append("\n\n")
            append("Dictionary entry: ").append(entry.word)
            if (entry.phonetic.isNotBlank()) append(' ').append(entry.phonetic)
            for (meaning in entry.meanings) {
                append('\n')
                if (meaning.partOfSpeech.isNotBlank()) append('\n').append(meaning.partOfSpeech).append(':')
                meaning.definitions.forEachIndexed { number, definition ->
                    append('\n').append(number + 1).append(". ").append(definition.text.trim())
                    definition.example?.takeIf { it.isNotBlank() }?.let {
                        append("\n   Example: \"").append(it.trim()).append('"')
                    }
                }
                val synonyms = (meaning.synonyms + meaning.definitions.flatMap { it.synonyms }).distinct()
                if (synonyms.isNotEmpty()) append("\nSynonyms: ").append(synonyms.take(12).joinToString(", "))
                if (meaning.antonyms.isNotEmpty()) append("\nAntonyms: ").append(meaning.antonyms.take(12).joinToString(", "))
            }
        }
    }

    fun dictionarySource(word: String): String = "the dictionary entry for \"$word\""

    /** A vocabulary card: the word, how it sounds, its senses and where it came from. */
    fun vocabulary(word: VocabWord, ipa: String?): String = buildString {
        append("Vocabulary word: ").append(word.word)
        if (word.pos.isNotEmpty()) append(" (").append(word.pos.joinToString(", ")).append(')')
        if (!ipa.isNullOrBlank()) append("\nPronunciation: ").append(ipa)
        word.senses.forEachIndexed { number, sense ->
            append('\n').append(number + 1).append(". ")
            if (sense.pos.isNotBlank()) append('(').append(sense.pos).append(") ")
            append(sense.definition.trim())
            sense.example?.takeIf { it.isNotBlank() }?.let { append("\n   Example: \"").append(it.trim()).append('"') }
        }
        if (word.synonyms.isNotEmpty()) append("\nSynonyms: ").append(word.synonyms.take(12).joinToString(", "))
        if (word.antonyms.isNotEmpty()) append("\nAntonyms: ").append(word.antonyms.take(12).joinToString(", "))
        word.etymology?.takeIf { it.isNotBlank() }?.let { append("\nEtymology: ").append(it.trim()) }
    }

    fun vocabularySource(word: String): String = "the vocabulary card for \"$word\""

    /**
     * One issue the grammar checker found: what it says, the words it
     * flagged, its fixes, and the sentence they sit in, which is what the
     * model needs to say whether the checker is right.
     */
    fun grammar(lint: GrammarLint, fixes: List<String>, text: String, kind: String): String = buildString {
        append("A grammar checker flagged something in my text.")
        if (kind.isNotBlank()) append("\nKind of issue: ").append(kind)
        if (lint.message.isNotBlank()) append("\nWhat it says: ").append(lint.message.trim())
        if (lint.original.isNotBlank()) append("\nFlagged words: \"").append(lint.original).append('"')
        if (fixes.isNotEmpty()) append("\nSuggested fixes: ").append(fixes.joinToString(", ") { "\"$it\"" })
        val sentence = sentenceAround(text, lint.start, lint.end)
        if (sentence.isNotBlank()) append("\nThe sentence: ").append(sentence)
    }

    /**
     * The sentence holding [start]..[end] of [text], cut at the nearest
     * sentence end or line break either side, and never longer than
     * [MAX_SURROUNDING].
     */
    fun sentenceAround(text: String, start: Int, end: Int): String {
        if (text.isEmpty()) return ""
        val from = start.coerceIn(0, text.length)
        val to = end.coerceIn(from, text.length)
        var head = from
        while (head > 0 && text[head - 1] !in SENTENCE_BREAKS && from - head < MAX_SURROUNDING / 2) head--
        var tail = to
        while (tail < text.length && text[tail] !in SENTENCE_BREAKS && tail - to < MAX_SURROUNDING / 2) tail++
        // Keep the mark that ends the sentence; a line break is not part of it.
        if (tail < text.length && text[tail] != '\n') tail++
        return text.substring(head, tail).trim()
    }

    private val SENTENCE_BREAKS = setOf('.', '!', '?', '\n', '。', '！', '？', '।')
}
