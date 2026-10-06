package com.wasimaster.wmkeyboard.ime

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.os.Build
import android.view.MotionEvent
import android.view.View
import androidx.compose.ui.unit.IntSize
import com.wasimaster.wmkeyboard.core.handwriting.HwPoint
import com.wasimaster.wmkeyboard.core.handwriting.HwStroke

/**
 * The ink layer inside Android's stylus handwriting window (issue #386,
 * "stylus" mode). The platform shows that window over the whole screen for
 * as long as the session lasts and sends it the stylus only — fingers go on
 * reaching the app — so this view just has to draw and collect strokes.
 *
 * A plain View, not Compose: the window belongs to the platform and has no
 * lifecycle owner to host a composition. Finished strokes go to the service
 * through [onStroke]; what stays on screen is whatever the service still
 * holds ([setStrokes]), so a commit or an undo clears it the same way it
 * clears the panel's canvas.
 */
@SuppressLint("ViewConstructor")
internal class StylusInkView(
    context: Context,
    private val onStroke: (HwStroke, IntSize) -> Unit,
) : View(context) {

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        strokeWidth = INK_WIDTH_DP * context.resources.displayMetrics.density
        color = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            context.getColor(android.R.color.system_accent1_500)
        } else {
            Color.rgb(0x1A, 0x73, 0xE8)
        }
    }

    private var strokes: List<HwStroke> = emptyList()
    private val active = ArrayList<HwPoint>()

    init {
        setBackgroundColor(Color.TRANSPARENT)
    }

    fun setStrokes(value: List<HwStroke>) {
        if (value === strokes) return
        strokes = value
        invalidate()
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                active.clear()
                active.add(HwPoint(event.x, event.y, event.eventTime))
            }
            MotionEvent.ACTION_MOVE -> {
                // A stylus reports at well over the frame rate; the batched
                // samples are the ones the recogniser's shape depends on.
                for (i in 0 until event.historySize) {
                    active.add(
                        HwPoint(event.getHistoricalX(i), event.getHistoricalY(i), event.getHistoricalEventTime(i)),
                    )
                }
                active.add(HwPoint(event.x, event.y, event.eventTime))
            }
            MotionEvent.ACTION_UP -> {
                if (active.isEmpty()) return true
                active.add(HwPoint(event.x, event.y, event.eventTime))
                val points = active.toList()
                active.clear()
                // A tap is still ink: a period, the dot on an i.
                val stroke = if (points.size == 1) points + points.first().copy(t = points.first().t + 1) else points
                onStroke(HwStroke(stroke), IntSize(width, height))
            }
            MotionEvent.ACTION_CANCEL -> active.clear()
        }
        invalidate()
        return true
    }

    override fun onDraw(canvas: Canvas) {
        for (stroke in strokes) canvas.drawPath(pathOf(stroke.points), paint)
        if (active.size > 1) canvas.drawPath(pathOf(active), paint)
    }

    private fun pathOf(points: List<HwPoint>): Path {
        val path = Path()
        if (points.isEmpty()) return path
        path.moveTo(points.first().x, points.first().y)
        for (i in 1 until points.size) path.lineTo(points[i].x, points[i].y)
        return path
    }

    private companion object {
        const val INK_WIDTH_DP = 3f
    }
}
