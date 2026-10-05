package com.wasimaster.wmkeyboard.core.icons

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream

/**
 * Reading a raster icon's header.
 *
 * Written by hand rather than handed to `BitmapFactory` so that it runs where
 * there is no Android — the icon-pack import and the Gboard theme converter are
 * both plain functions — which also makes it exactly the kind of byte-poking
 * that has to be tested rather than eyeballed.
 *
 * The fixtures are assembled here rather than checked in: every one is a dozen
 * bytes of header, and a file in the repository would say less about the format
 * than the code that builds it.
 */
class RasterIconsTest {

    // ---- fixtures ----

    private fun png(width: Int, height: Int): ByteArray = ByteArrayOutputStream().apply {
        write(byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A))
        // The IHDR chunk's length and type, then the two sizes, big-endian.
        write(byteArrayOf(0, 0, 0, 0x0D, 0x49, 0x48, 0x44, 0x52))
        writeInt32(width)
        writeInt32(height)
        write(byteArrayOf(8, 6, 0, 0, 0))
    }.toByteArray()

    private fun gif(width: Int, height: Int): ByteArray = ByteArrayOutputStream().apply {
        write("GIF89a".toByteArray())
        writeInt16LE(width)
        writeInt16LE(height)
        write(byteArrayOf(0, 0, 0))
    }.toByteArray()

    private fun jpeg(width: Int, height: Int): ByteArray = ByteArrayOutputStream().apply {
        write(byteArrayOf(0xFF.toByte(), 0xD8.toByte()))
        // An APP0 segment first, so the scan has to walk past something.
        write(byteArrayOf(0xFF.toByte(), 0xE0.toByte(), 0, 4, 0, 0))
        // SOF0: marker, length, precision, height, width, components.
        write(byteArrayOf(0xFF.toByte(), 0xC0.toByte(), 0, 11, 8))
        writeInt16(height)
        writeInt16(width)
        write(byteArrayOf(3, 0, 0, 0, 0))
    }.toByteArray()

    /** The lossless flavour, which packs both sizes minus one into 28 bits. */
    private fun webpLossless(width: Int, height: Int): ByteArray = ByteArrayOutputStream().apply {
        write("RIFF".toByteArray())
        write(byteArrayOf(0, 0, 0, 0))
        write("WEBP".toByteArray())
        write("VP8L".toByteArray())
        write(byteArrayOf(0, 0, 0, 0))
        write(byteArrayOf(0x2F))
        val packed = ((width - 1) and 0x3FFF) or (((height - 1) and 0x3FFF) shl 14)
        writeInt32LE(packed)
        write(ByteArray(8))
    }.toByteArray()

    private fun ByteArrayOutputStream.writeInt32(value: Int) = write(
        byteArrayOf(
            (value ushr 24).toByte(), (value ushr 16).toByte(),
            (value ushr 8).toByte(), value.toByte(),
        ),
    )

    private fun ByteArrayOutputStream.writeInt32LE(value: Int) = write(
        byteArrayOf(
            value.toByte(), (value ushr 8).toByte(),
            (value ushr 16).toByte(), (value ushr 24).toByte(),
        ),
    )

    private fun ByteArrayOutputStream.writeInt16(value: Int) =
        write(byteArrayOf((value ushr 8).toByte(), value.toByte()))

    private fun ByteArrayOutputStream.writeInt16LE(value: Int) =
        write(byteArrayOf(value.toByte(), (value ushr 8).toByte()))

    // ---- what the header says ----

    @Test
    fun `each format is recognised and measured`() {
        val cases = listOf(
            RasterFormat.PNG to png(98, 103),
            RasterFormat.GIF to gif(64, 48),
            RasterFormat.JPEG to jpeg(120, 90),
            RasterFormat.WEBP to webpLossless(32, 24),
        )
        val expected = listOf(98 to 103, 64 to 48, 120 to 90, 32 to 24)
        for ((case, size) in cases.zip(expected)) {
            val (format, bytes) = case
            val icon = RasterIcons.read(bytes)
            assertNotNull("$format did not read", icon)
            assertEquals(format, icon!!.format)
            assertEquals(size.first, icon.width)
            assertEquals(size.second, icon.height)
        }
    }

    @Test
    fun `anything that is not one of the four is not an icon`() {
        assertNull(RasterIcons.read(ByteArray(0)))
        assertNull(RasterIcons.read("<svg xmlns='x'><path d='M0 0'/></svg>".toByteArray()))
        // A truncated PNG: the signature is there and the sizes are not.
        assertNull(RasterIcons.formatOf(ByteArray(4)))
    }

    @Test
    fun `a file past the caps is refused before anything decodes it`() {
        // The whole reason the header is parsed by hand: a bitmap this size is
        // 64 MB of pixels, and nothing should allocate it to find that out.
        assertNull(RasterIcons.read(png(20_000, 20_000)))
        assertNull(RasterIcons.read(png(0, 10)))
        val huge = png(10, 10) + ByteArray(RasterIcons.MAX_SOURCE_BYTES)
        assertNull(RasterIcons.read(huge))
    }

    @Test
    fun `the extension list covers vectors and every raster format`() {
        assertEquals("svg", RasterIcons.FILE_EXTENSIONS.first())
        for (format in RasterFormat.entries) {
            assertTrue(format.extension in RasterIcons.FILE_EXTENSIONS)
        }
        // The names become file names, so they have to stay distinct.
        assertEquals(
            RasterIcons.FILE_EXTENSIONS.size,
            RasterIcons.FILE_EXTENSIONS.toSet().size,
        )
    }

    @Test
    fun `the pixel verdict is left open until something can read pixels`() {
        // The header cannot tell a stencil from artwork, so the safe answer is
        // "not monochrome" — drawing an icon as authored is never illegible,
        // while tinting deliberate artwork flat is.
        val icon = RasterIcons.read(png(10, 10))!!
        assertNull(icon.mask)
        assertEquals(false, icon.monochrome)
        assertEquals(true, icon.withMask(true).monochrome)
    }
}
