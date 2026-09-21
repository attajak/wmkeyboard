package com.wasimaster.wmkeyboard.core.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.util.Base64
import javax.imageio.ImageIO
import javax.imageio.ImageReader

/**
 * An animated WebP drawn frame by frame and written out as a GIF, on real
 * pixels (Robolectric's native graphics), and read back by the JDK's GIF
 * decoder.
 *
 * The file is the one [AnimatedWebpReaderTest] reads: 24×24, the canvas red,
 * then a green 8×8 square blended over it at (4, 4), then the canvas replaced
 * by one that is empty but for a blue square. A GIF frame here always clears
 * the canvas behind it, so what comes back is three whole pictures, each
 * cropped to what it draws. Getting the second one right needs the blend, and
 * the third the replace.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class AnimatedWebpToGifTest {

    @get:Rule
    val temp = TemporaryFolder()

    private val animated = Base64.getDecoder().decode(
        "UklGRsYAAABXRUJQVlA4WAoAAAASAAAAFwAAFwAAQU5JTQYAAAAAAAAAAABBTk1GKAAAAAAAAAAAABcAABcAAEYA" +
            "AAJWUDhMDwAAAC8XwAUABxD9j/4HIqL/AQBBTk1GKAAAAAIAAAIAAAcAAAcAAFoAAABWUDhMDwAAAC8HwAEAB9D/" +
            "iP4HIqL/AQBBTk1GOgAAAAAAAAAAABcAABcAAJYAAAJWUDhMIgAAAC8XwAUQDxAx//MfDIJsmxrc3/IUzxEi+j8O" +
            "AAAAAKA5zRo=",
    )

    private val red = 0xFFFF0000.toInt()
    private val green = 0xFF00FF00.toInt()
    private val blue = 0xFF0000FF.toInt()

    private fun reader(file: File): ImageReader =
        ImageIO.getImageReadersByFormatName("gif").next().apply { input = ImageIO.createImageInputStream(file) }

    private fun ImageReader.descriptor(index: Int, name: String): Int {
        val root = getImageMetadata(index).getAsTree("javax_imageio_gif_image_1.0")
        val nodes = root.childNodes
        val descriptor = (0 until nodes.length).map { nodes.item(it) }.first { it.nodeName == "ImageDescriptor" }
        return descriptor.attributes.getNamedItem(name).nodeValue.toInt()
    }

    @Test
    fun `every frame is drawn the way a player would draw it`() {
        val source = temp.newFile("sticker.webp").apply { writeBytes(animated) }
        val target = File(temp.root, "sticker.gif")
        assertTrue(AnimatedWebpToGif.transcode(source, target))

        val gif = reader(target)
        assertEquals(3, gif.getNumImages(true))

        // Frame 0: the whole canvas, red.
        assertEquals(24, gif.descriptor(0, "imageWidth"))
        assertEquals(red, gif.read(0).getRGB(1, 1))

        // Frame 1: blended, so the red is still there around the green.
        val second = gif.read(1)
        assertEquals(24, gif.descriptor(1, "imageWidth"))
        assertEquals(red, second.getRGB(1, 1))
        assertEquals(green, second.getRGB(6, 6))
        assertEquals(red, second.getRGB(15, 15))

        // Frame 2: replaced, so only the blue square is left, and the frame is
        // cropped to it.
        assertEquals(12, gif.descriptor(2, "imageLeftPosition"))
        assertEquals(12, gif.descriptor(2, "imageTopPosition"))
        assertEquals(8, gif.descriptor(2, "imageWidth"))
        assertEquals(8, gif.descriptor(2, "imageHeight"))
        assertEquals(blue, gif.read(2).getRGB(0, 0))
    }

    @Test
    fun `what is not an animated WebP is refused and leaves nothing behind`() {
        val still = temp.newFile("still.webp").apply {
            writeBytes(
                Base64.getDecoder().decode(
                    "UklGRjgAAABXRUJQVlA4TCwAAAAvD8ADEA8QMf+DHAxFkaRGU4UAJKwUrCOJ55VnRET0PwDSylutF3+rVZ4GAA==",
                ),
            )
        }
        val target = File(temp.root, "still.gif")
        assertFalse(AnimatedWebpToGif.transcode(still, target))
        assertFalse(target.exists())
        assertFalse(File(temp.root, "still.gif.part").exists())
    }

    /**
     * Not a test of anything on its own: converts a file of your choosing, so
     * the result can be looked at. Runs only when both variables are set.
     */
    @Test
    fun `converts the file named in the environment`() {
        val source = System.getenv("WM_WEBP_IN")?.takeIf { it.isNotBlank() }
        val target = System.getenv("WM_GIF_OUT")?.takeIf { it.isNotBlank() }
        assumeTrue(source != null && target != null)
        assertTrue(AnimatedWebpToGif.transcode(File(source!!), File(target!!)))
    }

    @Test
    fun `a sticker goes out at its own size, and anything larger is scaled down to it`() {
        // Built from the ground up, because a still has to decode to the size
        // its frame claims: two frames the writer wraps at the canvas's size,
        // which is twice the sticker canvas.
        val frame = android.graphics.Bitmap.createBitmap(1024, 1024, android.graphics.Bitmap.Config.ARGB_8888)
        val writer = AnimatedWebpWriter(1024, 1024)
        for (colour in listOf(red, blue)) {
            frame.eraseColor(colour)
            val out = java.io.ByteArrayOutputStream()
            @Suppress("DEPRECATION")
            frame.compress(android.graphics.Bitmap.CompressFormat.WEBP, 80, out)
            assertTrue(writer.addFrame(out.toByteArray(), 100))
        }
        val source = temp.newFile("big.webp").apply { writeBytes(writer.build()) }
        val target = File(temp.root, "big.gif")
        assertTrue(AnimatedWebpToGif.transcode(source, target))

        val gif = reader(target)
        assertEquals(2, gif.getNumImages(true))
        assertEquals(512, AnimatedWebpToGif.MAX_SIDE)
        assertEquals(AnimatedWebpToGif.MAX_SIDE, gif.getWidth(0))
        assertEquals(AnimatedWebpToGif.MAX_SIDE, gif.getHeight(0))
    }
}
