package com.wasimaster.wmkeyboard.core.voice

import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.assertEquals
import org.junit.Test

class WavEncoderTest {

    @Test
    fun `header describes 16 kHz mono PCM16`() {
        val wav = WavEncoder.encode(FloatArray(16_000))
        val b = ByteBuffer.wrap(wav).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals(44 + 32_000, wav.size)
        assertEquals("RIFF", String(wav, 0, 4, Charsets.US_ASCII))
        assertEquals(36 + 32_000, b.getInt(4))
        assertEquals("WAVE", String(wav, 8, 4, Charsets.US_ASCII))
        assertEquals(1, b.getShort(20).toInt())
        assertEquals(1, b.getShort(22).toInt())
        assertEquals(16_000, b.getInt(24))
        assertEquals(32_000, b.getInt(28))
        assertEquals(16, b.getShort(34).toInt())
        assertEquals("data", String(wav, 36, 4, Charsets.US_ASCII))
        assertEquals(32_000, b.getInt(40))
    }

    @Test
    fun `samples are clamped to the 16-bit range`() {
        val wav = WavEncoder.encode(floatArrayOf(2f, -2f, 0f, 0.5f))
        val b = ByteBuffer.wrap(wav).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals(Short.MAX_VALUE, b.getShort(44))
        assertEquals((-Short.MAX_VALUE).toShort(), b.getShort(46))
        assertEquals(0, b.getShort(48).toInt())
        assertEquals((0.5f * Short.MAX_VALUE).toInt().toShort(), b.getShort(50))
    }
}
