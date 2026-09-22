package com.wasimaster.wmkeyboard.app.netlog

import android.text.format.Formatter
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.wasimaster.wmkeyboard.R
import com.wasimaster.wmkeyboard.app.LocalReduceMotion
import com.wasimaster.wmkeyboard.core.netlog.NetSource
import java.text.DateFormat
import java.text.NumberFormat
import java.util.Calendar
import java.util.Date
import java.util.TimeZone

/**
 * One bar per hour (today) or per day, each bar stacked by feature in that
 * feature's colour, the same colour its tile and its row wear.
 *
 * Height is the number of requests rather than bytes: a failed request carries
 * no bytes and still happened, and activity is what the chart is for. The big
 * figure above it is the bytes.
 *
 * Press and drag along it to read one bar: the others fade and a line above
 * names the bar's time, its requests and its size. Hidden from screen readers
 * as a whole (the caller gives it one description); the cards below restate
 * every number.
 */
@Composable
internal fun NetActivityChart(
    buckets: List<NetBucket>,
    period: NetPeriod,
    colorOf: (NetSource) -> Color,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val numbers = remember { NumberFormat.getIntegerInstance() }
    var pressed by remember { mutableIntStateOf(-1) }
    val track = MaterialTheme.colorScheme.surfaceContainerHighest
    val reduceMotion = LocalReduceMotion.current
    val any = buckets.any { it.total.requests > 0 }
    val grown by animateFloatAsState(
        targetValue = if (any) 1f else 0f,
        animationSpec = if (reduceMotion) tween(0) else tween(GROW_MS, easing = FastOutSlowInEasing),
        label = "netlogChart",
    )
    // The same stacking order in every bar, busiest feature at the bottom, so
    // a colour sits at the same height all the way along.
    val order = remember(buckets) {
        buckets.flatMap { it.bySource.entries }
            .groupBy({ it.key }, { it.value.requests })
            .entries.sortedByDescending { it.value.sum() }
            .map { it.key }
    }

    Column(modifier) {
        val shown = buckets.getOrNull(pressed)
        // Always the same height, so the chart does not jump when a finger lands.
        Text(
            if (shown == null) {
                ""
            } else {
                stringResource(
                    R.string.netlog_chart_bucket,
                    bucketRange(shown.key, period),
                    pluralStringResource(
                        R.plurals.netlog_requests_count,
                        shown.total.requests.coerceAtMost(Int.MAX_VALUE.toLong()).toInt(),
                        numbers.format(shown.total.requests),
                    ),
                    Formatter.formatShortFileSize(context, shown.total.bytes),
                )
            },
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.fillMaxWidth().padding(bottom = 6.dp),
        )
        Box {
            Canvas(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(ChartHeight)
                    .pointerInput(buckets.size) {
                        fun indexAt(x: Float): Int =
                            (x / size.width * buckets.size).toInt().coerceIn(0, buckets.lastIndex)
                        awaitEachGesture {
                            val down = awaitFirstDown()
                            pressed = indexAt(down.position.x)
                            while (true) {
                                val event = awaitPointerEvent()
                                val change = event.changes.firstOrNull() ?: break
                                if (!change.pressed) break
                                pressed = indexAt(change.position.x)
                                change.consume()
                            }
                            pressed = -1
                        }
                    },
            ) {
                if (buckets.isEmpty()) return@Canvas
                val peak = buckets.maxOf { it.total.requests }.coerceAtLeast(1L).toFloat()
                val gap = BarGap.toPx()
                val barWidth = (size.width - gap * (buckets.size - 1)) / buckets.size
                val radius = CornerRadius(barWidth / 3f)
                buckets.forEachIndexed { index, bucket ->
                    val x = index * (barWidth + gap)
                    val faded = pressed >= 0 && pressed != index
                    // An empty bucket still leaves a tick, so the axis reads as a
                    // run of hours rather than a chart with holes in it.
                    if (bucket.total.requests == 0L) {
                        drawRoundRect(
                            track,
                            topLeft = Offset(x, size.height - FLOOR_PX),
                            size = Size(barWidth, FLOOR_PX),
                            cornerRadius = radius,
                        )
                        return@forEachIndexed
                    }
                    val height = (bucket.total.requests / peak * size.height * grown).coerceAtLeast(FLOOR_PX)
                    var top = size.height
                    val segments = order.mapNotNull { src -> bucket.bySource[src]?.let { src to it.requests } }
                    segments.forEachIndexed { i, (src, requests) ->
                        val h = height * requests / bucket.total.requests
                        top -= h
                        val color = colorOf(src).copy(alpha = if (faded) FADED_ALPHA else 1f)
                        // Rounded only where the bar ends: the top of the top
                        // segment. Inner joins stay square so the stack reads as
                        // one bar, with a hairline of track between segments.
                        val drawn = if (i < segments.lastIndex) (h - SEAM_PX).coerceAtLeast(0f) else h
                        if (i == segments.lastIndex) {
                            drawRoundRect(color, Offset(x, top), Size(barWidth, drawn), radius)
                            // Square off the bottom corners of the top segment
                            // when something sits under it.
                            if (segments.size > 1 && drawn > radius.y) {
                                drawRect(color, Offset(x, top + drawn - radius.y), Size(barWidth, radius.y))
                            }
                        } else {
                            drawRect(color, Offset(x, top + (h - drawn)), Size(barWidth, drawn))
                        }
                    }
                }
            }
        }
        Row(modifier = Modifier.fillMaxWidth().padding(top = 4.dp)) {
            for ((i, label) in axisLabels(buckets, period).withIndex()) {
                Text(
                    label,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = when (i) {
                        0 -> TextAlign.Start
                        1 -> TextAlign.Center
                        else -> TextAlign.End
                    },
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

/** Start, middle and end of the axis. */
@Composable
private fun axisLabels(buckets: List<NetBucket>, period: NetPeriod): List<String> {
    if (buckets.isEmpty()) return emptyList()
    val now = stringResource(R.string.netlog_chart_now)
    val context = LocalContext.current
    return remember(buckets, period, now) {
        if (period == NetPeriod.TODAY) {
            listOf(hourLabel(context, 0), hourLabel(context, HOURS / 2), now)
        } else {
            listOf(buckets.first().key, buckets[buckets.size / 2].key, buckets.last().key).map(::dayShort)
        }
    }
}

@Composable
private fun bucketRange(key: Int, period: NetPeriod): String {
    val context = LocalContext.current
    return if (period == NetPeriod.TODAY) {
        stringResource(R.string.netlog_chart_hour_range, hourLabel(context, key), hourLabel(context, (key + 1) % HOURS))
    } else {
        remember(key) {
            DateFormat.getDateInstance(DateFormat.MEDIUM).apply { timeZone = UTC }.format(Date(key * DAY_MS))
        }
    }
}

private fun hourLabel(context: android.content.Context, hour: Int): String {
    val calendar = Calendar.getInstance().apply {
        set(Calendar.HOUR_OF_DAY, hour)
        set(Calendar.MINUTE, 0)
    }
    return android.text.format.DateFormat.getTimeFormat(context).format(calendar.time)
}

private fun dayShort(epochDay: Int): String =
    java.text.SimpleDateFormat(
        android.text.format.DateFormat.getBestDateTimePattern(java.util.Locale.getDefault(), "MMMd"),
        java.util.Locale.getDefault(),
    ).apply { timeZone = UTC }.format(Date(epochDay * DAY_MS))

private val UTC: TimeZone = TimeZone.getTimeZone("UTC")
private val ChartHeight = 72.dp
private val BarGap = 3.dp
private const val FLOOR_PX = 3f
private const val SEAM_PX = 1.5f
private const val FADED_ALPHA = 0.25f
private const val GROW_MS = 700
private const val HOURS = 24
private const val DAY_MS = 86_400_000L
