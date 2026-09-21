package com.wasimaster.wmkeyboard.core.stickers.signal

/** One sticker as a Signal pack lists it. [id] is also its file name on the CDN. */
data class SignalSticker(
    val id: Int,
    val emoji: String = "",
    /** Newer packs say `image/webp`, `image/png` or `image/apng`; older ones say nothing. */
    val contentType: String = "",
)

/**
 * A Signal pack's decrypted `manifest.proto`.
 *
 * ```
 * message Pack {
 *   message Sticker { uint32 id = 1; string emoji = 2; string contentType = 3; }
 *   string title = 1; string author = 2; Sticker cover = 3; repeated Sticker stickers = 4;
 * }
 * ```
 *
 * Read by hand: it is two messages and four field types, and the app carries no
 * protobuf runtime to read them with.
 */
data class SignalPackManifest(
    val title: String,
    val author: String,
    /** The sticker shown for the pack. Falls back to the first one listed. */
    val coverId: Int,
    val stickers: List<SignalSticker>,
) {
    companion object {

        private const val TITLE = 1
        private const val AUTHOR = 2
        private const val COVER = 3
        private const val STICKERS = 4

        private const val STICKER_ID = 1
        private const val STICKER_EMOJI = 2
        private const val STICKER_CONTENT_TYPE = 3

        /** The manifest in [bytes], or null when they are not one. */
        fun parse(bytes: ByteArray): SignalPackManifest? {
            var title = ""
            var author = ""
            var cover: SignalSticker? = null
            val stickers = ArrayList<SignalSticker>()
            val reader = ProtoReader(bytes)
            while (!reader.exhausted) {
                val field = reader.next() ?: return null
                when (field.number) {
                    TITLE -> title = field.text(bytes)
                    AUTHOR -> author = field.text(bytes)
                    COVER -> cover = sticker(bytes, field) ?: return null
                    STICKERS -> stickers += sticker(bytes, field) ?: return null
                }
            }
            // Ids are file names on the CDN; a pack that lists one twice would
            // download the same picture twice.
            val distinct = stickers.distinctBy { it.id }
            if (distinct.isEmpty() && title.isEmpty() && author.isEmpty()) return null
            return SignalPackManifest(
                title = title.trim(),
                author = author.trim(),
                coverId = cover?.id ?: distinct.firstOrNull()?.id ?: 0,
                stickers = distinct,
            )
        }

        private fun sticker(bytes: ByteArray, span: ProtoField): SignalSticker? {
            if (!span.isBytes) return null
            var id = 0
            var emoji = ""
            var contentType = ""
            val reader = ProtoReader(bytes, span.from, span.until)
            while (!reader.exhausted) {
                val field = reader.next() ?: return null
                when (field.number) {
                    STICKER_ID -> id = field.value.toInt()
                    STICKER_EMOJI -> emoji = field.text(bytes)
                    STICKER_CONTENT_TYPE -> contentType = field.text(bytes)
                }
            }
            if (id < 0) return null
            return SignalSticker(id, emoji.trim(), contentType.trim())
        }
    }
}

/**
 * One protobuf field: its number, and either a [value] (varint and the two
 * fixed widths) or the range of a length-delimited payload.
 */
internal class ProtoField(
    val number: Int,
    val value: Long,
    val from: Int,
    val until: Int,
    val isBytes: Boolean,
) {
    fun text(data: ByteArray): String = if (isBytes) data.decodeToString(from, until) else ""
}

/**
 * Just enough protobuf to walk a message: every wire type is read or skipped,
 * so a field added to the format later does not break the ones read here.
 * Anything malformed ends the walk with null.
 */
internal class ProtoReader(
    private val data: ByteArray,
    private var offset: Int = 0,
    private val end: Int = data.size,
) {
    val exhausted: Boolean get() = offset >= end

    fun next(): ProtoField? {
        val key = varint() ?: return null
        val number = (key ushr FIELD_SHIFT).toInt()
        if (number <= 0) return null
        return when ((key and WIRE_BITS).toInt()) {
            WIRE_VARINT -> ProtoField(number, varint() ?: return null, 0, 0, isBytes = false)
            WIRE_FIXED64 -> fixed(number, FIXED64_BYTES)
            WIRE_FIXED32 -> fixed(number, FIXED32_BYTES)
            WIRE_LENGTH_DELIMITED -> {
                val length = varint() ?: return null
                if (length < 0 || length > end - offset) return null
                val from = offset
                offset += length.toInt()
                ProtoField(number, 0L, from, offset, isBytes = true)
            }
            // Groups are long deprecated and nothing Signal writes uses them.
            else -> null
        }
    }

    private fun fixed(number: Int, width: Int): ProtoField? {
        if (width > end - offset) return null
        var value = 0L
        for (i in 0 until width) value = value or ((data[offset + i].toLong() and BYTE_MASK) shl (i * Byte.SIZE_BITS))
        offset += width
        return ProtoField(number, value, 0, 0, isBytes = false)
    }

    private fun varint(): Long? {
        var result = 0L
        var shift = 0
        while (offset < end && shift <= MAX_SHIFT) {
            // Masked, not just widened: a byte over 0x7F is negative as a
            // Kotlin Byte and sign-extends into every high bit.
            val byte = data[offset++].toLong() and BYTE_MASK
            result = result or ((byte and PAYLOAD_MASK) shl shift)
            if (byte and CONTINUATION_MASK == 0L) return result
            shift += VARINT_BITS
        }
        return null
    }

    private companion object {
        const val WIRE_BITS = 7L
        const val FIELD_SHIFT = 3
        const val WIRE_VARINT = 0
        const val WIRE_FIXED64 = 1
        const val WIRE_LENGTH_DELIMITED = 2
        const val WIRE_FIXED32 = 5
        const val FIXED64_BYTES = 8
        const val FIXED32_BYTES = 4
        const val BYTE_MASK = 0xFFL
        const val PAYLOAD_MASK = 0x7FL
        const val CONTINUATION_MASK = 0x80L
        const val VARINT_BITS = 7
        const val MAX_SHIFT = 63
    }
}
