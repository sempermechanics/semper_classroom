package com.sempermechanics.semper.report

import android.graphics.Bitmap
import com.sempermechanics.semper.field.ValueRange

/**
 * A rendered heatmap and the colour range it was rendered with: what
 * `VisualizationEngine.render` returns, and what the viewer's scrub cache holds.
 * Destructures as `val (bitmap, min, max) = ...`.
 */
data class BakedHeatmap(val bitmap: Bitmap, val min: Float, val max: Float) {

    /** The colour range the bitmap was rendered with. */
    val range: ValueRange get() = ValueRange(min, max)

    companion object {
        /** From the engine's old `Triple(bitmap, min, max)`. */
        fun of(triple: Triple<Bitmap, Float, Float>): BakedHeatmap =
            BakedHeatmap(triple.first, triple.second, triple.third)
    }
}
