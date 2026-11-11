@file:Suppress("MagicNumber")

package com.sempermechanics.semper.report

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [ElasticModulus] on a real test, not a lab table: the app's own curve for
 * 1.4016 (X6Cr17) steel sheet, 12.5 mm² section, from Zenodo record 18311953
 * (CC BY 4.0). Strain is the DIC field's mean Exx over the accepted points
 * (ADR-035), read from the `.dat` files of a Samsung S22+ run on 2026-11-11
 * (engine 0.6.1); stress is the dataset's force over the area. All 40 frames
 * are on the curve: past frame 35 the strip carries its far end out of the
 * picture, but the points still in view keep a mean. How the run was made and
 * what it is compared with: docs/app/REAL_WORLD_VALIDATION.md.
 */
class RealSteelModulusTest {

    private val model = StressStrain.Model.Axial(areaMm2 = 12.5f, axisX = true)

    private val strainToStress = listOf(
        0.06216f to 6.5040f, 0.07809f to 12.5520f, 0.11071f to 16.7600f, 0.11295f to 20.7120f,
        0.14962f to 24.5200f, 0.20623f to 29.5200f, 0.18205f to 34.6480f, 0.26901f to 40.1760f,
        0.25397f to 46.8800f, 0.32070f to 53.8480f, 0.39331f to 61.7440f, 0.44524f to 70.9440f,
        0.54903f to 80.0240f, 0.55849f to 89.6240f, 0.61853f to 100.9360f, 0.68182f to 111.1920f,
        0.76741f to 123.6880f, 0.83514f to 135.3920f, 0.92982f to 147.8800f, 1.00620f to 160.3760f,
        1.10788f to 173.0000f, 1.21260f to 187.3360f, 1.29861f to 200.0960f, 1.43192f to 212.7200f,
        1.48666f to 226.1360f, 1.66113f to 238.7600f, 1.79364f to 251.2560f, 1.93946f to 263.3520f,
        3.18187f to 300.7040f, 5.03331f to 311.7520f, 7.03875f to 317.9280f, 11.08719f to 326.0880f,
        21.06574f to 345.6800f, 40.86362f to 375.8000f, 71.29238f to 404.0720f, 114.46372f to 423.9360f,
        161.07954f to 432.2160f, 213.93143f to 435.5040f, 268.09204f to 433.5360f, 336.70514f to 422.0880f,
    )

    private fun curve(): StressStrain.Curve =
        StressStrain.Curve(
            model,
            strainToStress.size,
            strainToStress.mapIndexed { i, (strain, stress) -> StressStrain.Point(i, stress * 12.5f, stress, strain) },
        )

    @Test
    fun `real steel gives E over the first 26 frames`() {
        val fit = ElasticModulus.fit(curve())!!

        assertEquals(149.6f, fit.modulusGPa, 0.05f)
        assertEquals(0, fit.firstFrame)
        assertEquals(25, fit.lastFrame)
        assertTrue(fit.r2 >= ElasticModulus.MIN_R2)
    }

    @Test
    fun `the first three frames alone are not straight, so the run must not stop there`() {
        // 6.5–16.8 MPa: strain noise is as large as the signal. The old rule gave up here.
        val first = curve().points.take(3)
        val line = LinearFit.fit(
            first.map { it.strainMilli.toDouble() }.toDoubleArray(),
            first.map { it.stressMPa.toDouble() }.toDoubleArray(),
        )!!

        assertEquals(0.913, line.r2, 1e-3)
        assertTrue(line.r2 < ElasticModulus.MIN_R2)
    }

    @Test
    fun `the viewer's elastic region zooms to the fitted frames, not the 337 me curve`() {
        // Window 0–2.49 mε: the 26 fitted frames plus frames 27–28, led by the origin.
        val fit = ElasticModulus.fit(curve())!!

        val region = ElasticRegion.of(curve(), fit)!!

        assertEquals(29, region.points.size)
        assertEquals(strainToStress.take(28), region.points.drop(1))
        assertEquals(1.93946f, region.line.last().first, 0f)
    }

    @Test
    fun `Rp0_2 falls between frames 29 and 30, within 1 percent of the gauge points'`() {
        // The gauge points over all 751 logged steps give 307.8 MPa.
        val yieldPoint = YieldStrength.offset(curve(), ElasticModulus.fit(curve())!!)!!

        assertEquals(305.7f, yieldPoint.stressMPa, 0.1f)
        assertEquals(4.02f, yieldPoint.strainMilli, 0.01f)
        assertEquals(28, yieldPoint.frameBefore)
        assertEquals(29, yieldPoint.frameAfter)
        assertEquals(1f, yieldPoint.stressMPa / 307.8f, 0.01f)
    }

    @Test
    fun `the peak is on the curve, at frame 38`() {
        // Under ΔL / L₀ the curve stopped at frame 35 and the peak was off it (TD-148).
        val peak = curve().peakStress!!

        assertEquals(37, peak.frame)
        assertEquals(435.504f, peak.stressMPa, 1e-3f)
        assertTrue(peak.onCurve)
    }

    @Test
    fun `E sits within ten percent of the dataset's own gauge points`() {
        // The dataset's stereo gauge points, 60 mm apart, over the same 26 frames.
        val gaugePointGPa = 157.5f

        val fit = ElasticModulus.fit(curve())!!

        assertEquals(1f, fit.modulusGPa / gaugePointGPa, 0.10f)
    }
}
