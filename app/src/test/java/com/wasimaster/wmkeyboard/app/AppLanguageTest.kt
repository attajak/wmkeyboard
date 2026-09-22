package com.wasimaster.wmkeyboard.app

import com.wasimaster.wmkeyboard.core.script.DeviceLanguageSignals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AppLanguageTest {

    private val shipped = listOf("en", "ar", "bn", "de", "iw", "in", "no", "zh-CN")

    @Test
    fun `retired resource codes match the modern ones`() {
        assertEquals("he", canonicalLocaleTag("iw"))
        assertEquals("id", canonicalLocaleTag("in"))
        assertEquals("nb", canonicalLocaleTag("no"))
        assertEquals("zh-CN", canonicalLocaleTag("zh_CN"))
        assertEquals("iw", matchAvailable("he", shipped))
        assertEquals("in", matchAvailable("id-ID", shipped))
        assertEquals("no", matchAvailable("nb", shipped))
    }

    @Test
    fun `a region the build lacks falls back to the language`() {
        assertEquals("bn", matchAvailable("bn-BD", shipped))
        assertEquals("zh-CN", matchAvailable("zh-TW", shipped))
        assertNull(matchAvailable("ja", shipped))
    }

    @Test
    fun `asks only outside English-first regions`() {
        assertTrue(shouldAskAppLanguage(DeviceLanguageSignals(regionCodes = listOf("BD")), shipped))
        assertTrue(shouldAskAppLanguage(DeviceLanguageSignals(regionCodes = listOf("in")), shipped))
        assertFalse(shouldAskAppLanguage(DeviceLanguageSignals(regionCodes = listOf("US")), shipped))
        assertFalse(shouldAskAppLanguage(DeviceLanguageSignals(regionCodes = listOf("GB", "BD")), shipped))
    }

    @Test
    fun `never asks with nothing to go on or nothing to offer`() {
        assertFalse(shouldAskAppLanguage(DeviceLanguageSignals(), shipped))
        assertFalse(shouldAskAppLanguage(DeviceLanguageSignals(regionCodes = listOf("BD")), listOf("en")))
    }

    @Test
    fun `suggestions follow the region and skip English`() {
        val signals = DeviceLanguageSignals(systemLocales = listOf("en-US"), regionCodes = listOf("BD"))
        assertEquals(listOf("bn"), suggestedAppLanguages(signals, shipped))
    }

    @Test
    fun `system languages come before the region's`() {
        val signals = DeviceLanguageSignals(systemLocales = listOf("de-DE"), regionCodes = listOf("SA"))
        assertEquals(listOf("de", "ar"), suggestedAppLanguages(signals, shipped))
    }
}
