package com.wasimaster.wmkeyboard.core.transliteration

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * [PunjabiPhonetic]'s hook rules, one test each. Every expected string is the
 * Python prototype's output for the same profile.
 */
class PunjabiPhoneticTest {

    private val engine = IndicPhonetic(PunjabiPhonetic.PROFILE)

    private fun t(s: String) = engine.transliterate(s)

    @Test fun plainWordsFollowTheNorthModel() {
        // Plain words: the inherent vowel drops, a doubled stop is the addak, a doubled
        // nasal a tippi, ੜ੍ਹ is pairin ha, and a sentence passes its spaces through.
        assertEquals("\u0A18\u0A30", t("ghar")) // ਘਰ
        assertEquals("\u0A2A\u0A71\u0A15\u0A3E", t("pakka")) // ਪੱਕਾ
        assertEquals("\u0A15\u0A3F\u0A70\u0A28\u0A3E", t("kinna")) // ਕਿੰਨਾ
        assertEquals("\u0A15\u0A70\u0A2E", t("kamm")) // ਕੰਮ
        assertEquals("\u0A2A\u0A5C\u0A4D\u0A39", t("parh")) // ਪੜ੍ਹ
        assertEquals("\u0A38\u0A24 \u0A38\u0A4D\u0A30\u0A40 \u0A05\u0A15\u0A32 \u0A1C\u0A40", t("sat sri akal ji")) // ਸਤ ਸ੍ਰੀ ਅਕਲ ਜੀ
    }

    @Test fun openingOhAndEhAreUAndI() {
        // Rule 1: "oh" / "eh" opening a word are ਉਹ / ਇਹ.
        assertEquals("\u0A09\u0A39\u0A26\u0A3E", t("ohda")) // ਉਹਦਾ
        assertEquals("\u0A07\u0A39\u0A28\u0A3E", t("ehna")) // ਇਹਨਾ
        assertEquals("\u0A07\u0A39\u0A4B", t("eho")) // ਇਹੋ
    }

    @Test fun ehAfterAConsonantIsAPlusHaSihari() {
        // Rule 2: "eh" after a consonant is ਅ + ਹਿ, and ਿਹਾ before a final "a".
        assertEquals("\u0A15\u0A39\u0A3F\u0A70\u0A26\u0A3E", t("kehnda")) // ਕਹਿੰਦਾ
        assertEquals("\u0A36\u0A39\u0A3F\u0A30", t("shehar")) // ਸ਼ਹਿਰ
        assertEquals("\u0A15\u0A39\u0A3F", t("keh")) // ਕਹਿ
        assertEquals("\u0A30\u0A3F\u0A39\u0A3E", t("reha")) // ਰਿਹਾ
    }

    @Test fun yGlidesAreVowelCarriers() {
        // Rule 3: Gurmukhi spells the y-glides with vowel carriers, not ਯ; "yo" stays ਯ.
        assertEquals("\u0A2A\u0A3F\u0A06\u0A30", t("pyar")) // ਪਿਆਰ
        assertEquals("\u0A26\u0A47\u0A16\u0A3F\u0A06", t("dekhya")) // ਦੇਖਿਆ
        assertEquals("\u0A17\u0A08", t("gyi")) // ਗਈ
        assertEquals("\u0A2A\u0A0F", t("pye")) // ਪਏ
        assertEquals("\u0A26\u0A41\u0A28\u0A40\u0A06", t("duniya")) // ਦੁਨੀਆ
        assertEquals("\u0A15\u0A41\u0A5C\u0A40\u0A06\u0A02", t("kuRiyan")) // ਕੁੜੀਆਂ
        assertEquals("\u0A2A\u0A3F\u0A06\u0A30", t("piyar")) // ਪਿਆਰ
        assertEquals("\u0A39\u0A4B\u0A07\u0A06", t("hoya")) // ਹੋਇਆ
        assertEquals("\u0A17\u0A08", t("gayi")) // ਗਈ
        assertEquals("\u0A09\u0A26\u0A2F\u0A4B\u0A17", t("udyog")) // ਉਦਯੋਗ
    }

    @Test fun auBeforeNIsKannaAunkar() {
        // Rule 4: "au" before "n" + d or a final vowel is ਾਉ.
        assertEquals("\u0A06\u0A09\u0A02\u0A26\u0A3E", t("aunda")) // ਆਉਂਦਾ
        assertEquals("\u0A32\u0A3E\u0A09\u0A23\u0A3E", t("launa")) // ਲਾਉਣਾ
    }

    @Test fun nBeforeAFinalVowelIsNna() {
        // Rule 5: ਣ before a final vowel, except after ਰ, the dentals, and ਸ before -e.
        assertEquals("\u0A1C\u0A3E\u0A23\u0A3E", t("jaana")) // ਜਾਣਾ
        assertEquals("\u0A26\u0A47\u0A16\u0A23\u0A3E", t("dekhna")) // ਦੇਖਣਾ
        assertEquals("\u0A15\u0A39\u0A3F\u0A23\u0A3E", t("kehna")) // ਕਹਿਣਾ
        assertEquals("\u0A15\u0A30\u0A28\u0A3E", t("karna")) // ਕਰਨਾ
        assertEquals("\u0A09\u0A38\u0A28\u0A47", t("usne")) // ਉਸਨੇ
    }

    @Test fun wordFinalN() {
        // Rule 6: a word-final "n" is the nasal after a long vowel, ਾਂ in a plural, ਣ in hun and kaun.
        assertEquals("\u0A24\u0A4B\u0A02", t("ton")) // ਤੋਂ
        assertEquals("\u0A39\u0A3E\u0A02", t("haan")) // ਹਾਂ
        assertEquals("\u0A17\u0A71\u0A32\u0A3E\u0A02", t("gallan")) // ਗੱਲਾਂ
        assertEquals("\u0A39\u0A41\u0A23", t("hun")) // ਹੁਣ
        assertEquals("\u0A15\u0A3F\u0A09\u0A02", t("kiun")) // ਕਿਉਂ
        assertEquals("\u0A15\u0A4C\u0A23", t("kaun")) // ਕੌਣ
    }

    @Test fun finalNuIsTheDative() {
        // Rule 7: a final "nu" is the dative ਨੂੰ.
        assertEquals("\u0A2E\u0A48\u0A28\u0A42\u0A70", t("mainu")) // ਮੈਨੂੰ
        assertEquals("\u0A09\u0A39\u0A28\u0A42\u0A70", t("ohnu")) // ਉਹਨੂੰ
    }

    @Test fun variantsOfferTheOtherReadings() {
        // The literal reading first, then the long inner "a", then the short final vowel.
        assertEquals(listOf("\u0A15\u0A30\u0A28\u0A3E", "\u0A15\u0A3E\u0A30\u0A28\u0A3E", "\u0A15\u0A30\u0A28"), engine.variants("karna")) // ਕਰਨਾ ਕਾਰਨਾ ਕਰਨ
    }
}
