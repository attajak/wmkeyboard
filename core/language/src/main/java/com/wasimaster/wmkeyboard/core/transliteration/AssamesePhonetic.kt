package com.wasimaster.wmkeyboard.core.transliteration

/**
 * Assamese phonetic: roman letters in, Assamese script out, on [AvroPhonetic].
 *
 * Assamese is written in the Bengali script, and the way people romanize it is
 * the way they romanize Bangla — "o" for the inherent vowel, "a" for া — so Avro's
 * rules read it well. Three things are Assamese's own, and they are fixed up on the
 * way through rather than by a second rule table:
 *
 *  - **ৰ, not র.** Assamese never writes the Bangla ra; every র Avro writes is ৰ.
 *  - **"x" is স.** Assamese says স, শ and ষ as a velar fricative that romanizers
 *    write x — "xokolu" সকলো, "axom" অসম — where Avro reads x as ক্স. So x is
 *    read as s before the rules see it.
 *  - **ৱ, not ওয়.** Avro spells a "w" gliding into a vowel as ওয় — Bangla's way —
 *    where Assamese has a letter for it: "suwali" is ছোৱালী, "guwahati" গুৱাহাটী.
 *    Inside a word ওয় becomes ৱ; opening one it stays, since ও there is a vowel.
 */
object AssamesePhonetic {

    private const val BENGALI_RA = 'র'
    private const val ASSAMESE_RA = 'ৰ'
    private const val ASSAMESE_WA = 'ৱ'
    private const val BENGALI_BA = 'ব'

    /** ও then য় (precomposed, or য with a nukta) after a letter of the word. */
    private val GLIDE_WA = Regex("(?<=[\u0980-\u09FF])\u0993(?:\u09DF|\u09AF\u09BC)")

    /** Transliterates roman Assamese — one word or free text — into the Assamese script. */
    fun transliterate(input: String): String =
        AvroPhonetic.transliterate(input.replace('x', 's'))
            .replace(BENGALI_RA, ASSAMESE_RA)
            .replace(GLIDE_WA, ASSAMESE_WA.toString())

    /**
     * Assamese text in the Bangla letters Avro and the Bengali index read: ৰ as
     * র and ৱ as ব. The way into [BengaliPhoneticIndex] and [BengaliRomanizer].
     */
    fun toBengali(text: String): String =
        text.replace(ASSAMESE_RA, BENGALI_RA).replace(ASSAMESE_WA, BENGALI_BA)

    /** An Assamese letter or sign: the Bengali block, which holds ৰ and ৱ too. */
    fun isAssamese(c: Char): Boolean = BengaliGraphemes.isBengali(c)
}

/**
 * Reverse-phonetic lookup for Assamese: [BengaliPhoneticIndex] over the list
 * spelled in Bangla letters ([AssamesePhonetic.toBengali]), answering in the
 * list's own Assamese spelling.
 */
class AssamesePhoneticIndex(entries: List<Pair<String, Int>>) : PhoneticIndex {

    private val native = HashMap<String, String>()

    private val inner: BengaliPhoneticIndex

    init {
        val kept = boundedEntries(entries)
        val converted = ArrayList<Pair<String, Int>>(kept.size)
        for ((word, frequency) in kept) {
            val bn = AssamesePhonetic.toBengali(word)
            native.putIfAbsent(bn, word)
            converted += bn to frequency
        }
        inner = BengaliPhoneticIndex(converted)
    }

    override val isEmpty: Boolean get() = inner.isEmpty

    override fun lookup(input: String): List<String> =
        inner.lookup(input.replace('x', 's')).mapNotNull { native[it] }.distinct()

    override fun frequencyOf(word: String): Int = inner.frequencyOf(AssamesePhonetic.toBengali(word))

    override fun matchStrength(input: String): Int = inner.matchStrength(input.replace('x', 's'))

    override val maxFrequency: Int get() = inner.maxFrequency
}
