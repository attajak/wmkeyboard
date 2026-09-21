package com.wasimaster.wmkeyboard.core.stickers.signal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.ByteArrayOutputStream

/**
 * The manifest reader, against messages built here field by field.
 *
 * It reads a format it does not own with no library behind it, so what is
 * pinned is the part that goes wrong quietly: a field it has never heard of
 * must be stepped over at the right width, and input that stops halfway must
 * be refused rather than read as a shorter pack.
 */
class SignalPackManifestTest {

    private class Message {
        private val out = ByteArrayOutputStream()

        private fun varint(value: Long) {
            var v = value
            while (v and 0x7FL.inv() != 0L) {
                out.write(((v and 0x7F) or 0x80).toInt())
                v = v ushr 7
            }
            out.write(v.toInt())
        }

        fun number(field: Int, value: Long) = apply {
            varint((field.toLong() shl 3) or 0)
            varint(value)
        }

        fun bytes(field: Int, value: ByteArray) = apply {
            varint((field.toLong() shl 3) or 2)
            varint(value.size.toLong())
            out.write(value)
        }

        fun text(field: Int, value: String) = bytes(field, value.toByteArray())

        fun fixed32(field: Int) = apply {
            varint((field.toLong() shl 3) or 5)
            out.write(ByteArray(4))
        }

        fun fixed64(field: Int) = apply {
            varint((field.toLong() shl 3) or 1)
            out.write(ByteArray(8))
        }

        fun build(): ByteArray = out.toByteArray()
    }

    private fun sticker(id: Int, emoji: String, type: String? = null): ByteArray =
        Message().number(1, id.toLong()).text(2, emoji).apply { type?.let { text(3, it) } }.build()

    @Test
    fun `title author cover and stickers are read`() {
        val bytes = Message()
            .text(1, "  Cats  ")
            .text(2, "Somebody")
            .bytes(3, sticker(2, "😺"))
            .bytes(4, sticker(0, "😸"))
            .bytes(4, sticker(1, "😹", "image/apng"))
            .bytes(4, sticker(2, "😺"))
            .build()
        val manifest = SignalPackManifest.parse(bytes)!!
        assertEquals("Cats", manifest.title)
        assertEquals("Somebody", manifest.author)
        assertEquals(2, manifest.coverId)
        assertEquals(listOf(0, 1, 2), manifest.stickers.map { it.id })
        assertEquals("image/apng", manifest.stickers[1].contentType)
        assertEquals("", manifest.stickers[0].contentType)
    }

    @Test
    fun `sticker id 0 is written as nothing at all and still reads as 0`() {
        // proto2 leaves a default value out, which is how every pack's first
        // sticker arrives.
        val first = Message().text(2, "✨").build()
        val manifest = SignalPackManifest.parse(Message().text(1, "T").bytes(4, first).build())!!
        assertEquals(0, manifest.stickers.single().id)
    }

    @Test
    fun `a pack with no cover shows its first sticker`() {
        val manifest = SignalPackManifest.parse(
            Message().text(1, "T").bytes(4, sticker(7, "")).bytes(4, sticker(8, "")).build(),
        )!!
        assertEquals(7, manifest.coverId)
    }

    @Test
    fun `fields this reader has never heard of are stepped over`() {
        val bytes = Message()
            .number(9, 300)
            .fixed32(10)
            .text(1, "T")
            .fixed64(11)
            .bytes(12, byteArrayOf(1, 2, 3))
            .bytes(4, Message().number(1, 5).number(8, 1).fixed32(9).text(2, "x").build())
            .build()
        val manifest = SignalPackManifest.parse(bytes)!!
        assertEquals("T", manifest.title)
        assertEquals(5, manifest.stickers.single().id)
        assertEquals("x", manifest.stickers.single().emoji)
    }

    @Test
    fun `a sticker listed twice is one sticker`() {
        val manifest = SignalPackManifest.parse(
            Message().text(1, "T").bytes(4, sticker(3, "a")).bytes(4, sticker(3, "b")).build(),
        )!!
        assertEquals(1, manifest.stickers.size)
    }

    @Test
    fun `input that stops halfway is refused`() {
        val whole = Message().text(1, "Title").bytes(4, sticker(1, "😀")).build()
        assertNotNull(SignalPackManifest.parse(whole))
        assertNull(SignalPackManifest.parse(whole.copyOf(whole.size - 1)))
        assertNull(SignalPackManifest.parse(byteArrayOf(0x0A, 0x7F, 0x41)))
    }

    @Test
    fun `nothing and noise are not a manifest`() {
        assertNull(SignalPackManifest.parse(ByteArray(0)))
        // Field number 0 is not a field.
        assertNull(SignalPackManifest.parse(byteArrayOf(0x00, 0x01)))
        // A group start, which nothing here can skip.
        assertNull(SignalPackManifest.parse(byteArrayOf(0x0B)))
    }
}
