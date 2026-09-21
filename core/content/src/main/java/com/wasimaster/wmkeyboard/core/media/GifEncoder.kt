package com.wasimaster.wmkeyboard.core.media

import java.io.OutputStream

/**
 * Writes an animated GIF with a transparent background, one frame at a time.
 *
 * GIF is what is left when a field takes no WebP: every app that takes any
 * picture at all takes a GIF. It is a poor home for a sticker (256 colours a
 * frame, and a pixel is either there or not, so soft edges go hard), which is
 * why this is only ever the fallback.
 *
 * Each frame is a finished picture of the whole canvas as ARGB pixels. It gets
 * a palette of its own, up to 255 colours chosen by median cut plus one index
 * kept for "nothing here", is cropped to the part that has anything in it, and
 * asks for the canvas to be cleared before the next frame, so no frame leans
 * on the one before. Pixels under half opacity become transparent.
 *
 * No android.graphics in here: the caller hands in `IntArray`s, and the
 * encoder is tested on the JVM against the JDK's own GIF reader.
 */
class GifEncoder(private val out: OutputStream, private val width: Int, private val height: Int) {

    private var started = false
    private var finished = false

    init {
        require(width in 1..MAX_DIMENSION && height in 1..MAX_DIMENSION) { "canvas $width x $height" }
    }

    /** Adds one frame of `width * height` ARGB pixels, shown for [durationMs]. */
    fun addFrame(argb: IntArray, durationMs: Int) {
        require(argb.size == width * height) { "frame of ${argb.size} pixels on a $width x $height canvas" }
        check(!finished) { "already finished" }
        if (!started) writeHeader()

        val box = opaqueBounds(argb)
        val palette = Palette.of(argb)
        val bits = palette.bits

        // Graphic control: clear to background afterwards, index 0 is transparent.
        out.write(EXTENSION)
        out.write(GRAPHIC_CONTROL)
        out.write(GRAPHIC_CONTROL_SIZE)
        out.write((DISPOSE_TO_BACKGROUND shl 2) or TRANSPARENT_FLAG)
        u16((durationMs.coerceIn(MIN_DELAY_MS, MAX_DELAY_MS) + HALF_TICK) / MS_PER_TICK)
        out.write(Palette.TRANSPARENT)
        out.write(0)

        out.write(IMAGE)
        u16(box.left)
        u16(box.top)
        u16(box.width)
        u16(box.height)
        out.write(LOCAL_TABLE_FLAG or (bits - 1))
        out.write(palette.rgb, 0, (1 shl bits) * RGB)

        val indices = ByteArray(box.width * box.height)
        var at = 0
        for (y in box.top until box.top + box.height) {
            val row = y * width
            for (x in box.left until box.left + box.width) indices[at++] = palette.indexOf(argb[row + x])
        }
        Lzw(out, maxOf(bits, MIN_CODE_BITS)).encode(indices)
    }

    /** Writes the trailer. A file with no frames is still a valid, empty GIF. */
    fun finish() {
        if (finished) return
        if (!started) writeHeader()
        out.write(TRAILER)
        out.flush()
        finished = true
    }

    private fun writeHeader() {
        started = true
        out.write("GIF89a".toByteArray(Charsets.US_ASCII))
        u16(width)
        u16(height)
        out.write(NO_GLOBAL_TABLE)
        out.write(0) // background index
        out.write(0) // aspect ratio
        // Loop for ever.
        out.write(EXTENSION)
        out.write(APPLICATION)
        out.write(APPLICATION_SIZE)
        out.write("NETSCAPE2.0".toByteArray(Charsets.US_ASCII))
        out.write(LOOP_BLOCK_SIZE)
        out.write(1)
        u16(0)
        out.write(0)
    }

    private fun u16(value: Int) {
        out.write(value and BYTE)
        out.write((value shr 8) and BYTE)
    }

    private class Bounds(val left: Int, val top: Int, val width: Int, val height: Int)

    /** The smallest rectangle holding every pixel that will be drawn; one pixel when there are none. */
    private fun opaqueBounds(argb: IntArray): Bounds {
        var left = width
        var top = height
        var right = -1
        var bottom = -1
        for (y in 0 until height) {
            val row = y * width
            for (x in 0 until width) {
                if (argb[row + x] ushr ALPHA_SHIFT >= OPAQUE_FROM) {
                    if (x < left) left = x
                    if (x > right) right = x
                    if (y < top) top = y
                    if (y > bottom) bottom = y
                }
            }
        }
        return if (right < 0) Bounds(0, 0, 1, 1) else Bounds(left, top, right - left + 1, bottom - top + 1)
    }

    /**
     * A frame's colours: index 0 is transparent, the rest come from a median
     * cut over the frame's opaque pixels, counted at five bits a channel.
     */
    private class Palette(val rgb: ByteArray, val bits: Int, private val lookup: ByteArray) {

        fun indexOf(argb: Int): Byte =
            if (argb ushr ALPHA_SHIFT < OPAQUE_FROM) TRANSPARENT.toByte() else lookup[bucket(argb)]

        /** A run of [used] buckets, which one split turns into two. */
        private class Box(val from: Int, val until: Int, val population: Long)

        companion object {
            const val TRANSPARENT = 0
            private const val MAX_COLOURS = 255
            private const val BUCKET_BITS = 5
            private const val BUCKETS = 1 shl (BUCKET_BITS * RGB)
            private const val CHANNEL_MASK = (1 shl BUCKET_BITS) - 1

            private fun bucket(argb: Int): Int {
                val r = (argb shr RED_SHIFT and BYTE) shr (BYTE_BITS - BUCKET_BITS)
                val g = (argb shr GREEN_SHIFT and BYTE) shr (BYTE_BITS - BUCKET_BITS)
                val b = (argb and BYTE) shr (BYTE_BITS - BUCKET_BITS)
                return (r shl (BUCKET_BITS * 2)) or (g shl BUCKET_BITS) or b
            }

            private fun channel(bucket: Int, which: Int): Int =
                bucket shr (BUCKET_BITS * (2 - which)) and CHANNEL_MASK

            fun of(argb: IntArray): Palette {
                // Per bucket: how many pixels, and the sum of each true channel,
                // so that a palette entry is the mean of what it stands for and
                // not the corner of a bucket.
                val count = IntArray(BUCKETS)
                val sums = LongArray(BUCKETS * RGB)
                for (pixel in argb) {
                    if (pixel ushr ALPHA_SHIFT < OPAQUE_FROM) continue
                    val at = bucket(pixel)
                    count[at]++
                    sums[at * RGB] += (pixel shr RED_SHIFT and BYTE).toLong()
                    sums[at * RGB + 1] += (pixel shr GREEN_SHIFT and BYTE).toLong()
                    sums[at * RGB + 2] += (pixel and BYTE).toLong()
                }
                val used = (0 until BUCKETS).filter { count[it] > 0 }.toIntArray()
                val boxes = split(used, count)

                // Entry 0 is the transparent one; the palette is padded to a
                // power of two, which is the only size GIF can declare.
                var bits = 1
                while (1 shl bits < boxes.size + 1) bits++
                val rgb = ByteArray((1 shl bits) * RGB)
                val lookup = ByteArray(BUCKETS)
                boxes.forEachIndexed { index, box ->
                    var n = 0L
                    var r = 0L
                    var g = 0L
                    var b = 0L
                    for (i in box.from until box.until) {
                        val at = used[i]
                        n += count[at]
                        r += sums[at * RGB]
                        g += sums[at * RGB + 1]
                        b += sums[at * RGB + 2]
                        lookup[at] = (index + 1).toByte()
                    }
                    val entry = (index + 1) * RGB
                    rgb[entry] = (r / n).toByte()
                    rgb[entry + 1] = (g / n).toByte()
                    rgb[entry + 2] = (b / n).toByte()
                }
                return Palette(rgb, bits, lookup)
            }

            /**
             * Sorts `used[from, until)` by one channel. Through a key with
             * the channel on top and the bucket underneath, because a range
             * of an `IntArray` sorts by value and not by a comparator.
             */
            private fun sortByChannel(used: IntArray, from: Int, until: Int, which: Int) {
                val keyed = LongArray(until - from) { i ->
                    (channel(used[from + i], which).toLong() shl Int.SIZE_BITS) or used[from + i].toLong()
                }
                keyed.sort()
                for (i in keyed.indices) used[from + i] = keyed[i].toInt()
            }

            /** Median cut: keep halving the most populous box that still holds more than one colour. */
            private fun split(used: IntArray, count: IntArray): List<Box> {
                if (used.isEmpty()) return emptyList()
                fun population(from: Int, until: Int): Long {
                    var n = 0L
                    for (i in from until until) n += count[used[i]]
                    return n
                }
                val boxes = arrayListOf(Box(0, used.size, population(0, used.size)))
                while (boxes.size < MAX_COLOURS) {
                    val at = boxes.indices
                        .filter { boxes[it].until - boxes[it].from > 1 }
                        .maxByOrNull { boxes[it].population } ?: break
                    val box = boxes[at]
                    // The channel this box is widest along.
                    val widest = (0 until RGB).maxByOrNull { which ->
                        var lo = Int.MAX_VALUE
                        var hi = Int.MIN_VALUE
                        for (i in box.from until box.until) {
                            val v = channel(used[i], which)
                            if (v < lo) lo = v
                            if (v > hi) hi = v
                        }
                        hi - lo
                    } ?: 0
                    sortByChannel(used, box.from, box.until, widest)
                    // Cut where half the pixels are on each side, but never so
                    // that one side is empty.
                    var seen = 0L
                    var cut = box.from + 1
                    for (i in box.from until box.until - 1) {
                        seen += count[used[i]]
                        cut = i + 1
                        if (seen * 2 >= box.population) break
                    }
                    boxes[at] = Box(box.from, cut, population(box.from, cut))
                    boxes += Box(cut, box.until, population(cut, box.until))
                }
                return boxes
            }
        }
    }

    /** GIF's flavour of LZW: variable-width codes, least significant bit first, in blocks of up to 255 bytes. */
    private class Lzw(private val out: OutputStream, private val dataBits: Int) {

        private val clear = 1 shl dataBits
        private val end = clear + 1

        // (prefix code, next byte) -> code, open addressing.
        private val keys = IntArray(TABLE_SIZE)
        private val codes = IntArray(TABLE_SIZE)

        private val block = ByteArray(MAX_BLOCK)
        private var blockFill = 0
        private var accumulator = 0
        private var accumulated = 0

        fun encode(indices: ByteArray) {
            out.write(dataBits)
            var width = dataBits + 1
            var next = end + 1
            keys.fill(EMPTY)
            emit(clear, width)

            var prefix = indices[0].toInt() and BYTE
            for (i in 1 until indices.size) {
                val byte = indices[i].toInt() and BYTE
                val key = (prefix shl BYTE_BITS) or byte
                var slot = (key * HASH_MULTIPLIER ushr HASH_SHIFT) and TABLE_MASK
                while (keys[slot] != EMPTY && keys[slot] != key) slot = (slot + 1) and TABLE_MASK
                if (keys[slot] == key) {
                    prefix = codes[slot]
                    continue
                }
                emit(prefix, width)
                prefix = byte
                if (next < MAX_CODES) {
                    keys[slot] = key
                    codes[slot] = next
                    // The decoder widens one code later than the table grows.
                    if (next == 1 shl width) width++
                    next++
                } else {
                    emit(clear, width)
                    keys.fill(EMPTY)
                    width = dataBits + 1
                    next = end + 1
                }
            }
            emit(prefix, width)
            emit(end, width)
            if (accumulated > 0) put(accumulator and BYTE)
            flushBlock()
            out.write(0)
        }

        private fun emit(code: Int, width: Int) {
            accumulator = accumulator or (code shl accumulated)
            accumulated += width
            while (accumulated >= BYTE_BITS) {
                put(accumulator and BYTE)
                accumulator = accumulator ushr BYTE_BITS
                accumulated -= BYTE_BITS
            }
        }

        private fun put(byte: Int) {
            block[blockFill++] = byte.toByte()
            if (blockFill == MAX_BLOCK) flushBlock()
        }

        private fun flushBlock() {
            if (blockFill == 0) return
            out.write(blockFill)
            out.write(block, 0, blockFill)
            blockFill = 0
        }

        private companion object {
            const val MAX_CODES = 4096
            const val TABLE_SIZE = 16384
            const val TABLE_MASK = TABLE_SIZE - 1
            const val EMPTY = -1
            const val MAX_BLOCK = 255
            const val HASH_MULTIPLIER = -1640531535 // 2^32 / golden ratio
            const val HASH_SHIFT = 18
        }
    }

    private companion object {
        const val EXTENSION = 0x21
        const val GRAPHIC_CONTROL = 0xF9
        const val GRAPHIC_CONTROL_SIZE = 4
        const val APPLICATION = 0xFF
        const val APPLICATION_SIZE = 11
        const val LOOP_BLOCK_SIZE = 3
        const val IMAGE = 0x2C
        const val TRAILER = 0x3B
        const val NO_GLOBAL_TABLE = 0x70
        const val LOCAL_TABLE_FLAG = 0x80
        const val DISPOSE_TO_BACKGROUND = 2
        const val TRANSPARENT_FLAG = 1
        const val MIN_CODE_BITS = 2
        const val RGB = 3
        const val BYTE = 0xFF
        const val BYTE_BITS = 8
        const val ALPHA_SHIFT = 24
        const val RED_SHIFT = 16
        const val GREEN_SHIFT = 8

        /** Half opacity and up is drawn; under it is not. */
        const val OPAQUE_FROM = 128
        const val MS_PER_TICK = 10
        const val HALF_TICK = 5

        /** Players treat a delay under two ticks as "unset" and slow it to a tenth of a second. */
        const val MIN_DELAY_MS = 20
        const val MAX_DELAY_MS = 655_350
        const val MAX_DIMENSION = 65_535
    }
}
