package com.wasimaster.wmkeyboard.core.media

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64

/**
 * Taking an animated WebP apart, against one this app's own writer made and
 * one an encoder that tries harder made.
 *
 * The second is the one that matters. [AnimatedWebpWriter] only ever writes
 * whole-canvas frames that replace what was there, and a reader tested against
 * that alone would never meet an offset or a blended frame, which is what a
 * sticker saved from a provider is full of.
 */
class AnimatedWebpReaderTest {

    private val lossyAlpha = Base64.getDecoder().decode(
        "UklGRrIAAABXRUJQVlA4WAoAAAAQAAAADwAADwAAQUxQSCAAAAABUNC2DVP+pLt/CUTEBOAoSVaYJiwOsfwDFQwh" +
            "Q9HhKFZQOCBsAAAA0AMAnQEqEAAQAAKAQiWwAnS6AUQDYANgA3wDrAPQA8oD2APJVCVAAP7vdtdpSxFtQqx3wUfd" +
            "XFfyEW/5iNxIahb/5ov9y6QRBQzQyPw5mYdLahgSPyL4c/G2ltalJOjF5C2ZK2aAhmZ6IAAA",
    )

    private val lossless = Base64.getDecoder().decode(
        "UklGRjgAAABXRUJQVlA4TCwAAAAvD8ADEA8QMf+DHAxFkaRGU4UAJKwUrCOJ55VnRET0PwDSylutF3+rVZ4GAA==",
    )

    /**
     * 24×24, three lossless frames, from libwebp's own animation encoder with
     * size minimising on: the whole canvas red, then an 8×8 green square at
     * (4, 4) blended over it, then the whole canvas again, replacing it.
     */
    private val optimised = Base64.getDecoder().decode(
        "UklGRsYAAABXRUJQVlA4WAoAAAASAAAAFwAAFwAAQU5JTQYAAAAAAAAAAABBTk1GKAAAAAAAAAAAABcAABcAAEYA" +
            "AAJWUDhMDwAAAC8XwAUABxD9j/4HIqL/AQBBTk1GKAAAAAIAAAIAAAcAAAcAAFoAAABWUDhMDwAAAC8HwAEAB9D/" +
            "iP4HIqL/AQBBTk1GOgAAAAAAAAAAABcAABcAAJYAAAJWUDhMIgAAAC8XwAUQDxAx//MfDIJsmxrc3/IUzxEi+j8O" +
            "AAAAAKA5zRo=",
    )

    @Test
    fun `what the writer wrapped comes back out as the stills that went in`() {
        val writer = AnimatedWebpWriter(16, 16)
        writer.addFrame(lossyAlpha, 80)
        writer.addFrame(lossless, 120)
        val animation = AnimatedWebpReader.read(writer.build())!!

        assertEquals(16, animation.width)
        assertEquals(16, animation.height)
        assertEquals(listOf(80, 120), animation.frames.map { it.durationMs })
        // Byte for byte: the lossy one gets back the extended header that says
        // it has an alpha plane, the lossless one needs none.
        assertArrayEquals(lossyAlpha, animation.frames[0].still)
        assertArrayEquals(lossless, animation.frames[1].still)
        for (frame in animation.frames) {
            assertEquals(0, frame.x)
            assertEquals(0, frame.y)
            assertEquals(16, frame.width)
            assertFalse("the writer's frames replace", frame.blend)
            assertFalse(frame.disposeToBackground)
        }
    }

    @Test
    fun `offsets, sizes and blending are read from a file that uses them`() {
        val animation = AnimatedWebpReader.read(optimised)!!
        assertEquals(24, animation.width)
        assertEquals(listOf(70, 90, 150), animation.frames.map { it.durationMs })

        val square = animation.frames[1]
        assertEquals("offsets are stored halved", 4, square.x)
        assertEquals(4, square.y)
        assertEquals(8, square.width)
        assertEquals(8, square.height)
        assertTrue(square.blend)

        assertFalse(animation.frames[0].blend)
        assertFalse(animation.frames[2].blend)
        assertEquals(24, animation.frames[2].width)
    }

    @Test
    fun `each still is a whole WebP file of its own`() {
        for (frame in AnimatedWebpReader.read(optimised)!!.frames) {
            val still = frame.still
            assertEquals("RIFF", String(still, 0, 4, Charsets.US_ASCII))
            assertEquals("WEBP", String(still, 8, 4, Charsets.US_ASCII))
            val declared = (0 until 4).sumOf { (still[4 + it].toInt() and 0xFF) shl (8 * it) }
            assertEquals(still.size - 8, declared)
            assertEquals(0, still.size % 2)
        }
    }

    @Test
    fun `a still, another format and a file cut short are not animations`() {
        assertFalse(AnimatedWebpReader.isAnimated(lossless))
        assertFalse(AnimatedWebpReader.isAnimated(lossyAlpha))
        assertFalse(AnimatedWebpReader.isAnimated("GIF89a".toByteArray()))
        assertFalse(AnimatedWebpReader.isAnimated(ByteArray(0)))
        assertNull(AnimatedWebpReader.read(lossless))

        assertTrue(AnimatedWebpReader.isAnimated(optimised))
        assertNotNull(AnimatedWebpReader.read(optimised))
        // The header still says animated; the frames no longer add up.
        assertNull(AnimatedWebpReader.read(optimised.copyOf(optimised.size - 20)))
    }
}
