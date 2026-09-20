package com.wasimaster.wmkeyboard.core.prediction

/**
 * Reading a compiled AOSP `.dict` dictionary.
 *
 * This is the binary every AOSP-derived keyboard ships and every dictionary
 * repository publishes: HeliBoard's and LeanType's downloads, FUTO's, the ones
 * pulled out of a GApps package, and anything built with the `dicttool`
 * compiler. It is also the only form most of those dictionaries exist in, so
 * without this a user moving over has to find the source word list again, and
 * for most languages there is not one.
 *
 * **Written from the published format**, on the same footing as the layout
 * converters in `:core:language`: AOSP's `FormatSpec` documents the byte layout
 * in its own header comment and that description is what this follows. No
 * upstream code is copied; AOSP LatinIME is Apache-2.0 and this app is MIT.
 *
 * ### What it reads, and what it does not
 *
 * Words and their frequencies, which is all this app has anywhere to put. The
 * file's bigrams and shortcut lists are walked past rather than read: an
 * imported list here is a flat set of words, and a shortcut (`teh` standing in
 * for `the`) is an expansion, not a word anybody typed.
 *
 * Version 2 files, in all of their revisions (2, 201, 202, 203), static or
 * dynamic. Version 4 is not a file at all but a directory of them, so a `.dict`
 * that turns out to be one is refused rather than half-read.
 *
 * A file carrying a `codePointTable` is refused as well. That header attribute
 * re-points the one-byte character encoding at a table, so reading one without
 * applying the table does not fail — it silently produces different words.
 * Refusing is the only honest answer this can give without the table's own
 * semantics pinned down against a real file that uses it.
 */
object AospDictionary {

    /** The first four bytes of every version of this format. */
    const val MAGIC: Int = -0x643EC502 // 0x9BC13AFE

    /** Enough of the file to answer [looksLikeDictionary]. */
    const val MAGIC_BYTES: Int = 4

    /** What [read] gives back. */
    sealed interface Result {

        /** Words and their frequencies, already on this app's 1..10000 scale. */
        data class Words(val entries: List<Pair<String, Int>>) : Result

        /** Not this format at all. */
        data object NotADictionary : Result

        /**
         * This format, but a shape this reader will not guess at: version 4,
         * or a file whose characters are indirected through a code point table.
         */
        data class Unsupported(val version: Int) : Result
    }

    /** True when [head] (at least [MAGIC_BYTES] long) starts with the magic number. */
    fun looksLikeDictionary(head: ByteArray): Boolean =
        head.size >= MAGIC_BYTES && readInt(head, 0) == MAGIC

    /**
     * Reads every word out of [bytes].
     *
     * Never throws: a truncated file, a cyclic children address and a file that
     * is not a dictionary at all are all ordinary outcomes. The walk is bounded
     * on both axes ([MAX_DEPTH], [MAX_WORDS]) so a corrupt file cannot spin.
     */
    fun read(bytes: ByteArray): Result {
        if (!looksLikeDictionary(bytes)) return Result.NotADictionary
        val header = readHeader(bytes) ?: return Result.NotADictionary
        if (header.version >= FIRST_VERSION_4) return Result.Unsupported(header.version)
        if (header.hasCodePointTable) return Result.Unsupported(header.version)
        val entries = ArrayList<Pair<String, Int>>()
        val cursor = Cursor(bytes)
        runCatching {
            walkArray(cursor, header, header.bodyStart, "", depth = 0, out = entries)
        }
        // A file that parsed into nothing is more likely to be one this reader
        // misread than a dictionary with no words in it.
        return if (entries.isEmpty()) Result.NotADictionary else Result.Words(entries)
    }

    // ---- header ----

    private class Header(
        val version: Int,
        val bodyStart: Int,
        val dynamic: Boolean,
        val hasCodePointTable: Boolean,
    )

    /**
     * Magic, version, option flags, header size, then key/value attribute
     * strings until the header size is reached.
     *
     * The attributes are read rather than skipped for one reason: the
     * `codePointTable` key changes what every later byte means.
     */
    private fun readHeader(bytes: ByteArray): Header? {
        if (bytes.size < MIN_HEADER_BYTES) return null
        val version = readShort(bytes, VERSION_OFFSET)
        val optionFlags = readShort(bytes, OPTION_FLAGS_OFFSET)
        val headerSize = readInt(bytes, HEADER_SIZE_OFFSET)
        if (headerSize < MIN_HEADER_BYTES || headerSize > bytes.size) return null
        val cursor = Cursor(bytes).apply { pos = MIN_HEADER_BYTES }
        var hasTable = false
        while (cursor.pos < headerSize) {
            val key = cursor.readString(headerSize) ?: break
            val value = cursor.readString(headerSize) ?: break
            if (key == CODE_POINT_TABLE_KEY && value.isNotEmpty()) hasTable = true
        }
        return Header(
            version = version,
            bodyStart = headerSize,
            dynamic = (optionFlags and SUPPORTS_DYNAMIC_UPDATE) != 0,
            hasCodePointTable = hasTable,
        )
    }

    // ---- the trie ----

    /**
     * One node array: a count, that many nodes, and in a dynamic file a link to
     * the array that continues it.
     *
     * Iterative over forward links and recursive over children, which is the
     * shape of the format: links chain sideways without bound, children nest
     * only as deep as the longest word.
     */
    private fun walkArray(
        cursor: Cursor,
        header: Header,
        startPos: Int,
        prefix: String,
        depth: Int,
        out: MutableList<Pair<String, Int>>,
    ) {
        if (depth > MAX_DEPTH) return
        var arrayPos = startPos
        var links = 0
        while (arrayPos in 0 until cursor.size && out.size < MAX_WORDS && links <= MAX_FORWARD_LINKS) {
            cursor.pos = arrayPos
            val count = cursor.readNodeCount()
            if (count <= 0 || count > MAX_NODES_IN_ARRAY) return
            repeat(count) {
                if (out.size < MAX_WORDS) walkNode(cursor, header, prefix, depth, out)
            }
            if (!header.dynamic) return
            val linkFieldPos = cursor.pos
            val link = cursor.u24()
            if (link == NO_FORWARD_LINK) return
            arrayPos = linkFieldPos + link
            links++
        }
    }

    /** One node, leaving [cursor] on the byte after it. */
    @Suppress("CyclomaticComplexMethod")
    private fun walkNode(
        cursor: Cursor,
        header: Header,
        prefix: String,
        depth: Int,
        out: MutableList<Pair<String, Int>>,
    ) {
        val flags = cursor.u8()
        // A dynamic file carries a parent address here. It is of no use to a
        // walk that already knows its own prefix, but it has to be stepped over.
        if (header.dynamic) cursor.u24()
        val word = StringBuilder(prefix)
        if ((flags and FLAG_HAS_MULTIPLE_CHARS) != 0) {
            while (true) {
                val code = cursor.readCodePoint() ?: break
                word.appendCodePoint(code)
            }
        } else {
            cursor.readCodePoint()?.let { word.appendCodePoint(it) }
        }
        val frequency = if ((flags and FLAG_IS_TERMINAL) != 0) cursor.u8() else NOT_A_TERMINAL
        // "Relative to the position of this field", so the offset is added to
        // where the field starts, not to where it ends.
        val childrenFieldPos = cursor.pos
        val childrenOffset = cursor.readChildrenAddress(flags, header.dynamic)
        if ((flags and FLAG_HAS_SHORTCUT_TARGETS) != 0) cursor.skipShortcuts()
        if ((flags and FLAG_HAS_BIGRAMS) != 0) cursor.skipBigrams()
        val text = word.toString()
        // `is not a word` marks an entry that exists only to hang a shortcut or
        // a bigram off, which is not a word this app should ever suggest.
        if (frequency != NOT_A_TERMINAL && (flags and FLAG_IS_NOT_A_WORD) == 0 && text.isNotEmpty()) {
            out.add(text to DictionaryLoader.scaleAospFrequency(frequency))
        }
        if (childrenOffset != null && text.isNotEmpty()) {
            val resume = cursor.pos
            walkArray(cursor, header, childrenFieldPos + childrenOffset, text, depth + 1, out)
            cursor.pos = resume
        }
    }

    // ---- bytes ----

    private class Cursor(private val bytes: ByteArray) {

        var pos: Int = 0

        val size: Int get() = bytes.size

        fun u8(): Int {
            require(pos in bytes.indices) { "past the end of the file" }
            return bytes[pos++].toInt() and BYTE_MASK
        }

        fun u16(): Int = (u8() shl BITS_PER_BYTE) or u8()

        fun u24(): Int = (u16() shl BITS_PER_BYTE) or u8()

        /** 1 or 2 bytes: a top bit set means the count runs into the next byte. */
        fun readNodeCount(): Int {
            val first = u8()
            return if (first <= MAX_NODES_IN_ONE_BYTE_COUNT) {
                first
            } else {
                ((first and MAX_NODES_IN_ONE_BYTE_COUNT) shl BITS_PER_BYTE) or u8()
            }
        }

        /**
         * One character, or null at the terminator.
         *
         * Everything from 0x20 up is its own byte, which makes Latin text one
         * byte a letter. A first byte below that is the top third of a 24-bit
         * code point, which is how the format reaches the whole of Unicode, and
         * 0x1F alone ends a string — no code point can start with it.
         */
        fun readCodePoint(): Int? {
            val first = u8()
            if (first >= MIN_ONE_BYTE_CHARACTER) return first
            if (first == CHARACTERS_TERMINATOR) return null
            return (first shl BITS_PER_SHORT) or u16()
        }

        /** The children offset, or null when the node has no children. */
        fun readChildrenAddress(flags: Int, dynamic: Boolean): Int? {
            if (dynamic) {
                val raw = u24()
                if (raw == 0) return null
                return if ((raw and MSB24) != 0) -(raw and SINT24_MAX) else raw
            }
            return when (flags and MASK_CHILDREN_ADDRESS_TYPE) {
                FLAG_CHILDREN_ADDRESS_TYPE_ONEBYTE -> u8()
                FLAG_CHILDREN_ADDRESS_TYPE_TWOBYTES -> u16()
                FLAG_CHILDREN_ADDRESS_TYPE_THREEBYTES -> u24()
                else -> null
            }
        }

        /** The list declares its own byte length, so this is one addition. */
        fun skipShortcuts() {
            val start = pos
            val length = u16()
            require(length >= SHORTCUT_LIST_SIZE_BYTES) { "shortcut list shorter than its own size" }
            pos = start + length
        }

        /** A chain of attributes, each saying whether another follows it. */
        fun skipBigrams() {
            var read = 0
            while (read++ < MAX_BIGRAMS_IN_A_NODE) {
                val flags = u8()
                when (flags and MASK_BIGRAM_ADDRESS_TYPE) {
                    FLAG_BIGRAM_ADDRESS_TYPE_ONEBYTE -> u8()
                    FLAG_BIGRAM_ADDRESS_TYPE_TWOBYTES -> u16()
                    FLAG_BIGRAM_ADDRESS_TYPE_THREEBYTES -> u24()
                    // A bigram with no address is a file this reader cannot
                    // keep its place in, so it stops rather than drifting.
                    else -> error("bigram with no address")
                }
                if ((flags and FLAG_ATTRIBUTE_HAS_NEXT) == 0) return
            }
        }

        /** A header attribute string, terminated the same way a key label is. */
        fun readString(limit: Int): String? {
            val out = StringBuilder()
            while (pos < limit) {
                val code = readCodePoint() ?: return out.toString()
                out.appendCodePoint(code)
            }
            return null
        }
    }

    private fun readInt(bytes: ByteArray, offset: Int): Int =
        (bytes[offset].toInt() and BYTE_MASK shl BITS_PER_BYTE * 3) or
            (bytes[offset + 1].toInt() and BYTE_MASK shl BITS_PER_BYTE * 2) or
            (bytes[offset + 2].toInt() and BYTE_MASK shl BITS_PER_BYTE) or
            (bytes[offset + 3].toInt() and BYTE_MASK)

    private fun readShort(bytes: ByteArray, offset: Int): Int =
        (bytes[offset].toInt() and BYTE_MASK shl BITS_PER_BYTE) or (bytes[offset + 1].toInt() and BYTE_MASK)

    // Byte layout, all transcribed from the published format.
    private const val VERSION_OFFSET = 4
    private const val OPTION_FLAGS_OFFSET = 6
    private const val HEADER_SIZE_OFFSET = 8
    private const val MIN_HEADER_BYTES = 12

    private const val SUPPORTS_DYNAMIC_UPDATE = 0x2
    private const val FIRST_VERSION_4 = 399

    private const val MASK_CHILDREN_ADDRESS_TYPE = 0xC0
    private const val FLAG_CHILDREN_ADDRESS_TYPE_ONEBYTE = 0x40
    private const val FLAG_CHILDREN_ADDRESS_TYPE_TWOBYTES = 0x80
    private const val FLAG_CHILDREN_ADDRESS_TYPE_THREEBYTES = 0xC0
    private const val FLAG_HAS_MULTIPLE_CHARS = 0x20
    private const val FLAG_IS_TERMINAL = 0x10
    private const val FLAG_HAS_SHORTCUT_TARGETS = 0x08
    private const val FLAG_HAS_BIGRAMS = 0x04
    private const val FLAG_IS_NOT_A_WORD = 0x02

    private const val FLAG_ATTRIBUTE_HAS_NEXT = 0x80
    private const val MASK_BIGRAM_ADDRESS_TYPE = 0x30
    private const val FLAG_BIGRAM_ADDRESS_TYPE_ONEBYTE = 0x10
    private const val FLAG_BIGRAM_ADDRESS_TYPE_TWOBYTES = 0x20
    private const val FLAG_BIGRAM_ADDRESS_TYPE_THREEBYTES = 0x30

    private const val CHARACTERS_TERMINATOR = 0x1F
    private const val MIN_ONE_BYTE_CHARACTER = 0x20
    private const val MAX_NODES_IN_ONE_BYTE_COUNT = 0x7F
    private const val MAX_NODES_IN_ARRAY = 0x7FFF
    private const val MAX_BIGRAMS_IN_A_NODE = 10_000
    private const val SHORTCUT_LIST_SIZE_BYTES = 2
    private const val NO_FORWARD_LINK = 0
    private const val MSB24 = 0x800000
    private const val SINT24_MAX = 0x7FFFFF
    private const val CODE_POINT_TABLE_KEY = "codePointTable"

    private const val NOT_A_TERMINAL = -1
    private const val BYTE_MASK = 0xFF
    private const val BITS_PER_BYTE = 8
    private const val BITS_PER_SHORT = 16

    /** Longer than any word in any of these dictionaries; a stop, not a limit. */
    private const val MAX_DEPTH = 64

    /** Bounds a corrupt file rather than the honest ones: the largest ship ~250k. */
    private const val MAX_WORDS = 1_000_000

    /** A static file has none of these at all; a dynamic one has a handful. */
    private const val MAX_FORWARD_LINKS = 1024
}
