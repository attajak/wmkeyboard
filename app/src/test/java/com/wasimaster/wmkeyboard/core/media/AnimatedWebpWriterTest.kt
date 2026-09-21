package com.wasimaster.wmkeyboard.core.media

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.Base64

/**
 * The animated-WebP container, against two real stills.
 *
 * The frames' pixels are the platform encoder's business. What is written here
 * is the wrapping, and a decoder is unforgiving about it: a size off by one or
 * a chunk that is not padded to an even length and the file is a broken image
 * with no error to read. So the output is walked chunk by chunk.
 *
 * Set `WM_WEBP_OUT` to a path and the muxed file is also written there, to be
 * looked at with `webpinfo` or a browser.
 */
class AnimatedWebpWriterTest {

    /** 16×16, lossy with alpha: `VP8X`, `ALPH` (32 bytes), `VP8 ` (108 bytes). */
    private val lossyAlpha = Base64.getDecoder().decode(
        "UklGRrIAAABXRUJQVlA4WAoAAAAQAAAADwAADwAAQUxQSCAAAAABUNC2DVP+pLt/CUTEBOAoSVaYJiwOsfwDFQwh" +
            "Q9HhKFZQOCBsAAAA0AMAnQEqEAAQAAKAQiWwAnS6AUQDYANgA3wDrAPQA8oD2APJVCVAAP7vdtdpSxFtQqx3wUfd" +
            "XFfyEW/5iNxIahb/5ov9y6QRBQzQyPw5mYdLahgSPyL4c/G2ltalJOjF5C2ZK2aAhmZ6IAAA",
    )

    /** 16×16, lossless: a bare `VP8L` (44 bytes). */
    private val lossless = Base64.getDecoder().decode(
        "UklGRjgAAABXRUJQVlA4TCwAAAAvD8ADEA8QMf+DHAxFkaRGU4UAJKwUrCOJ55VnRET0PwDSylutF3+rVZ4GAA==",
    )

    private class Chunk(val tag: String, val from: Int, val size: Int)

    private fun u32(data: ByteArray, at: Int): Int =
        (0 until 4).sumOf { (data[at + it].toInt() and 0xFF) shl (8 * it) }

    private fun u24(data: ByteArray, at: Int): Int =
        (0 until 3).sumOf { (data[at + it].toInt() and 0xFF) shl (8 * it) }

    /** The chunks in `data[from, until)`, failing on one that overruns or is left unpadded. */
    private fun chunks(data: ByteArray, from: Int, until: Int): List<Chunk> {
        val out = ArrayList<Chunk>()
        var at = from
        while (at < until) {
            assertTrue("chunk header at $at runs past $until", at + 8 <= until)
            val size = u32(data, at + 4)
            val chunk = Chunk(String(data, at, 4, Charsets.US_ASCII), at + 8, size)
            assertTrue("${chunk.tag} at $at runs past $until", chunk.from + size <= until)
            out += chunk
            at = chunk.from + size + (size and 1)
        }
        assertEquals("chunks end exactly where their parent does", until, at)
        return out
    }

    private fun mux(): ByteArray {
        val writer = AnimatedWebpWriter(16, 16)
        assertTrue(writer.addFrame(lossyAlpha, 80))
        assertTrue(writer.addFrame(lossless, 120))
        assertEquals(2, writer.frameCount)
        return writer.build().also { bytes ->
            System.getenv("WM_WEBP_OUT")?.takeIf { it.isNotBlank() }?.let { File(it).writeBytes(bytes) }
        }
    }

    @Test
    fun `the file is a RIFF WEBP whose size field is its length less the header`() {
        val out = mux()
        assertEquals("RIFF", String(out, 0, 4, Charsets.US_ASCII))
        assertEquals(out.size - 8, u32(out, 4))
        assertEquals("WEBP", String(out, 8, 4, Charsets.US_ASCII))
        assertEquals(0, out.size % 2)
    }

    @Test
    fun `it opens with VP8X flagged as animated with alpha, then ANIM, then one ANMF a frame`() {
        val out = mux()
        val top = chunks(out, 12, out.size)
        assertEquals(listOf("VP8X", "ANIM", "ANMF", "ANMF"), top.map { it.tag })

        val vp8x = top[0]
        assertEquals(10, vp8x.size)
        assertEquals(0x12, out[vp8x.from].toInt() and 0xFF)
        assertEquals(15, u24(out, vp8x.from + 4))
        assertEquals(15, u24(out, vp8x.from + 7))

        val anim = top[1]
        assertEquals(6, anim.size)
        assertEquals("transparent background", 0, u32(out, anim.from))
        assertEquals("loops for ever", 0, (out[anim.from + 4].toInt() and 0xFF) or (out[anim.from + 5].toInt() and 0xFF shl 8))
    }

    @Test
    fun `each frame covers the canvas, replaces what was there and keeps its duration`() {
        val out = mux()
        val frames = chunks(out, 12, out.size).filter { it.tag == "ANMF" }
        for ((frame, duration) in frames.zip(listOf(80, 120))) {
            assertEquals("x", 0, u24(out, frame.from))
            assertEquals("y", 0, u24(out, frame.from + 3))
            assertEquals("width - 1", 15, u24(out, frame.from + 6))
            assertEquals("height - 1", 15, u24(out, frame.from + 9))
            assertEquals(duration, u24(out, frame.from + 12))
            assertEquals("do not blend, do not dispose", 0x02, out[frame.from + 15].toInt() and 0xFF)
        }
    }

    @Test
    fun `a frame carries the still's picture chunks byte for byte and nothing else of it`() {
        val out = mux()
        val frames = chunks(out, 12, out.size).filter { it.tag == "ANMF" }

        val first = chunks(out, frames[0].from + 16, frames[0].from + frames[0].size)
        assertEquals("the still's own VP8X stays behind", listOf("ALPH", "VP8 "), first.map { it.tag })
        val source = chunks(lossyAlpha, 12, lossyAlpha.size).associateBy { it.tag }
        for (chunk in first) {
            val from = source.getValue(chunk.tag)
            assertArrayEquals(
                chunk.tag,
                lossyAlpha.copyOfRange(from.from, from.from + from.size),
                out.copyOfRange(chunk.from, chunk.from + chunk.size),
            )
        }

        val second = chunks(out, frames[1].from + 16, frames[1].from + frames[1].size)
        assertEquals(listOf("VP8L"), second.map { it.tag })
    }

    @Test
    fun `a chunk of odd length is padded, and the frame around it stays even`() {
        // A still whose one picture chunk is 5 bytes long.
        val odd = "RIFF".toByteArray() + byteArrayOf(18, 0, 0, 0) + "WEBP".toByteArray() +
            "VP8L".toByteArray() + byteArrayOf(5, 0, 0, 0) + byteArrayOf(1, 2, 3, 4, 5, 0)
        val writer = AnimatedWebpWriter(4, 4)
        assertTrue(writer.addFrame(odd, 50))
        val out = writer.build()
        val frame = chunks(out, 12, out.size).single { it.tag == "ANMF" }
        assertEquals(0, frame.size % 2)
        val inner = chunks(out, frame.from + 16, frame.from + frame.size).single()
        assertEquals(5, inner.size)
        assertEquals(out.size - 8, u32(out, 4))
    }

    @Test
    fun `what is not a still WebP is refused and adds nothing`() {
        val writer = AnimatedWebpWriter(16, 16)
        assertFalse(writer.addFrame(ByteArray(0), 50))
        assertFalse(writer.addFrame("GIF89a and then some more bytes".toByteArray(), 50))
        // A RIFF WEBP with no picture in it.
        val empty = "RIFF".toByteArray() + byteArrayOf(22, 0, 0, 0) + "WEBP".toByteArray() +
            "VP8X".toByteArray() + byteArrayOf(10, 0, 0, 0) + ByteArray(10)
        assertFalse(writer.addFrame(empty, 50))
        // A chunk that claims more bytes than the file has.
        val overrun = lossless.copyOf(lossless.size - 10)
        assertFalse(writer.addFrame(overrun, 50))
        assertEquals(0, writer.frameCount)
    }

    @Test
    fun `a duration is kept inside the 24 bits it is stored in`() {
        val writer = AnimatedWebpWriter(16, 16)
        writer.addFrame(lossless, -5)
        writer.addFrame(lossless, Int.MAX_VALUE)
        val out = writer.build()
        val frames = chunks(out, 12, out.size).filter { it.tag == "ANMF" }
        assertEquals(0, u24(out, frames[0].from + 12))
        assertEquals(0xFFFFFF, u24(out, frames[1].from + 12))
    }
}
