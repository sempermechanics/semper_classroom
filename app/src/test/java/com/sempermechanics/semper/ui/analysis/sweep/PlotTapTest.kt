@file:Suppress("MagicNumber")

package com.sempermechanics.semper.ui.analysis.sweep

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * A tap on a marker of the plot's curve reports that point through
 * [SweepPlotView.onPointTap], which is how the Results curve opens a photo.
 * A drag stays a scrub and reports nothing.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class PlotTapTest {

    private val context = ApplicationProvider.getApplicationContext<Application>()
    private lateinit var view: SweepPlotView
    private val taps = mutableListOf<Pair<Float, Float>>()

    private val curve = SweepPlotView.Series("curve", Color.RED, listOf(0f to 0f, 10f to 10f, 20f to 40f))
    private val fit =
        SweepPlotView.Series("fit", Color.GRAY, listOf(0f to 5f, 20f to 25f), markers = false, muted = true)

    private var clock = 0L

    @Before
    fun setUp() {
        view = SweepPlotView(context).apply {
            onPointTap = { x, y -> taps += x to y }
            setData(listOf(curve, fit), "strain", "stress")
        }
        view.measure(
            View.MeasureSpec.makeMeasureSpec(W, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(H, View.MeasureSpec.EXACTLY),
        )
        view.layout(0, 0, W, H)
        redraw()
        clock = SystemClock.uptimeMillis()
    }

    /** The plot area is fixed on draw. */
    private fun redraw() {
        view.draw(Canvas(Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888)))
    }

    /** Where the plot draws the data point ([x], [y]), in view pixels. */
    private fun pixelOf(x: Float, y: Float): Pair<Float, Float> {
        val b = view.dataBounds()!!
        val frame = view.frame
        return (frame.left + (x - b.xMin) / (b.xMax - b.xMin) * frame.width()) to
            (frame.bottom - (y - b.yMin) / (b.yMax - b.yMin) * frame.height())
    }

    private fun event(action: Int, x: Float, y: Float, at: Long): MotionEvent =
        MotionEvent.obtain(clock, clock + at, action, x, y, 0)

    private fun tapAt(x: Float, y: Float) {
        view.onTouchEvent(event(MotionEvent.ACTION_DOWN, x, y, 0))
        view.onTouchEvent(event(MotionEvent.ACTION_UP, x, y, 40))
    }

    @Test
    fun `a tap on a marker reports that point`() {
        val (x, y) = pixelOf(10f, 10f)
        tapAt(x, y)

        assertEquals(listOf(10f to 10f), taps)
    }

    @Test
    fun `a tap a little off a marker still finds it`() {
        val (x, y) = pixelOf(20f, 40f)
        tapAt(x - 10f, y + 10f)

        assertEquals(listOf(20f to 40f), taps)
    }

    @Test
    fun `a tap between two markers, far from both, reports nothing`() {
        val (x0, y0) = pixelOf(0f, 0f)
        val (x1, y1) = pixelOf(10f, 10f)
        assertTrue("the markers are far enough apart to tell", x1 - x0 > 4 * TAP_RADIUS_DP)
        tapAt((x0 + x1) / 2f, (y0 + y1) / 2f)

        assertTrue(taps.isEmpty())
    }

    @Test
    fun `a drag that ends on a marker is a scrub, not a tap`() {
        val (x, y) = pixelOf(10f, 10f)
        view.onTouchEvent(event(MotionEvent.ACTION_DOWN, x - 120f, y, 0))
        view.onTouchEvent(event(MotionEvent.ACTION_MOVE, x - 60f, y, 16))
        view.onTouchEvent(event(MotionEvent.ACTION_MOVE, x, y, 32))
        view.onTouchEvent(event(MotionEvent.ACTION_UP, x, y, 48))

        assertTrue(taps.isEmpty())
    }

    @Test
    fun `a cancelled press reports nothing`() {
        val (x, y) = pixelOf(10f, 10f)
        view.onTouchEvent(event(MotionEvent.ACTION_DOWN, x, y, 0))
        view.onTouchEvent(event(MotionEvent.ACTION_CANCEL, x, y, 40))

        assertTrue(taps.isEmpty())
    }

    @Test
    fun `the fitted line has no markers, so a tap on its end reports nothing`() {
        // (20, 25) is on the muted fit line only; the curve's nearest marker is (20, 40).
        val (x, y) = pixelOf(20f, 25f)
        val (_, curveY) = pixelOf(20f, 40f)
        assertTrue("the line's end is clear of the curve's marker", y - curveY > TAP_RADIUS_DP)
        tapAt(x, y)

        assertTrue(taps.isEmpty())
    }

    @Test
    fun `a curve drawn without markers has no point to tap`() {
        view.setData(listOf(curve.copy(markers = false)), "strain", "stress")
        redraw()
        val (x, y) = pixelOf(10f, 10f)
        tapAt(x, y)

        assertTrue(taps.isEmpty())
    }

    private companion object {
        const val W = 480
        const val H = 320
    }
}
