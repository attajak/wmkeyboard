package com.wasimaster.wmkeyboard.core.transliteration

import org.junit.Assert.assertEquals
import org.junit.Test

/** Expected outputs generated from the tuned Python prototype the table was ported from. */
class PersianPhoneticTest {

    private fun t(input: String) = PersianPhonetic.transliterate(input)

    @Test fun finglish() {
        assertEquals("\u062E\u0648\u0628\u0647", t("khoobe")) // خوبه
        assertEquals("\u062F\u06CC\u06AF\u0647", t("dige")) // دیگه
        assertEquals("\u062E\u06CC\u0644\u06CC", t("kheili")) // خیلی
        assertEquals("\u06A9\u062C\u0627\u06CC\u06CC", t("kojai")) // کجایی
        assertEquals("\u0686\u06CC\u0647", t("chiye")) // چیه
        assertEquals("\u0645\u0631\u0633\u06CC", t("merci")) // مرسی
        assertEquals("\u0633\u06CC\u0646\u0645\u0627", t("cinema")) // سینما
        assertEquals("\u062E\u0648\u062F\u0645", t("khodam")) // خودم
        assertEquals("\u062E\u0648\u0634\u06AF\u0644", t("khoshgel")) // خوشگل
        assertEquals("\u0642\u0634\u0646\u06AF", t("ghashang")) // قشنگ
        assertEquals("\u063A\u0632\u0627", t("Ghaza")) // غزا
        assertEquals("\u0627\u0645\u0631\u0648\u0632", t("emrooz")) // امروز
        assertEquals("\u0627\u0648\u0646", t("oon")) // اون
        assertEquals("\u0628\u0686\u0647", t("bache")) // بچه
        assertEquals("\u0647\u0645\u06CC\u0634\u0647", t("hamishe")) // همیشه
        assertEquals("\u0645\u06CC\u200C\u06A9\u0646\u0645", t("mikonam")) // می‌کنم
        assertEquals("\u0646\u0645\u06CC\u200C\u062A\u0648\u0646\u0645", t("nemitoonam")) // نمی‌تونم
        assertEquals("\u0645\u06CC\u200C\u0627\u062F", t("miad")) // می‌اد
        assertEquals("\u0645\u06CC\u0632", t("miz")) // میز
        assertEquals("\u0633\u0644\u0645 \u062E\u0648\u0628\u06CC", t("salam khoobi")) // سلم خوبی
    }

    @Test fun theVerbPrefixIsItsOwnUnit() {
        // می‌ and نمی‌ joined to the stem by a ZWNJ, as the list spells them…
        assertEquals("\u0645\u06CC\u200C\u06A9\u0646\u0645", t("mikonam"))
        // …but not where the stem would be a single token: میز is one word.
        assertEquals("\u0645\u06CC\u0632", t("miz"))
        // And the strip offers the reading without the prefix too: میوه.
        assertEquals(
            listOf("\u0645\u06CC\u200C\u0648\u0647", "\u0645\u06CC\u0648\u0647"),
            PersianPhonetic.variants("mive"),
        )
    }

    @Test fun persianLettersNotArabicOrUrdu() {
        val out = t("ketab ke khane hast")
        // ی ک ه, never ي ك ہ
        assertEquals(false, out.any { it == '\u064A' || it == '\u0643' || it == '\u06C1' })
    }

    @Test fun theLongAIsAVariant() {
        assertEquals(listOf("\u06A9\u062A\u0628", "\u06A9\u062A\u0627\u0628"), PersianPhonetic.variants("ketab"))
    }
}
