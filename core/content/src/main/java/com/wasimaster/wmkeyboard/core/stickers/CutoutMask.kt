package com.wasimaster.wmkeyboard.core.stickers

/**
 * The arithmetic on either side of the [CutoutModel] network: pixels into the
 * numbers it was trained on, and its saliency map into an alpha channel.
 *
 * Plain arrays in and out, so none of it needs a device to be tested.
 */
object CutoutMask {

    // The ImageNet statistics the network was trained with, per channel.
    private const val MEAN_R = 0.485f
    private const val MEAN_G = 0.456f
    private const val MEAN_B = 0.406f
    private const val STD_R = 0.229f
    private const val STD_G = 0.224f
    private const val STD_B = 0.225f

    /**
     * How hard the map is pulled toward black and white around its midpoint.
     *
     * At 1 the map is used as it comes, and its faint far-off residue, a few
     * percent of alpha where the network was not quite sure, survives into the
     * sticker: invisible on its own, but the border traces whatever alpha is
     * there and draws a ring around it. At 2 everything under a quarter is
     * gone and everything over three quarters is solid, and the band between
     * is still wide enough to antialias the edge once the map is scaled up.
     */
    private const val EDGE_GAIN = 2f

    /**
     * [pixels] as ARGB ints, row by row, into `[row][column][r, g, b]` floats.
     *
     * Scaled by the brightest channel value in the picture and not by 255,
     * because that is what the reference pipeline the weights are published
     * with does, and the graph was only ever checked against that pipeline.
     */
    fun normalise(pixels: IntArray, out: FloatArray = FloatArray(pixels.size * 3)): FloatArray {
        require(out.size == pixels.size * 3) { "out must hold three floats per pixel" }
        var brightest = 1
        for (pixel in pixels) {
            brightest = maxOf(brightest, (pixel shr 16) and 0xFF, (pixel shr 8) and 0xFF, pixel and 0xFF)
        }
        val scale = 1f / brightest
        var at = 0
        for (pixel in pixels) {
            out[at++] = (((pixel shr 16) and 0xFF) * scale - MEAN_R) / STD_R
            out[at++] = (((pixel shr 8) and 0xFF) * scale - MEAN_G) / STD_G
            out[at++] = ((pixel and 0xFF) * scale - MEAN_B) / STD_B
        }
        return out
    }

    /**
     * The network's saliency [map] as alpha values, 0 to 255.
     *
     * Stretched to its own range first: the map of a low-contrast picture can
     * top out well under 1, and a subject that is 70% opaque everywhere is not
     * a cutout. A flat map, where the network saw nothing at all, comes back
     * all zero and not as divide-by-nothing noise.
     */
    fun alphaOf(map: FloatArray): IntArray {
        var low = Float.MAX_VALUE
        var high = -Float.MAX_VALUE
        for (value in map) {
            if (value < low) low = value
            if (value > high) high = value
        }
        val range = high - low
        if (map.isEmpty() || range < MIN_RANGE) return IntArray(map.size)
        return IntArray(map.size) { index ->
            val stretched = (map[index] - low) / range
            val pulled = ((stretched - 0.5f) * EDGE_GAIN + 0.5f).coerceIn(0f, 1f)
            (pulled * 255f + 0.5f).toInt()
        }
    }

    /** Under this spread the map is one flat value and says nothing. */
    private const val MIN_RANGE = 1e-4f
}
