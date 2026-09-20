package com.wasimaster.wmkeyboard.ime.ui

import android.content.Context
import android.graphics.Rect
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.HorizontalScrollView
import android.widget.LinearLayout

/**
 * The scrolling row that hosts inline-suggestion chips — password-manager
 * credentials and platform smart replies alike.
 *
 * A chip is an `InlineContentView`: a surface the *sending* process draws
 * into, z-ordered above this window. That is what keeps credentials out of the
 * keyboard, and it is also why a chip does not behave like an ordinary child.
 * It is not clipped by its parents, and it keeps its full width however little
 * of it is on screen — so in a plain Compose `horizontalScroll` the chips
 * painted straight over the strip's chevron, emoji key and dismiss ✕, and took
 * those controls' touches with them (#250).
 *
 * A real [HorizontalScrollView] answers the touch half by construction: a
 * child is only ever reached through its parent, so nothing outside this
 * scroller's own bounds can land on a chip. The drawing half is the platform's
 * own remedy — [View.setClipBounds], which `InlineContentView` forwards to its
 * surface. The visible slice of each chip changes as the row moves, so it is
 * re-applied on every scroll and every layout.
 */
class InlineChipScroller(context: Context) : HorizontalScrollView(context) {

    private val row = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
    }

    /**
     * The chips currently hosted, so a recomposition that did not change them
     * does not tear the row down and rebuild it — the strip recomposes on
     * every keystroke, and re-parenting a remote surface is not free.
     */
    private var hosted: List<View> = emptyList()

    init {
        isHorizontalScrollBarEnabled = false
        addView(
            row,
            LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.MATCH_PARENT),
        )
    }

    /** Hosts [chips] in order, separated by [gapPx] either side. */
    fun setChips(chips: List<View>, gapPx: Int) {
        if (hosted == chips) return
        hosted = chips
        row.removeAllViews()
        for (chip in chips) {
            // The same chip view can be re-hosted — a reply moves between the
            // idle row and the tail row as candidates come and go — and a view
            // that still remembers its old container throws on the way in.
            (chip.parent as? ViewGroup)?.removeView(chip)
            // Keep the size the platform gave the view. `inflate` sets the
            // chip's LayoutParams from the *remote* measure — the only place
            // its real width is known, since an InlineContentView is a bare
            // surface host with nothing of its own to measure. Replacing them
            // with WRAP_CONTENT measures every chip to zero and the row comes
            // up empty.
            val size = chip.layoutParams
            row.addView(
                chip,
                LinearLayout.LayoutParams(
                    size?.width ?: LinearLayout.LayoutParams.WRAP_CONTENT,
                    size?.height ?: LinearLayout.LayoutParams.WRAP_CONTENT,
                ).apply {
                    marginStart = gapPx
                    marginEnd = gapPx
                },
            )
        }
        scrollX = 0
        clipChipsToViewport()
    }

    override fun onScrollChanged(l: Int, t: Int, oldl: Int, oldt: Int) {
        super.onScrollChanged(l, t, oldl, oldt)
        clipChipsToViewport()
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        super.onLayout(changed, l, t, r, b)
        clipChipsToViewport()
    }

    /**
     * Clips every chip to the part of it that is actually inside the viewport.
     * A chip scrolled clean out gets an empty rect rather than being left to
     * paint over whatever the strip is showing beside this row.
     */
    private fun clipChipsToViewport() {
        if (width == 0) return
        for (i in 0 until row.childCount) {
            val chip = row.getChildAt(i)
            if (chip.width == 0) continue
            val slice = inlineChipVisibleSlice(
                chipLeft = chip.left,
                chipWidth = chip.width,
                scrollX = scrollX,
                viewportWidth = width,
            )
            // A fresh Rect each time: the clip is handed across to a remote
            // surface, so a shared scratch instance is not worth the risk.
            chip.clipBounds = if (slice.isEmpty()) {
                Rect()
            } else {
                Rect(slice.first, 0, slice.last + 1, chip.height)
            }
        }
    }
}

/**
 * The part of a chip that is inside the viewport, in the chip's own
 * coordinates — `chipLeft` and `scrollX` are both measured against the
 * scrolling row. Empty when the chip has been scrolled clean out.
 */
internal fun inlineChipVisibleSlice(
    chipLeft: Int,
    chipWidth: Int,
    scrollX: Int,
    viewportWidth: Int,
): IntRange {
    val left = (scrollX - chipLeft).coerceIn(0, chipWidth)
    val right = (scrollX + viewportWidth - chipLeft).coerceIn(0, chipWidth)
    return if (right > left) left until right else IntRange.EMPTY
}
