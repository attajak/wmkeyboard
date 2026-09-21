package com.wasimaster.wmkeyboard.core.stickers

import android.graphics.Color
import com.wasimaster.wmkeyboard.core.media.MediaMime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.util.Base64

/**
 * An animated PNG, read frame by frame and turned into an animated WebP, with
 * real pixels: Robolectric's native graphics runs the platform's own bitmap,
 * canvas and encoders on the JVM.
 *
 * Here rather than beside the other sticker tests because this is the module
 * with Robolectric in it. What it guards is the part of [ApngFrames] that
 * leans on the decoder library's protected members: if an update to the
 * library moves them, the frames stop arriving, and every animated Signal
 * sticker quietly becomes a still.
 *
 * The file is 24×24 and three frames, made to use each thing a frame can ask
 * for. Frame 0 fills the canvas red. Frame 1 draws a green square over it and
 * asks for the canvas to be cleared afterwards. Frame 2 replaces a region with
 * a blue square. Frame 1 also gives a delay of 0, which a player reads as "as
 * fast as you like" and this reads as a tenth of a second.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ApngStickerTest {

    private val apng = Base64.getDecoder().decode(
        "iVBORw0KGgoAAAANSUhEUgAAABgAAAAYCAYAAADgdz34AAAACGFjVEwAAAADAAAAAM7tusAAAAAaZmNUTAAAAAAA" +
            "AAAYAAAAGAAAAAAAAAAAAEYD6AAA924xXgAAACdJREFUeJztzTEBAAAIw7CBf89gAr5UQFOTTB7rzzkAAAAAAADA" +
            "XQv9OwIubDcPggAAABpmY1RMAAAAAQAAABgAAAAYAAAAAAAAAAAAAAPoAQHVsne0AAAAMWZkQVQAAAACeJxjYBgF" +
            "Aw0YMUT+M/wnoANTDx7ARJp7SAejFoxaMApGwSgYBcMDAABoLQIQdm9g0wAAABpmY1RMAAAAAwAAAAgAAAAIAAAA" +
            "DAAAAAwAlgPoAACS5CvMAAAAGmZkQVQAAAAEeJxjZGD4/58BD2DCJzl8FAAAEF0CDimJkkoAAAAASUVORK5CYII=",
    )

    @Test
    fun `every frame arrives once, in order, composed, with its duration`() {
        assertEquals(3, ApngFrames.frameCount(apng))

        val durations = ArrayList<Int>()
        val corner = ArrayList<Int>()
        val greenSpot = ArrayList<Int>()
        val blueSpot = ArrayList<Int>()
        val delivered = ApngFrames.forEach(apng) { frame, durationMs ->
            assertEquals(24, frame.width)
            assertEquals(24, frame.height)
            durations += durationMs
            corner += frame.getPixel(1, 1)
            greenSpot += frame.getPixel(6, 6)
            blueSpot += frame.getPixel(15, 15)
        }

        assertEquals(3, delivered)
        assertEquals(listOf(70, 100, 150), durations)
        // Red stays under the green square, and is gone once frame 1 has asked
        // for the canvas to be cleared behind it.
        assertEquals(listOf(Color.RED, Color.RED, Color.TRANSPARENT), corner)
        assertEquals(listOf(Color.RED, Color.GREEN, Color.TRANSPARENT), greenSpot)
        assertEquals(listOf(Color.RED, Color.RED, Color.BLUE), blueSpot)
    }

    @Test
    fun `an animated PNG becomes an animated WebP sticker with every frame in it`() {
        assertTrue(StickerImage.isAnimatedSource(apng))
        val result = StickerImage.process(apng)
        assertTrue("$result", result is StickerImage.Result.Ok)
        val sticker = (result as StickerImage.Result.Ok).sticker

        assertEquals(MediaMime.WEBP, sticker.mime)
        assertTrue(sticker.animated)
        assertTrue(!sticker.flattened)
        assertEquals(1f, sticker.aspectRatio)

        val bytes = sticker.bytes
        assertEquals("RIFF", String(bytes, 0, 4, Charsets.US_ASCII))
        assertEquals("VP8X", String(bytes, 12, 4, Charsets.US_ASCII))
        assertTrue("animation flag", bytes[20].toInt() and 0x02 != 0)
        // The sticker canvas, as width - 1 in 24 bits.
        assertEquals(511, (bytes[24].toInt() and 0xFF) or ((bytes[25].toInt() and 0xFF) shl 8))
        assertEquals(3, countChunks(bytes, "ANMF"))
        assertTrue(bytes.size <= StickerImage.ANIMATED_TARGET_BYTES)
    }

    /**
     * Not a test of anything on its own: converts a file of your choosing, so
     * the result can be looked at. Runs only when both variables are set.
     *
     * `WM_APNG_IN=sticker.png WM_APNG_OUT=sticker.webp ./gradlew
     * :feature:ime:testFullDebugUnitTest --tests '*ApngStickerTest' --rerun`
     */
    @Test
    fun `converts the file named in the environment`() {
        val source = System.getenv("WM_APNG_IN")?.takeIf { it.isNotBlank() }
        val target = System.getenv("WM_APNG_OUT")?.takeIf { it.isNotBlank() }
        assumeTrue(source != null && target != null)
        val result = StickerImage.process(File(source!!).readBytes())
        assertTrue("$result", result is StickerImage.Result.Ok)
        File(target!!).writeBytes((result as StickerImage.Result.Ok).sticker.bytes)
    }

    private fun countChunks(webp: ByteArray, tag: String): Int {
        var at = 12
        var found = 0
        while (at + 8 <= webp.size) {
            if (String(webp, at, 4, Charsets.US_ASCII) == tag) found++
            val size = (0 until 4).sumOf { (webp[at + 4 + it].toInt() and 0xFF) shl (8 * it) }
            at += 8 + size + (size and 1)
        }
        return found
    }
}
