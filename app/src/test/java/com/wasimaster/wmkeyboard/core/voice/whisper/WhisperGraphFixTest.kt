package com.wasimaster.wmkeyboard.core.voice.whisper

import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class WhisperGraphFixTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val broken = ints(-1, -1, 50359, 50363)
    private val fixed = ints(-1, -1, -1, -1)

    /** A stand-in graph: filler bytes with the forced-token table at [offsets]. */
    private fun graph(name: String, size: Int, vararg offsets: Int): Pair<File, ByteArray> {
        val bytes = ByteArray(size) { (it * 31 + 7).toByte() }
        for (at in offsets) broken.copyInto(bytes, at)
        val file = File(tmp.root, name).apply { writeBytes(bytes) }
        return file to bytes
    }

    private fun vocab(name: String) = File(tmp.root, name).apply { writeBytes(ByteArray(4)) }

    @Test
    fun `an English-only graph gets every copy of the table rewritten, one across a read boundary`() {
        // 1 MiB is the scan's read size, so the second copy straddles two reads.
        val (file, before) = graph("whisper-tiny.en.tflite", 2_300_000, 4_096, (1 shl 20) - 8)
        assertEquals(2, WhisperGraphFix.ensure(file, vocab("filters_vocab_en.bin")))

        val expected = before.copyOf()
        fixed.copyInto(expected, 4_096)
        fixed.copyInto(expected, (1 shl 20) - 8)
        assertArrayEquals(expected, file.readBytes())
    }

    @Test
    fun `a multilingual graph keeps the same bytes, which are its right prompt`() {
        val (file, before) = graph("whisper-small-transcribe-translate.tflite", 50_000, 1_000)
        assertEquals(0, WhisperGraphFix.ensure(file, vocab("filters_vocab_multilingual.bin")))
        assertArrayEquals(before, file.readBytes())
    }

    @Test
    fun `a graph is scanned once, and again after it is downloaded anew`() {
        val en = vocab("filters_vocab_en.bin")
        val (file, _) = graph("whisper-base.en.tflite", 60_000, 100)
        assertEquals(1, WhisperGraphFix.ensure(file, en))

        // Put the broken bytes back without the file looking new: still skipped.
        val stamp = file.lastModified()
        file.writeBytes(file.readBytes().also { broken.copyInto(it, 100) })
        file.setLastModified(stamp)
        assertEquals(0, WhisperGraphFix.ensure(file, en))

        // A fresh download of a different size is a different file.
        graph("whisper-base.en.tflite", 70_000, 200)
        assertEquals(1, WhisperGraphFix.ensure(file, en))
    }

    @Test
    fun `a graph without the table is left alone`() {
        val (file, before) = graph("whisper-small.en.tflite", 40_000)
        assertEquals(0, WhisperGraphFix.ensure(file, vocab("filters_vocab_en.bin")))
        assertArrayEquals(before, file.readBytes())
    }

    private fun ints(vararg values: Int): ByteArray =
        ByteBuffer.allocate(values.size * 4).order(ByteOrder.LITTLE_ENDIAN)
            .apply { values.forEach { putInt(it) } }
            .array()
}
