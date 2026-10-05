package com.wasimaster.wmkeyboard.core.transliteration

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Expected outputs generated from the tuned Python prototype the table was ported from. */
class ArabicPhoneticTest {

    private fun t(input: String) = ArabicPhonetic.transliterate(input)

    @Test fun arabizi() {
        assertEquals("\u0642\u0644\u0628\u064A", t("2albi")) // قلبي
        assertEquals("\u0648\u0642\u062A", t("wa2t")) // وقت
        assertEquals("\u0639\u0631\u0628\u064A", t("3arabi")) // عربي
        assertEquals("\u0634\u063A\u0644", t("sho3'l")) // شغل
        assertEquals("\u0636\u0639\u064A\u0641", t("9'a3eef")) // ضعيف
        assertEquals("\u062E\u0628\u0632", t("5obz")) // خبز
        assertEquals("\u062D\u0644\u0648", t("7elw")) // حلو
        assertEquals("\u0642\u0645\u0631", t("8amar")) // قمر
        assertEquals("\u0638\u0647\u0631", t("6'ohr")) // ظهر
        assertEquals("\u0634\u0648", t("chou")) // شو
        assertEquals("\u062C\u062F\u064A\u062F", t("gdeed")) // جديد
        assertEquals("\u0643\u0648\u064A\u0633", t("kwayyes")) // كويس
        assertEquals("\u0645\u062F\u0631\u0633\u0629", t("madrase")) // مدرسة
        assertEquals("\u0627\u0644\u062E\u064A\u0631", t("el5eir")) // الخير
        assertEquals("\u0627\u0644\u064A\u0648\u0645", t("elyoum")) // اليوم
        assertEquals("\u0623\u0646\u0627", t("ana")) // أنا
        assertEquals("\u0623\u062E\u062A\u064A", t("ukhti")) // أختي
        assertEquals("\u0627\u0644\u0633\u0644\u0645", t("essalam")) // السلم
        assertEquals("\u0627\u0644\u062D\u0645\u062F\u0644\u0644\u0647", t("el7amdulillah")) // الحمدلله
        assertEquals("\u0643\u064A\u0641", t("kayf")) // كيف
        assertEquals("2024", t("2024")) // 2024
        assertEquals("\u0639\u0644\u0627 2000", t("3ala 2000")) // علا 2000
    }

    @Test fun aNumberStaysANumber() {
        assertEquals("2024", t("2024"))
        assertEquals("3 7", t("3 7"))
    }

    @Test fun theArticle() {
        // al / el / il before a consonant, and an assimilated sun letter.
        assertTrue(t("elyoum").startsWith("\u0627\u0644"))
        assertTrue(t("essalam").startsWith("\u0627\u0644\u0633"))
        // Too short for an article and a word after it: "ali" opens on the
        // hamza alif, أ, like any word-initial a.
        assertEquals("\u0623\u0644\u064A", t("ali"))
    }

    @Test fun theClosingAIsOfferedAsTaMarbutaAndAlifMaqsura() {
        assertEquals(
            listOf(
                "\u0633\u0639\u0627", "\u0633\u0627\u0639\u0627", "\u0633\u0639\u0629", "\u0633\u0639\u0649",
                "\u0633\u0639", "\u0633\u0627\u0639\u0629", "\u0633\u0627\u0639\u0649", "\u0633\u0627\u0639",
            ),
            ArabicPhonetic.variants("sa3a"),
        )
    }
}
