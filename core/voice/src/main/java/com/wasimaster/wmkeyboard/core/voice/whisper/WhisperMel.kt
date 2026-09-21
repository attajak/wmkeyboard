package com.wasimaster.wmkeyboard.core.voice.whisper

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.log10
import kotlin.math.sin

/**
 * Log-mel spectrogram matching Whisper's preprocessing (a direct port of the
 * whisper.cpp / vilassn reference, verified end-to-end against the tflite
 * models). Output is the `[nMel][nLen]` matrix flattened mel-major
 * (`data[mel * nLen + frame]`), exactly the layout the `input_features`
 * `[1, nMel, 3000]` tensor expects.
 *
 * The band count comes from the filterbank rather than being fixed at 80: the
 * large-v3 generation (large-v3 and turbo) wants 128 bands. Only the filterbank
 * differs — window, hop, FFT size and the log/clamp/normalize steps are the same
 * for both, so one routine covers them.
 *
 * The window is always 30 seconds but a dictated phrase is a few, so the work
 * is kept proportional to the audio: a frame that lies wholly in the padding is
 * silence, whose value is known without transforming it, and the transform
 * itself runs on tables and scratch buffers built once instead of per frame.
 * None of that changes a single output value.
 */
object WhisperMel {

    const val SAMPLE_RATE = 16_000
    const val N_FFT = 400

    /** Band count of every Whisper model before large-v3, and the usual case. */
    const val N_MEL = 80

    /** Band count large-v3 and turbo want instead. */
    const val N_MEL_V3 = 128
    const val HOP_LENGTH = 160
    const val CHUNK_SECONDS = 30
    /** Fixed Whisper window: 16 kHz * 30 s = 480000 samples → 3000 frames. */
    const val N_SAMPLES = SAMPLE_RATE * CHUNK_SECONDS
    const val MEL_LEN = N_SAMPLES / HOP_LENGTH

    /** The floor a band's energy is raised to before the log, so silence is finite. */
    private const val ENERGY_FLOOR = 1e-10

    private val HANN = FloatArray(N_FFT) { (0.5 * (1.0 - cos(2.0 * PI * it / N_FFT))).toFloat() }

    /** What every band of an all-zero frame comes to: the log of [ENERGY_FLOOR]. */
    private val SILENT_LOG = log10(ENERGY_FLOOR).toFloat()

    /**
     * @param samples 16 kHz mono PCM as floats in [-1, 1]. Shorter input is
     *   zero-padded to 30 s; longer is truncated to the first 30 s.
     * @param filters the `[nMel * nFftCols]` filterbank from [WhisperVocab].
     * @param nFftCols the filterbank's column count (1 + N_FFT/2 = 201).
     * @param nMel the filterbank's row count — [N_MEL] or [N_MEL_V3].
     */
    fun compute(
        samples: FloatArray,
        filters: FloatArray,
        nFftCols: Int,
        nMel: Int = N_MEL,
    ): FloatArray {
        val used = minOf(samples.size, N_SAMPLES)
        val nLen = MEL_LEN
        val mel = FloatArray(nMel * nLen)

        // A frame starting at or past the end of the audio reads only padding.
        val liveFrames = minOf(nLen, (used + HOP_LENGTH - 1) / HOP_LENGTH)

        // A mel band is a triangle over a few neighbouring bins and zero
        // everywhere else, so only its own stretch of the row is summed.
        val bandStart = IntArray(nMel)
        val bandEnd = IntArray(nMel)
        for (j in 0 until nMel) {
            val base = j * nFftCols
            var lo = 0
            while (lo < nFftCols && filters[base + lo] == 0f) lo++
            var hi = nFftCols
            while (hi > lo && filters[base + hi - 1] == 0f) hi--
            bandStart[j] = lo
            bandEnd[j] = hi
        }

        val fft = Fft(N_FFT)
        val fftIn = FloatArray(N_FFT)
        val fftOut = FloatArray(N_FFT * 2)

        for (i in 0 until liveFrames) {
            val offset = i * HOP_LENGTH
            for (j in 0 until N_FFT) {
                val at = offset + j
                fftIn[j] = if (at < used) HANN[j] * samples[at] else 0f
            }

            fft.transform(fftIn, fftOut)
            // magnitude^2
            for (j in 0 until N_FFT) {
                fftOut[j] = fftOut[2 * j] * fftOut[2 * j] + fftOut[2 * j + 1] * fftOut[2 * j + 1]
            }
            // fold the mirror image onto the first half
            for (j in 1 until N_FFT / 2) fftOut[j] += fftOut[N_FFT - j]

            for (j in 0 until nMel) {
                var sum = 0.0
                val base = j * nFftCols
                for (k in bandStart[j] until bandEnd[j]) sum += fftOut[k] * filters[base + k]
                if (sum < ENERGY_FLOOR) sum = ENERGY_FLOOR
                mel[j * nLen + i] = log10(sum).toFloat()
            }
        }
        if (liveFrames < nLen) {
            for (j in 0 until nMel) mel.fill(SILENT_LOG, j * nLen + liveFrames, (j + 1) * nLen)
        }

        // clamp to a 8-decade floor below the max, then scale into ~[0, 1]
        var mmax = -1e20
        for (v in mel) if (v > mmax) mmax = v.toDouble()
        mmax -= 8.0
        for (idx in mel.indices) {
            var v = mel[idx].toDouble()
            if (v < mmax) v = mmax
            mel[idx] = ((v + 4.0) / 4.0).toFloat()
        }
        return mel
    }

    /**
     * Radix-2 FFT that halves down to the odd factor of the size and finishes
     * with a plain DFT there (N_FFT = 400 → 25). Same recursion and the same
     * arithmetic, operation for operation, as the reference it replaces; what
     * it no longer does is allocate four arrays per level per frame or call
     * `cos`/`sin` inside the inner loops. One instance serves one [compute]
     * call, so its scratch is never shared between threads.
     */
    private class Fft(size: Int) {
        /** How many times the size halves before it turns odd. */
        private val levels = Integer.numberOfTrailingZeros(size)
        private val leaf = size shr levels

        private val even = Array(levels) { FloatArray(size shr (it + 1)) }
        private val odd = Array(levels) { FloatArray(size shr (it + 1)) }
        private val evenOut = Array(levels) { FloatArray(size shr it) }
        private val oddOut = Array(levels) { FloatArray(size shr it) }

        /** Per level, the twiddle `e^(-2πik/n)` for k below n/2. */
        private val twiddleRe = Array(levels) { level ->
            val n = size shr level
            FloatArray(n / 2) { k -> cos(2 * PI * k / n).toFloat() }
        }
        private val twiddleIm = Array(levels) { level ->
            val n = size shr level
            FloatArray(n / 2) { k -> (-sin(2 * PI * k / n)).toFloat() }
        }

        /** `cos`/`sin` of `2πkt/leaf`, row k, kept as doubles like the products they feed. */
        private val leafCos = DoubleArray(leaf * leaf) { cos(2 * PI * (it / leaf) * (it % leaf) / leaf) }
        private val leafSin = DoubleArray(leaf * leaf) { sin(2 * PI * (it / leaf) * (it % leaf) / leaf) }

        fun transform(input: FloatArray, output: FloatArray) = fft(input, output, 0)

        private fun fft(input: FloatArray, output: FloatArray, level: Int) {
            if (level == levels) {
                dft(input, output)
                return
            }
            val n = input.size
            val half = n / 2
            val ev = even[level]
            val od = odd[level]
            for (i in 0 until half) {
                ev[i] = input[2 * i]
                od[i] = input[2 * i + 1]
            }
            val evenFft = evenOut[level]
            val oddFft = oddOut[level]
            fft(ev, evenFft, level + 1)
            fft(od, oddFft, level + 1)
            val res = twiddleRe[level]
            val ims = twiddleIm[level]
            for (k in 0 until half) {
                val re = res[k]
                val im = ims[k]
                val reOdd = oddFft[2 * k]
                val imOdd = oddFft[2 * k + 1]
                output[2 * k] = evenFft[2 * k] + re * reOdd - im * imOdd
                output[2 * k + 1] = evenFft[2 * k + 1] + re * imOdd + im * reOdd
                output[2 * (k + half)] = evenFft[2 * k] - re * reOdd + im * imOdd
                output[2 * (k + half) + 1] = evenFft[2 * k + 1] - re * imOdd - im * reOdd
            }
        }

        private fun dft(input: FloatArray, output: FloatArray) {
            val n = leaf
            for (k in 0 until n) {
                var re = 0f
                var im = 0f
                val row = k * n
                for (t in 0 until n) {
                    re += (input[t] * leafCos[row + t]).toFloat()
                    im -= (input[t] * leafSin[row + t]).toFloat()
                }
                output[k * 2] = re
                output[k * 2 + 1] = im
            }
        }
    }
}
