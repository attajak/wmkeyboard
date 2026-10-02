package com.wasimaster.wmkeyboard.core.input.composer

import com.wasimaster.wmkeyboard.core.script.ComposerType
import com.wasimaster.wmkeyboard.core.script.ScriptId
import com.wasimaster.wmkeyboard.core.script.ScriptRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM Unit tests for Vietnamese Telex and VNI transliteration engine.
 * Fast local test execution without requiring ADB or Android devices.
 */
class VietnameseComposerTest {

    private val latinScript = ScriptRegistry[ScriptId.LATIN]

    @Test
    fun factoryMapsTelexAndVniComposers() {
        val telex = composerFor(latinScript, ComposerType.TELEX)
        val vni = composerFor(latinScript, ComposerType.VNI)

        assertNotNull(telex)
        assertTrue(telex is VietnameseTelexComposer)
        assertTrue(telex.isTransliterating)

        assertNotNull(vni)
        assertTrue(vni is VietnameseVniComposer)
        assertTrue(vni.isTransliterating)
        assertTrue(vni.bufferDigits)
    }

    // --- Telex Tests ---

    @Test
    fun telexBasicTones() {
        val c = VietnameseTelexComposer
        assertEquals("á", c.composeBuffer("as"))
        assertEquals("à", c.composeBuffer("af"))
        assertEquals("ả", c.composeBuffer("ar"))
        assertEquals("ã", c.composeBuffer("ax"))
        assertEquals("ạ", c.composeBuffer("aj"))
    }

    @Test
    fun telexLetterMarks() {
        val c = VietnameseTelexComposer
        assertEquals("â", c.composeBuffer("aa"))
        assertEquals("ă", c.composeBuffer("aw"))
        assertEquals("ê", c.composeBuffer("ee"))
        assertEquals("ô", c.composeBuffer("oo"))
        assertEquals("ơ", c.composeBuffer("ow"))
        assertEquals("ư", c.composeBuffer("uw"))
        assertEquals("ư", c.composeBuffer("w"))
        assertEquals("đ", c.composeBuffer("dd"))
    }

    @Test
    fun telexFullSyllables() {
        val c = VietnameseTelexComposer
        assertEquals("việt", c.composeBuffer("vieejt"))
        assertEquals("tiếng", c.composeBuffer("tieengs"))
        assertEquals("đây", c.composeBuffer("ddaay"))
        assertEquals("nước", c.composeBuffer("nuocsw"))
        assertEquals("quả", c.composeBuffer("quar"))
    }

    @Test
    fun telexToneAndMarkCancellation() {
        val c = VietnameseTelexComposer
        assertEquals("as", c.composeBuffer("ass"))
        assertEquals("af", c.composeBuffer("aff"))
        assertEquals("dd", c.composeBuffer("ddd"))
        assertEquals("aa", c.composeBuffer("aaa"))
        assertEquals("ee", c.composeBuffer("eee"))
        assertEquals("oo", c.composeBuffer("ooo"))
    }

    @Test
    fun telexCapitalization() {
        val c = VietnameseTelexComposer
        assertEquals("Việt", c.composeBuffer("Vieejt"))
        assertEquals("Tiếng", c.composeBuffer("Tieengs"))
        assertEquals("ĐÂY", c.composeBuffer("DDAAY"))
    }

    @Test
    fun telexToneNeedsOneUnbrokenVowelRun() {
        val c = VietnameseTelexComposer
        // A Vietnamese syllable has exactly one vowel nucleus, so a tone key
        // after a broken run is the letter it is drawn as.
        assertEquals("bananas", c.composeBuffer("bananas"))
        assertEquals("relax", c.composeBuffer("relax"))
        assertEquals("inbox", c.composeBuffer("inbox"))
        // The rule catches nothing real: every syllable keeps its vowels
        // together, however many of them there are.
        assertEquals("nguyễn", c.composeBuffer("nguyeenx"))
        assertEquals("khuỷu", c.composeBuffer("khuyur"))
        assertEquals("ngoèo", c.composeBuffer("ngoeof"))
    }

    @Test
    fun telexSecondWTakesTheMarkOffAndTypesTheLetter() {
        val c = VietnameseTelexComposer
        assertEquals("row", c.composeBuffer("roww"))
        assertEquals("draw", c.composeBuffer("draww"))
        assertEquals("show", c.composeBuffer("showw"))
        assertEquals("flow", c.composeBuffer("floww"))
        assertEquals("ow", c.composeBuffer("oww"))
        assertEquals("uw", c.composeBuffer("uww"))
        // A w typed on its own made the ư, so a second one gives the w back (#467).
        assertEquals("w", c.composeBuffer("ww"))
        assertEquals("W", c.composeBuffer("Ww"))
        assertEquals("why", c.composeBuffer("wwhy"))
        // English behind a leading w: nothing Vietnamese goes from ư to these.
        assertEquals("why", c.composeBuffer("why"))
        assertEquals("when", c.composeBuffer("when"))
        assertEquals("we", c.composeBuffer("we"))
        assertEquals("with", c.composeBuffer("with"))
        // Vietnamese that opens with ư is untouched.
        assertEquals("ưa", c.composeBuffer("wa"))
        assertEquals("ưng", c.composeBuffer("wng"))
        assertEquals("ước", c.composeBuffer("wowcs"))
        assertEquals("ức", c.composeBuffer("wcs"))
        // The uo cluster behaves the same way, both marks at once.
        assertEquals("dương", c.composeBuffer("duongw"))
        assertEquals("đương", c.composeBuffer("dduongw"))
        assertEquals("duongw", c.composeBuffer("duongww"))
    }

    @Test
    fun telexTakesToneMarksTypedAsThemselves() {
        val c = VietnameseTelexComposer
        assertEquals("cháo", c.composeBuffer("chao\u0301"))
        assertEquals("chào", c.composeBuffer("chao\u0300"))
        assertEquals("chảo", c.composeBuffer("chao\u0309"))
        assertEquals("chão", c.composeBuffer("chao\u0303"))
        assertEquals("chạo", c.composeBuffer("chao\u0323"))
        // The key's faces are drawn on a dotted circle, which is swallowed —
        // so the ring's bare circle is its "no tone" entry.
        assertEquals("cháo", c.composeBuffer("chao\u25CC\u0301"))
        assertEquals("chao", c.composeBuffer("chaos\u25CC"))
        // Named outright, a tone does not toggle the way a letter key does.
        assertEquals("cháo", c.composeBuffer("chao\u0301\u0301"))
        // And it still needs a nucleus to land on.
        assertEquals("bcd", c.composeBuffer("bcd\u0301"))
    }

    @Test
    fun toneKeyCharactersStayInTheBuffer() {
        for (c in listOf(VietnameseTelexComposer, VietnameseVniComposer)) {
            for (mark in "\u25CC\u0301\u0300\u0309\u0303\u0323") {
                assertTrue(c.toString(), c.buffersChar(mark))
            }
            assertTrue(c.toString(), !c.buffersChar('z'))
        }
    }

    // --- VNI Tests ---

    @Test
    fun vniBasicTones() {
        val c = VietnameseVniComposer
        assertEquals("á", c.composeBuffer("a1"))
        assertEquals("à", c.composeBuffer("a2"))
        assertEquals("ả", c.composeBuffer("a3"))
        assertEquals("ã", c.composeBuffer("a4"))
        assertEquals("ạ", c.composeBuffer("a5"))
        assertEquals("a", c.composeBuffer("a10")) // 0 clears tone
    }

    @Test
    fun vniLetterMarks() {
        val c = VietnameseVniComposer
        assertEquals("â", c.composeBuffer("a6"))
        assertEquals("ơ", c.composeBuffer("o7"))
        assertEquals("ư", c.composeBuffer("u7"))
        assertEquals("ă", c.composeBuffer("a8"))
        assertEquals("đ", c.composeBuffer("d9"))
    }

    @Test
    fun vniFullSyllables() {
        val c = VietnameseVniComposer
        assertEquals("việt", c.composeBuffer("viet65"))
        assertEquals("tiếng", c.composeBuffer("tieng61"))
        assertEquals("đây", c.composeBuffer("d9ay6"))
    }

    @Test
    fun vniToneMarksAndVowelRun() {
        val c = VietnameseVniComposer
        assertEquals("cháo", c.composeBuffer("chao\u0301"))
        assertEquals("chao", c.composeBuffer("chao1\u25CC"))
        assertEquals("banana1", c.composeBuffer("banana1"))
    }

    @Test
    fun vniCapitalization() {
        val c = VietnameseVniComposer
        assertEquals("Việt", c.composeBuffer("Viet65"))
        assertEquals("Tiếng", c.composeBuffer("Tieng61"))
    }
}
