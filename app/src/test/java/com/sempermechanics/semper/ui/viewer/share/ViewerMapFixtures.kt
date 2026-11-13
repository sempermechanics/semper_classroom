package com.sempermechanics.semper.ui.viewer.share

import android.graphics.Color
import com.sempermechanics.semper.field.DicResult
import com.sempermechanics.semper.fixtures.DeformedFrameFixture
import com.sempermechanics.semper.report.VisualizationEngine
import org.junit.Assert.assertEquals

/**
 * The pixel at [at] of the viewer's Exx map of [data], a
 * [DeformedFrameFixture] field ([VisualizationEngine.generateFrameHeatmap],
 * the viewer's own render, as [com.sempermechanics.semper.ui.viewer.ViewerScaleController]
 * draws the frame), [displaced] or at the reference positions. The map must
 * cover [at]. For the exports that draw a frame as the viewer does
 * ([FieldImageExportTest], `ReportDeformedFrameTest`).
 */
internal fun viewerMapPixel(data: FloatArray, displaced: Boolean, at: Pair<Int, Int>): Int {
    val map = VisualizationEngine.generateFrameHeatmap(
        displaced,
        data,
        DeformedFrameFixture.W,
        DeformedFrameFixture.H,
        DicResult.IDX_EXX,
        DeformedFrameFixture.STEP,
        maxLongEdge = VisualizationEngine.REPORT_MAX_EDGE,
    ).bitmap
    val pixel = map.getPixel(at.first, at.second)
    map.recycle()
    assertEquals("the viewer's map covers $at", 0xFF, Color.alpha(pixel))
    return pixel
}
