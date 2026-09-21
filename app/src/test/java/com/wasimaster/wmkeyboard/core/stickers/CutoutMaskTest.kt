package com.wasimaster.wmkeyboard.core.stickers

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CutoutMaskTest {

    private fun argb(r: Int, g: Int, b: Int) = (0xFF shl 24) or (r shl 16) or (g shl 8) or b

    @Test
    fun `pixels come out as r g b in that order, in ImageNet units`() {
        val out = CutoutMask.normalise(intArrayOf(argb(255, 0, 0)))
        assertEquals((1f - 0.485f) / 0.229f, out[0], 1e-5f)
        assertEquals((0f - 0.456f) / 0.224f, out[1], 1e-5f)
        assertEquals((0f - 0.406f) / 0.225f, out[2], 1e-5f)
    }

    @Test
    fun `a dark picture is scaled by its own brightest value`() {
        // The brightest channel anywhere is 100, so 100 reads as full scale.
        val out = CutoutMask.normalise(intArrayOf(argb(100, 50, 0), argb(0, 0, 0)))
        assertEquals((1f - 0.485f) / 0.229f, out[0], 1e-5f)
        assertEquals((0.5f - 0.456f) / 0.224f, out[1], 1e-5f)
    }

    @Test
    fun `an all black picture does not divide by zero`() {
        val out = CutoutMask.normalise(intArrayOf(argb(0, 0, 0)))
        assertTrue(out.all { it.isFinite() })
    }

    @Test
    fun `the map is stretched to its own range`() {
        // Tops out at 0.6, as a low-contrast picture does; that is still "keep".
        val alpha = CutoutMask.alphaOf(floatArrayOf(0f, 0.6f))
        assertArrayEquals(intArrayOf(0, 255), alpha)
    }

    @Test
    fun `faint residue is dropped and the middle of the edge stays soft`() {
        val alpha = CutoutMask.alphaOf(floatArrayOf(0f, 0.2f, 0.5f, 0.8f, 1f))
        assertEquals(0, alpha[1])
        assertTrue(alpha[2] in 120..135)
        assertEquals(255, alpha[3])
    }

    @Test
    fun `a flat map keeps nothing`() {
        assertArrayEquals(IntArray(3), CutoutMask.alphaOf(floatArrayOf(0.4f, 0.4f, 0.4f)))
        assertArrayEquals(IntArray(0), CutoutMask.alphaOf(FloatArray(0)))
    }

    @Test
    fun `the pinned digest is a sha-256`() {
        assertTrue(Regex("[0-9a-f]{64}").matches(CutoutModel.SHA256))
    }
}
