package com.wasimaster.wmkeyboard.core.input.composer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Spelling a finished Vietnamese word back into the keystrokes that compose it.
 *
 * This is what lets a caret that lands on a word already in the field go on
 * editing it (see `Composer.resumeBuffer`): the composing buffer holds keys
 * (`toois`), the field holds the word they composed (`tối`), and a tone key
 * typed into the buffer only means anything if the buffer was holding keys.
 *
 * The round trip is the whole test. A spelling is one of several that compose a
 * word — `tooi` and `tôii` and any number of dead ends — so a candidate is only
 * a reading of the word when composing it gives the word back, and the answer
 * for anything else has to be no reading at all rather than a wrong one. The
 * tests assert through the public composer, so the engine's spelling is checked
 * by the thing that will use it.
 */
class VietnameseResumeTest {

    private fun telexKeys(word: String) = VietnameseTelexComposer.resumeBuffer(word)

    private fun vniKeys(word: String) = VietnameseVniComposer.resumeBuffer(word)

    /** The keys compose the word they were read from, or nothing was read. */
    private fun assertRoundTrips(composer: Composer, word: String) {
        val keys = composer.resumeBuffer(word)
        assertNotNull("no spelling for `$word`", keys)
        assertEquals("`$word` read back as `$keys`", word, composer.composeBuffer(keys!!))
    }

    // --- Telex -------------------------------------------------------------

    @Test
    fun `toned words read back as the keys that spell them`() {
        for (word in listOf(
            "tôi", "nước", "đường", "tiếng", "hoặc", "quyển", "giường",
            "muối", "rượu", "ăn", "ở", "cửa", "nghiêng", "khuỷu", "chuyện",
            "được", "người", "trường", "Việt", "Tôi", "ĐƯỜNG",
            // The open `uơ` and the horned `ươ`: the same keys up to the coda,
            // so reading one back must not spell the other.
            "huơ", "quơ", "thuở", "hương", "hướng", "hường",
        )) {
            assertRoundTrips(VietnameseTelexComposer, word)
        }
    }

    @Test
    fun `a word with no mark reads back as itself`() {
        // The buffer of a Telex word that carries no mark *is* the word: the
        // user's own keys, unaltered. Both a word about to be toned (`toi` →
        // `tói`) and one that never will be (`hello`) have to come back whole,
        // which is what makes backspace into them an ordinary edit.
        // `banana` is deliberately absent: a mark key now reaches its second
        // `a` (`bânna`), so the word is no longer one the keys spell as typed —
        // under the strict rule it comes back as `banana`, but this test runs
        // with the strict rule off.
        for (word in listOf("toi", "hello", "xin", "chao", "cactus")) {
            assertEquals(word, telexKeys(word))
            assertRoundTrips(VietnameseTelexComposer, word)
        }
    }

    @Test
    fun `a tone key spelled as the word's own last letter still round-trips`() {
        // `cá` spells back as `cas`, and the `s` is a tone key rather than the
        // letter the word ends in — the engine reads it as one because the word
        // in hand (`ca`) could still be a syllable. The reverse has to hold too:
        // `các` is `cacs`, whose last `s` is a tone key over a word that already
        // ends in the letter `c`.
        assertRoundTrips(VietnameseTelexComposer, "cá")
        assertRoundTrips(VietnameseTelexComposer, "các")
        assertRoundTrips(VietnameseTelexComposer, "cạnh")
    }

    @Test
    fun `a word the engine would rewrite reads as nothing`() {
        // Not every Latin word survives being read back: `as` spells `á` on a
        // Telex board, `new` spells `neư`, and `www` is three bare `w`s that are
        // each `ư`. A resume over any of them would compose something the user
        // never typed, so the answer is none — the caret keeps the read-only
        // treatment it has today.
        for (word in listOf("as", "new", "www", "café", "row", "show")) {
            assertNull("`$word` should not be readable", telexKeys(word))
        }
    }

    @Test
    fun `strict tones does not stop a word being read back`() {
        // The setting the composer reads at call time, and the only one that can
        // differ between two runs of the same code: `resumeBuffer` verifies its
        // spelling with `transduce`, which is the strict rule's entry point, so
        // a word the verification path answers differently under it would come
        // back null and the resume would silently decline.
        val was = VietnameseConfig.strictTones
        VietnameseConfig.strictTones = true
        try {
            for (word in listOf(
                "tói", "tôi", "nước", "đường", "hoà", "quà", "tiếng", "hoặc",
            )) {
                assertRoundTrips(VietnameseTelexComposer, word)
            }
        } finally {
            VietnameseConfig.strictTones = was
        }
    }

    @Test
    fun `a word no keys compose is not read back, even under strict tones`() {
        // The strict rule answers a word it cannot spell by handing the keys
        // back, so `transduce("Web")` is `Web` — and a check written against
        // `transduce` read that as a spelling of the word. The resume then
        // armed the composing region with the field's own text, and the next
        // backspace ran the composer over it (`W` is `ư`, which is how `Web`
        // came back as `Ư`).
        val was = VietnameseConfig.strictTones
        VietnameseConfig.strictTones = true
        try {
            assertNull(VietnameseTelexComposer.resumeBuffer("Web"))
            assertNull(VietnameseTelexComposer.resumeBuffer("web"))
            assertNull(VietnameseTelexComposer.resumeBuffer("banana"))
            // A word the keys do spell is still read back, fallback or not.
            assertEquals("cactus", VietnameseTelexComposer.resumeBuffer("cactus"))
        } finally {
            VietnameseConfig.strictTones = was
        }
    }

    // --- VNI ---------------------------------------------------------------

    @Test
    fun `vni words read back with the digits that spell them`() {
        for (word in listOf("tôi", "nước", "đường", "tiếng", "hoặc", "quyển", "ăn", "Tôi")) {
            assertRoundTrips(VietnameseVniComposer, word)
        }
    }

    @Test
    fun `a vni word with no mark reads back as itself`() {
        assertEquals("toi", vniKeys("toi"))
        assertEquals("hello", vniKeys("hello"))
    }

    @Test
    fun `a vni spelling uses digits the vni engine reads as marks`() {
        // The two methods share the engine but not the keys, so a Vietnamese
        // spelling has to be asked for in the method it belongs to: digits on
        // the Telex board are digits, and a Telex word on the VNI one is read
        // by different rules entirely.
        // `tôi` carries a letter mark and no tone, so its spelling ends on the
        // mark key and not on a tone key; `tối` carries both.
        assertEquals("to6i", vniKeys("tôi"))
        assertEquals("to6i1", vniKeys("tối"))
        assertTrue(vniKeys("tối")!!.any { it.isDigit() })
        assertEquals("tooi", telexKeys("tôi"))
        assertEquals("toois", telexKeys("tối"))
        assertTrue(telexKeys("tối")!!.none { it.isDigit() })
    }

    // --- what a caller may rely on -----------------------------------------

    @Test
    fun `a resumed buffer composes the word the caret came back to`() {
        // The service arms a composing region over the word and puts this
        // buffer behind it, so the two have to agree character for character:
        // the region shows the word, the buffer shows the keys, and one more
        // keystroke has to read as a tone or a mark on that word rather than as
        // a letter appended to a word that already exists.
        val keys = telexKeys("nước")!!
        // A tone key after the word replaces the tone the word already has
        // rather than spelling a letter: `j` turns `nước` into `nược`, and the
        // new tone lands on the same vowel the old one was on.
        assertEquals("nược", VietnameseTelexComposer.composeBuffer(keys + "j"))
        assertEquals("nườc", VietnameseTelexComposer.composeBuffer(keys + "f"))
        // Repeating the tone key is the key's own undo — the mark comes off and
        // the letter is typed — which is what leaves `nươcs` rather than a
        // second tone on one syllable.
        assertEquals("nươcs", VietnameseTelexComposer.composeBuffer(keys + "s"))
    }
}
