package com.wasimaster.wmkeyboard.core.ocr

import com.wasimaster.wmkeyboard.core.ocr.OcrThresholding.Method
import java.io.IOException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Picking between the Sauvola and Otsu reads. The texts are what Tesseract
 * 5.5.1 with tessdata_fast 4.1.0 returned for test photos (see
 * native/tesseract-jni/README.md).
 */
class OcrThresholdingTest {

    private fun best(reads: Map<Method, String>): String = runBlocking {
        OcrThresholding.bestRead { reads.getValue(it) }
    }

    @Test
    fun sauvolaIsTriedFirstAndOtsuStillRuns() {
        val asked = mutableListOf<Method>()
        runBlocking { OcrThresholding.bestRead { asked += it; "" } }
        assertEquals(listOf(Method.SAUVOLA, Method.OTSU), asked)
        assertEquals(2, Method.SAUVOLA.tesseractValue)
        assertEquals(0, Method.OTSU.tesseractValue)
    }

    @Test
    fun unevenlyLitPrintKeepsTheSauvolaRead() {
        // Global Otsu blacked out the vignetted half of the photo.
        val sauvola = "Scan this receipt\nTotal due 42.50\nand the small print below it\n"
        val otsu = "mall print below it\n\n"
        assertEquals(sauvola, best(mapOf(Method.SAUVOLA to sauvola, Method.OTSU to otsu)))
    }

    @Test
    fun lightTextOnADarkScreenKeepsTheOtsuRead() {
        val otsu = "Your code is 481 223\nDo not share it\n"
        assertEquals(otsu, best(mapOf(Method.SAUVOLA to "", Method.OTSU to otsu)))
        // Sauvola can leave fragments rather than nothing; more text wins.
        val settings = "Settings\nWi-Fi Bluetooth Display\n"
        assertEquals(settings, best(mapOf(Method.SAUVOLA to "i Bluetooth Displ\n\n", Method.OTSU to settings)))
    }

    @Test
    fun noiseScoresNothing() {
        assertEquals(0, OcrThresholding.score(""))
        assertEquals(0, OcrThresholding.score("— | e ‘ . _"))
        // Noise around real words does not add to them.
        assertEquals(OcrThresholding.score("the lazy dog"), OcrThresholding.score("— the | lazy e dog ."))
        // A garbled read full of stray marks loses to the clean one.
        assertTrue(
            OcrThresholding.score("The quick brown fox jumps over\nthe lazy dog near the river bank.") >
                OcrThresholding.score("— ‘the e lazy dog r near the river ‘bank. |"),
        )
    }

    @Test
    fun banglaVowelSignsAndViramasCountAsPartOfTheWord() {
        // ো, া, ্ are combining marks, not letters.
        assertEquals("ভালোবাসি".codePointCount(0, "ভালোবাসি".length), OcrThresholding.score("ভালোবাসি"))
        val sauvola = "আমার সোনার বাংলা আমি তোমায়\nভালোবাসি চিরদিন তোমার আকাশ\n"
        val otsu = "আমার সোনার বাংলা আমি তোমায়\n\n"
        assertEquals(sauvola, best(mapOf(Method.SAUVOLA to sauvola, Method.OTSU to otsu)))
    }

    @Test
    fun aTieKeepsTheFirstRead() {
        assertEquals("Total due 42.50", best(mapOf(Method.SAUVOLA to "Total due 42.50", Method.OTSU to "Total due 42.50 |")))
    }

    @Test
    fun aLoneCharacterIsStillText() {
        assertEquals("7\n", best(mapOf(Method.SAUVOLA to "", Method.OTSU to "7\n")))
        assertEquals("", best(mapOf(Method.SAUVOLA to "\n", Method.OTSU to "  ")))
    }

    @Test
    fun aFailedReadFailsInsteadOfLookingEmpty() {
        try {
            runBlocking {
                OcrThresholding.bestRead { method ->
                    if (method == Method.OTSU) throw IOException("engine error") else ""
                }
            }
            fail("a failed read must not come back as no text")
        } catch (e: IOException) {
            assertEquals("engine error", e.message)
        }
    }
}
