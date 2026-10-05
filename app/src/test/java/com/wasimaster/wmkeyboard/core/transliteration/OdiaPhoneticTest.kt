package com.wasimaster.wmkeyboard.core.transliteration

import org.junit.Assert.assertEquals
import org.junit.Test

class OdiaPhoneticTest {

    private val engine = IndicPhonetic(OdiaPhonetic.PROFILE)

    private fun t(s: String) = engine.transliterate(s)

    @Test fun plainWordsAndSentences() {
        // Every vowel typed; the inherent vowel is "a", "o" is ୋ, and a final consonant stays bare.
        assertEquals("\u0B2D\u0B32", t("bhala")) // ଭଲ
        assertEquals("\u0B18\u0B30", t("ghara")) // ଘର
        assertEquals("\u0B2E\u0B4B", t("mo")) // ମୋ
        assertEquals("\u0B2E\u0B41 \u0B2D\u0B32 \u0B05\u0B1B\u0B3F", t("mu bhala achi")) // ମୁ ଭଲ ଅଛି
        assertEquals("\u0B2C\u0B5C", t("baRa")) // ବଡ଼
    }

    @Test fun aiAndAuAreTwoVowels() {
        // Rule 1: ai and au are a long aa and then i or u, not ୈ or ୌ.
        assertEquals("\u0B2D\u0B3E\u0B07", t("bhai")) // ଭାଇ
        assertEquals("\u0B2A\u0B3E\u0B07", t("pai")) // ପାଇ
        assertEquals("\u0B16\u0B3E\u0B07\u0B2C\u0B3E", t("khaiba")) // ଖାଇବା
        assertEquals("\u0B1C\u0B3E\u0B09\u0B1B\u0B3F", t("jauchi")) // ଜାଉଛି
    }

    @Test fun aVowelThenAIsLongAa() {
        // Rule 2: an "a" right after a vowel is ଆ, not ଅ.
        assertEquals("\u0B13\u0B5C\u0B3F\u0B06", t("oRia")) // ଓଡ଼ିଆ
        assertEquals("\u0B28\u0B41\u0B06", t("nua")) // ନୁଆ
        assertEquals("\u0B15\u0B3E\u0B07\u0B06", t("kaia")) // କାଇଆ
    }

    @Test fun consonantRuIsTheVowelSign() {
        // Rule 3: consonant + "ru" is ୃ.
        assertEquals("\u0B15\u0B43\u0B37\u0B4D\u0B23", t("krushna")) // କୃଷ୍ଣ
        assertEquals("\u0B2A\u0B43\u0B25\u0B3F\u0B2C\u0B3F", t("pruthibi")) // ପୃଥିବି
    }

    @Test fun shBeforeTThNIsRetroflex() {
        // Rule 4: sh before t, th or n is the retroflex cluster.
        assertEquals("\u0B15\u0B37\u0B4D\u0B1F", t("kashta")) // କଷ୍ଟ
        assertEquals("\u0B26\u0B43\u0B37\u0B4D\u0B1F\u0B3F", t("drushti")) // ଦୃଷ୍ଟି
        assertEquals("\u0B2C\u0B3F\u0B37\u0B4D\u0B23\u0B41", t("bishnu")) // ବିଷ୍ଣୁ
    }

    @Test fun aNasalTakesThePlaceOfItsStop() {
        // Rule 5: a conjunct before a stop, the anusvara before s, sh, r or l, and nh as ହ୍ନ.
        assertEquals("\u0B32\u0B19\u0B4D\u0B15", t("lanka")) // ଲଙ୍କ
        assertEquals("\u0B2A\u0B1E\u0B4D\u0B1A", t("pancha")) // ପଞ୍ଚ
        assertEquals("\u0B18\u0B23\u0B4D\u0B1F", t("ghanTa")) // ଘଣ୍ଟ
        assertEquals("\u0B38\u0B02\u0B38\u0B30", t("sansara")) // ସଂସର
        assertEquals("\u0B05\u0B02\u0B36", t("ansha")) // ଅଂଶ
        assertEquals("\u0B1C\u0B39\u0B4D\u0B28", t("janha")) // ଜହ୍ନ
        assertEquals("\u0B1A\u0B3F\u0B39\u0B4D\u0B28", t("chinha")) // ଚିହ୍ନ
    }

    @Test fun chInTheCopulaAndEndingsIsChha() {
        // Rule 6: ch between a vowel and a closing i, u, e or a, or before "ant", is ଛ; opening a word it stays ଚ.
        assertEquals("\u0B05\u0B1B\u0B3F", t("achi")) // ଅଛି
        assertEquals("\u0B15\u0B30\u0B41\u0B1B\u0B3F", t("karuchi")) // କରୁଛି
        assertEquals("\u0B05\u0B1B\u0B28\u0B4D\u0B24\u0B3F", t("achanti")) // ଅଛନ୍ତି
        assertEquals("\u0B17\u0B1B", t("gacha")) // ଗଛ
        assertEquals("\u0B1A\u0B32", t("chala")) // ଚଲ
    }

    @Test fun ibaIlaElaCloseOnALongAa() {
        // Rule 7: -iba, -ila and -ela close on a long aa; -iba keeps it before ku, re and ra.
        assertEquals("\u0B15\u0B30\u0B3F\u0B2C\u0B3E", t("kariba")) // କରିବା
        assertEquals("\u0B25\u0B3F\u0B32\u0B3E", t("thila")) // ଥିଲା
        assertEquals("\u0B39\u0B47\u0B32\u0B3E", t("hela")) // ହେଲା
        assertEquals("\u0B15\u0B30\u0B3F\u0B2C\u0B3E\u0B15\u0B41", t("karibaku")) // କରିବାକୁ
    }

    @Test fun vIsBaExceptAfterAConsonant() {
        // Rule 8: v is ବ, subjoined ୱ after a consonant; w is always ୱ.
        assertEquals("\u0B38\u0B4D\u0B71\u0B30", t("svara")) // ସ୍ୱର
        assertEquals("\u0B2C\u0B47\u0B26", t("veda")) // ବେଦ
        assertEquals("\u0B71\u0B30\u0B3F", t("wari")) // ୱରି
    }

    @Test fun variantsOfferTheLongAs() {
        // The likely reading first, then the closing "a" long, then the last inner "a" long.
        assertEquals(listOf("\u0B18\u0B30", "\u0B18\u0B30\u0B3E", "\u0B18\u0B3E\u0B30"), engine.variants("ghara")) // ଘର, ଘରା, ଘାର
        assertEquals(listOf("\u0B15\u0B30\u0B3F\u0B2C\u0B3E", "\u0B15\u0B3E\u0B30\u0B3F\u0B2C\u0B3E"), engine.variants("kariba")) // କରିବା, କାରିବା
    }
}
