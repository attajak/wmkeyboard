package com.wasimaster.wmkeyboard.core.prediction

import com.wasimaster.wmkeyboard.core.prediction.AospDictionary.Ngram
import com.wasimaster.wmkeyboard.core.prediction.AospDictionary.Result
import com.wasimaster.wmkeyboard.core.prediction.AospDictionary.Shortcut

/**
 * Reading a version 4 AOSP dictionary: the format of every dictionary an AOSP
 * keyboard *writes* rather than ships. HeliBoard's and LeanType's user history
 * (what the keyboard learned from the user's own typing, pairs included), the
 * contacts dictionary, and any dictionary a user added words to on the device.
 *
 * Written from AOSP's published native reader, as [AospDictionary] is; no
 * upstream code is copied.
 *
 * ### Two files, not one
 *
 * A version 4 dictionary is a directory `X.dict/` holding `X.dict.header` and
 * `X.dict.body`. The header file is a version 2 header and nothing else. The
 * body is a run of buffers, each a four-byte length and then that many bytes,
 * seven of them in revision 403:
 *
 * 0. the node trie, which spells the words and gives each one a *terminal id*
 * 1. terminal id → trie position (not needed here: the walk finds them all)
 * 2. the language model, a hash trie keyed by terminal id holding each word's
 *    strength and, one level down, the pairs and triples that start from it
 * 3. the language model's two global counters (the total count first)
 * 4. 5. 6. the shortcut table: index, addresses, and the shortcut lists
 *
 * ### Strengths
 *
 * A dictionary with `HAS_HISTORICAL_INFO` (a user history) stores how often
 * each word and pair was typed and when; the others store a 0..255
 * probability. Both are converted through [AospScores], so a pair the user
 * typed twelve times on the other keyboard arrives as twelve personal uses.
 *
 * Revision 402, the layout before 403, is refused rather than guessed at. No
 * current keyboard writes it.
 */
object AospDictionaryV4 {

    /** 403 is the current layout; 399 is the same layout under its test number. */
    fun isSupported(version: Int): Boolean = version == VERSION_403 || version == VERSION_4_TESTING

    /**
     * Reads a version 4 dictionary from its [header] file and its [body] file.
     *
     * Never throws. A body that does not split into the expected buffers, or
     * whose trie spells nothing, is [Result.NotADictionary].
     */
    fun read(header: ByteArray, body: ByteArray): Result {
        val parsed = AospDictionary.readHeader(header) ?: return Result.NotADictionary
        if (parsed.version < FIRST_VERSION_4) return Result.NotADictionary
        if (!isSupported(parsed.version)) return Result.Unsupported(parsed.version)
        val buffers = splitBody(body) ?: return Result.NotADictionary
        val historical = parsed.attributes[HAS_HISTORICAL_INFO_KEY]?.trim()?.toIntOrNull()?.let { it != 0 } ?: false
        return runCatching {
            val words = walkTrie(buffers[TRIE])
            if (words.isEmpty()) return Result.NotADictionary
            val model = LanguageModel(buffers[LANGUAGE_MODEL], historical)
            val total = if (historical) totalCount(buffers[GLOBAL_COUNTERS]) else 0
            contents(words, model, total, buffers, parsed.attributes)
        }.getOrDefault(Result.NotADictionary)
    }

    /** The body's buffers, or null when their lengths do not add up. */
    private fun splitBody(body: ByteArray): List<Slice>? {
        val out = ArrayList<Slice>(BUFFER_COUNT)
        var pos = 0
        while (pos < body.size) {
            if (pos + LENGTH_BYTES > body.size) return null
            val length = AospDictionary.readInt(body, pos)
            pos += LENGTH_BYTES
            if (length < 0 || pos + length > body.size) return null
            out.add(Slice(body, pos, length))
            pos += length
        }
        return if (out.size == BUFFER_COUNT) out else null
    }

    /** A window into the body, with the big-endian reads every buffer needs. */
    private class Slice(private val bytes: ByteArray, private val start: Int, val size: Int) {

        fun u8(at: Int): Int {
            require(at in 0 until size) { "past the end of a buffer" }
            return bytes[start + at].toInt() and BYTE_MASK
        }

        fun uint(at: Int, width: Int): Long {
            var value = 0L
            for (i in 0 until width) value = (value shl BITS_PER_BYTE) or u8(at + i).toLong()
            return value
        }

        fun cursor(): AospDictionary.Cursor =
            AospDictionary.Cursor(bytes.copyOfRange(start, start + size), codePointTable = null)
    }

    // ---- the node trie ----

    /**
     * Every live word in the trie, by terminal id.
     *
     * The version 4 node differs from version 2 in four ways: the top two flag
     * bits are its state (live, moved, deleted, about to stop being a word)
     * rather than an address size; a parent address follows the flags; a word
     * carries a four-byte terminal id instead of a frequency; and the children
     * address is always a signed 24-bit offset. A *moved* node's parent field
     * says where the node now lives, and its siblings still follow it here.
     */
    private fun walkTrie(trie: Slice): Map<Int, String> {
        val cursor = trie.cursor()
        val words = HashMap<Int, String>()
        fun array(startPos: Int, prefix: String, depth: Int) {
            if (depth > AospDictionary.MAX_DEPTH) return
            var arrayPos = startPos
            var links = 0
            while (arrayPos in 0 until cursor.size && words.size < AospDictionary.MAX_WORDS &&
                links <= AospDictionary.MAX_FORWARD_LINKS
            ) {
                cursor.pos = arrayPos
                val count = cursor.readNodeCount()
                if (count < 0 || count > MAX_NODES_IN_ARRAY) return
                repeat(count) {
                    val node = readNode(cursor, followMoves = MAX_MOVES) ?: return
                    val text = prefix + node.chars
                    if (node.live && node.terminalId >= 0 && text.isNotEmpty()) words[node.terminalId] = text
                    if (node.children != null && !node.deleted && text.isNotEmpty()) {
                        val resume = cursor.pos
                        array(node.children, text, depth + 1)
                        cursor.pos = resume
                    }
                }
                val linkFieldPos = cursor.pos
                val link = cursor.s24()
                if (link == 0) return
                arrayPos = linkFieldPos + link
                links++
            }
        }
        array(0, "", depth = 0)
        return words
    }

    private class Node(
        val chars: String,
        val terminalId: Int,
        val children: Int?,
        val live: Boolean,
        val deleted: Boolean,
    )

    /**
     * One node, leaving [cursor] after the *original* node even when it has
     * moved: that is where its sibling starts.
     */
    private fun readNode(cursor: AospDictionary.Cursor, followMoves: Int): Node? {
        val head = cursor.pos
        val flags = cursor.u8()
        val parentOffset = cursor.s24()
        val chars = StringBuilder()
        if ((flags and AospDictionary.FLAG_HAS_MULTIPLE_CHARS) != 0) {
            while (true) chars.appendCodePoint(cursor.readCodePoint() ?: break)
        } else {
            cursor.readCodePoint()?.let { chars.appendCodePoint(it) }
        }
        val terminalId = if ((flags and AospDictionary.FLAG_IS_TERMINAL) != 0) cursor.u32() else NOT_A_TERMINAL
        val childrenFieldPos = cursor.pos
        val children = offsetFrom(childrenFieldPos, cursor.s24())
        val state = flags and MASK_STATE
        if (state == STATE_MOVED) {
            if (followMoves <= 0) return null
            val destination = offsetFrom(head, parentOffset) ?: return null
            val after = cursor.pos
            cursor.pos = destination
            val moved = readNode(cursor, followMoves - 1)
            cursor.pos = after
            return moved
        }
        return Node(
            chars = chars.toString(),
            terminalId = terminalId,
            children = children,
            live = state == STATE_LIVE,
            deleted = state == STATE_DELETED,
        )
    }

    /**
     * A dynamic offset: 0 means none, and 0x7FFFFF means "zero", because 0
     * was already taken.
     */
    private fun offsetFrom(base: Int, offset: Int): Int? = when (offset) {
        0 -> null
        AospDictionary.SINT24_MAX -> base
        else -> base + offset
    }

    // ---- the language model ----

    /**
     * The hash trie AOSP calls a trie map: fixed seven-byte entries after a
     * 128-byte table of free lists. A *bitmap* entry points at a table of
     * entries; a *terminal* entry is a key with its value inline, or with a
     * link to a value entry that is followed by the bitmap of the next level.
     * Iterating a level is a walk of its tables, which is all this needs:
     * nothing here ever looks one key up.
     */
    private class LanguageModel(private val map: Slice, private val historical: Boolean) {

        class Entry(val key: Int, val value: Long, val nextLevel: Int)

        fun level(bitmapIndex: Int, out: (Entry) -> Unit) {
            val root = entry(bitmapIndex)
            val stack = ArrayDeque<IntArray>()
            stack.addLast(intArrayOf(Integer.bitCount(root.first.toInt()), root.second.toInt(), 0))
            var visited = 0
            while (stack.isNotEmpty() && visited++ < MAX_MAP_ENTRIES) {
                val state = stack.last()
                if (state[2] >= state[0]) {
                    stack.removeLast()
                    continue
                }
                val index = state[1] + state[2]
                state[2]++
                val (field0, field1) = entry(index)
                val link = (field1 and TERMINAL_LINK_FLAG) != 0L
                val inline = (field1 and VALUE_FLAG) != 0L
                when {
                    !link && !inline -> {
                        if (stack.size < MAX_MAP_DEPTH) {
                            stack.addLast(intArrayOf(Integer.bitCount(field0.toInt()), field1.toInt(), 0))
                        }
                    }
                    !link && (field1 and VALUE_MASK) == VALUE_MASK -> Unit // removed
                    !link -> out(Entry(field0.toInt(), field1 and VALUE_MASK, NO_LEVEL))
                    else -> {
                        val valueIndex = (field1 and TERMINAL_LINK_MASK).toInt()
                        val (high, low) = entry(valueIndex)
                        out(Entry(field0.toInt(), (high shl VALUE_LOW_BITS) xor low, valueIndex + 1))
                    }
                }
            }
        }

        private fun entry(index: Int): Pair<Long, Long> {
            val at = ROOT_ENTRY_POS + index * ENTRY_SIZE
            return map.uint(at, FIELD0_SIZE) to map.uint(at + FIELD0_SIZE, FIELD1_SIZE)
        }

        /** A decoded probability entry. [strength] is 0..255 or a typed count. */
        class Probability(val flags: Int, val strength: Int, val typed: Boolean) {
            val valid: Boolean get() = (flags and FLAG_NOT_A_VALID_ENTRY) == 0
            val usable: Boolean
                get() = valid && (flags and (FLAG_BEGINNING_OF_SENTENCE or FLAG_NOT_A_WORD or FLAG_BLACKLISTED)) == 0 &&
                    (!typed || strength > 0)
        }

        fun decode(value: Long): Probability = if (historical) {
            // flags(1) timestamp(4) count(2); the level field is zero bytes wide.
            Probability(
                flags = ((value ushr HISTORICAL_FLAGS_SHIFT) and BYTE_MASK.toLong()).toInt(),
                strength = (value and COUNT_MASK).toInt(),
                typed = true,
            )
        } else {
            Probability(
                flags = ((value ushr BITS_PER_BYTE) and BYTE_MASK.toLong()).toInt(),
                strength = (value and BYTE_MASK.toLong()).toInt(),
                typed = false,
            )
        }
    }

    private fun totalCount(counters: Slice): Int =
        if (counters.size >= COUNTER_BYTES) counters.uint(0, COUNTER_BYTES).toInt() else 0

    // ---- putting it together ----

    @Suppress("LongParameterList")
    private fun contents(
        words: Map<Int, String>,
        model: LanguageModel,
        total: Int,
        buffers: List<Slice>,
        attributes: Map<String, String>,
    ): Result.Contents {
        val entries = ArrayList<Pair<String, Int>>()
        val usable = HashSet<Int>()
        val ngrams = ArrayList<Ngram>()
        val deeper = ArrayList<Pair<Int, Int>>()
        model.level(ROOT_BITMAP_INDEX) { entry ->
            val probability = model.decode(entry.value)
            val word = words[entry.key]
            if (word != null && probability.usable) {
                usable.add(entry.key)
                val frequency = if (probability.typed) {
                    AospScores.historicalFrequency(probability.strength, total)
                } else {
                    DictionaryLoader.scaleAospFrequency(probability.strength)
                }
                entries.add(word to frequency)
            }
            if (entry.nextLevel != NO_LEVEL) deeper.add(entry.key to entry.nextLevel)
        }
        // Pairs and triples hang under the word they start from: the key one
        // level down is the next word, and two levels down the context is
        // (level one's key, the root key) with the root key the more recent.
        for ((first, level) in deeper) {
            if (first !in usable) continue
            val firstWord = words.getValue(first)
            model.level(level) { pair ->
                val probability = model.decode(pair.value)
                val second = words[pair.key]
                if (second != null && pair.key in usable && probability.valid && probability.strength > 0) {
                    ngrams.add(Ngram(listOf(firstWord), second, pairCount(probability)))
                }
                if (pair.nextLevel != NO_LEVEL && pair.key in usable && second != null) {
                    model.level(pair.nextLevel) { triple ->
                        val third = words[triple.key]
                        val tripleProbability = model.decode(triple.value)
                        if (third != null && triple.key in usable && tripleProbability.valid &&
                            tripleProbability.strength > 0
                        ) {
                            ngrams.add(Ngram(listOf(second, firstWord), third, pairCount(tripleProbability)))
                        }
                    }
                }
            }
        }
        val shortcuts = readShortcuts(words, buffers)
        return Result.Contents(entries, ngrams, shortcuts, attributes)
    }

    private fun pairCount(probability: LanguageModel.Probability): Int =
        if (probability.typed) {
            AospScores.historicalPairCount(probability.strength)
        } else {
            AospScores.pairCount(probability.strength)
        }

    /**
     * The shortcut table is a sparse table: an index per block of 64 terminal
     * ids, then per id the position of its list in the content buffer. A list
     * is a run of (flags, target) with a has-next bit in the flags.
     */
    private fun readShortcuts(words: Map<Int, String>, buffers: List<Slice>): List<Shortcut> {
        val index = buffers[SHORTCUT_INDEX]
        val addresses = buffers[SHORTCUT_ADDRESSES]
        val content = buffers[SHORTCUT_CONTENT]
        if (content.size == 0) return emptyList()
        val cursor = content.cursor()
        val out = ArrayList<Shortcut>()
        // Every spelled word, usable or not: a not-a-word entry exists in the
        // first place to carry a shortcut.
        for ((id, trigger) in words) {
            val indexPos = (id / SHORTCUT_BLOCK) * SHORTCUT_DATA_SIZE
            if (indexPos + SHORTCUT_DATA_SIZE > index.size) continue
            val block = index.uint(indexPos, SHORTCUT_DATA_SIZE)
            if (block == NOT_EXIST) continue
            val addressPos = (block * SHORTCUT_BLOCK + id % SHORTCUT_BLOCK) * SHORTCUT_DATA_SIZE
            if (addressPos + SHORTCUT_DATA_SIZE > addresses.size) continue
            val listPos = addresses.uint(addressPos.toInt(), SHORTCUT_DATA_SIZE)
            if (listPos == NOT_EXIST || listPos >= content.size) continue
            cursor.pos = listPos.toInt()
            runCatching {
                var read = 0
                while (read++ < MAX_SHORTCUTS_IN_A_LIST) {
                    val flags = cursor.u8()
                    val target = cursor.readString(content.size) ?: break
                    if (target.isNotEmpty() && target != trigger) {
                        val strength = flags and SHORTCUT_PROBABILITY_MASK
                        out.add(Shortcut(trigger, target, strength == WHITELIST_SHORTCUT_PROBABILITY))
                    }
                    if ((flags and SHORTCUT_HAS_NEXT) == 0) break
                }
            }
        }
        return out
    }

    private const val VERSION_4_TESTING = 399
    private const val VERSION_403 = 403
    private const val FIRST_VERSION_4 = 399
    private const val HAS_HISTORICAL_INFO_KEY = "HAS_HISTORICAL_INFO"

    private const val LENGTH_BYTES = 4
    private const val BUFFER_COUNT = 7
    private const val TRIE = 0
    private const val LANGUAGE_MODEL = 2
    private const val GLOBAL_COUNTERS = 3
    private const val SHORTCUT_INDEX = 4
    private const val SHORTCUT_ADDRESSES = 5
    private const val SHORTCUT_CONTENT = 6

    private const val MASK_STATE = 0xC0
    private const val STATE_LIVE = 0xC0
    private const val STATE_MOVED = 0x40
    private const val STATE_DELETED = 0x80
    private const val NOT_A_TERMINAL = -1
    private const val MAX_NODES_IN_ARRAY = 0x7FFF
    private const val MAX_MOVES = 8

    private const val FIELD0_SIZE = 4
    private const val FIELD1_SIZE = 3
    private const val ENTRY_SIZE = FIELD0_SIZE + FIELD1_SIZE
    private const val ROOT_ENTRY_POS = 32 * FIELD0_SIZE
    private const val ROOT_BITMAP_INDEX = 0
    private const val VALUE_FLAG = 0x400000L
    private const val VALUE_MASK = 0x3FFFFFL
    private const val TERMINAL_LINK_FLAG = 0x800000L
    private const val TERMINAL_LINK_MASK = 0x7FFFFFL
    private const val VALUE_LOW_BITS = 24
    private const val NO_LEVEL = -1
    private const val MAX_MAP_DEPTH = 8
    private const val MAX_MAP_ENTRIES = 20_000_000

    private const val FLAG_BEGINNING_OF_SENTENCE = 0x1
    private const val FLAG_NOT_A_VALID_ENTRY = 0x2
    private const val FLAG_NOT_A_WORD = 0x4
    private const val FLAG_BLACKLISTED = 0x8
    private const val HISTORICAL_FLAGS_SHIFT = 48
    private const val COUNT_MASK = 0xFFFFL
    private const val COUNTER_BYTES = 4

    private const val SHORTCUT_BLOCK = 64
    private const val SHORTCUT_DATA_SIZE = 4
    private const val NOT_EXIST = 0xFFFFFFFFL
    private const val SHORTCUT_PROBABILITY_MASK = 0x0F
    private const val SHORTCUT_HAS_NEXT = 0x80
    private const val WHITELIST_SHORTCUT_PROBABILITY = 15
    private const val MAX_SHORTCUTS_IN_A_LIST = 1_000

    private const val BYTE_MASK = 0xFF
    private const val BITS_PER_BYTE = 8
}
