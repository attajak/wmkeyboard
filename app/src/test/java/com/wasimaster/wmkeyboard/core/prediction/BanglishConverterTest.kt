package com.wasimaster.wmkeyboard.core.prediction

import com.wasimaster.wmkeyboard.core.transliteration.BengaliPhoneticIndex
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BanglishConverterTest {

    private val map = SpellingMap.load(
        """
        tmr	তোমার
        tomar	তোমার
        tumar	তোমার
        ki	কি
        ki	কী
        valo	ভালো
        bhalo	ভালো
        valoo	ভালো
        school	স্কুল
        phone	ফোন
        phn	ফোন
        thankss	ধন্যবাদ
        dhonnobad	ধন্যবাদ
        amk	আমাকে
        monehoy	মনে হয়
        """.trimIndent().byteInputStream(Charsets.UTF_8),
    )

    private val index = BengaliPhoneticIndex(
        listOf(
            "আছি" to 6900, "আসি" to 2300, "থাকে" to 1750, "তাকে" to 4850, "ঠিক" to 4000,
            "আমি" to 9000, "কেমন" to 3000, "আছো" to 2000, "হল" to 1200, "হলো" to 1500,
            "ভালো" to 6500, "ভাল" to 1900,
        ),
    )

    private val converter = BanglishConverter(map, index)

    @Test
    fun `the spelling map wins, whatever the case`() {
        assertEquals("তোমার", converter.toBengali("tmr"))
        assertEquals("তোমার", converter.toBengali("Tmr"))
        assertEquals("তোমার", converter.toBengali("TMR"))
    }

    @Test
    fun `the index absorbs a dialect spelling when clearly commoner, the literal stands otherwise`() {
        assertEquals("আছি", converter.toBengali("asi"))
        assertEquals("থাকে", converter.toBengali("thake"))
        assertEquals("ঠিক", converter.toBengali("tik"))
        assertEquals("হল", converter.toBengali("holo"))
    }

    @Test
    fun `whole sentences, with digits and punctuation left alone`() {
        assertEquals("আমি ভালো আছি", converter.toBengali("ami valo asi"))
        assertEquals("কেমন আছো?", converter.toBengali("kemon acho?"))
        assertEquals("নদী", converter.toBengali("nodI"))
        val out = converter.toBengali("ami 2024 e jabo.")!!
        assertTrue(out, out.startsWith("আমি 2024 ") && out.endsWith("."))
        assertEquals("আমি ভালো", converter.toBengali("আমি valo"))
    }

    @Test
    fun `nothing latin means nothing to do`() {
        assertNull(converter.toBengali("2024"))
        assertNull(converter.toBengali("আমি"))
        assertNull(converter.toBengali(""))
    }

    @Test
    fun `back to banglish takes the readable map spelling, then the rules`() {
        assertEquals("tomar", converter.toBanglish("তোমার"))
        assertEquals("bhalo", converter.toBanglish("ভালো"))
        assertEquals("phone", converter.toBanglish("ফোন"))
        assertEquals("school", converter.toBanglish("স্কুল"))
        assertEquals("dhonnobad", converter.toBanglish("ধন্যবাদ"))
        assertEquals("ki", converter.toBanglish("কী"))
        assertEquals("amake", converter.toBanglish("আমাকে"))
    }

    @Test
    fun `back to banglish over a sentence`() {
        assertEquals("ami bhalo achhi", converter.toBanglish("আমি ভালো আছি"))
        assertEquals("ami OK achhi!", converter.toBanglish("আমি OK আছি!"))
        assertEquals("2024 sale", converter.toBanglish("২০২৪ সালে"))
        assertEquals("mone hoy", converter.toBanglish("মনে হয়"))
        assertEquals("pora.", converter.toBanglish("পড়া।"))
        assertNull(converter.toBanglish("hello"))
    }
}
