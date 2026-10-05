package com.wasimaster.wmkeyboard.core.transliteration

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * [MarathiPhonetic]'s word hook, one test per rule. Every expected string is
 * the Python prototype's output for the same profile.
 */
class MarathiPhoneticTest {

    private val engine = IndicPhonetic(MarathiPhonetic.PROFILE)

    private fun t(s: String) = engine.transliterate(s)

    @Test fun plainWords() {
        // Words no hook rule touches: the NORTH model as it stands.
        assertEquals("\u0906\u0939\u0947", t("aahe"))
        assertEquals("\u092E\u0940", t("mi"))
        assertEquals("\u0915\u093E\u092F", t("kaay"))
    }

    @Test fun marathiLetters() {
        // L is ळ, z is झ (not ज़), capital R a plain ड.
        assertEquals("\u0915\u0933\u093E", t("kaLa"))
        assertEquals("\u091D\u0932\u093E", t("zala"))
        assertEquals("\u0918\u0921\u0940", t("ghaRi"))
    }

    @Test fun gluedPostpositionsKeepTheirSpelling() {
        // Rule 1.
        assertEquals("\u0918\u0930\u0938\u093E\u0920\u0940", t("gharasathi"))
        assertEquals("\u0918\u0930\u0915\u0921\u0942\u0928", t("gharakadun"))
        assertEquals("\u0918\u0930\u092A\u093E\u0938\u0942\u0928", t("gharapasun"))
        assertEquals("\u0918\u0930\u092E\u0927\u094D\u092F\u0947", t("gharamadhe"))
    }

    @Test fun iOrUAfterAVowelIsLong() {
        // Rule 2.
        assertEquals("\u0939\u094B\u0908\u0932", t("hoil"))
        assertEquals("\u092F\u0947\u090A\u0928", t("yeun"))
    }

    @Test fun shBeforeTIsRetroflex() {
        // Rule 3, with rule 10 keeping the closing a short.
        assertEquals("\u0917\u094B\u0937\u094D\u091F", t("goshta"))
        assertEquals("\u0936\u094D\u0930\u0947\u0937\u094D\u0920", t("shreshtha"))
    }

    @Test fun clustersWrittenAsOneLetter() {
        // Rule 3b: shtr, vh, lh.
        assertEquals("\u092E\u0939\u093E\u0930\u0937\u094D\u091F\u094D\u0930", t("maharashtra"))
        assertEquals("\u0915\u0947\u0935\u094D\u0939\u093E", t("kevha"))
        assertEquals("\u0928\u0935\u094D\u0939\u0924\u093E", t("navhta"))
        assertEquals("\u091C\u093F\u0932\u094D\u0939\u093E", t("jilha"))
    }

    @Test fun mahaPrefixAndTatPlural() {
        // Rules 3c and 3d.
        assertEquals("\u092E\u0939\u093E\u0917", t("mahag"))
        assertEquals("\u0915\u0930\u0924\u093E\u0924", t("kartat"))
    }

    @Test fun absolutiveUnIsLong() {
        // Rule 4.
        assertEquals("\u0915\u0930\u0942\u0928", t("karun"))
        assertEquals("\u092C\u0918\u0942\u0928", t("baghun"))
    }

    @Test fun closingAuIsAaU() {
        // Rule 5.
        assertEquals("\u092D\u093E\u090A", t("bhau"))
        assertEquals("\u091C\u093E\u090A\u0928", t("jaun"))
    }

    @Test fun futureParticipleIsNnaar() {
        // Rule 6: after a consonant stem, a schwa stem, an open stem, and with a vowel after it.
        assertEquals("\u0915\u0930\u0923\u093E\u0930", t("karnar"))
        assertEquals("\u091C\u093E\u0923\u093E\u0930", t("janar"))
        assertEquals("\u092F\u0947\u0923\u093E\u0930", t("yenar"))
        assertEquals("\u0915\u0930\u0923\u093E\u0930\u093E", t("karnara"))
    }

    @Test fun pluralOblique() {
        // Rule 7: all-consonant stem, a stem with a vowel, and the -chya tail.
        assertEquals("\u0924\u094D\u092F\u093E\u0902\u091A\u0940", t("tyanchi"))
        assertEquals("\u0932\u094B\u0915\u093E\u0902\u0928\u093E", t("lokanna"))
        assertEquals("\u092E\u093F\u0924\u094D\u0930\u093E\u0902\u0928\u094B", t("mitranno"))
        assertEquals("\u0924\u094D\u092F\u093E\u0902\u091A\u094D\u092F\u093E", t("tyanchya"))
    }

    @Test fun obliqueYaa() {
        // Rule 8, also after a glued postposition (rule 1).
        assertEquals("\u0924\u094D\u092F\u093E\u0932\u093E", t("tyala"))
        assertEquals("\u0924\u094D\u092F\u093E\u091A\u094D\u092F\u093E", t("tyachya"))
        assertEquals("\u0924\u094D\u092F\u093E\u092E\u0941\u0933\u0947", t("tyamule"))
    }

    @Test fun nounObliqueBeforeGenitive() {
        // Rule 9.
        assertEquals("\u0918\u0930\u093E\u091A\u094D\u092F\u093E", t("gharachya"))
        assertEquals("\u0926\u0947\u0935\u093E\u091A\u093E", t("devacha"))
    }

    @Test fun closingClusterKeepsInherentVowel() {
        // Rule 10.
        assertEquals("\u092E\u093F\u0924\u094D\u0930", t("mitra"))
        assertEquals("\u0926\u0941\u0938\u0930", t("dusra"))
    }

    @Test fun variantsOfferTheOtherReadings() {
        // The likely reading first, then the last inner a long, then every cluster loose.
        assertEquals(
            listOf("\u0930\u0924\u094D\u0930", "\u0930\u093E\u0924\u094D\u0930", "\u0930\u0924\u0930"),
            engine.variants("ratra"),
        )
    }
}
