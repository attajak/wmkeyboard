package com.wasimaster.wmkeyboard.core.stickers

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream

/**
 * Telling an animated PNG from a still one, which is the half of [ApngFrames]
 * that needs no device. It decides whether a picture is walked frame by frame
 * or decoded once, so a wrong answer either flattens an animation or sends a
 * plain PNG down the slow path.
 */
class ApngFramesTest {

    private val magic = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)

    private fun chunk(type: String, data: ByteArray = ByteArray(0)): ByteArray {
        val out = ByteArrayOutputStream()
        for (shift in listOf(24, 16, 8, 0)) out.write(data.size shr shift)
        out.write(type.toByteArray(Charsets.US_ASCII))
        out.write(data)
        out.write(ByteArray(4)) // CRC, which nothing here checks
        return out.toByteArray()
    }

    private fun actl(frames: Int): ByteArray =
        chunk("acTL", byteArrayOf(0, 0, (frames shr 8).toByte(), frames.toByte(), 0, 0, 0, 0))

    private val ihdr = chunk("IHDR", ByteArray(13))

    @Test
    fun `an acTL chunk before the pixels makes it animated, and says how many frames`() {
        val apng = magic + ihdr + actl(24) + chunk("fcTL", ByteArray(26)) + chunk("IDAT", ByteArray(9)) + chunk("IEND")
        assertEquals(24, ApngFrames.frameCount(apng))
        assertTrue(ApngFrames.isAnimated(apng))
    }

    @Test
    fun `a plain PNG is a still`() {
        val png = magic + ihdr + chunk("IDAT", ByteArray(9)) + chunk("IEND")
        assertEquals(0, ApngFrames.frameCount(png))
        assertFalse(ApngFrames.isAnimated(png))
    }

    @Test
    fun `an acTL after the pixels does not count`() {
        // The specification puts it before the first IDAT. A decoder that has
        // already seen pixels treats the file as a still, and so does this.
        val late = magic + ihdr + chunk("IDAT", ByteArray(9)) + actl(24) + chunk("IEND")
        assertFalse(ApngFrames.isAnimated(late))
    }

    @Test
    fun `one frame is a still that happens to be an APNG`() {
        val single = magic + ihdr + actl(1) + chunk("IDAT", ByteArray(9)) + chunk("IEND")
        assertEquals(1, ApngFrames.frameCount(single))
        assertFalse(ApngFrames.isAnimated(single))
    }

    @Test
    fun `other formats and broken files are not animated PNGs`() {
        assertFalse(ApngFrames.isAnimated(ByteArray(0)))
        assertFalse(ApngFrames.isAnimated("GIF89a".toByteArray()))
        assertFalse(ApngFrames.isAnimated("RIFF0000WEBPVP8X".toByteArray()))
        // A chunk length that points past the end of the file, and one that
        // is negative as a signed int.
        assertFalse(ApngFrames.isAnimated(magic + byteArrayOf(0x7F, 0x7F, 0x7F, 0x7F) + "junk".toByteArray()))
        assertFalse(ApngFrames.isAnimated(magic + byteArrayOf(-1, -1, -1, -1) + "junk".toByteArray()))
        // Cut off inside the acTL chunk.
        val cut = magic + ihdr + actl(24)
        assertFalse(ApngFrames.isAnimated(cut.copyOf(cut.size - 10)))
    }
}
