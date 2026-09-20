package com.wasimaster.wmkeyboard.core.input.composer

import com.wasimaster.wmkeyboard.core.script.ScriptId
import com.wasimaster.wmkeyboard.core.transliteration.HindiPhonetic

/**
 * Hindi phonetic: roman letters transliterated to Devanagari as the buffer
 * grows, committed as a unit — [BengaliTransliterateComposer]'s twin over
 * [HindiPhonetic].
 *
 * What the buffer shows is the rules' reading of it, and Hindi's rules guess
 * more than Avro's do (see [HindiPhonetic]), so the preview moves as a word is
 * typed: "kar" is कर, "karn" closes on a cluster and reads कर्न, and the "a"
 * that makes it an infinitive opens it again to करना. That is the honest
 * preview — it is what a space would commit at each point — and the commit
 * itself goes through the dictionary whenever there is one.
 */
object HindiTransliterateComposer : Composer {

    private val DEVANAGARI = 0x0900..0x097F
    private const val VIRAMA = '्'

    override val isTransliterating: Boolean get() = true

    override val phoneticLanguage: String get() = "hi"

    override fun composeBuffer(buffer: String): String = HindiPhonetic.transliterate(buffer)

    /** The same diff-the-two-transliterations answer as Avro's, for the same reason. */
    override fun keyPreview(buffer: String, key: String, wholeCluster: Boolean): String? {
        if (key.isEmpty() || !key.all { it.isLetter() }) return null
        val after = HindiPhonetic.transliterate(buffer + key)
        if (wholeCluster) return after.takeLast(deleteLength(after))
        val before = HindiPhonetic.transliterate(buffer)
        var shared = 0
        while (shared < before.length && shared < after.length && before[shared] == after[shared]) {
            shared++
        }
        return after.substring(shared)
    }

    override fun deleteLength(before: CharSequence): Int =
        clusterDeleteLength(before, DEVANAGARI, viramaFor(ScriptId.DEVANAGARI))

    /**
     * A word the rules produced with a join left hanging, or with Latin still
     * in it, is a buffer that was committed half-typed — not a word to learn.
     */
    override fun isPlausibleWord(word: String): Boolean =
        word.isNotEmpty() && word.last() != VIRAMA && word.none { it in 'a'..'z' || it in 'A'..'Z' }
}
