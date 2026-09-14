package com.wasimaster.wmkeyboard.core.transliteration

import org.junit.Assert.assertEquals
import org.junit.Test

class BengaliRomanizerTest {

    private fun check(vararg pairs: Pair<String, String>) {
        for ((bengali, latin) in pairs) assertEquals(bengali, latin, BengaliRomanizer.romanize(bengali))
    }

    @Test
    fun everydayWords() = check(
        "কাল" to "kal", "ভালো" to "bhalo", "আমার" to "amar", "তোমার" to "tomar", "কী" to "ki", "কি" to "ki",
        "বাংলা" to "bangla", "ধন্যবাদ" to "dhonnobad", "শুভ" to "shubho", "রাত" to "rat", "কষ্ট" to "koshto",
        "স্কুল" to "skul", "বন্ধু" to "bondhu", "বৃষ্টি" to "brishti", "পড়া" to "pora",
    )

    @Test
    fun sibilantsAndAffricates() = check(
        "সব" to "sob", "শুধু" to "shudhu", "ষাট" to "shat", "চা" to "cha", "ছবি" to "chhobi", "আছি" to "achhi",
    )

    @Test
    fun inherentVowel() = check(
        "মন" to "mon", "কলম" to "kolom", "একটা" to "ekta", "বলছি" to "bolchhi", "কলকাতা" to "kolkata",
        "কেমন" to "kemon", "অনেক" to "onek", "প্রথম" to "prothom", "মানুষ" to "manush",
        "বাংলাদেশ" to "bangladesh", "ভাল" to "bhal", "দেহ" to "deho", "বড়" to "boro",
    )

    @Test
    fun clusters() = check(
        "ধর্ম" to "dhormo", "কর্ম" to "kormo", "স্বপ্ন" to "swopno", "সত্য" to "sotto", "অন্য" to "onno",
        "সমস্যা" to "somossa", "ব্যাপার" to "byapar", "ব্যবহার" to "byabohar", "বিশ্ব" to "bissho",
        "ক্ষমা" to "khoma", "লক্ষ" to "lokkho", "জ্ঞান" to "ggan", "বঙ্গ" to "bongo", "পাঞ্জাবি" to "panjabi",
        "স্বাগতম" to "swagotom", "হঠাৎ" to "hothat",
    )

    @Test
    fun vowelsAndGlides() = check(
        "হয়" to "hoy", "মেয়ে" to "meye", "ভাই" to "bhai", "বই" to "boi", "দুই" to "dui",
        "খাওয়া" to "khaoya", "ওকে!" to "oke!",
    )

    @Test
    fun signs() = check("চাঁদ" to "chand", "রং" to "rong", "দুঃখ" to "dukkho", "কিংবা" to "kingba")

    @Test
    fun digitsPunctuationAndPassthrough() = check(
        "২০২৪" to "2024", "আমি ২টা বাজে আসি।" to "ami 2ta baje asi.", "hello আমি" to "hello ami",
        "" to "", "abc 123" to "abc 123",
    )

    @Test
    fun decomposedNuktaReadsLikePrecomposed() {
        val decomposed = "পড়া"
        val precomposed = "পড়া"
        assertEquals("pora", BengaliRomanizer.romanize(decomposed))
        assertEquals("pora", BengaliRomanizer.romanize(precomposed))
        assertEquals("y", BengaliRomanizer.romanize("য়"))
        assertEquals(precomposed, BengaliRomanizer.normalize(decomposed))
    }
}
