package com.wasimaster.wmkeyboard.core.media

import java.io.ByteArrayOutputStream

/**
 * Builds an animated WebP out of still ones.
 *
 * Android has no encoder for animated WebP, but it has one for stills
 * (`Bitmap.compress`), and an animated WebP is a container around exactly the
 * chunks a still one is made of. So each frame is encoded as a still by the
 * platform, its image chunks are lifted out, and this wraps them:
 *
 * ```
 * RIFF <size> WEBP
 *   VP8X  flags (animation, alpha), canvas size
 *   ANIM  background colour, loop count
 *   ANMF  frame 1: position, size, duration, flags, then its ALPH / VP8 / VP8L
 *   ANMF  frame 2 …
 * ```
 *
 * Every frame covers the whole canvas and replaces what was there ("do not
 * blend"), so no frame depends on the one before it and the caller hands in
 * finished pictures, not deltas. Frames are added as compressed bytes and
 * nothing else is held, which is what keeps a forty-frame sticker from needing
 * forty bitmaps in memory.
 *
 * No android.graphics in here, so the container is tested on the JVM.
 */
class AnimatedWebpWriter(private val width: Int, private val height: Int) {

    private val frames = ByteArrayOutputStream()

    var frameCount: Int = 0
        private set

    init {
        require(width in 1..MAX_DIMENSION && height in 1..MAX_DIMENSION) { "canvas $width x $height" }
    }

    /**
     * Adds one frame. [stillWebp] is a whole still WebP file of the canvas's
     * size; returns false, adding nothing, when it is not one.
     */
    fun addFrame(stillWebp: ByteArray, durationMs: Int): Boolean {
        val image = imageChunks(stillWebp) ?: return false
        frames.fourCc("ANMF")
        frames.u32(ANMF_HEADER + image.size)
        frames.u24(0) // x / 2
        frames.u24(0) // y / 2
        frames.u24(width - 1)
        frames.u24(height - 1)
        frames.u24(durationMs.coerceIn(0, MAX_U24))
        frames.write(NO_BLEND)
        frames.write(image)
        // Image chunks are each padded to an even length already, so the
        // frame's payload is even too and needs no padding of its own.
        frameCount++
        return true
    }

    /** The finished file. Meaningful once at least one frame went in. */
    fun build(): ByteArray {
        val body = ByteArrayOutputStream()
        body.fourCc("WEBP")

        body.fourCc("VP8X")
        body.u32(VP8X_PAYLOAD)
        body.write(FLAG_ANIMATION or FLAG_ALPHA)
        body.u24(0) // reserved
        body.u24(width - 1)
        body.u24(height - 1)

        body.fourCc("ANIM")
        body.u32(ANIM_PAYLOAD)
        body.u32(0) // background: transparent
        body.u16(0) // loop for ever

        frames.writeTo(body)

        val out = ByteArrayOutputStream(body.size() + RIFF_HEADER)
        out.fourCc("RIFF")
        out.u32(body.size())
        body.writeTo(out)
        return out.toByteArray()
    }

    /**
     * The chunks of [webp] that hold its picture (`ALPH`, `VP8 `, `VP8L`), each
     * with its header and padding, in the order they came. Everything else a
     * still may carry (`VP8X`, a colour profile, EXIF, XMP) belongs to the file
     * and not to a frame.
     */
    private fun imageChunks(webp: ByteArray): ByteArray? {
        if (webp.size < RIFF_HEADER + CHUNK_HEADER || !webp.isFourCc(0, "RIFF") || !webp.isFourCc(8, "WEBP")) {
            return null
        }
        val kept = ByteArrayOutputStream()
        var hasBitstream = false
        var at = RIFF_HEADER + FOURCC
        while (at + CHUNK_HEADER <= webp.size) {
            val size = webp.u32At(at + FOURCC)
            val padded = size + (size and 1)
            val end = at + CHUNK_HEADER + size
            if (size < 0 || end > webp.size) return null
            val bitstream = webp.isFourCc(at, "VP8 ") || webp.isFourCc(at, "VP8L")
            if (bitstream || webp.isFourCc(at, "ALPH")) {
                kept.write(webp, at, CHUNK_HEADER + size)
                if (size and 1 == 1) kept.write(0)
                hasBitstream = hasBitstream || bitstream
            }
            at += CHUNK_HEADER + padded
        }
        return kept.toByteArray().takeIf { hasBitstream }
    }

    private fun ByteArray.isFourCc(at: Int, tag: String): Boolean =
        at + FOURCC <= size && tag.indices.all { this[at + it] == tag[it].code.toByte() }

    private fun ByteArray.u32At(at: Int): Int =
        (this[at].toInt() and BYTE) or
            ((this[at + 1].toInt() and BYTE) shl 8) or
            ((this[at + 2].toInt() and BYTE) shl 16) or
            ((this[at + 3].toInt() and BYTE) shl 24)

    private fun ByteArrayOutputStream.fourCc(tag: String) = tag.forEach { write(it.code) }

    private fun ByteArrayOutputStream.u16(value: Int) {
        write(value and BYTE)
        write((value shr 8) and BYTE)
    }

    private fun ByteArrayOutputStream.u24(value: Int) {
        u16(value)
        write((value shr 16) and BYTE)
    }

    private fun ByteArrayOutputStream.u32(value: Int) {
        u24(value)
        write((value shr 24) and BYTE)
    }

    private companion object {
        const val FOURCC = 4
        const val CHUNK_HEADER = 8
        const val RIFF_HEADER = 8
        const val VP8X_PAYLOAD = 10
        const val ANIM_PAYLOAD = 6
        const val ANMF_HEADER = 16
        const val FLAG_ANIMATION = 0x02
        const val FLAG_ALPHA = 0x10

        /** ANMF flags: bit 1 set is "do not blend", bit 0 clear is "do not dispose". */
        const val NO_BLEND = 0x02
        const val BYTE = 0xFF
        const val MAX_U24 = 0xFFFFFF
        const val MAX_DIMENSION = 16384
    }
}
