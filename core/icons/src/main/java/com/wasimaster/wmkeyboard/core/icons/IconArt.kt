package com.wasimaster.wmkeyboard.core.icons

/**
 * One icon as it is stored: a vector document, or a raster image's bytes.
 *
 * Vectors were the only kind for a long time, and are still the better kind —
 * they recolour with the theme, scale to any key height and cost a few hundred
 * bytes. Raster is here because two whole worlds of icons are only ever raster:
 * every Gboard theme that replaces the delete, shift and enter glyphs ships them
 * as PNGs (264 of the 491 themes in the Rboard repository do), and a user who
 * draws an icon in anything but a vector editor has a PNG to hand (issue #504).
 *
 * The renderer is the only place that cares which this is, and it cares about
 * exactly one thing beyond drawing: whether the caller's tint applies. That is
 * [monochrome], and it means the same for both kinds — the icon declared no
 * colours of its own, so it tracks the theme like a built-in glyph. For a vector
 * it is read off the paint attributes; for a raster it is measured from the
 * pixels, because a white-on-transparent mask (the common case in Gboard themes,
 * and invisible on a light theme if drawn as authored) cannot be told from
 * deliberate white artwork any other way.
 */
sealed interface IconArt {

    /** Whether every pixel may be repainted in the caller's tint. */
    val monochrome: Boolean

    /** A parsed SVG. */
    data class Vector(val doc: SvgDoc) : IconArt {
        override val monochrome: Boolean get() = doc.monochrome
    }

    /**
     * A raster image, still encoded.
     *
     * Kept as bytes rather than decoded here because decoding needs
     * `android.graphics`, and this module is deliberately reachable from a plain
     * unit test. The renderer in `:feature:ime` decodes it once, off the main
     * thread, the same way it builds a vector once.
     *
     * [monochrome] is not known from the header, so it is null until something
     * that can read pixels fills it in; [IconArt.monochrome] reads null as "not
     * monochrome", which is the safe answer — drawing an icon as its author
     * painted it is never illegible, while tinting deliberate artwork flat is.
     */
    class Raster(
        val bytes: ByteArray,
        val format: RasterFormat,
        val width: Int,
        val height: Int,
        val mask: Boolean? = null,
    ) : IconArt {
        override val monochrome: Boolean get() = mask == true

        /** The same image with the pixel verdict filled in. */
        fun withMask(value: Boolean): Raster = Raster(bytes, format, width, height, value)

        // A ByteArray compares by identity, and a resolved icon rides in
        // Compose state where that difference is visible.
        override fun equals(other: Any?): Boolean =
            this === other || (
                other is Raster && format == other.format && width == other.width &&
                    height == other.height && mask == other.mask &&
                    bytes.size == other.bytes.size && bytes.contentEquals(other.bytes)
                )

        override fun hashCode(): Int =
            ((format.hashCode() * 31 + width) * 31 + height) * 31 + bytes.size
    }
}

/**
 * The raster formats an icon may be in: the four every Android version decodes,
 * which is also exactly what the theme importers find in the wild.
 *
 * [extension] is what the file is stored as inside a pack, and is derived from
 * the *bytes*, never from the name a file or an archive entry arrived with — so
 * a `.png` that is really a JPEG is stored as the JPEG it is, and a name that
 * tries to be a path never reaches the file system. See [RasterIcons.read].
 */
enum class RasterFormat(val extension: String, val mimeType: String) {
    PNG("png", "image/png"),
    WEBP("webp", "image/webp"),
    JPEG("jpg", "image/jpeg"),
    GIF("gif", "image/gif"),
}

/**
 * Reading a raster icon's header: what format it is, and how big.
 *
 * Written by hand rather than handed to `BitmapFactory` for two reasons. It
 * runs where there is no Android — the icon-pack import and the Gboard theme
 * converter are both plain functions a unit test drives — and it has to answer
 * "is this an image at all" *before* anything allocates a bitmap for it, which
 * is the whole point of a size guard against a stranger's file.
 *
 * Nothing here throws. A truncated or hostile file is simply not an icon.
 */
object RasterIcons {

    /**
     * Largest encoded icon worth keeping.
     *
     * A Gboard theme's glyphs are 1–4 KB each and a hand-drawn PNG at a sane
     * size is tens of KB, so this is two orders of magnitude of headroom. It is
     * deliberately larger than [SvgParser.MAX_SOURCE_BYTES]: an SVG's size is
     * its source text, a PNG's is its pixels.
     */
    const val MAX_SOURCE_BYTES = 512 * 1024

    /**
     * Largest edge an icon may declare.
     *
     * An icon is drawn into a box a key tall. Past this the file is either not
     * an icon or is a decompression bomb waiting for a bitmap to be allocated
     * for it, and the two are not worth telling apart.
     */
    const val MAX_DIMENSION = 2048

    /** What a picker should offer, alongside the vector types. */
    val IMPORT_MIME_TYPES: Array<String> = arrayOf(
        "image/png",
        "image/webp",
        "image/jpeg",
        "image/gif",
    )

    /** Every extension a pack's icon file may carry, vectors included. */
    val FILE_EXTENSIONS: List<String> = listOf("svg") + RasterFormat.entries.map { it.extension }

    /**
     * [bytes] as a raster icon, or null when they are not one this can draw.
     *
     * The caps are applied here rather than by the caller so that every route
     * into a pack — the archive, the file picker, a theme import — is bounded by
     * the same numbers.
     */
    fun read(bytes: ByteArray): IconArt.Raster? {
        if (bytes.size > MAX_SOURCE_BYTES) return null
        val format = formatOf(bytes) ?: return null
        val size = sizeOf(bytes, format) ?: return null
        val (width, height) = size
        if (width <= 0 || height <= 0 || width > MAX_DIMENSION || height > MAX_DIMENSION) return null
        return IconArt.Raster(bytes, format, width, height)
    }

    /** The format [bytes] are in, by their first bytes alone. */
    @Suppress("ReturnCount")
    fun formatOf(bytes: ByteArray): RasterFormat? {
        if (bytes.size < MIN_HEADER) return null
        fun at(index: Int) = bytes[index].toInt() and 0xFF
        if (at(0) == 0x89 && at(1) == 0x50 && at(2) == 0x4E && at(3) == 0x47) return RasterFormat.PNG
        if (at(0) == 0xFF && at(1) == 0xD8 && at(2) == 0xFF) return RasterFormat.JPEG
        if (at(0) == 'G'.code && at(1) == 'I'.code && at(2) == 'F'.code && at(3) == '8'.code) {
            return RasterFormat.GIF
        }
        val riff = at(0) == 'R'.code && at(1) == 'I'.code && at(2) == 'F'.code && at(3) == 'F'.code
        val webp = at(8) == 'W'.code && at(9) == 'E'.code && at(10) == 'B'.code && at(11) == 'P'.code
        if (riff && webp) return RasterFormat.WEBP
        return null
    }

    /** `width to height`, or null when the header does not state it readably. */
    fun sizeOf(bytes: ByteArray, format: RasterFormat): Pair<Int, Int>? = when (format) {
        RasterFormat.PNG -> pngSize(bytes)
        RasterFormat.GIF -> gifSize(bytes)
        RasterFormat.JPEG -> jpegSize(bytes)
        RasterFormat.WEBP -> webpSize(bytes)
    }

    /** IHDR is always the first chunk, so the two sizes sit at a fixed offset. */
    private fun pngSize(bytes: ByteArray): Pair<Int, Int>? {
        if (bytes.size < 24) return null
        return bytes.int32(16) to bytes.int32(20)
    }

    /** The logical screen descriptor, little-endian, right after the signature. */
    private fun gifSize(bytes: ByteArray): Pair<Int, Int>? {
        if (bytes.size < 10) return null
        return bytes.int16LE(6) to bytes.int16LE(8)
    }

    /**
     * The first start-of-frame marker's height and width.
     *
     * JPEG carries its size inside a segment rather than in the header, so the
     * segments have to be walked. Bounded by the file's own length and by
     * [MAX_SEGMENTS], because a crafted file can otherwise describe a loop.
     */
    @Suppress("ReturnCount")
    private fun jpegSize(bytes: ByteArray): Pair<Int, Int>? {
        var index = 2
        var segments = 0
        while (index + 9 < bytes.size && segments++ < MAX_SEGMENTS) {
            if ((bytes[index].toInt() and 0xFF) != 0xFF) return null
            val marker = bytes[index + 1].toInt() and 0xFF
            // Standalone markers carry no length, and a fill byte repeats 0xFF.
            if (marker == 0xFF) {
                index++
                continue
            }
            if (marker == 0xD8 || marker == 0x01 || marker in 0xD0..0xD7) {
                index += 2
                continue
            }
            val length = bytes.int16(index + 2)
            if (length < 2) return null
            // Every SOF but the arithmetic/huffman-table markers in the range.
            if (marker in 0xC0..0xCF && marker != 0xC4 && marker != 0xC8 && marker != 0xCC) {
                return bytes.int16(index + 7) to bytes.int16(index + 5)
            }
            index += 2 + length
        }
        return null
    }

    /**
     * The size out of a `VP8 `, `VP8L` or `VP8X` chunk.
     *
     * Three encodings of the same number, because WebP's three flavours each
     * spell it differently: lossy puts 14-bit values after a sync code, lossless
     * packs two 14-bit values minus one into a little-endian run, and the
     * extended header states them as 24-bit values minus one.
     */
    @Suppress("ReturnCount", "MagicNumber")
    private fun webpSize(bytes: ByteArray): Pair<Int, Int>? {
        if (bytes.size < 30) return null
        return when (String(bytes, 12, 4, Charsets.US_ASCII)) {
            "VP8 " -> {
                // 3-byte frame tag, then the 3-byte sync code 0x9D 0x01 0x2A.
                if (bytes.size < 30) return null
                bytes.int16LE(26) and 0x3FFF to (bytes.int16LE(28) and 0x3FFF)
            }

            "VP8L" -> {
                val packed = bytes.int32LE(21)
                ((packed and 0x3FFF) + 1) to (((packed ushr 14) and 0x3FFF) + 1)
            }

            "VP8X" -> (bytes.int24LE(24) + 1) to (bytes.int24LE(27) + 1)
            else -> null
        }
    }

    private fun ByteArray.byteAt(index: Int): Int = this[index].toInt() and 0xFF

    private fun ByteArray.int32(at: Int): Int =
        (byteAt(at) shl 24) or (byteAt(at + 1) shl 16) or (byteAt(at + 2) shl 8) or byteAt(at + 3)

    private fun ByteArray.int16(at: Int): Int = (byteAt(at) shl 8) or byteAt(at + 1)

    private fun ByteArray.int16LE(at: Int): Int = byteAt(at) or (byteAt(at + 1) shl 8)

    private fun ByteArray.int24LE(at: Int): Int =
        byteAt(at) or (byteAt(at + 1) shl 8) or (byteAt(at + 2) shl 16)

    private fun ByteArray.int32LE(at: Int): Int =
        byteAt(at) or (byteAt(at + 1) shl 8) or (byteAt(at + 2) shl 16) or (byteAt(at + 3) shl 24)

    /** Enough for the longest signature here, WebP's twelve bytes. */
    private const val MIN_HEADER = 12

    /** More segments than any real JPEG has before its first frame. */
    private const val MAX_SEGMENTS = 512
}
