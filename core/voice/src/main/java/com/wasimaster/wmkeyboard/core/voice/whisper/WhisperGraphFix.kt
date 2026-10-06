package com.wasimaster.wmkeyboard.core.voice.whisper

import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Repairs the decoder prompt compiled into the English-only `.en` graphs
 * (#440), in place, the first time each downloaded file is used.
 *
 * Every one of these graphs runs Hugging Face's `generate()` traced inside it,
 * and the conversion script handed that call the forced-token table for the
 * *multilingual* tokenizer: position 2 forced to 50359 and position 3 to 50363,
 * which there are `<|transcribe|>` and `<|notimestamps|>`. The English-only
 * tokenizer numbers its special tokens one lower, so on these graphs the same
 * ids are `<|startoflm|>` and the `<|0.00|>` timestamp. Every clip therefore
 * started decoding from `<|startoftranscript|> <|notimestamps|> <|startoflm|>
 * <|0.00|>`, a prefix the model never saw in training, and a short phrase often
 * came back as an immediate end-of-text: on LibriSpeech, `tiny.en` typed nothing
 * for 5 of 16 clean clips under four seconds, 15 of 16 with other people talking
 * behind them.
 *
 * The table is a 16-byte int32 constant, `[-1, -1, 50359, 50363]`, where -1
 * means "not forced". Rewriting it to all -1 leaves the model to continue after
 * `<|notimestamps|>` by itself, which is the prompt OpenAI's English models were
 * trained on. That took `tiny.en` from 12.4 % to 9.0 % word error rate on clean
 * speech, 30.9 % to 25.7 % in white noise, and to no empty clips at all.
 *
 * Only graphs on the English-only tokenizer are touched: the multilingual
 * graphs carry the very same bytes, and there they are the right prompt.
 * A file without the pattern is left exactly as it was, so this is safe on any
 * `.en` graph, fixed upstream or not. A marker beside the model records that
 * the file has been looked at, so the scan, a full read of the graph, happens
 * once per download rather than once per dictation.
 */
object WhisperGraphFix {

    private const val MARKER_SUFFIX = ".prompt-checked"
    private const val CHUNK = 1 shl 20

    private val BROKEN = intsLe(-1, -1, 50359, 50363)
    private val FIXED = intsLe(-1, -1, -1, -1)

    private val lock = Any()

    /**
     * Repairs [modelFile] if it is an English-only graph carrying the broken
     * prompt and has not been looked at since it was written. Returns how many
     * copies of the table were rewritten (0 when there was nothing to do).
     * Best effort: a failure leaves the graph as it was and is not reported,
     * since the graph still runs, just as badly as before. Blocking; call off
     * the main thread, and before the graph is mapped by an interpreter.
     */
    fun ensure(modelFile: File, vocabFile: File): Int {
        if (!englishOnly(vocabFile)) return 0
        synchronized(lock) {
            if (!modelFile.isFile) return 0
            val marker = File(modelFile.parentFile, modelFile.name + MARKER_SUFFIX)
            if (marker.isFile && runCatching { marker.readText() }.getOrNull() == stamp(modelFile)) return 0
            return runCatching {
                val patched = patch(modelFile)
                marker.writeText(stamp(modelFile))
                patched
            }.getOrDefault(0)
        }
    }

    /** The same test the runtime uses to pick the English-only special-token ids. */
    fun englishOnly(vocabFile: File): Boolean = vocabFile.name.contains("_en", ignoreCase = true)

    /** Rewrites every occurrence of [BROKEN] in [file] to [FIXED]; returns the count. */
    private fun patch(file: File): Int {
        val hits = find(file)
        if (hits.isEmpty()) return 0
        RandomAccessFile(file, "rw").use { raf ->
            for (offset in hits) {
                raf.seek(offset)
                raf.write(FIXED)
            }
        }
        return hits.size
    }

    /** Offsets of [BROKEN] in [file], read in chunks that overlap by one pattern length. */
    private fun find(file: File): List<Long> {
        val hits = ArrayList<Long>(2)
        val overlap = BROKEN.size - 1
        val buf = ByteArray(CHUNK + overlap)
        RandomAccessFile(file, "r").use { raf ->
            var base = 0L
            var carried = 0
            while (true) {
                val n = raf.read(buf, carried, CHUNK)
                if (n <= 0) break
                val filled = carried + n
                var i = 0
                while (i <= filled - BROKEN.size) {
                    if (matchesAt(buf, i)) hits += base + i
                    i++
                }
                carried = minOf(overlap, filled)
                System.arraycopy(buf, filled - carried, buf, 0, carried)
                base += filled - carried
            }
        }
        return hits
    }

    private fun matchesAt(buf: ByteArray, at: Int): Boolean {
        for (k in BROKEN.indices) if (buf[at + k] != BROKEN[k]) return false
        return true
    }

    /** Length and modification time: a file downloaded again over the old one reads as new. */
    private fun stamp(file: File): String = "${file.length()}:${file.lastModified()}"

    private fun intsLe(vararg values: Int): ByteArray =
        ByteBuffer.allocate(values.size * Int.SIZE_BYTES).order(ByteOrder.LITTLE_ENDIAN)
            .apply { values.forEach { putInt(it) } }
            .array()
}
