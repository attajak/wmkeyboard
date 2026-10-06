package com.wasimaster.wmkeyboard.core.prediction

import com.wasimaster.wmkeyboard.core.transliteration.BengaliPhoneticIndex
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Desktop Avro's own list, out of the shipped `assets/avro/` data. The expected
 * lists are what ibus-avro's `SuggestionBuilder.suggest` returns for the word
 * typed a letter at a time (so every prefix is in its cache, as on a desktop),
 * run in node against the same data.
 */
class AvroDesktopTest {

    /**
     * Avro's dictionary is a download, so the test carries the few of its
     * words these cases need, under the tables Avro keeps them in.
     */
    private val dictionary = """
        @a
        অমার
        অম্বার
        অ্যাম্বার
        @aa
        আমার
        আম্মার
        @bh
        ভদ্র
        ভয়দর
        ভয়ডর
        ভোঁদড়
        ভদর
        @b
        বন্ধু
        বঁধু
        @k
        কথা
        কোথা
        কোঠা
        কোঁথা
        কি
        কই
        @m
        মা
        ম্যা
        মায়্যা
    """.trimIndent()

    private val desktop = AvroDesktop.load(
        open = { File("src/main/assets/avro/$it").inputStream() },
        dictionary = {
            val bytes = java.io.ByteArrayOutputStream()
            java.util.zip.GZIPOutputStream(bytes).use { it.write(dictionary.toByteArray()) }
            bytes.toByteArray().inputStream()
        },
    )

    private fun avro(typed: String): List<String> {
        val s = desktop.suggest(typed, ours = null)
        return (listOfNotNull(s.autocorrect) + s.words + s.phonetic).map(AvroDesktop::precomposed).distinct()
    }

    /**
     * Every word desktop Avro shows is in the list. Avro's data spells য় ড় ঢ়
     * precomposed, and a literal typed here may not be.
     */
    private fun assertEquals(expected: List<String>, actual: List<String>) =
        assertTrue("$expected not all in $actual", actual.containsAll(expected.map(AvroDesktop::precomposed)))

    @Test fun holdsEveryWordDesktopAvroShows() {
        assertEquals(listOf("ভদ্র", "ভয়দর", "ভয়ডর", "ভোঁদড়", "ভদর"), avro("vodor"))
        assertEquals(listOf("বন্ধুদের", "বঁধুদের"), avro("bondhuder"))
        assertEquals(listOf("কথা", "কোথা", "কোঠা", "কোঁথা"), avro("kotha"))
        assertEquals(listOf("আমার", "অমার", "আম্মার", "অম্বার", "অ্যাম্বার"), avro("amar"))
        assertEquals(listOf("মায়ের", "ম্যায়ের", "মায়্যার"), avro("mayer"))
        assertEquals(listOf("কি", "কই"), avro("ki"))
    }

    @Test fun phoneticReadingIsAvros() {
        org.junit.Assert.assertEquals(
            listOf("আমার", "ভালো").map(AvroDesktop::precomposed),
            listOf(desktop.transliterate("amar"), desktop.transliterate("bhalO")).map(AvroDesktop::precomposed),
        )
    }

    /** Words only the keyboard's list has are found by the same search and added, never in place of Avro's. */
    @Test fun ourListAddsToAvros() {
        val ours = BengaliPhoneticIndex(listOf("ভোদর" to 10, "ভদ্র" to 50))
        val backend = PhoneticBackend(PhoneticSchemes.BENGALI, ours)
        val list = PhoneticCandidates.build(backend, "vodor", 100, desktop = desktop).map(AvroDesktop::precomposed)
        assertTrue(list.containsAll(avro("vodor")))
        assertTrue(AvroDesktop.precomposed("ভোদর") in list)
    }
}
