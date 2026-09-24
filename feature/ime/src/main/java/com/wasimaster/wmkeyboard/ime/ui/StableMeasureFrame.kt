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
 * So both passes are handed one height: the largest room offered in this
 * traversal. The first pass offers the whole screen and the second what is
 * left, so the second is measured against the first's room too, and Compose
 * sees one set of constraints and keeps a key press to the nodes it touched.
 *
 * It used to be the smaller of what was offered and the height the frame was
 * last laid out in. That also held the room to the window's old height, so
 * the window could never grow: a panel that asked for more room (the AI chat's
 * composer, Translate, Wikipedia's expanded article) opened into the height
 * the last panel left and squeezed its own content to fit. The first pass of
 * each traversal starts the room afresh, so a rotation or the extract view
 * that really does shrink the room is picked up at once.
 *
 * Exact specs are passed through untouched: they cannot differ between passes
 * in a way this could smooth over.
 */
internal class StableMeasureFrame(context: Context) : FrameLayout(context) {

    /** The largest `AT_MOST` height offered since the last layout. */
    private var roomSize = 0

    /** No measure pass has run since the last layout, so the next one starts the room afresh. */
    private var freshTraversal = true

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        super.onMeasure(widthMeasureSpec, stableHeightSpec(heightMeasureSpec))
        // Never report more than was offered. The content may have been
        // measured against the traversal's larger room; the window takes its
        // size from the first pass, which offered that room.
        setMeasuredDimension(measuredWidth, View.resolveSize(measuredHeight, heightMeasureSpec))
    }

    private fun stableHeightSpec(offered: Int): Int {
        if (MeasureSpec.getMode(offered) != MeasureSpec.AT_MOST) return offered
        val size = MeasureSpec.getSize(offered)
        roomSize = if (freshTraversal) size else maxOf(roomSize, size)
        freshTraversal = false
        return MeasureSpec.makeMeasureSpec(roomSize, MeasureSpec.AT_MOST)
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        super.onLayout(changed, left, top, right, bottom)
        freshTraversal = true
    }
}
