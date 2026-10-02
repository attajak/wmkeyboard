package com.wasimaster.wmkeyboard.core.prediction

import com.wasimaster.wmkeyboard.core.transliteration.BengaliPhoneticIndex
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What the correction engine makes of Vietnamese, over a word list standing in
 * for a downloaded `vi` one.
 *
 * Vietnamese is the one transliterator whose buffer composes into a word of a
 * language the app has a list for, so it is the one the dictionary can say
 * something about. These pin down how much it says, because the answer is
 * narrower than it looks and the whole feature is scoped by it:
 *
 *  - **A word the list holds is never touched.** The composed word is a real
 *    Vietnamese word, so correcting it would be rewriting what the user typed
 *    correctly. This is the invariant the rest has to live under, and the
 *    reason a "wrong tone on a real word" cannot be fixed this way.
 *  - **Restoring what was left out is what fires** — an omitted tone or a
 *    missing letter, when the list's answer is unambiguous — and it fires well
 *    under the default confidence, so it happens without the user turning
 *    anything up.
 *  - **An unaccented spelling with several accented readings is left alone.**
 *    "toan" is toàn, toán and toản at once; there is no one word to pick, so
 *    nothing is applied. Most Vietnamese without tones looks like this.
 */
class VietnameseCorrectionTest {

    private fun viEngine() = SuggestionEngine(
        Trie().apply {
            insert("tiếng", 9000)
            insert("toán", 8000)
            insert("toàn", 7500)
            insert("nghiêng", 6000)
            insert("nguyễn", 7000)
            insert("gửi", 5000)
            insert("đường", 5500)
            insert("không", 8500)
            insert("người", 8200)
        },
        BengaliPhoneticIndex(emptyList()),
        UserLexicon(null),
    )

    @Test
    fun aRealWordIsNeverRewritten() {
        val e = viEngine()
        // The protection rule, which is what makes this safe to switch on: the
        // composer already produced a word, and it is the word the user typed.
        for (word in listOf("tiếng", "nguyễn", "gửi", "toán", "toàn")) {
            val decision = e.decideCorrection(word)
            assertNull("$word was corrected to ${decision.apply}", decision.apply)
            assertNull("$word was offered ${decision.offer}", decision.offer)
        }
    }

    @Test
    fun anOmittedToneIsRestored() {
        val e = viEngine()
        assertEquals("tiếng", e.decideCorrection("tieng").apply)
        assertEquals("nguyễn", e.decideCorrection("nguyen").apply)
        assertEquals("không", e.decideCorrection("khong").apply)
        assertEquals("người", e.decideCorrection("nguoi").apply)
    }

    @Test
    fun anOmittedLetterMarkIsRestored() {
        val e = viEngine()
        // đ is a letter the user leaves out, not a tone.
        assertEquals("đường", e.decideCorrection("duong").apply)
    }

    @Test
    fun aMissingLetterIsRestored() {
        val e = viEngine()
        assertEquals("nghiêng", e.decideCorrection("ngiêng").apply)
    }

    @Test
    fun theseFireBelowTheDefaultConfidenceGate() {
        val e = viEngine()
        // The point: the feature does not need the slider moved. A regression
        // that only worked at a loose setting would be a silent no-op for
        // every user who never touches it.
        assertEquals(SuggestionEngine.DEFAULT_AUTOCORRECT_CONFIDENCE, e.autocorrectConfidence, 0.0)
        for ((typed, intended) in listOf(
            "tieng" to "tiếng",
            "nguyen" to "nguyễn",
            "khong" to "không",
            "ngiêng" to "nghiêng",
        )) {
            val decision = e.decideCorrection(typed)
            assertEquals("$typed -> $intended", intended, decision.apply)
            assertTrue("$typed cleared the gate by ${decision.certainty}", decision.certainty > 0.5)
        }
    }

    @Test
    fun anUnaccentedWordWithSeveralReadingsIsLeftAlone() {
        val e = viEngine()
        // toàn and toán share this spelling once the tone is gone, so there is
        // no single word to commit and guessing would be a coin toss. This is
        // most Vietnamese typed without tones, and the feature does not reach
        // it — which is why the strip, not the commit, is where those belong.
        val decision = e.decideCorrection("toan")
        assertNull(decision.apply)
        assertNull(decision.offer)
    }

    @Test
    fun aWordTheListDoesNotHoldIsLeftAlone() {
        val e = viEngine()
        val decision = e.decideCorrection("xyzabc")
        assertNull(decision.apply)
        assertNull(decision.offer)
    }

    @Test
    fun anAllCapsWordIsLeftAlone() {
        val e = viEngine()
        assertNull(e.decideCorrection("TIENG").apply)
    }
}
