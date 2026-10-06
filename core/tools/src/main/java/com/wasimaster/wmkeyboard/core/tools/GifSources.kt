package com.wasimaster.wmkeyboard.core.tools

import androidx.annotation.StringRes
import com.wasimaster.wmkeyboard.tools.R

/**
 * Where a GIF/sticker result came from. [LOCAL] is the user's own sticker
 * packs on device — sticker-only, always available, and never mixed into a
 * provider grid. [OFFLINE] is its GIF-panel twin: packs of GIFs imported from
 * a file ([com.wasimaster.wmkeyboard.core.tools.offlinegif.OfflineGifPacks]),
 * offered once one is installed and needing no network at all.
 *
 * [COMMONS] is the F-Droid build's only remote source and appears in no other
 * channel: KLIPY and GIPHY both want an API key that build cannot carry. Its
 * corpus is educational rather than reactive, which the panel says out loud
 * rather than leaving the user to conclude the search is broken.
 */
enum class GifSource {
    KLIPY, GIPHY, LOCAL, COMMONS, OFFLINE;

    /** Files on the device rather than a provider: its own grid, never interleaved, never metered. */
    val onDevice: Boolean get() = this == LOCAL || this == OFFLINE
}

/**
 * One GIF or sticker result: a small preview for the panel grid and the
 * full-size file that actually gets committed to the editor. Produced by
 * [KlipyClient] and [GiphyClient].
 */
data class GifItem(
    val id: String,
    /** Compact preview shown in the picker grid (animated for KLIPY/GIPHY). */
    val previewUrl: String,
    /** Full-size file downloaded and inserted on tap. */
    val fullUrl: String,
    /** MIME of [fullUrl] — image/gif, image/webp or image/png. */
    val mime: String,
    /** width/height of the preview, for grid cell sizing. */
    val aspectRatio: Float,
    val source: GifSource,
    /** Provider's name for the item, or a local sticker's — may be blank. */
    val title: String = "",
)

/** One chip in the source row, with the label it shows. */
data class SourceChip(@StringRes val labelRes: Int, val source: GifSource)

/** Helpers shared by the multi-provider GIF/sticker pipeline. */
object GifSources {

    /** The name a source goes by on screen. The UI resolves the id. */
    @StringRes
    fun displayNameRes(source: GifSource): Int = when (source) {
        GifSource.KLIPY -> R.string.core_tools_gif_source_klipy
        GifSource.GIPHY -> R.string.core_tools_gif_source_giphy
        GifSource.LOCAL -> R.string.core_tools_gif_source_local
        GifSource.COMMONS -> R.string.core_tools_gif_source_commons
        GifSource.OFFLINE -> R.string.core_tools_gif_source_offline
    }

    /**
     * Which sources one fetch should hit, given the chip the user is on.
     *
     * Packs on the device are never interleaved with providers: picking
     * them is always a grid of its own, in mixed mode as much as in tabs mode.
     */
    fun targets(sources: List<GifSource>, selected: GifSource, tabs: Boolean): List<GifSource> {
        if (sources.isEmpty()) return emptyList()
        val pick = selected.takeIf { it in sources }
        if (pick != null && pick.onDevice) return listOf(pick)
        if (tabs) return listOf(pick ?: sources.first())
        val remote = sources.filterNot { it.onDevice }
        return remote.ifEmpty { listOf(sources.first()) }
    }

    /**
     * Chips for the source row, or an empty list when there is nothing to
     * switch between. Mixed mode collapses the providers into one "Online"
     * chip standing for the interleaved grid, so packs on the device still
     * get a tab each.
     */
    fun chips(sources: List<GifSource>, tabs: Boolean): List<SourceChip> {
        if (tabs) {
            return if (sources.size > 1) sources.map { SourceChip(displayNameRes(it), it) } else emptyList()
        }
        val remote = sources.filterNot { it.onDevice }
        val onDevice = sources.filter { it.onDevice }
        if (remote.size + onDevice.size < 2 || onDevice.isEmpty()) return emptyList()
        val online = remote.firstOrNull()?.let { listOf(SourceChip(R.string.core_tools_gif_source_online, it)) }
        return online.orEmpty() + onDevice.map { SourceChip(displayNameRes(it), it) }
    }

    /** Index of the active chip in [chips], falling back to the first. */
    fun selectedChip(chips: List<SourceChip>, selected: GifSource): Int {
        val exact = chips.indexOfFirst { it.source == selected }
        if (exact >= 0) return exact
        // Mixed mode: any provider means the "Online" chip is the active one.
        return chips.indexOfFirst { !it.source.onDevice }.coerceAtLeast(0)
    }

    /**
     * A cell's shape in the picker grid. Clamped so one degenerate banner or
     * filmstrip cannot flatten its whole row (or stretch it off the panel);
     * the preview letterboxes inside the clamped cell instead of cropping.
     */
    fun cellRatio(item: GifItem): Float = item.aspectRatio.coerceIn(MIN_CELL_RATIO, MAX_CELL_RATIO)

    /**
     * Splits picker results into justified rows: every item in a row shares
     * one height, widths follow each item's own aspect ratio, and nothing is
     * cropped. Rows aim for [perRow] items and close early once they carry
     * [targetRowRatio] worth of width, so a pair of wide GIFs makes a row of
     * two rather than squeezing a cropped third in.
     *
     * A row never takes an item that would push it past [MAX_ROW_STRETCH]
     * times its target: heights are `width / ratio-sum`, and an unbounded sum
     * is a row of thumbnails too short to read.
     */
    fun rows(items: List<GifItem>, perRow: Int = DEFAULT_PER_ROW): List<List<GifItem>> {
        val most = perRow.coerceAtLeast(1)
        val target = targetRowRatio(most)
        val rows = ArrayList<List<GifItem>>()
        val row = ArrayList<GifItem>()
        var sum = 0f
        for (item in items) {
            val ratio = cellRatio(item)
            if (row.isNotEmpty() && (row.size == most || sum + ratio > target * MAX_ROW_STRETCH)) {
                rows += row.toList()
                row.clear()
                sum = 0f
            }
            row += item
            sum += ratio
            if (sum >= target) {
                rows += row.toList()
                row.clear()
                sum = 0f
            }
        }
        if (row.isNotEmpty()) rows += row.toList()
        return rows
    }

    /**
     * How much ratio-width a row of at most [perRow] items wants; also the
     * floor rows are padded to, so a short last row keeps its neighbours'
     * height. A little over one square per item, so [perRow] squares close a
     * row and fewer leave room.
     */
    fun targetRowRatio(perRow: Int): Float = perRow.coerceAtLeast(1) + 0.2f

    /** The ratio-width a row wants at the shipped three to a row. */
    val TARGET_ROW_RATIO: Float get() = targetRowRatio(DEFAULT_PER_ROW)

    /** Items to a row unless the setting says otherwise. */
    const val DEFAULT_PER_ROW = 3

    /** How far past its target a row may run before an item starts the next one: 4.6 at three to a row. */
    private const val MAX_ROW_STRETCH = 1.4375f
    private const val MIN_CELL_RATIO = 0.5f
    private const val MAX_CELL_RATIO = 2.6f

    /**
     * Round-robin merge for "mixed" mode: one item from each provider in
     * turn, so no source dominates the top of the grid.
     */
    fun interleave(lists: List<List<GifItem>>): List<GifItem> {
        val iterators = lists.map { it.iterator() }
        val merged = ArrayList<GifItem>(lists.sumOf { it.size })
        while (iterators.any { it.hasNext() }) {
            for (iterator in iterators) {
                if (iterator.hasNext()) merged += iterator.next()
            }
        }
        return merged
    }
}
