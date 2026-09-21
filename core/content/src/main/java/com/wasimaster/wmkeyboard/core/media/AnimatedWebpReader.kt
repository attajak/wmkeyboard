package com.wasimaster.wmkeyboard.core.media

import java.io.ByteArrayOutputStream

/**
 * Takes an animated WebP apart into stills, the other way round from
 * [AnimatedWebpWriter].
 *
 * Android plays an animated WebP and will not hand over its frames, so there
 * is no asking the platform for them. But each frame in the container is the
 * same chunks a still WebP is made of, so each one is lifted out and given a
 * still's header, and the platform decodes *that*. What this returns is the
 * stills, with where each one goes on the canvas, how long it shows and how it
 * meets the frame before it. Composing them is [AnimatedWebpToGif]'s job,
 * because it needs a bitmap and this needs only bytes.
 */
object AnimatedWebpReader {

    /** One frame: a whole still WebP of [width] × [height], placed at [x], [y] on the canvas. */
    class Frame(
        val x: Int,
        val y: Int,
        val width: Int,
        val height: Int,
        val durationMs: Int,
        /** True to draw over what is there with alpha, false to replace it. */
        val blend: Boolean,
        /** True to clear this frame's rectangle before the next frame is drawn. */
        val disposeToBackground: Boolean,
        val still: ByteArray,
    )

    class Animation(val width: Int, val height: Int, val frames: List<Frame>)

    /** True when [bytes] start like a WebP whose header says it is animated. */
    fun isAnimated(bytes: ByteArray): Boolean =
        bytes.size > VP8X_FLAGS && bytes.isFourCc(0, "RIFF") && bytes.isFourCc(8, "WEBP") &&
            bytes.isFourCc(RIFF_HEADER + FOURCC, "VP8X") && bytes[VP8X_FLAGS].toInt() and FLAG_ANIMATION != 0

    /** The frames of [bytes], or null when they are not an animated WebP or are cut short. */
    fun read(bytes: ByteArray): Animation? {
        if (!isAnimated(bytes) || bytes.size < VP8X_FLAGS + VP8X_PAYLOAD) return null
        val canvasWidth = bytes.u24At(VP8X_FLAGS + 4) + 1
        val canvasHeight = bytes.u24At(VP8X_FLAGS + 7) + 1
        val frames = ArrayList<Frame>()
        var at = RIFF_HEADER + FOURCC
        while (at + CHUNK_HEADER <= bytes.size) {
            val size = bytes.u32At(at + FOURCC)
            val end = at + CHUNK_HEADER + size
            if (size < 0 || end > bytes.size) return null
            if (bytes.isFourCc(at, "ANMF")) {
                frames += frame(bytes, at + CHUNK_HEADER, end) ?: return null
            }
            at = end + (size and 1)
        }
        return Animation(canvasWidth, canvasHeight, frames).takeIf { frames.isNotEmpty() }
    }

    private fun frame(bytes: ByteArray, from: Int, until: Int): Frame? {
        if (until - from < ANMF_HEADER) return null
        val width = bytes.u24At(from + 6) + 1
        val height = bytes.u24At(from + 9) + 1
        val flags = bytes[from + 15].toInt()
        val data = from + ANMF_HEADER

        // A still with transparency in a lossy frame needs the extended header
        // to say so; a lossless one carries its alpha in the bitstream.
        val hasAlphaChunk = hasChunk(bytes, data, until, "ALPH") ?: return null
        val body = ByteArrayOutputStream(until - data + VP8X_PAYLOAD + CHUNK_HEADER + FOURCC)
        body.fourCc("WEBP")
        if (hasAlphaChunk) {
            body.fourCc("VP8X")
            body.u32(VP8X_PAYLOAD)
            body.write(FLAG_ALPHA)
            body.u24(0)
            body.u24(width - 1)
            body.u24(height - 1)
        }
        body.write(bytes, data, until - data)
        if ((until - data) and 1 == 1) body.write(0)

        val still = ByteArrayOutputStream(body.size() + RIFF_HEADER)
        still.fourCc("RIFF")
        still.u32(body.size())
        body.writeTo(still)
        return Frame(
            // Offsets are stored halved.
            x = bytes.u24At(from) * 2,
            y = bytes.u24At(from + 3) * 2,
            width = width,
            height = height,
            durationMs = bytes.u24At(from + 12),
            blend = flags and FLAG_NO_BLEND == 0,
            disposeToBackground = flags and FLAG_DISPOSE != 0,
            still = still.toByteArray(),
        )
    }

    /** Whether the chunks in `[from, until)` include [tag]; null when they do not add up. */
    private fun hasChunk(bytes: ByteArray, from: Int, until: Int, tag: String): Boolean? {
        var at = from
        var found = false
        while (at + CHUNK_HEADER <= until) {
            val size = bytes.u32At(at + FOURCC)
            if (size < 0 || at + CHUNK_HEADER + size > until) return null
            if (bytes.isFourCc(at, tag)) found = true
            at += CHUNK_HEADER + size + (size and 1)
        }
        return found
    }

    private fun ByteArray.isFourCc(at: Int, tag: String): Boolean =
        at + FOURCC <= size && tag.indices.all { this[at + it] == tag[it].code.toByte() }

    private fun ByteArray.u24At(at: Int): Int =
        (this[at].toInt() and BYTE) or ((this[at + 1].toInt() and BYTE) shl 8) or ((this[at + 2].toInt() and BYTE) shl 16)

    private fun ByteArray.u32At(at: Int): Int = u24At(at) or ((this[at + 3].toInt() and BYTE) shl 24)

    private fun ByteArrayOutputStream.fourCc(tag: String) = tag.forEach { write(it.code) }

    private fun ByteArrayOutputStream.u24(value: Int) {
        write(value and BYTE)
        write((value shr 8) and BYTE)
        write((value shr 16) and BYTE)
    }

    private fun ByteArrayOutputStream.u32(value: Int) {
        u24(value)
        write((value shr 24) and BYTE)
    }

    private const val FOURCC = 4
    private const val CHUNK_HEADER = 8
    private const val RIFF_HEADER = 8
    private const val VP8X_PAYLOAD = 10

    /** Where the VP8X flags byte sits in a file that opens with a VP8X chunk. */
    private const val VP8X_FLAGS = 20
    private const val ANMF_HEADER = 16
    private const val FLAG_ANIMATION = 0x02
    private const val FLAG_ALPHA = 0x10
    private const val FLAG_NO_BLEND = 0x02
    private const val FLAG_DISPOSE = 0x01
    private const val BYTE = 0xFF
}
