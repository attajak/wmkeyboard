package com.wasimaster.wmkeyboard.core.prediction

import com.wasimaster.wmkeyboard.core.transliteration.AvroPhonetic
import com.wasimaster.wmkeyboard.core.transliteration.BengaliPhoneticIndex
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** English mixed into a phonetic layout: the strip, the verdict, and what a space commits. */
class PhoneticEnglishMixTest {

    private val english = Trie().apply {
        insert("the", 10000)
        insert("to", 9800)
        insert("you", 9000)
        insert("are", 8250)
        insert("how", 5000)
        insert("because", 3000)
        insert("world", 2000)
        insert("help", 900)
        insert("hello", 830)
        insert("hell", 600)
        insert("keyboard", 120)
    }

    private val bengali = BengaliPhoneticIndex(
        listOf(
            "আমি" to 9000,
            "আছি" to 6900,
            "তো" to 5200,
            "কেমন" to 5000,
            "আসি" to 2300,
            "হ্যালো" to 1900,
            "আরে" to 2200,
        ),
    )

    private val spellings = SpellingMap.load(
        "hello\tহ্যালো\nkeyboard\tকিবোর্ড\n".byteInputStream(Charsets.UTF_8),
        "to\tতো\nare\tআরে\nkemon\tকেমন\n".byteInputStream(Charsets.UTF_8),
        loanwordStreams = 1,
    )

    private fun engine(
        autoEnglish: Boolean = true,
        mixing: Boolean = true,
        lexicon: UserLexicon = UserLexicon(null),
    ) = SuggestionEngine(english, bengali, lexicon, spellings).apply {
        primaryLanguageId = "bn"
        englishSources = false
        englishAsSecondary = mixing
        fieldDetectionShift = SuggestionEngine.FIELD_SHIFT_BALANCED
        phoneticAutoEnglish = autoEnglish
    }

    private fun SuggestionEngine.avro(buffer: String, slots: Int = 3) =
        suggest(buffer, previousWord = null, phoneticLanguage = "bn", phoneticSlots = slots)

    private fun isLatin(word: String) = word.all { it in 'a'..'z' || it in 'A'..'Z' }

    @Test fun withoutEnglishAsASecondaryNothingChanges() {
        val plain = SuggestionEngine(english, bengali, UserLexicon(null), spellings)
        val off = engine(mixing = false)
        for (buffer in listOf("asi", "hello", "to", "kemon", "wasi", "because")) {
            assertEquals(buffer, plain.avro(buffer), off.avro(buffer))
            assertTrue(buffer, off.avro(buffer).none(::isLatin))
            assertEquals(PhoneticScript.NATIVE, off.phoneticScript("bn", buffer))
        }
    }

    @Test fun theBufferAsTypedIsPinnedToTheLastVisibleChip() {
        val strip = engine(autoEnglish = false).avro("asi")
        assertEquals(listOf("আছি", "আসি", "asi"), strip.take(3))
        // A wider strip pins it further along, never in front of the siblings.
        assertEquals("asi", engine(autoEnglish = false).avro("asi", slots = 4).getOrNull(2))
    }

    @Test fun withTheToggleOffEnglishIsOnlyEverOffered() {
        val e = engine(autoEnglish = false)
        for (buffer in listOf("hello", "because", "the")) {
            assertFalse(buffer, isLatin(e.avro(buffer).first()))
            assertTrue(buffer, buffer in e.avro(buffer).take(3))
            assertEquals(PhoneticScript.NATIVE, e.phoneticCommit("bn", buffer)!!.script)
        }
    }

    @Test fun aWordOnlyOneSideKnowsGoesToThatSide() {
        val e = engine()
        assertEquals("because", e.avro("because").first())
        assertEquals("কেমন", e.avro("kemon").first())
        // Neither knows it: the layout's own script, which is what it is for.
        assertEquals(AvroPhonetic.transliterate("wasi"), e.avro("wasi").first())
        // One-sided commits are not worth a flip.
        assertNull(e.phoneticCommit("bn", "because")!!.alternate)
        assertNull(e.phoneticCommit("bn", "kemon")!!.alternate)
    }

    @Test fun aLoanwordEntryIsTheEnglishWord() {
        val e = engine()
        assertEquals("hello", e.avro("hello").first())
        // The native spelling stays a tap away, and a backspace away.
        assertTrue("হ্যালো" in e.avro("hello").take(3))
        assertEquals("হ্যালো", e.phoneticCommit("bn", "hello")!!.alternate)
        // Listed as a loanword and in no Bengali list at all.
        assertEquals("keyboard", e.avro("keyboard").first())
        assertEquals("কিবোর্ড", e.phoneticCommit("bn", "keyboard")!!.alternate)
    }

    @Test fun aRealCollisionStaysNativeInAFieldThatSaysNothing() {
        val e = engine()
        assertEquals("তো", e.avro("to").first())
        assertEquals("আরে", e.avro("are").first())
        assertEquals("to", e.phoneticCommit("bn", "to")!!.alternate)
    }

    @Test fun theFieldsLanguageDecidesACollision() {
        val e = engine()
        e.seedFieldContext(listOf("how", "you"))
        assertEquals("to", e.avro("to").first())
        assertEquals("are", e.avro("are").first())
        // The native word is still on the strip, inside what is visible.
        assertTrue("তো" in e.avro("to").take(3))
        e.seedFieldContext(listOf("আমি", "কেমন"))
        assertEquals("তো", e.avro("to").first())
        // A loanword follows the field too.
        assertEquals("হ্যালো", e.avro("hello").first())
        // What only English knows is still English, whatever the field says.
        assertEquals("because", e.avro("because").first())
    }

    @Test fun anEnglishFieldLeadsTheStripWithEnglishEvenWithTheToggleOff() {
        val e = engine(autoEnglish = false)
        e.seedFieldContext(listOf("how", "you"))
        val strip = e.avro("hel")
        // The space bar still writes Bengali, so Bengali is still first.
        assertFalse(isLatin(strip.first()))
        assertEquals("hel", strip[1])
        assertTrue("hello" in strip || "help" in strip)
    }

    @Test fun aCapitalPastTheFirstLetterIsAvroNotation() {
        val e = engine()
        assertEquals(PhoneticScript.NATIVE, e.phoneticScript("bn", "heLLo"))
        assertEquals(PhoneticScript.LATIN, e.phoneticScript("bn", "Hello"))
        assertEquals("Hello", e.avro("Hello").first())
    }

    @Test fun twoOverrulingsCloseTheQuestion() {
        val e = engine()
        assertEquals(PhoneticScript.LATIN, e.phoneticScript("bn", "hello"))
        e.recordScriptChoice("bn", "hello", PhoneticScript.NATIVE)
        e.recordScriptChoice("bn", "hello", PhoneticScript.NATIVE)
        assertEquals(PhoneticScript.NATIVE, e.phoneticScript("bn", "hello"))
        // And the other way, against the field's own say-so.
        e.recordScriptChoice("bn", "to", PhoneticScript.LATIN)
        e.recordScriptChoice("bn", "to", PhoneticScript.LATIN)
        e.seedFieldContext(listOf("আমি", "কেমন"))
        assertEquals(PhoneticScript.LATIN, e.phoneticScript("bn", "to"))
    }

    @Test fun theHeadOfTheStripIsWhatTheSpaceBarCommits() {
        val e = engine()
        for (context in listOf(emptyList(), listOf("how", "you"), listOf("আমি", "কেমন"))) {
            e.seedFieldContext(context)
            for (buffer in listOf("hello", "to", "are", "kemon", "because", "wasi", "hel", "asi")) {
                assertEquals("$buffer in $context", e.phoneticCommit("bn", buffer)!!.output, e.avro(buffer).first())
            }
        }
    }

    @Test fun anUnknownWordStaysLatinOnlyOnceTheFieldIsPlainlyEnglish() {
        val e = engine()
        val native = AvroPhonetic.transliterate("wasi")
        e.seedFieldContext(listOf("hello"))
        assertEquals(native, e.avro("wasi").first())
        e.seedFieldContext(listOf("how", "are", "you"))
        assertEquals("wasi", e.avro("wasi").first())
        assertEquals(native, e.phoneticCommit("bn", "wasi")!!.alternate)
    }

    @Test fun aNativeWordNoListHasStillCountsForItsLanguage() {
        val e = engine()
        // ওয়াসি is in no dictionary; the field is Bengali all the same.
        e.seedFieldContext(listOf(AvroPhonetic.transliterate("wasi"), AvroPhonetic.transliterate("rafi")))
        assertEquals("হ্যালো", e.avro("hello").first())
    }

    @Test fun englishFollowsAnEnglishWord() {
        val seeds = SeedBigrams.load("hello world 50\n".byteInputStream(Charsets.UTF_8))
        val e = SuggestionEngine(english, bengali, UserLexicon(null), spellings, seeds).apply {
            primaryLanguageId = "bn"
            englishSources = false
            englishAsSecondary = true
        }
        assertTrue("world" in e.suggest("", previousWord = "hello"))
        e.englishAsSecondary = false
        assertFalse("world" in e.suggest("", previousWord = "hello"))
    }
}
