package com.wasimaster.wmkeyboard.core.input.composer

import com.wasimaster.wmkeyboard.core.transliteration.UrduPhonetic

/**
 * Urdu phonetic: roman letters transliterated to the Urdu script as the buffer
 * grows, committed as a unit — [BengaliTransliterateComposer]'s and
 * [HindiTransliterateComposer]'s twin over [UrduPhonetic] (issue #496).
 *
 * The preview moves more than either of theirs does, because Urdu spells a
 * vowel differently depending on where in the word it falls: "k" is ک, "ka" is
 * کا with the ا that closes a word, and the "r" of "kar" takes that ا straight
 * back out again (کر). That is the honest preview — it is what a space would
 * commit at each point — and the commit itself goes through the spelling map
 * and the word list whenever there is one.
 */
object UrduTransliterateComposer : Composer {

    override val isTransliterating: Boolean get() = true

    override val isRomanBuffer: Boolean get() = true

    override val phoneticLanguage: String get() = "ur"

    override fun composeBuffer(buffer: String): String = UrduPhonetic.transliterate(buffer)

    /**
     * The same answer the other two give, by the same means: transliterate the
     * buffer with and without the key and read the difference off the two
     * results, rather than keeping a second table of "which Urdu letter does
     * this key mean here" that could drift from the rules.
     *
     * [wholeCluster] makes no difference here. Urdu has no conjuncts — the
     * cluster its Indic cousins would show is just the letter this key writes —
     * and the diff is taken from the common prefix rather than from the end,
     * because the output does not only ever grow: a key can take the previous
     * letter away (the ا of "ka" when "kar" is typed).
     */
    override fun keyPreview(buffer: String, key: String, wholeCluster: Boolean): String? {
        // Only the keys that reach the buffer. A digit or a full stop commits
        // straight through — the transliterator never sees it — so previewing
        // what the rules would have made of it promises what the keypress does
        // not keep.
        if (key.isEmpty() || !key.all { it.isLetter() }) return null
        val before = UrduPhonetic.transliterate(buffer)
        val after = UrduPhonetic.transliterate(buffer + key)
        var shared = 0
        while (shared < before.length && shared < after.length && before[shared] == after[shared]) {
            shared++
        }
        return after.substring(shared)
    }
}
