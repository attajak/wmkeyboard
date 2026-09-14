package com.wasimaster.wmkeyboard.core.selection

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ColourCodesTest {

    @Test
    fun `things that are not colours`() {
        for (text in listOf("#fun", "0x12", "rgb(300,0,0)", "#12345", "#123456789", "rgb(1,2)", "hsl(1,2)", "#123 and more", "red", "", "0x1234567", "hsl(0, 101%, 50%)")) {
            assertNull(text, ColourCodes.parse(text))
        }
    }

    @Test
    fun `every spelling of red reads the same`() {
        for (text in listOf("#f00", "#ff0000", "#F00", "0xFF0000", "rgb(255,0,0)", "rgb(100%, 0%, 0%)", "rgb(255 0 0)", "hsl(0,100%,50%)", "hsl(360, 100%, 50%)", " #ff0000 ")) {
            val colour = ColourCodes.parse(text)!!
            assertEquals(text, Triple(255, 0, 0), Triple(colour.r, colour.g, colour.b))
            assertEquals(text, 1f, colour.alpha, 0f)
        }
    }

    @Test
    fun `alpha in each notation`() {
        assertEquals(0x88 / 255f, ColourCodes.parse("#f008")!!.alpha, 0.001f)
        assertEquals(0x80 / 255f, ColourCodes.parse("#ff000080")!!.alpha, 0.001f)
        assertEquals(0x80 / 255f, ColourCodes.parse("0x80FF0000")!!.alpha, 0.001f)
        assertEquals(0.5f, ColourCodes.parse("rgba(255,0,0,0.5)")!!.alpha, 0f)
        assertEquals(0.5f, ColourCodes.parse("rgb(255 0 0 / 50%)")!!.alpha, 0f)
        assertEquals(0.5f, ColourCodes.parse("hsla(0,100%,50%,0.5)")!!.alpha, 0f)
        assertEquals(255, ColourCodes.parse("0x80FF0000")!!.r)
    }

    @Test
    fun `hsl round trips and renders`() {
        val colour = ColourCodes.parse("#336699")!!
        assertEquals("hsl(210, 50%, 40%)", ColourCodes.render(colour, ColourForm.HSL))
        val back = ColourCodes.parse("hsl(210, 50%, 40%)")!!
        assertEquals("#336699", ColourCodes.render(back, ColourForm.HEX))
        assertEquals("hsl(0, 0%, 100%)", ColourCodes.render(ColourCodes.parse("#fff")!!, ColourForm.HSL))
        assertEquals("hsl(0, 0%, 0%)", ColourCodes.render(ColourCodes.parse("#000")!!, ColourForm.HSL))
        assertEquals("rgb(51, 102, 153)", ColourCodes.render(colour, ColourForm.RGB))
        assertEquals("0x336699", ColourCodes.render(colour, ColourForm.HEX_0X))
        assertEquals("rgba(255, 0, 0, 0.5)", ColourCodes.render(ColourCodes.parse("rgba(255,0,0,0.5)")!!, ColourForm.RGBA))
        assertEquals("#ff000080", ColourCodes.render(ColourCodes.parse("rgba(255,0,0,0.5)")!!, ColourForm.HEX_ALPHA))
    }

    @Test
    fun `the ladder leaves out the current form and the alpha forms without alpha`() {
        val opaque = ColourCodes.parse("#336699")!!
        assertEquals(listOf(ColourForm.HEX_0X, ColourForm.RGB, ColourForm.HSL), ColourCodes.ladder(opaque))
        val translucent = ColourCodes.parse("rgba(255,0,0,0.5)")!!
        assertTrue(ColourForm.RGBA !in ColourCodes.ladder(translucent))
        assertTrue(ColourForm.HSLA in ColourCodes.ladder(translucent))
        assertEquals(6, ColourCodes.ladder(translucent).size)
    }

    @Test
    fun `argb packs for a swatch`() {
        assertEquals(0xFF336699.toInt(), ColourCodes.parse("#336699")!!.argb)
    }
}
