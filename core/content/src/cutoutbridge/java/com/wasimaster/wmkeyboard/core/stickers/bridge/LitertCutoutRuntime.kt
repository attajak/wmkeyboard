package com.wasimaster.wmkeyboard.core.stickers.bridge

import android.graphics.Bitmap
import com.wasimaster.wmkeyboard.core.stickers.CutoutMask
import com.wasimaster.wmkeyboard.core.stickers.CutoutModel
import com.wasimaster.wmkeyboard.core.stickers.CutoutRuntime
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.tensorflow.lite.Interpreter

/**
 * The half of the app's own background remover that touches LiteRT, and
 * nothing else: the [CutoutModel] network run on the interpreter. Reached
 * only by reflection (see `CutoutModule.BRIDGE_CLASS`), because on Play it
 * ships in the on-demand `:feature:litert` split and the base APK has no
 * LiteRT import. Download, digest check and the judging of the result all
 * stay in `LocalSubjectCutout` on the other side of [CutoutRuntime].
 *
 * The interpreter is built for one cutout and closed after it. Loading a graph
 * this small is quick next to running it, a cutout happens a few times in an
 * editing session and not at all in most, and the alternative is the settings
 * process holding the graph and its arena for as long as it lives.
 *
 * The network sees a 320 pixel square whatever the picture's shape, which is
 * how it was trained, and the map it answers with is stretched back over the
 * picture with filtering, so the step from 320 up to the sticker canvas
 * softens the edge and does not staircase it.
 *
 * Public with a public no-arg constructor, both for `newInstance()`.
 */
class LitertCutoutRuntime : CutoutRuntime {

    override fun segment(model: File, image: Bitmap): Bitmap {
        val side = CutoutModel.INPUT_SIDE
        // getPixels cannot read a hardware bitmap, and the editor's canvas is
        // not one, but a caller's picture is the caller's business.
        val readable = if (image.config == Bitmap.Config.ARGB_8888) image
        else image.copy(Bitmap.Config.ARGB_8888, false)
        val small = Bitmap.createScaledBitmap(readable, side, side, true)
        val pixels = IntArray(side * side)
        small.getPixels(pixels, 0, side, 0, 0, side, side)
        if (small !== readable) small.recycle()
        if (readable !== image) readable.recycle()

        val input = ByteBuffer.allocateDirect(pixels.size * 3 * Float.SIZE_BYTES).order(ByteOrder.nativeOrder())
        input.asFloatBuffer().put(CutoutMask.normalise(pixels))
        val output = ByteBuffer.allocateDirect(pixels.size * Float.SIZE_BYTES).order(ByteOrder.nativeOrder())

        val options = Interpreter.Options().apply {
            numThreads = Runtime.getRuntime().availableProcessors().coerceIn(2, 4)
        }
        Interpreter(model, options).use { interpreter ->
            interpreter.runSignature(
                mapOf(CutoutModel.INPUT_NAME to input),
                mapOf(CutoutModel.OUTPUT_NAME to output),
                CutoutModel.SIGNATURE,
            )
        }

        val map = FloatArray(pixels.size)
        output.rewind()
        output.asFloatBuffer().get(map)
        val alpha = CutoutMask.alphaOf(map)
        // White under the alpha, so the scaled copy's alpha channel is the
        // filtered mask and extractAlpha lifts it straight out.
        for (index in alpha.indices) pixels[index] = (alpha[index] shl 24) or 0x00FFFFFF
        val coarse = Bitmap.createBitmap(pixels, side, side, Bitmap.Config.ARGB_8888)
        val full = Bitmap.createScaledBitmap(coarse, image.width, image.height, true)
        val mask = full.extractAlpha()
        if (full !== coarse) full.recycle()
        coarse.recycle()
        return mask
    }
}
