@file:Suppress("MagicNumber")

package com.sempermechanics.semper.ui.viewer.mechanical

import com.sempermechanics.semper.data.mechanical.BeamEdgeTaps
import com.sempermechanics.semper.data.mechanical.CurveCorrection
import com.sempermechanics.semper.data.mechanical.TypedLoads
import com.sempermechanics.semper.report.BeamDeflection
import com.sempermechanics.semper.report.ElasticModulus
import com.sempermechanics.semper.report.ElasticRegion
import com.sempermechanics.semper.report.StressStrain
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * A point tapped on the Results curve opens its photo, so each drawn point has
 * to lead back to its frame ([ViewerStressStrainHelper.frameOfPlotPoint]) on
 * every plot the viewer draws: the whole curve, the elastic region, and
 * bending's load on deflection in kg.
 */
class PlotPointFrameTest {

    private val model = StressStrain.Model.Axial(areaMm2 = 100f, axisX = true)

    /** Frame 3 (index 2) is not on the curve, as when it had no load row. */
    private fun tensile(axial: StressStrain.Model.Axial = model): StressStrain.Curve {
        val points = listOf(0, 1, 3, 4, 5, 6).mapIndexed { i, frame ->
            val loadN = 2000f * (i + 1)
            StressStrain.Point(frame, loadN, axial.stressMPa(loadN), axial.correction.strain(0.1f * (i + 1)))
        }
        return StressStrain.Curve(axial, 7, points)
    }

    @Test
    fun `every point of the whole curve leads back to its frame`() {
        val curve = tensile()
        val drawn = curve.plotPoints()

        assertEquals(
            listOf(0, 1, 3, 4, 5, 6),
            drawn.drop(1).map { (x, y) -> ViewerStressStrainHelper.frameOfPlotPoint(curve, x, y) },
        )
    }

    @Test
    fun `the unloaded reference that leads the curve is not a frame`() {
        val curve = tensile()
        val (x, y) = curve.plotPoints().first()

        assertNull(ViewerStressStrainHelper.frameOfPlotPoint(curve, x, y))
    }

    @Test
    fun `a corrected curve is found by its corrected values`() {
        val corrected = model.copy(correction = CurveCorrection.of(1.08f, 0.02f, 1f, 0f))
        val curve = tensile(corrected)

        assertEquals(
            listOf(null, 0, 1, 3, 4, 5, 6),
            curve.plotPoints().map { (x, y) -> ViewerStressStrainHelper.frameOfPlotPoint(curve, x, y) },
        )
    }

    @Test
    fun `the elastic region's points lead back to the same frames`() {
        val curve = tensile()
        val fit = ElasticModulus.fit(curve)!!
        val region = ElasticRegion.of(curve, fit)!!

        val frames = region.points.drop(1).map { (x, y) -> ViewerStressStrainHelper.frameOfPlotPoint(curve, x, y) }
        assertEquals(frames.size, frames.filterNotNull().size)
        assertEquals(frames, frames.sortedBy { it })
        assertEquals(0, frames.first())
    }

    @Test
    fun `a bending point is found by its deflection and its load in kg`() {
        val taps = BeamEdgeTaps(0f, 0f, 0f, 127.6f)
        val bending = StressStrain.Model.Flexural(935f, 150f, 6.38f, true, BeamDeflection.Probe(taps, 6.38f))
        val kg = listOf(4.28f, 5.24f, 6.92f)
        val mm = listOf(1.14f, 1.46f, 2.02f)
        val points = kg.indices.map { i ->
            val w = kg[i] * TypedLoads.G
            StressStrain.Point(i + 2, w, bending.stressMPa(w), 0f, mm[i])
        }
        val curve = StressStrain.Curve(bending, 5, points)
        // What ViewerBendingResults.plotSeries draws: δ in mm against the load in kg.
        val drawn = TypedLoads.loadsInKg(curve.plotPoints())

        assertEquals(
            listOf(null, 2, 3, 4),
            drawn.map { (x, y) -> ViewerStressStrainHelper.frameOfPlotPoint(curve, x, y) },
        )
    }
}
