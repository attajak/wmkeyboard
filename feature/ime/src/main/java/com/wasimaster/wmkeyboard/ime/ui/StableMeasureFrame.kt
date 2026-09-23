package com.wasimaster.wmkeyboard.ime.ui

import android.content.Context
import android.view.View
import android.widget.FrameLayout

/**
 * The input view's outermost frame: measures the keyboard against the same
 * height in both of the platform's measure passes.
 *
 * The IME window's own layout (a vertical `LinearLayout` with a weighted
 * fullscreen area above the input area) measures the input view twice per
 * traversal, first against the whole screen and then against what is left —
 * `AT_MOST 2400`, then `AT_MOST 1512`, measured on a 1080x2400 phone. Compose
 * reads two different constraints inside one traversal as "this view is
 * measured with multiple constraints" (`AndroidComposeView`'s
 * `wasMeasuredWithMultipleConstraints`), and from then until the next
 * traversal *any* node that needs measuring again is walked up to the root and
 * turned into a full window layout, because its size might now matter to a
 * parent that is measured more than one way. For a keyboard that is every key
 * press, every release and every strip update: a whole-window layout pass two
 * or three times per keystroke, with the whole tree measured twice in each
 * because its root constraints changed between the passes.
 *
 * So both passes are handed one height: the smaller of what is offered and
 * what the frame was last laid out in. The keyboard's content is shorter than
 * either, so it measures to exactly what it did before — only now Compose sees
 * one set of constraints and keeps a key press to the nodes it touched.
 *
 * A real change of room — rotation, the extract view, a floating panel — shows
 * up as a different final offer, which the next [onLayout] adopts. If content
 * that wants all the room it can get was held to the old, smaller height, it
 * asks for one more layout so it is measured against the new one. Exact specs
 * are passed through untouched: they cannot differ between passes in a way
 * this could smooth over.
 */
internal class StableMeasureFrame(context: Context) : FrameLayout(context) {

    /** The height spec the frame was last laid out under; 0 before the first layout. */
    private var settledHeightSpec = 0

    /** The spec of the latest measure pass, which the coming layout is under. */
    private var offeredHeightSpec = 0

    /** The spec the content was actually measured against in that pass. */
    private var usedHeightSpec = 0

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        offeredHeightSpec = heightMeasureSpec
        val used = stableHeightSpec(heightMeasureSpec)
        usedHeightSpec = used
        super.onMeasure(widthMeasureSpec, used)
        // Never report more than was offered: the content is measured against
        // at most that much, so this only matters for a pass that offered
        // less than the settled height, which then gets what it offered.
        setMeasuredDimension(measuredWidth, View.resolveSize(measuredHeight, heightMeasureSpec))
    }

    private fun stableHeightSpec(offered: Int): Int {
        val settled = settledHeightSpec
        if (settled == 0) return offered
        if (MeasureSpec.getMode(offered) != MeasureSpec.AT_MOST) return offered
        if (MeasureSpec.getMode(settled) != MeasureSpec.AT_MOST) return offered
        val size = minOf(MeasureSpec.getSize(offered), MeasureSpec.getSize(settled))
        return MeasureSpec.makeMeasureSpec(size, MeasureSpec.AT_MOST)
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        super.onLayout(changed, left, top, right, bottom)
        val offered = offeredHeightSpec
        if (offered == settledHeightSpec) return
        settledHeightSpec = offered
        // Held below the room it was given, and it filled what it was held
        // to: it may want more. Measure again against the new height.
        val usedSize = MeasureSpec.getSize(usedHeightSpec)
        val cappedLow = usedSize < MeasureSpec.getSize(offered)
        val content = if (childCount > 0) getChildAt(0) else null
        if (cappedLow && content != null && content.measuredHeight >= usedSize) post { requestLayout() }
    }
}
