package com.wasimaster.wmkeyboard.core.prediction

import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.OutputStream

/**
 * Serializer for the `.wmng` corpus n-gram pack (version 1).
 *
 * The format this replaced stored an n-gram as a character trie key —
 * `"of\0the"` — so every follower's *letters* were written out again for
 * each head it followed, shared only with the other followers of that same
 * head. At the shipped caps that came to 17.4 MB per language. Here a follower
 * is a number, and the pack carries one copy of the vocabulary those numbers
 * index: about 1.7 MB for the same 150k bigrams and 75k trigrams.
 *
 * **Word ids are ranks in UTF-8 byte order.** Everything cheap about the reader
 * follows from that one property: a follower run sorted by id is also sorted
 * alphabetically, so [MappedNgramPack] can binary-search a run *by string* and
 * never look a candidate up in the vocabulary at all.
 *
 * Layout, big-endian throughout to match [PackedTrieCodec]:
 * ```
 * magic          u32   "WMNG" (0x574D4E47)
 * version        u16   1
 * flags          u16   0 (reserved)
 * vocabCount     i32   V
 * bigramCount    i32   B
 * contextCount   i32   C   distinct (first, second) trigram contexts
 * trigramCount   i32   T
 * offsets        i32 × 9   absolute file offset of each section below
 * reserved       i32   0
 *
 * 0 vocabOffset     i32 × (V + 1)   byte offsets into section 1
 * 1 vocabBytes      u8              UTF-8, concatenated, no terminators
 * 2 bgRunStart      i32 × (V + 1)   head id -> its slice of sections 3 and 4
 * 3 bgFollowerId    u24 × B         ascending within each run
 * 4 bgCount         u16 × B         FrequencyCodec, parallel to section 3
 * 5 triCtxKey       u48 × C         (first shl 24) or second, ascending
 * 6 triCtxRunStart  i32 × (C + 1)   context rank -> its slice of 7 and 8
 * 7 triFollowerId   u24 × T         ascending within each run
 * 8 triCount        u16 × T         parallel to section 7
 * ```
 *
 * Every section is padded to a 4-byte boundary, and the two packed-integer
 * sections are padded *past* their own last element as well: a `u24` is read as
 * one `getInt(p) ushr 8` and a `u48` as one `getLong(p) ushr 16`, so the final
 * element of each reads one and two bytes beyond itself. Without that slack the
 * file opens cleanly and then throws on whichever rare word happens to sort
 * last.
 */
object NgramPackCodec {

    const val MAGIC = 0x574D4E47
    const val VERSION = 1

    /** Ids are `u24`, so this many distinct words fit in one pack. */
    const val MAX_VOCAB = (1 shl 24) - 1

    internal const val HEADER_BYTES = 4 + 2 + 2 + 4 + 4 + 4 + 4 + 9 * 4 + 4

    internal const val SECTIONS = 9

    /** Trailing bytes each packed section needs so its last element is readable. */
    internal const val U24_TAIL = 1
    internal const val U48_TAIL = 2

    fun write(pack: NgramPackData, out: OutputStream) {
        val vocabCount = pack.vocab.size
        val bigramCount = pack.bigramFollower.size
        val contextCount = pack.contextKey.size
        val trigramCount = pack.trigramFollower.size
        val encoded = pack.vocab.map { it.toByteArray(Charsets.UTF_8) }
        val vocabBytes = encoded.sumOf { it.size }

        var cursor = HEADER_BYTES
        val offsets = IntArray(SECTIONS)
        offsets[0] = cursor.also { cursor += (vocabCount + 1) * 4 }
        offsets[1] = cursor.also { cursor += PackedTrieCodec.pad4(vocabBytes) }
        offsets[2] = cursor.also { cursor += (vocabCount + 1) * 4 }
        offsets[3] = cursor.also { cursor += PackedTrieCodec.pad4(bigramCount * 3 + U24_TAIL) }
        offsets[4] = cursor.also { cursor += PackedTrieCodec.pad4(bigramCount * 2) }
        offsets[5] = cursor.also { cursor += PackedTrieCodec.pad4(contextCount * 6 + U48_TAIL) }
        offsets[6] = cursor.also { cursor += (contextCount + 1) * 4 }
        offsets[7] = cursor.also { cursor += PackedTrieCodec.pad4(trigramCount * 3 + U24_TAIL) }
        offsets[8] = cursor

        val data = DataOutputStream(out.buffered())
        data.writeInt(MAGIC)
        data.writeShort(VERSION)
        data.writeShort(0)
        data.writeInt(vocabCount)
        data.writeInt(bigramCount)
        data.writeInt(contextCount)
        data.writeInt(trigramCount)
        for (offset in offsets) data.writeInt(offset)
        data.writeInt(0)

        var running = 0
        for (bytes in encoded) {
            data.writeInt(running)
            running += bytes.size
        }
        data.writeInt(running)
        for (bytes in encoded) data.write(bytes)
        data.pad(PackedTrieCodec.pad4(vocabBytes) - vocabBytes)

        for (value in pack.bigramRunStart) data.writeInt(value)
        data.writeU24Section(pack.bigramFollower)
        data.writeCountSection(pack.bigramCount)

        for (key in pack.contextKey) data.writeU48(key)
        data.pad(PackedTrieCodec.pad4(contextCount * 6 + U48_TAIL) - contextCount * 6)
        for (value in pack.trigramRunStart) data.writeInt(value)
        data.writeU24Section(pack.trigramFollower)
        data.writeCountSection(pack.trigramCount)
        data.flush()
    }

    /**
     * Bigrams plus trigrams in the pack at [file], read off its header alone;
     * 0 when there is no file or it is not a pack.
     */
    fun entryCount(file: File): Int {
        if (!file.isFile) return 0
        return runCatching {
            DataInputStream(file.inputStream().buffered()).use { input ->
                if (input.readInt() != MAGIC) return 0
                input.readShort() // version
                input.readShort() // flags
                input.readInt() // vocabCount
                val bigrams = input.readInt()
                input.readInt() // contextCount
                val trigrams = input.readInt()
                (bigrams + trigrams).coerceAtLeast(0)
            }
        }.getOrDefault(0)
    }

    private fun DataOutputStream.writeU24Section(values: IntArray) {
        for (value in values) {
            writeByte(value ushr 16)
            writeByte(value ushr 8)
            writeByte(value)
        }
        pad(PackedTrieCodec.pad4(values.size * 3 + U24_TAIL) - values.size * 3)
    }

    private fun DataOutputStream.writeCountSection(values: IntArray) {
        for (value in values) writeShort(FrequencyCodec.encode(value))
        pad(PackedTrieCodec.pad4(values.size * 2) - values.size * 2)
    }

    private fun DataOutputStream.writeU48(value: Long) {
        writeByte((value ushr 40).toInt())
        writeByte((value ushr 32).toInt())
        writeByte((value ushr 24).toInt())
        writeByte((value ushr 16).toInt())
        writeByte((value ushr 8).toInt())
        writeByte(value.toInt())
    }

    private fun DataOutputStream.pad(bytes: Int) = repeat(bytes) { writeByte(0) }
}

/**
 * The id-space form of a pack, ready to serialize. Produced by
 * [NgramPackBuilder]; nothing else should construct one, because the reader
 * relies on invariants — vocabulary in UTF-8 byte order, followers ascending
 * within each run, context keys ascending — that the builder is what
 * establishes.
 */
class NgramPackData internal constructor(
    val vocab: List<String>,
    val bigramRunStart: IntArray,
    val bigramFollower: IntArray,
    val bigramCount: IntArray,
    val contextKey: LongArray,
    val trigramRunStart: IntArray,
    val trigramFollower: IntArray,
    val trigramCount: IntArray,
)

/**
 * Accumulates n-grams as they stream off the network and lays them out in id
 * space once, at [build].
 *
 * Words are interned to provisional ids on arrival — the download parses lines
 * in count order, not alphabetical order, so real ids cannot be handed out
 * until every word has been seen. [build] sorts the vocabulary by UTF-8 bytes,
 * inverts that permutation, and rewrites the stored triples in one pass.
 */
class NgramPackBuilder {

    private val ids = HashMap<String, Int>(1 shl 16)
    private val words = ArrayList<String>()
    private val bigrams = ArrayList<IntArray>()
    private val trigrams = ArrayList<IntArray>()

    private fun intern(word: String): Int = ids.getOrPut(word) {
        words.add(word)
        words.size - 1
    }

    fun addBigram(first: String, second: String, count: Int) {
        bigrams.add(intArrayOf(intern(first), intern(second), count))
    }

    fun addTrigram(first: String, second: String, third: String, count: Int) {
        trigrams.add(intArrayOf(intern(first), intern(second), intern(third), count))
    }

    val isEmpty: Boolean get() = bigrams.isEmpty() && trigrams.isEmpty()

    fun build(): NgramPackData {
        require(words.size <= NgramPackCodec.MAX_VOCAB) {
            "${words.size} distinct words exceeds the u24 id space"
        }
        // UTF-8 byte order, not String.compareTo: the two agree across the BMP
        // but disagree once surrogates are involved, and the reader compares
        // bytes.
        val encoded = words.map { it.toByteArray(Charsets.UTF_8) }
        val order = words.indices.sortedWith { a, b -> compareBytes(encoded[a], encoded[b]) }
        val rank = IntArray(words.size)
        for (position in order.indices) rank[order[position]] = position
        val vocab = order.map { words[it] }

        val (bigramRunStart, bigramFollower, bigramCount) = layoutBigrams(rank, vocab.size)
        val trigram = layoutTrigrams(rank)
        return NgramPackData(
            vocab = vocab,
            bigramRunStart = bigramRunStart,
            bigramFollower = bigramFollower,
            bigramCount = bigramCount,
            contextKey = trigram.contextKey,
            trigramRunStart = trigram.runStart,
            trigramFollower = trigram.follower,
            trigramCount = trigram.count,
        )
    }

    private fun layoutBigrams(rank: IntArray, vocabSize: Int): Triple<IntArray, IntArray, IntArray> {
        val sorted = bigrams.sortedWith(
            compareBy({ rank[it[0]] }, { rank[it[1]] }),
        )
        val runStart = IntArray(vocabSize + 1)
        for (entry in sorted) runStart[rank[entry[0]] + 1]++
        for (i in 1..vocabSize) runStart[i] += runStart[i - 1]
        val follower = IntArray(sorted.size)
        val count = IntArray(sorted.size)
        for (i in sorted.indices) {
            follower[i] = rank[sorted[i][1]]
            count[i] = sorted[i][2]
        }
        return Triple(runStart, follower, count)
    }

    private class Trigrams(
        val contextKey: LongArray,
        val runStart: IntArray,
        val follower: IntArray,
        val count: IntArray,
    )

    private fun layoutTrigrams(rank: IntArray): Trigrams {
        val sorted = trigrams.sortedWith(
            compareBy({ contextKey(rank, it) }, { rank[it[2]] }),
        )
        val keys = ArrayList<Long>()
        val runStart = ArrayList<Int>()
        val follower = IntArray(sorted.size)
        val count = IntArray(sorted.size)
        var previous = -1L
        for (i in sorted.indices) {
            val key = contextKey(rank, sorted[i])
            if (key != previous) {
                keys.add(key)
                runStart.add(i)
                previous = key
            }
            follower[i] = rank[sorted[i][2]]
            count[i] = sorted[i][3]
        }
        runStart.add(sorted.size)
        return Trigrams(keys.toLongArray(), runStart.toIntArray(), follower, count)
    }

    private fun contextKey(rank: IntArray, entry: IntArray): Long =
        (rank[entry[0]].toLong() shl 24) or rank[entry[1]].toLong()

    private fun compareBytes(a: ByteArray, b: ByteArray): Int {
        val shared = minOf(a.size, b.size)
        for (i in 0 until shared) {
            val diff = (a[i].toInt() and 0xFF) - (b[i].toInt() and 0xFF)
            if (diff != 0) return diff
        }
        return a.size - b.size
    }
}
