package com.wasimaster.wmkeyboard.core.script

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RomanizedPairingTest {

    private fun lang(id: String) = LanguageRegistry.byId(id)

    @Test fun `romanized ids are the marker, not the locale tag`() {
        assertTrue(lang("bn_rom").isRomanized)
        assertTrue(lang("ar_rom").isRomanized)
        // Crimean Tatar's native orthography is Latin (crh-Latn) — it is not
        // a romanization and must never be auto-paired as one.
        assertFalse(lang("crh").isRomanized)
        assertFalse(lang("en").isRomanized)
    }

    @Test fun `banglish and english pair both ways`() {
        val result = RomanizedPairing.autoPair(
            listOf(lang("en"), lang("bn_rom")),
            emptyMap(),
        )
        assertEquals(listOf("bn_rom"), result.secondaries["en"])
        assertEquals(listOf("en"), result.secondaries["bn_rom"])
    }

    @Test fun `a second run adds nothing`() {
        val first = RomanizedPairing.autoPair(listOf(lang("en"), lang("bn_rom")), emptyMap())
        val second = RomanizedPairing.autoPair(
            listOf(lang("en"), lang("bn_rom")),
            first.secondaries,
        )
        assertTrue(second.added.isEmpty())
        assertEquals(first.secondaries, second.secondaries)
    }

    @Test fun `manual links survive and are not duplicated`() {
        val result = RomanizedPairing.autoPair(
            listOf(lang("en"), lang("fr"), lang("bn_rom")),
            mapOf("en" to listOf("fr", "bn_rom")),
        )
        // The hand-made French link stays, the existing Banglish link is not
        // doubled, and only the missing directions are added.
        assertEquals(listOf("fr", "bn_rom"), result.secondaries["en"])
        assertEquals(listOf("en", "fr"), result.secondaries["bn_rom"])
        assertEquals(listOf("bn_rom"), result.secondaries["fr"])
        assertFalse(result.added.contains("en" to "bn_rom"))
    }

    @Test fun `adding an unrelated language keeps a removed link removed`() {
        // The user unlinked English and Banglish by hand, then added French.
        val result = RomanizedPairing.autoPair(
            listOf(lang("en"), lang("bn_rom"), lang("fr")),
            mapOf("en" to emptyList(), "bn_rom" to emptyList()),
            involving = setOf("fr"),
        )
        assertFalse(result.secondaries["en"].orEmpty().contains("bn_rom"))
        assertFalse(result.secondaries["bn_rom"].orEmpty().contains("en"))
        // French itself is new, so its own Banglish pair is still wired.
        assertEquals(listOf("fr"), result.secondaries["bn_rom"])
        assertEquals(listOf("bn_rom"), result.secondaries["fr"])
    }

    @Test fun `re-adding the romanized language links it again`() {
        val result = RomanizedPairing.autoPair(
            listOf(lang("en"), lang("bn_rom")),
            emptyMap(),
            involving = setOf("bn_rom"),
        )
        assertEquals(listOf("bn_rom"), result.secondaries["en"])
        assertEquals(listOf("en"), result.secondaries["bn_rom"])
    }

    @Test fun `plain same-script languages do not auto-pair`() {
        val result = RomanizedPairing.autoPair(
            listOf(lang("en"), lang("fr"), lang("crh")),
            emptyMap(),
        )
        assertTrue(result.added.isEmpty())
    }

    @Test fun `a romanized language never pairs across scripts`() {
        // ar_rom is Latin; native Arabic is Arabic-script. Different keyboards,
        // and one fuzzy walk could never merge their vocabularies anyway.
        val result = RomanizedPairing.autoPair(
            listOf(lang("ar"), lang("ar_rom")),
            emptyMap(),
        )
        assertTrue(result.added.isEmpty())
    }

    @Test fun `added pairs collapse the two directions into one fact`() {
        val result = RomanizedPairing.autoPair(
            listOf(lang("en"), lang("bn_rom")),
            emptyMap(),
        )
        assertEquals(2, result.added.size)
        assertEquals(1, result.addedPairs.size)
    }
}
