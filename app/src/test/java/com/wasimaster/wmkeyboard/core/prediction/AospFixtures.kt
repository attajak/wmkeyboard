package com.wasimaster.wmkeyboard.core.prediction

import java.io.ByteArrayOutputStream

/**
 * AOSP dictionaries assembled byte by byte from the published format, for the
 * readers' tests. Nothing here is copied from a dictionary anyone published.
 */
object AospFixtures {

    const val TERMINATOR = 0x1F

    fun bytes(values: List<Int>): ByteArray = values.map { it.toByte() }.toByteArray()

    /** A header: magic, version, flags, size, then the attributes as strings. */
    fun header(version: Int, optionFlags: Int = 0, attributes: List<Pair<String, String>> = emptyList()): ByteArray {
        val attributeBytes = attributes.flatMap { (key, value) -> string(key) + string(value) }
        val size = HEADER_FIXED_BYTES + attributeBytes.size
        return bytes(
            listOf(0x9B, 0xC1, 0x3A, 0xFE) +
                listOf((version shr 8) and 0xFF, version and 0xFF) +
                listOf((optionFlags shr 8) and 0xFF, optionFlags and 0xFF) +
                listOf((size ushr 24) and 0xFF, (size shr 16) and 0xFF, (size shr 8) and 0xFF, size and 0xFF) +
                attributeBytes,
        )
    }

    /** A string in the format's character encoding, terminator included. */
    fun string(text: String): List<Int> {
        val out = ArrayList<Int>()
        text.codePoints().forEach { code ->
            if (code in 0x20..0xFF) {
                out.add(code)
            } else {
                out.addAll(listOf((code shr 16) and 0xFF, (code shr 8) and 0xFF, code and 0xFF))
            }
        }
        out.add(TERMINATOR)
        return out
    }

    // ---- version 4 ----

    /** One entry of a version 4 language model level: a word id, its value, the level under it. */
    class Entry(val key: Int, val value: Long, val next: List<Entry> = emptyList())

    /** A probability entry of a dictionary without history: flags, then 0..255. */
    fun probability(probability: Int, flags: Int = 0): Long = ((flags.toLong() and 0xFF) shl 8) or probability.toLong()

    /** A probability entry of a user history: flags, timestamp, count. */
    fun history(count: Int, flags: Int = 0, timestamp: Long = 1_700_000_000L): Long =
        ((flags.toLong() and 0xFF) shl 48) or ((timestamp and 0xFFFFFFFFL) shl 16) or (count.toLong() and 0xFFFF)

    /**
     * A version 4 `.body`: a root node array spelling [words] (each a word
     * whose terminal id is its index), the language model [model], the total
     * count [total], and [shortcuts] by terminal id.
     */
    fun body(
        words: List<String>,
        model: List<Entry>,
        total: Int = 0,
        shortcuts: Map<Int, List<Pair<String, Int>>> = emptyMap(),
    ): ByteArray {
        val trie = trie(words)
        val map = trieMap(model)
        val counters = u32(total) + u32(0)
        val (index, addresses, content) = shortcutTable(shortcuts)
        val out = ByteArrayOutputStream()
        for (buffer in listOf(trie, emptyList(), map, counters, index, addresses, content)) {
            out.write(bytes(u32(buffer.size)))
            out.write(bytes(buffer))
        }
        return out.toByteArray()
    }

    /** One flat array: every word a live, terminal, childless node. */
    private fun trie(words: List<String>): List<Int> {
        val out = ArrayList<Int>()
        out.add(words.size)
        words.forEachIndexed { id, word ->
            val multiple = word.codePointCount(0, word.length) > 1
            out.add(0xC0 or 0x10 or (if (multiple) 0x20 else 0))
            out.addAll(listOf(0, 0, 0)) // no parent
            if (multiple) out.addAll(string(word)) else out.addAll(string(word).dropLast(1))
            out.addAll(u32(id))
            out.addAll(listOf(0, 0, 0)) // no children
        }
        out.addAll(listOf(0, 0, 0)) // no forward link
        return out
    }

    /**
     * The trie map's entries, laid out the way its reader walks them: a
     * bitmap entry per level, then the level's table, with value entries
     * (and the next level's bitmap right after each) wherever a value is too
     * wide or a level hangs under it.
     */
    private fun trieMap(root: List<Entry>): List<Int> {
        val entries = ArrayList<Pair<Long, Long>>()
        fun alloc(n: Int): Int = entries.size.also { repeat(n) { entries.add(0L to 0L) } }
        fun fill(table: Int, items: List<Entry>) {
            items.forEachIndexed { i, item ->
                if (item.next.isEmpty() && item.value < 0x3FFFFF) {
                    entries[table + i] = item.key.toLong() to (0x400000L or item.value)
                } else {
                    val valueIndex = alloc(2)
                    entries[valueIndex] = (item.value ushr 24) to (item.value and 0xFFFFFF)
                    if (item.next.isNotEmpty()) {
                        val childTable = alloc(item.next.size)
                        entries[valueIndex + 1] = ((1L shl item.next.size) - 1) to childTable.toLong()
                        fill(childTable, item.next)
                    }
                    entries[table + i] = item.key.toLong() to (0x800000L or valueIndex.toLong())
                }
            }
        }
        val rootBitmap = alloc(1)
        val rootTable = alloc(root.size)
        entries[rootBitmap] = ((1L shl root.size) - 1) to rootTable.toLong()
        fill(rootTable, root)
        val out = ArrayList<Int>()
        repeat(32 * 4) { out.add(0) }
        for ((field0, field1) in entries) {
            out.addAll(u32(field0.toInt()))
            out.addAll(listOf(((field1 shr 16) and 0xFF).toInt(), ((field1 shr 8) and 0xFF).toInt(), (field1 and 0xFF).toInt()))
        }
        return out
    }

    private fun shortcutTable(shortcuts: Map<Int, List<Pair<String, Int>>>): Triple<List<Int>, List<Int>, List<Int>> {
        if (shortcuts.isEmpty()) return Triple(emptyList(), emptyList(), emptyList())
        val blocks = (shortcuts.keys.max() / 64) + 1
        val index = ArrayList<Int>()
        repeat(blocks) { index.addAll(u32(it)) }
        val addresses = MutableList(blocks * 64 * 4) { 0xFF }
        val content = ArrayList<Int>()
        for ((id, list) in shortcuts) {
            val at = id * 4
            u32(content.size).forEachIndexed { i, b -> addresses[at + i] = b }
            list.forEachIndexed { i, (target, strength) ->
                val hasNext = if (i < list.size - 1) 0x80 else 0
                content.add(hasNext or (strength and 0x0F))
                content.addAll(string(target))
            }
        }
        return Triple(index, addresses, content)
    }

    private fun u32(value: Int): List<Int> =
        listOf((value ushr 24) and 0xFF, (value shr 16) and 0xFF, (value shr 8) and 0xFF, value and 0xFF)

    private const val HEADER_FIXED_BYTES = 12
}
