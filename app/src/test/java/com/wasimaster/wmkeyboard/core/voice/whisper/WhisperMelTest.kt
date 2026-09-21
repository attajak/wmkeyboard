package com.wasimaster.wmkeyboard.core.voice.whisper

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sin

class WhisperMelTest {

    /** An identity-ish filterbank: one column per fft bin, mapped 1:1 to mels. */
    private fun flatFilters(nMel: Int, nFft: Int) = FloatArray(nMel * nFft) { 1f }

    @Test
    fun `output is 80 x 3000 mel-major`() {
        val nFft = 1 + WhisperMel.N_FFT / 2
        val mel = WhisperMel.compute(FloatArray(WhisperMel.N_SAMPLES), flatFilters(WhisperMel.N_MEL, nFft), nFft)
        assertEquals(WhisperMel.N_MEL * WhisperMel.MEL_LEN, mel.size)
    }

    @Test
    fun `the band count follows the filterbank, not a fixed 80`() {
        // large-v3 and turbo want 128 bands from the same audio and the same
        // window, so only the filterbank and the output height change.
        val nFft = 1 + WhisperMel.N_FFT / 2
        val mel = WhisperMel.compute(
            FloatArray(WhisperMel.N_SAMPLES),
            flatFilters(WhisperMel.N_MEL_V3, nFft),
            nFft,
            WhisperMel.N_MEL_V3,
        )
        assertEquals(WhisperMel.N_MEL_V3 * WhisperMel.MEL_LEN, mel.size)
        assertEquals(128, WhisperMel.N_MEL_V3)
    }

    @Test
    fun `values are finite and span at most the clamped range`() {
        val nFft = 1 + WhisperMel.N_FFT / 2
        val samples = FloatArray(WhisperMel.N_SAMPLES) {
            (0.5 * sin(2.0 * PI * 440.0 * it / WhisperMel.SAMPLE_RATE)).toFloat()
        }
        val mel = WhisperMel.compute(samples, flatFilters(WhisperMel.N_MEL, nFft), nFft)
        for (v in mel) assertTrue("mel value not finite: $v", v.isFinite())
        // The clamp floors values 8 log-decades below the max; dividing by 4
        // makes the post-normalization span exactly 2.0 (the absolute ceiling
        // depends on the filterbank, so span is the invariant to assert).
        val span = mel.max() - mel.min()
        assertTrue("normalized span $span exceeds 2.0", span <= 2.0001f)
    }

    @Test
    fun `silence maps to the normalized floor everywhere`() {
        val nFft = 1 + WhisperMel.N_FFT / 2
        val mel = WhisperMel.compute(FloatArray(WhisperMel.N_SAMPLES), flatFilters(WhisperMel.N_MEL, nFft), nFft)
        // Pure silence → every bin hits the 1e-10 clamp → identical value.
        val first = mel[0]
        assertTrue(mel.all { kotlin.math.abs(it - first) < 1e-4f })
    }

    @Test
    fun `a short clip is the same spectrogram as that clip padded to the window`() {
        // The frames past the end of the audio are filled in rather than
        // transformed, which must not show in the output.
        val nFft = 1 + WhisperMel.N_FFT / 2
        val filters = flatFilters(WhisperMel.N_MEL, nFft)
        val short = FloatArray(WhisperMel.SAMPLE_RATE * 2 + 37) {
            (0.3 * sin(2.0 * PI * 310.0 * it / WhisperMel.SAMPLE_RATE)).toFloat()
        }
        val padded = short.copyOf(WhisperMel.N_SAMPLES)
        assertTrue(WhisperMel.compute(short, filters, nFft).contentEquals(WhisperMel.compute(padded, filters, nFft)))
    }

    @Test
    fun `a tone lands in the band over its own frequency`() {
        // One band per fft bin, so the transform is checked against where the
        // energy of a known tone has to be: 1 kHz is bin 1000 / (16000 / 400) = 25.
        val nFft = 1 + WhisperMel.N_FFT / 2
        val identity = FloatArray(nFft * nFft) { if (it / nFft == it % nFft) 1f else 0f }
        val samples = FloatArray(WhisperMel.SAMPLE_RATE) {
            (0.5 * sin(2.0 * PI * 1000.0 * it / WhisperMel.SAMPLE_RATE)).toFloat()
        }
        val mel = WhisperMel.compute(samples, identity, nFft, nFft)
        val frame = 10
        val loudest = (0 until nFft).maxByOrNull { mel[it * WhisperMel.MEL_LEN + frame] }
        assertEquals(25, loudest)
    }
}
