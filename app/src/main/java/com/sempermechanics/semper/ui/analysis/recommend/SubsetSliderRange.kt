package com.sempermechanics.semper.ui.analysis.recommend

import com.sempermechanics.semper.ui.analysis.recommend.SubsetRecommender.MAX_SUBSET
import com.sempermechanics.semper.ui.analysis.recommend.SubsetRecommender.MIN_SUBSET

/**
 * The subset slider's range and starting size once the reference is
 * measured, from a [SubsetRecommender.Recommendation].
 */
object SubsetSliderRange {
    /**
     * How far past the SSSIG size a measured slider reaches. The SSSIG size is
     * the least the target accuracy allows, so it is the slider's floor; this
     * much above it leaves room for the speckle span and for smoothing, and
     * keeps each step of the slider a small move.
     */
    const val SPAN = 50

    /** The subset slider's step: sizes stay odd. */
    internal const val STEP = 2
}

/**
 * The subset slider's ends once this is measured: from the SSSIG
 * [SubsetRecommender.Recommendation.subsetSize], the least the accuracy target
 * allows, to [SubsetSliderRange.SPAN] above it, inside the full range. At the
 * top of the range the floor gives way by a step, so the slider keeps two ends.
 */
val SubsetRecommender.Recommendation.sliderRange: IntRange
    get() {
        val floor = (subsetSize or 1).coerceIn(MIN_SUBSET, MAX_SUBSET - SubsetSliderRange.STEP)
        return floor..minOf(floor + SubsetSliderRange.SPAN, MAX_SUBSET)
    }

/**
 * Where the slider starts: the size that spans three of the measured speckles
 * ([SubsetRecommender.Recommendation.subsetSpanningSpeckles]), inside
 * [sliderRange]. A speckle too coarse for any subset to span starts it at the
 * top; one not measured, at the SSSIG floor.
 */
val SubsetRecommender.Recommendation.startingSubset: Int
    get() {
        val range = sliderRange
        val wanted = when {
            speckleDiameterPx == null -> range.first
            else -> subsetSpanningSpeckles ?: range.last
        }
        return wanted.coerceIn(range)
    }
