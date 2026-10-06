package com.wasimaster.wmkeyboard.core.gesture

import com.wasimaster.wmkeyboard.core.prediction.MappedTrie
import com.wasimaster.wmkeyboard.core.prediction.PackedTrie
import com.wasimaster.wmkeyboard.core.prediction.PackedTrieCodec
import com.wasimaster.wmkeyboard.core.prediction.WordSource
import com.wasimaster.wmkeyboard.core.transliteration.KhiproRomanizer
import com.wasimaster.wmkeyboard.core.transliteration.SortedWords
import java.io.File
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/**
 * The Khipro spellings of a Bangla word list, worked out on the device and
 * kept on disk, so a swipe over the Khipro grid can be read as any word of the
 * list (#541): the bundled one, a downloaded one, an imported one, all of it.
 *
 * Every word goes through [KhiproRomanizer], which runs Khipro's own rules
 * backwards and keeps a spelling only if Khipro turns it back into the word.
 * That is a few hundred microseconds a word, minutes for a 450,000-word list
 * on a phone, so it is done once, on every core, and written as a `.wmdict`
 * that is mapped rather than read; it is redone only when the list changes.
 *
 * The slicer `/` counts as a key a stroke passes through, so words with
 * চন্দ্রবিন্দু and খণ্ড-ত are spelled too; a word that needs any other
 * non-letter key has no spelling and is typed.
 */
object KhiproGlideSpellings {

    /** Bumped whenever the romanizer's output could change, so old caches are rebuilt. */
    private const val VERSION = 2

    private const val FILE_NAME = "khipro_glide.wmdict"
    private const val SIGNATURE_NAME = "khipro_glide.sig"

    /** Words per task handed to a core. */
    private const val CHUNK = 2_000

    /**
     * The spellings of [words], from [dir] when they were already worked out
     * for this exact list, built and saved there otherwise. Null when they
     * could not be built (a full disk); the caller then has nothing to glide
     * with, the same as before.
     */
    fun load(words: SortedWords, dir: File, onProgress: ((done: Int, total: Int) -> Unit)? = null): WordSource? {
        val file = File(dir, FILE_NAME)
        val signatureFile = File(dir, SIGNATURE_NAME)
        val signature = signatureOf(words)
        if (file.isFile && runCatching { signatureFile.readText() }.getOrNull() == signature) {
            MappedTrie.open(file)?.let { return it }
        }
        val trie = build(words, onProgress)
        return runCatching {
            dir.mkdirs()
            val part = File(dir, "$FILE_NAME.part")
            part.outputStream().buffered().use { PackedTrieCodec.write(trie, it) }
            check(part.renameTo(file)) { "could not move ${part.name} into place" }
            signatureFile.writeText(signature)
            MappedTrie.open(file)
        }.getOrNull() ?: trie
    }

    /** The spellings of a few words held in memory: the user's own, which change as they type. */
    fun ofWords(words: List<Pair<String, Int>>): WordSource {
        val spelled = ArrayList<String>()
        val frequencies = ArrayList<Int>()
        for ((word, frequency) in words) {
            for (spelling in KhiproRomanizer.spellings(word)) {
                spelled += spelling
                frequencies += frequency.coerceAtLeast(1)
            }
        }
        return PackedTrie.of(spelled.toTypedArray<String?>(), frequencies.toIntArray(), spelled.size)
    }

    /** [onProgress] hears from the worker threads, a chunk at a time; only a build reports. */
    private fun build(words: SortedWords, onProgress: ((Int, Int) -> Unit)?): PackedTrie {
        onProgress?.invoke(0, words.size)
        KhiproRomanizer.warm()
        val finished = AtomicInteger()
        val cores = Runtime.getRuntime().availableProcessors().coerceIn(1, 8)
        val pool = Executors.newFixedThreadPool(cores)
        try {
            val tasks = (0 until words.size step CHUNK).map { from ->
                Callable {
                    val spelled = ArrayList<String>()
                    val frequencies = ArrayList<Int>()
                    for (i in from until minOf(from + CHUNK, words.size)) {
                        val frequency = (-words.rank(i)).coerceIn(1, Int.MAX_VALUE.toLong()).toInt()
                        for (spelling in KhiproRomanizer.spellings(words.word(i))) {
                            spelled += spelling
                            frequencies += frequency
                        }
                    }
                    val done = finished.addAndGet(minOf(CHUNK, words.size - from))
                    onProgress?.invoke(done, words.size)
                    spelled to frequencies
                }
            }
            val parts = pool.invokeAll(tasks).map { it.get() }
            val count = parts.sumOf { it.first.size }
            val all = arrayOfNulls<String>(count)
            val frequencies = IntArray(count)
            var at = 0
            for ((spelled, freqs) in parts) {
                for (j in spelled.indices) {
                    all[at] = spelled[j]
                    frequencies[at] = freqs[j]
                    at++
                }
            }
            // Sorts both arrays in place; a spelling met twice keeps its larger frequency.
            return PackedTrie.of(all, frequencies, count)
        } finally {
            pool.shutdown()
        }
    }

    /** Which list the cache was built for: its size and every word, hashed, and the romanizer's version. */
    private fun signatureOf(words: SortedWords): String {
        var hash = 1125899906842597L
        for (i in 0 until words.size) {
            val length = words.length(i)
            for (j in 0 until length) hash = 31 * hash + words.charAt(i, j).code
            hash = 31 * hash + words.rank(i)
        }
        return "v$VERSION:${words.size}:$hash"
    }
}
