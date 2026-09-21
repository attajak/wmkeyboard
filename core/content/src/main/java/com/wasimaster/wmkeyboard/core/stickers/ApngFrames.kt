package com.wasimaster.wmkeyboard.core.stickers

import android.graphics.Bitmap
import com.github.penfeizhou.animation.apng.decode.APNGDecoder
import com.github.penfeizhou.animation.loader.ByteBufferLoader
import java.nio.ByteBuffer

/**
 * The frames of an animated PNG.
 *
 * APNG is what an animated Signal sticker is, and Android does not read it:
 * every decoder on the platform sees the ordinary PNG the format wraps itself
 * in and stops at the first picture. APNG4Android reads the rest.
 *
 * Its public way to a frame, `getFrameBitmap(i)`, draws the animation from the
 * start up to `i` on every call, which is quadratic over a whole sticker and
 * minutes over a whole pack. The decoder's own step (`renderFrame`) is
 * protected rather than private, so [Stepper] walks the frames once with it.
 * Each frame arrives fully composed: the offsets, disposal and blending an
 * APNG frame carries are already applied, and what comes out is the whole
 * canvas as it looks at that moment.
 */
object ApngFrames {

    private val PNG_MAGIC = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)
    private const val CHUNK_HEADER = 8
    private const val CHUNK_CRC = 4
    private const val BYTE = 0xFF

    /** A frame shorter than this is a file that says 0 and means "as fast as you like". */
    private const val MIN_FRAME_MS = 20
    private const val DEFAULT_FRAME_MS = 100
    private const val PARSE_RETRIES = 2

    /**
     * How many frames [bytes] animate through, or 0 when they are not an APNG.
     * Read from the `acTL` chunk, which has to come before the first `IDAT`:
     * a PNG that gets as far as its pixels without one is a still.
     */
    fun frameCount(bytes: ByteArray): Int {
        if (bytes.size < PNG_MAGIC.size || !PNG_MAGIC.indices.all { bytes[it] == PNG_MAGIC[it] }) return 0
        var at = PNG_MAGIC.size
        while (at + CHUNK_HEADER <= bytes.size) {
            val length = bytes.u32At(at)
            if (length < 0) return 0
            val type = String(bytes, at + 4, 4, Charsets.US_ASCII)
            if (type == "IDAT" || type == "IEND") return 0
            if (type == "acTL") {
                return if (at + CHUNK_HEADER + 4 <= bytes.size) bytes.u32At(at + CHUNK_HEADER).coerceAtLeast(0) else 0
            }
            at += CHUNK_HEADER + length + CHUNK_CRC
            if (at < 0) return 0
        }
        return 0
    }

    /** True for an APNG with more than one frame; a one-frame APNG is a still. */
    fun isAnimated(bytes: ByteArray): Boolean = frameCount(bytes) > 1

    /**
     * Calls [onFrame] with each composed frame of [bytes] and how long it
     * shows, in order. The bitmap is only valid during the call: it is reused
     * for the next frame. Returns the number of frames delivered, 0 when the
     * file could not be read.
     *
     * Blocking, and not for the main thread.
     */
    fun forEach(bytes: ByteArray, onFrame: (frame: Bitmap, durationMs: Int) -> Unit): Int =
        runCatching { Stepper(bytes).run(onFrame) }.getOrDefault(0)

    private class Stepper(bytes: ByteArray) : APNGDecoder(
        object : ByteBufferLoader() {
            override fun getByteBuffer(): ByteBuffer = ByteBuffer.wrap(bytes)
        },
        null,
    ) {
        fun run(onFrame: (Bitmap, Int) -> Unit): Int {
            // Parses the file on the decoder's own thread and waits for it.
            // The wait is a park, which may return early; asking again is
            // free, because the second request queues behind the parse.
            var bounds = getBounds()
            repeat(PARSE_RETRIES) { if (bounds.isEmpty) bounds = getBounds() }
            val buffer = frameBuffer
            if (bounds.isEmpty || buffer == null || frames.isEmpty()) return 0
            val canvas = Bitmap.createBitmap(bounds.width(), bounds.height(), Bitmap.Config.ARGB_8888)
            try {
                for (index in frames.indices) {
                    // renderFrame reads this to know the first frame, which
                    // starts from a clear canvas rather than the last one.
                    frameIndex = index
                    val frame = frames[index]
                    renderFrame(frame)
                    buffer.rewind()
                    canvas.copyPixelsFromBuffer(buffer)
                    val shown = frame.frameDuration
                    onFrame(canvas, if (shown < MIN_FRAME_MS) DEFAULT_FRAME_MS else shown)
                }
                return frames.size
            } finally {
                canvas.recycle()
                release()
            }
        }
    }

    private fun ByteArray.u32At(at: Int): Int =
        ((this[at].toInt() and BYTE) shl 24) or
            ((this[at + 1].toInt() and BYTE) shl 16) or
            ((this[at + 2].toInt() and BYTE) shl 8) or
            (this[at + 3].toInt() and BYTE)
}
