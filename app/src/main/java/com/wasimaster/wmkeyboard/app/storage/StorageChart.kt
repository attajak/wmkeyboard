package com.wasimaster.wmkeyboard.app.storage

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.wasimaster.wmkeyboard.R
import com.wasimaster.wmkeyboard.app.formatBytes
import com.wasimaster.wmkeyboard.app.rememberGrowIn

/**
 * The two pictures the Storage screen draws.
 *
 * Both are plain [Canvas] work rather than a charting library, because both are
 * one shape: a ring of three arcs, and a bar of N segments. Neither carries any
 * information the rows below it do not also state in words, so both are hidden
 * from screen readers — the ring gets one summary sentence instead, and the
 * bars get nothing.
 */

/** A category's share of a bar or ring. */
internal data class StorageSlice(val color: Color, val bytes: Long)

private val RingBuckets = listOf(
    Color(0xFF78909C), // the app itself
    Color(0xFF42A5F5), // data
    Color(0xFFFFB300), // cache
)

internal fun ringBucketColor(index: Int): Color = RingBuckets[index]

/**
 * The app's total, split into the same three buckets the system's App info page
 * shows, inside a hairline outer ring showing how full the device itself is.
 *
 * The device is deliberately *not* one of the inner arcs. The app is a fraction
 * of a percent of a phone, so putting the two on one scale leaves the three
 * numbers this screen is actually about as slivers. Two rings, two scales: the
 * thick one is the app against itself, the hairline is the device against
 * itself, and it wears a neutral colour so it does not read as a fourth
 * category.
 */
@Composable
internal fun StorageRing(
    report: StorageReport,
    modifier: Modifier = Modifier,
    contentDescription: String? = null,
) {
    val slices = listOf(
        StorageSlice(RingBuckets[0], report.appBytes),
        StorageSlice(RingBuckets[1], report.userDataBytes),
        StorageSlice(RingBuckets[2], report.cacheBytes),
    )
    val total = slices.sumOf { it.bytes }
    val track = MaterialTheme.colorScheme.surfaceVariant
    val deviceInk = MaterialTheme.colorScheme.onSurfaceVariant
    val deviceShare = if (report.deviceBytes > 0) {
        (report.deviceUsedBytes.toFloat() / report.deviceBytes).coerceIn(0f, 1f)
    } else {
        0f
    }
    // Grows in once, when the first complete measurement lands — or on the
    // first frame, when a cached report paints before the measure. Keying the
    // animation on "have we got a total yet" rather than on the total itself
    // keeps the ring still while the categories fill in underneath it. Reduce
    // motion draws it whole.
    val grown by rememberGrowIn(ready = total > 0L)

    Box(
        modifier = modifier
            .size(RingSize)
            .semanticsOrNone(contentDescription),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.fillMaxWidth().height(RingSize)) {
            val outer = OuterStroke.toPx()
            val main = MainStroke.toPx()
            val gap = RingGap.toPx()
            val side = minOf(size.width, size.height)
            // The hairline sits on the outside, the thick ring just inside it.
            val outerInset = outer / 2f
            val mainInset = outer + gap + main / 2f
            drawArc(
                color = track,
                startAngle = 0f,
                sweepAngle = FULL_TURN,
                useCenter = false,
                topLeft = Offset(outerInset, outerInset),
                size = Size(side - outerInset * 2, side - outerInset * 2),
                style = Stroke(width = outer),
            )
            if (deviceShare > 0f) {
                drawArc(
                    color = deviceInk,
                    startAngle = START_ANGLE,
                    // Floored, so a nearly empty phone still reads as a ring
                    // with a little in it rather than as no ring at all.
                    sweepAngle = (deviceShare * FULL_TURN * grown).coerceAtLeast(MIN_SWEEP),
                    useCenter = false,
                    topLeft = Offset(outerInset, outerInset),
                    size = Size(side - outerInset * 2, side - outerInset * 2),
                    style = Stroke(width = outer),
                )
            }
            drawArc(
                color = track,
                startAngle = 0f,
                sweepAngle = FULL_TURN,
                useCenter = false,
                topLeft = Offset(mainInset, mainInset),
                size = Size(side - mainInset * 2, side - mainInset * 2),
                style = Stroke(width = main),
            )
            if (total <= 0L) return@Canvas
            var angle = START_ANGLE
            for (slice in slices) {
                val share = slice.bytes.toFloat() / total
                val sweep = share * FULL_TURN
                if (sweep > 0f) {
                    drawArc(
                        color = slice.color,
                        startAngle = angle,
                        sweepAngle = ((sweep - ARC_GAP_DEGREES) * grown).coerceAtLeast(0f),
                        useCenter = false,
                        topLeft = Offset(mainInset, mainInset),
                        size = Size(side - mainInset * 2, side - mainInset * 2),
                        style = Stroke(width = main),
                    )
                }
                angle += sweep
            }
        }
        RingCentre(report)
    }
}

@Composable
private fun RingCentre(report: StorageReport) {
    val formatted = formatBytes(report.totalBytes)
    // "412 MB" splits into a big number and a small unit; a size with no space
    // in it (a plain byte count) keeps the whole string in the number slot.
    val cut = formatted.lastIndexOf(' ')
    val amount = if (cut > 0) formatted.substring(0, cut) else formatted
    val unit = if (cut > 0) formatted.substring(cut + 1) else ""
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Row(verticalAlignment = Alignment.Bottom) {
            Text(amount, style = MaterialTheme.typography.headlineLarge)
            if (unit.isNotEmpty()) {
                Spacer(Modifier.width(3.dp))
                Text(
                    unit,
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 5.dp),
                )
            }
        }
        Text(
            stringResource(R.string.storage_total_label),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}

/** A dot, a name and a size — one bucket of the ring, or one row of a legend. */
@Composable
internal fun StorageLegendChip(color: Color, label: String, bytes: Long) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Surface(color = color, shape = CircleShape, modifier = Modifier.size(8.dp)) {}
        Spacer(Modifier.width(6.dp))
        Text(
            label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.width(4.dp))
        Text(formatBytes(bytes), style = MaterialTheme.typography.bodySmall)
    }
}

/**
 * One group's split as a thin stacked bar. Every non-empty segment is given a
 * visible minimum width, so a category holding a few kilobytes next to one
 * holding a gigabyte is still a mark on the bar rather than nothing at all.
 */
@Composable
internal fun StorageBar(slices: List<StorageSlice>, modifier: Modifier = Modifier) {
    val track = MaterialTheme.colorScheme.surfaceVariant
    val total = slices.sumOf { it.bytes }
    // Wipes in from the left once, as the ring above it grows. A wipe rather
    // than scaled segments: scaling would squeeze the minimum widths that keep
    // a tiny category visible, and the segments would change proportion on
    // the way in. The track underneath is there from the start.
    val grown by rememberGrowIn(ready = total > 0L)
    Canvas(
        modifier = modifier
            .fillMaxWidth()
            .height(BarHeight)
            .clearAndSetSemantics {},
    ) {
        val radius = CornerRadius(size.height / 2f)
        drawRoundRect(color = track, size = size, cornerRadius = radius)
        if (total <= 0L) return@Canvas
        val gap = BarGap.toPx()
        val minWidth = size.height
        val drawn = slices.filter { it.bytes > 0L }
        // Share out what the gaps and the minimum widths do not already claim,
        // so the segments still end exactly at the bar's right edge.
        val fixed = drawn.size * minWidth + (drawn.size - 1).coerceAtLeast(0) * gap
        val flexible = (size.width - fixed).coerceAtLeast(0f)
        clipRect(right = size.width * grown) {
            var x = 0f
            for (slice in drawn) {
                val width = minWidth + flexible * (slice.bytes.toFloat() / total)
                drawRoundRect(
                    color = slice.color,
                    topLeft = Offset(x, 0f),
                    size = Size(width.coerceAtMost(size.width - x), size.height),
                    cornerRadius = radius,
                )
                x += width + gap
                if (x >= size.width) break
            }
        }
    }
}

/**
 * The ring reads as one sentence or not at all — never as a pile of unlabelled
 * arcs, and never twice over, since the rows below already say every number.
 */
private fun Modifier.semanticsOrNone(description: String?): Modifier =
    if (description == null) {
        clearAndSetSemantics {}
    } else {
        clearAndSetSemantics { contentDescription = description }
    }

private val RingSize = 176.dp
private val MainStroke = 20.dp
private val OuterStroke = 4.dp
private val RingGap = 6.dp
private val BarHeight = 6.dp
private val BarGap = 2.dp

private const val START_ANGLE = -90f
private const val FULL_TURN = 360f
private const val ARC_GAP_DEGREES = 3f
private const val MIN_SWEEP = 4f
