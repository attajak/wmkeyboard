package com.wasimaster.wmkeyboard.core.gesture

/**
 * One finger path, kept whole so it can be decoded again later.
 *
 * The grid goes with the points on purpose: a stroke is only meaningful
 * against the keys it was drawn on, and by the time it is read back the
 * keyboard may be in another orientation, another layout or another size.
 * [shape] is the sample the shape store files under the word, when swipe-style
 * learning is on.
 */
class GlideStroke(
    val points: List<GesturePoint>,
    val keys: List<KeyCenter>,
    val keyWidthPx: Float,
    val shape: GlideShapeSample? = null,
)
