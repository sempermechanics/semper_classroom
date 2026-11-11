package com.sempermechanics.semper.ui.analysis.sweep

import android.content.Context
import android.view.MotionEvent
import android.view.ViewConfiguration
import com.sempermechanics.semper.ui.common.dp
import kotlin.math.hypot

/**
 * Tells a tap from a scrub on a [SweepPlotView]: a finger that lifts within
 * the touch slop of where it went down, with no second finger. The plot still
 * shows its scrub readout while the finger is down; this only decides, at the
 * lift, whether [SweepPlotView.onPointTap] hears of it.
 */
internal class PlotTap(context: Context) {
    private val slopPx = ViewConfiguration.get(context).scaledTouchSlop.toFloat()
    private var downX = 0f
    private var downY = 0f
    private var live = false

    /** Follows [event]; true when it is the lift that completes a tap. */
    fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = event.x
                downY = event.y
                live = true
            }
            MotionEvent.ACTION_MOVE -> if (hypot(event.x - downX, event.y - downY) > slopPx) live = false
            MotionEvent.ACTION_POINTER_DOWN, MotionEvent.ACTION_CANCEL -> live = false
            MotionEvent.ACTION_UP -> return live.also { live = false }
        }
        return false
    }
}

/** How far from a marker a tap still counts as on it: a 48 dp target. */
internal const val TAP_RADIUS_DP = 24f

/**
 * The marker nearest to ([xPx], [yPx]) on the first curve drawn in colour with
 * markers, in data units; null when none lies within [maxPx] of it. Distances
 * are in view pixels, so a steep curve and a flat one are judged alike.
 */
internal fun SweepPlotView.nearestMarker(xPx: Float, yPx: Float, b: PlotBounds, maxPx: Float): Pair<Float, Float>? {
    val target = series.firstOrNull { !it.muted && it.markers && it.points.isNotEmpty() }
    if (target == null || !hasFrame()) return null
    var nearest: Pair<Float, Float>? = null
    var best = maxPx
    for (point in target.points) {
        val px = frame.left + (point.first - b.xMin) / (b.xMax - b.xMin) * frame.width()
        val py = frame.bottom - (point.second - b.yMin) / (b.yMax - b.yMin) * frame.height()
        val distance = hypot(px - xPx, py - yPx)
        if (distance <= best) {
            best = distance
            nearest = point
        }
    }
    return nearest
}

/** Reports a tap at ([xPx], [yPx]) to [SweepPlotView.onPointTap] when it lands on a marker. */
internal fun SweepPlotView.reportPointTap(xPx: Float, yPx: Float, full: PlotBounds) {
    val listener = onPointTap ?: return
    nearestMarker(xPx, yPx, viewport.visible(full), dp(TAP_RADIUS_DP))?.let { listener(it.first, it.second) }
}
