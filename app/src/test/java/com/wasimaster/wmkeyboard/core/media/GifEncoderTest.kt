package com.wasimaster.wmkeyboard.core.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Node
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import javax.imageio.ImageIO
import javax.imageio.ImageReader
import kotlin.math.abs

/**
 * The GIF writer, read back by a decoder that is not ours: the JDK's.
 *
 * An encoder checked against its own idea of the format proves nothing. The
 * compression in particular fails in ways that look fine for a small picture
 * and turn to noise once the code table fills, so the pictures here are made
 * to cross every boundary it has: codes growing from 3 bits to 12, and the
 * table filling up and starting again.
 */
class GifEncoderTest {

    private val red = 0xFFFF0000.toInt()
    private val green = 0xFF00FF00.toInt()
    private val clear = 0x00000000

    private class Decoded(val reader: ImageReader) {
        val frames: Int get() = reader.getNumImages(true)

        fun image(index: Int): BufferedImage = reader.read(index)

        private fun node(index: Int, name: String): Node {
            val root = reader.getImageMetadata(index).getAsTree("javax_imageio_gif_image_1.0")
            val children = root.childNodes
            return (0 until children.length).map { children.item(it) }.first { it.nodeName == name }
        }

        fun attribute(index: Int, node: String, name: String): String =
            node(index, node).attributes.getNamedItem(name).nodeValue
    }

    private fun decode(bytes: ByteArray): Decoded {
        val reader = ImageIO.getImageReadersByFormatName("gif").next()
        reader.input = ImageIO.createImageInputStream(ByteArrayInputStream(bytes))
        return Decoded(reader)
    }

    private fun encode(width: Int, height: Int, vararg frames: Pair<IntArray, Int>): ByteArray {
        val out = ByteArrayOutputStream()
        val encoder = GifEncoder(out, width, height)
        for ((pixels, duration) in frames) encoder.addFrame(pixels, duration)
        encoder.finish()
        return out.toByteArray()
    }

    @Test
    fun `frames come back with their colours, their timing and a cleared canvas between them`() {
        val first = IntArray(64) { red }
        val second = IntArray(64) { if (it % 8 < 4) green else red }
        val gif = decode(encode(8, 8, first to 70, second to 154))

        assertEquals(2, gif.frames)
        assertEquals(red, gif.image(0).getRGB(3, 3))
        assertEquals(green, gif.image(1).getRGB(0, 0))
        assertEquals(red, gif.image(1).getRGB(7, 7))
        // Hundredths of a second, rounded to the nearest.
        assertEquals("7", gif.attribute(0, "GraphicControlExtension", "delayTime"))
        assertEquals("15", gif.attribute(1, "GraphicControlExtension", "delayTime"))
        assertEquals("restoreToBackgroundColor", gif.attribute(0, "GraphicControlExtension", "disposalMethod"))
        assertEquals("TRUE", gif.attribute(0, "GraphicControlExtension", "transparentColorFlag"))
    }

    @Test
    fun `what is transparent stays out, and the frame shrinks to what is drawn`() {
        // A 3 x 2 block of red at (5, 2) on an otherwise empty 12 x 8 canvas,
        // one pixel of it under half opacity.
        val pixels = IntArray(12 * 8) { clear }
        for (y in 2..3) for (x in 5..7) pixels[y * 12 + x] = red
        pixels[3 * 12 + 7] = 0x40FF0000
        val gif = decode(encode(12, 8, pixels to 100))

        assertEquals("5", gif.attribute(0, "ImageDescriptor", "imageLeftPosition"))
        assertEquals("2", gif.attribute(0, "ImageDescriptor", "imageTopPosition"))
        assertEquals("3", gif.attribute(0, "ImageDescriptor", "imageWidth"))
        assertEquals("2", gif.attribute(0, "ImageDescriptor", "imageHeight"))
        val image = gif.image(0)
        assertEquals(red, image.getRGB(0, 0))
        assertEquals("the faint pixel is dropped, not drawn solid", 0, image.getRGB(2, 1) ushr 24)
    }

    @Test
    fun `a frame with nothing in it is still a frame`() {
        val gif = decode(encode(6, 6, IntArray(36) { clear } to 100, IntArray(36) { red } to 100))
        assertEquals(2, gif.frames)
        assertEquals(0, gif.image(0).getRGB(0, 0) ushr 24)
        assertEquals(red, gif.image(1).getRGB(5, 5))
    }

    @Test
    fun `a picture with far more than 256 colours survives the palette and the code table`() {
        // 65,536 colours in, 255 out. Enough distinct runs to fill the 4,096
        // entry code table several times over, so a mistake at the point where
        // codes widen or the table restarts turns the rest of the picture to
        // noise, which an average cannot hide.
        val side = 256
        val pixels = IntArray(side * side) { i ->
            val x = i % side
            val y = i / side
            (0xFF shl 24) or (x shl 16) or (y shl 8) or ((x * 7 + y * 13) and 0xFF)
        }
        val image = decode(encode(side, side, pixels to 100)).image(0)

        var error = 0L
        for (y in 0 until side) {
            for (x in 0 until side) {
                val want = pixels[y * side + x]
                val got = image.getRGB(x, y)
                error += abs((want shr 16 and 0xFF) - (got shr 16 and 0xFF))
                error += abs((want shr 8 and 0xFF) - (got shr 8 and 0xFF))
                error += abs((want and 0xFF) - (got and 0xFF))
            }
        }
        val mean = error.toDouble() / (side * side * 3)
        assertTrue("mean error per channel $mean", mean < 24.0)
    }

    @Test
    fun `long flat runs, which make the longest codes, come back exact`() {
        val side = 300
        val pixels = IntArray(side * side) { if (it / side < 150) red else green }
        pixels[0] = 0xFF0000FF.toInt()
        val image = decode(encode(side, side, pixels to 100)).image(0)
        assertEquals(0xFF0000FF.toInt(), image.getRGB(0, 0))
        assertEquals(red, image.getRGB(299, 149))
        assertEquals(green, image.getRGB(0, 150))
        assertEquals(green, image.getRGB(299, 299))
    }

    @Test
    fun `few colours are kept as they are`() {
        val colours = intArrayOf(red, green, 0xFF123456.toInt(), 0xFFFEDCBA.toInt())
        val pixels = IntArray(16) { colours[it % 4] }
        val image = decode(encode(4, 4, pixels to 100)).image(0)
        for (i in 0 until 16) assertEquals(colours[i % 4], image.getRGB(i % 4, i / 4))
    }

    @Test
    fun `the file loops for ever`() {
        val bytes = encode(2, 2, IntArray(4) { red } to 100)
        assertEquals("GIF89a", String(bytes, 0, 6, Charsets.US_ASCII))
        assertTrue(String(bytes, Charsets.ISO_8859_1).contains("NETSCAPE2.0"))
        assertEquals(0x3B, bytes.last().toInt())
    }
}
