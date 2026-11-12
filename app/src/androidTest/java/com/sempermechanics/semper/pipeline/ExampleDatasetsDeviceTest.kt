package com.sempermechanics.semper.pipeline

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.sempermechanics.semper.ProgressCallback
import com.sempermechanics.semper.SemperNativeLib
import com.sempermechanics.semper.data.examples.ExampleAssetProvider
import com.sempermechanics.semper.data.examples.ExampleCatalog
import com.sempermechanics.semper.data.examples.ExampleManifest
import com.sempermechanics.semper.field.DicResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs

/**
 * The bundled example sets on a real build (ADR-031): every file each set
 * lists opens through [ExampleAssetProvider] as Try it would open it (a
 * compressed asset would fail here, not on the JVM), and the DICe open-hole
 * series, decoded by the engine's own imgcodecs as the wizard hands it over,
 * gives DICe's answers on its first and last frames.
 */
@RunWith(AndroidJUnit4::class)
class ExampleDatasetsDeviceTest {

    private val context: Context = InstrumentationRegistry.getInstrumentation().targetContext

    private fun set(id: String): ExampleManifest = requireNotNull(ExampleCatalog.find(context.assets, id)) { id }

    private fun bytes(set: ExampleManifest, file: String): ByteArray {
        val uri = ExampleAssetProvider.uriFor(context, set.id, file)
        return requireNotNull(context.contentResolver.openInputStream(uri)) { "$uri" }.use { it.readBytes() }
    }

    @Test
    fun everyListedFileOpensThroughTheProvider() {
        val sets = ExampleCatalog.load(context.assets)
        assertEquals(3, sets.size)
        sets.forEach { set ->
            (listOf(set.reference) + set.deformed + listOfNotNull(set.loadLog?.file)).forEach { file ->
                val uri = ExampleAssetProvider.uriFor(context, set.id, file)
                context.contentResolver.openAssetFileDescriptor(uri, "r").use { fd ->
                    assertTrue("$uri is empty", requireNotNull(fd) { "$uri" }.length > 0)
                }
            }
        }
    }

    /** Medians of u and v over the accepted points, and the share accepted. */
    private class Field(val medianU: Float, val medianV: Float, val accepted: Float)

    private fun solve(set: ExampleManifest, frame: String, step: Int, subset: Int): Field {
        val ref = bytes(set, set.reference)
        val def = bytes(set, frame)
        val dims = SemperNativeLib.getImageDimensions(ref)
        assertEquals("${set.id} decoded size", set.imageSize, listOf(dims[0], dims[1]))
        SemperNativeLib.initializeReference(ref, null, dims[0], dims[1])
        val roi = set.roiRect
        val maxPoints = (roi.w / step + 1) * (roi.h / step + 1)
        val buffer = ByteBuffer.allocateDirect(maxPoints * DicResult.BYTES_PER_POINT).order(ByteOrder.nativeOrder())
        val metrics = FloatArray(METRICS) { if (it == METRICS - 1) -1f else 0f }
        val callback = object : ProgressCallback {
            override fun onProgressUpdate(percentage: Int) {}
        }
        val points = SemperNativeLib.computeFullFieldDirect(
            ref, def, ByteArray(0),
            roi.x, roi.y, roi.w, roi.h,
            step, subset, (STRAIN_POINTS - 1) * step + 1,
            false,
            buffer, callback, metrics,
        )
        assertTrue("${set.id}: engine returned $points", points > 0)
        val data = FloatArray(points * DicResult.STRIDE)
        buffer.position(0)
        buffer.asFloatBuffer().get(data)
        val u = ArrayList<Float>()
        val v = ArrayList<Float>()
        for (i in data.indices step DicResult.STRIDE) {
            if (DicResult.isAcceptedPoint(data[i + DicResult.IDX_ZNSSD])) {
                u += data[i + DicResult.IDX_U]
                v += data[i + DicResult.IDX_V]
            }
        }
        assertTrue("${set.id}: no accepted points", u.isNotEmpty())
        u.sort()
        v.sort()
        return Field(u[u.size / 2], v[v.size / 2], u.size / points.toFloat())
    }

    @Test
    fun openHoleSeriesMatchesDicesSolution() {
        val set = set("dice_oht_series")
        // The settings Try it proposes (speckle 5 px). DICe's medians over its 230 points: frame 1 (DICe_solution_01.txt)
        // u −0.096, v −0.627 px; frame 11 (DICe_solution_11.txt) u −0.748, v −8.636 px.
        // Engine 0.6.1 on x86_64 gave −0.108 / −0.620 and −0.721 / −8.689 (README.md of the set).
        listOf(
            Triple(set.deformed.first(), OHT_FIRST_U_PX, OHT_FIRST_V_PX),
            Triple(set.deformed.last(), OHT_LAST_U_PX, OHT_LAST_V_PX),
        ).forEach { (frame, u, v) ->
            val field = solve(set, frame, step = STEP, subset = SUBSET)
            assertTrue("$frame accepted ${field.accepted}", field.accepted >= MIN_ACCEPTED)
            assertTrue("$frame median u ${field.medianU}", abs(field.medianU - u) <= OHT_TOL_PX)
            assertTrue("$frame median v ${field.medianV}", abs(field.medianV - v) <= OHT_TOL_PX)
        }
    }

    private companion object {
        const val METRICS = 17

        // The window is in pixels, (points − 1) × step + 1 as the app sizes it: the VSG fit needs 3 nodes in it.
        const val STRAIN_POINTS = 5
        const val MIN_ACCEPTED = 0.5f
        const val STEP = 5
        const val SUBSET = 17
        const val OHT_FIRST_U_PX = -0.096f
        const val OHT_FIRST_V_PX = -0.627f
        const val OHT_LAST_U_PX = -0.748f
        const val OHT_LAST_V_PX = -8.636f
        const val OHT_TOL_PX = 0.1f
    }
}
