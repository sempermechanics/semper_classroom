package com.sempermechanics.semper.ui.analysis.recommend

import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Turns a burst of static test frames into a verdict on whether this setup can
 * measure the strain the user is about to apply. Not wired into any screen since
 * the in-app camera went (TD-283).
 *
 * Nothing here is loaded, so every displacement the correlator reports between
 * two of these frames is error. Converting it into the strain it would
 * masquerade as makes it a decision: "this setup cannot see strains below 20 mε".
 *
 * **Why a burst rather than a pair.** One pair estimates σ precisely, but about
 * *one instant*: a lorry, an AE step or a flicker beat makes it confidently
 * wrong. Extra frames ask "what is the noise of this setup" instead, so the
 * count is set by breakdown and false-alarm rates, not by `1/√n`.
 *
 * **Frames are not estimates.** *n* frames give *n − 1* estimates: each sample
 * is one frame against the reference, itself one of the *n*. Rows are in
 * frames, with the estimates they yield in brackets:
 *
 * | Test | Needs | Why |
 * |---|---|---|
 * | Median — one bad frame must not decide | 3 frames (2), 4+ safe | the median's breakdown is half the samples |
 * | Spread — settling, or intermittent? | 4 frames (3) | 2 estimates give a difference, not a scatter |
 * | Drift vs noise | 6 frames (5) | a monotone run of *k* **estimates** happens by
 *   chance with probability `2/k!`: 33% at k=3, 8.3% at k=4, 1.7% at k=5 |
 *
 * The drift row sets [MAX_FRAMES] at six: five frames give four estimates, a
 * false drift call on one steady rig in twelve. Drift is the one finding whose
 * fix (wait or re-mount) differs from noise's (more light, a larger area), so a
 * false call sends someone to re-level a level tripod. Above six the rate keeps
 * falling but no verdict changes.
 */
object NoiseFloorStats {

    /**
     * One reference→frame correlation over the ROI. Displacements are in pixels;
     * [meanU] and [meanV] carry the rigid-body part that separates drift from
     * noise, and [sigmaU]/[sigmaV] the scatter left after it.
     */
    data class PairSample(
        val sigmaU: Double,
        val sigmaV: Double,
        val meanU: Double,
        val meanV: Double,
        /** `var(I1 - I2) / 2` over the ROI — the image noise variance `D(η)`. */
        val noiseVariance: Double,
        /**
         * How alike neighbouring pixels of the difference image are; NaN when
         * it could not be measured. Near zero for real sensor noise, high when
         * something is smoothing the frames underneath the pipeline lockdown.
         */
        val noiseCorrelation: Double = Double.NaN,
        /** Mean grey level of the deformed frame; flicker shows up here. */
        val meanIntensity: Double,
    ) {
        /** Worst axis, since a gauge can be oriented either way. */
        val sigmaPx: Double get() = maxOf(sigmaU, sigmaV)

        /** Rigid-body displacement magnitude for this frame. */
        val driftPx: Double get() = sqrt(meanU * meanU + meanV * meanV)
    }

    /**
     * What the burst concluded. Only [Outcome.HIGH_FLOOR] is about precision,
     * and it never sets [Verdict.blocking].
     */
    enum class Outcome {
        /** The floor is within limits and the series was steady. */
        PASS,

        /**
         * Measurable, but coarser than the limit — strain smaller than the
         * floor will be indistinguishable from noise in the result.
         *
         * A warning, not [Verdict.blocking]: the measurement is a proxy, and the
         * user knows what it does not (a 50 mε test tolerates a 2 mε floor; a
         * shakedown; a fixture that cannot be re-mounted). They are owed the
         * number, not a locked door.
         */
        HIGH_FLOOR,

        /** Estimates disagree: something intermittent, so no number can be trusted. */
        NOT_SETTLING,

        /** A monotone trend: thermal settling, tripod creep, or OIS drift. */
        DRIFTING,

        /** Too few usable frames to conclude anything. Reports, never blocks. */
        INSUFFICIENT,
    }

    /** The verdict. [blocking] is not "failed": see [STOPS_TO_ASK]. */
    data class Verdict(
        val outcome: Outcome,
        /** Strain floor in microstrain at the gauge length in use. */
        val floorMicrostrain: Double,
        /** Displacement noise behind that floor, in pixels. */
        val sigmaPx: Double,
        /** Median image noise variance `D(η)`, for the subset recommendation. */
        val noiseVariance: Double,
        /**
         * Median neighbour correlation of the difference image, or NaN.
         *
         * Kept beside [noiseVariance] because it is the one thing that says
         * whether that variance can be believed: the model behind the subset
         * recommendation assumes white noise, and this is the measurement that
         * checks the assumption. See [NoiseFloorPixels.noiseCorrelationOf].
         */
        val noiseCorrelation: Double = Double.NaN,
        /** Frames the verdict is based on, after unusable ones were dropped. */
        val frameCount: Int,
        /** Fraction by which estimates disagreed — 0 when they agree exactly. */
        val spread: Double,
        /** Largest rigid-body displacement seen across the burst, in pixels. */
        val driftPx: Double,
        /** Frame-to-frame brightness scatter as a fraction of mean level. */
        val brightnessScatter: Double,
        /**
         * True when a run should not start on this burst without a deliberate
         * override: one of [STOPS_TO_ASK], never a high floor.
         */
        val blocking: Boolean,
        /**
         * True when the floor is above the limit, whatever the outcome: kept
         * apart from [outcome] so a drifting burst's too-high floor is still
         * on record with the result.
         */
        val floorExceeded: Boolean,
        /** True when averaging credit was applied; false when drift denied it. */
        val averagingCredited: Boolean,
    ) {
        /** Confidence follows frame count; see the table on [NoiseFloorStats]. */
        val canAssertDrift: Boolean get() = frameCount >= FRAMES_FOR_DRIFT
        val canAssertSpread: Boolean get() = frameCount >= FRAMES_FOR_SPREAD
    }

    /**
     * Reduce a burst to a verdict.
     *
     * @param samples reference→frame correlations, in capture order.
     * @param vsgPx virtual strain gauge length in pixels — the floor is
     *   meaningless without it, which is why it is not optional.
     * @param averagingK frames the run will average per state; the credit is
     *   `1/√k` and is withheld when the burst is drifting, because drift and
     *   correlated pipeline terms do not average down at all.
     * @param limitMicrostrain the floor limit, in microstrain.
     */
    fun evaluate(
        samples: List<PairSample>,
        vsgPx: Double,
        averagingK: Int = 1,
        limitMicrostrain: Double = DEFAULT_LIMIT_MICROSTRAIN,
    ): Verdict {
        val usable = samples.filter { it.sigmaPx.isFinite() && it.sigmaPx >= 0.0 }
        if (usable.isEmpty() || vsgPx <= 0.0) {
            return insufficient(usable.size)
        }

        // Frames, not estimates: each sample is one frame correlated against the
        // reference, and the reference itself is the frame that makes it a pair.
        val frameCount = usable.size + 1
        val sigmas = usable.map { it.sigmaPx }
        val medianSigma = median(sigmas)
        val spread = spreadOf(sigmas, medianSigma)
        val drifting = frameCount >= FRAMES_FOR_DRIFT && isMonotone(usable.map { it.driftPx })

        // 1/sqrt(k) assumes the error is independent between frames. Drift and
        // correlated ISP terms are not, and do not average down — so a drifting
        // burst gets no credit rather than an optimistic one.
        val credited = averagingK > 1 && !drifting
        val effectiveSigma = if (credited) medianSigma / sqrt(averagingK.toDouble()) else medianSigma
        val floor = microstrainFor(effectiveSigma, vsgPx)

        val exceeded = frameCount >= FRAMES_FOR_MEDIAN &&
            floor > effectiveLimit(limitMicrostrain, frameCount)
        val outcome = outcomeFor(frameCount, spread, drifting, exceeded)

        return Verdict(
            outcome = outcome,
            floorMicrostrain = floor,
            sigmaPx = effectiveSigma,
            noiseVariance = median(usable.map { it.noiseVariance }),
            noiseCorrelation = median(usable.map { it.noiseCorrelation }),
            frameCount = frameCount,
            spread = spread,
            driftPx = usable.maxOf { it.driftPx },
            brightnessScatter = brightnessScatter(usable.map { it.meanIntensity }),
            // A burst too short for a median never blocks on a single sample.
            blocking = outcome in STOPS_TO_ASK && frameCount >= FRAMES_FOR_MEDIAN,
            floorExceeded = exceeded,
            averagingCredited = credited,
        )
    }

    /**
     * Which single finding the burst is reported as.
     *
     * Ordered by what the user should act on first, not by severity. A burst
     * that will not settle says nothing trustworthy about anything else, so it
     * outranks drift; drift outranks the floor because a drifting burst's floor
     * is measured through the drift. Only the last of them is about precision.
     */
    private fun outcomeFor(
        frameCount: Int,
        spread: Double,
        drifting: Boolean,
        floorExceeded: Boolean,
    ): Outcome = when {
        frameCount < FRAMES_FOR_MEDIAN -> Outcome.INSUFFICIENT
        frameCount >= FRAMES_FOR_SPREAD && spread > SPREAD_LIMIT -> Outcome.NOT_SETTLING
        drifting -> Outcome.DRIFTING
        floorExceeded -> Outcome.HIGH_FLOOR
        else -> Outcome.PASS
    }

    /**
     * The outcomes that stop and ask, rather than warn and continue.
     *
     * Both mean the burst could not measure *itself* consistently, so retrying
     * costs seconds and buys a number worth having. A high floor is not here:
     * that measurement succeeded and came back worse than the limit, which is
     * for the user to act on.
     */
    private val STOPS_TO_ASK = setOf(Outcome.NOT_SETTLING, Outcome.DRIFTING)

    /**
     * How many test frames to take, given what one frame costs.
     *
     * [FRAME_BUDGET_MS] is what a test shot may spend before it stops feeling
     * like a test shot and starts feeling like a run. The count is only ever
     * reduced from [MAX_FRAMES], never raised: the tests above degrade in a
     * known order, and promising a confidence the burst did not earn is worse
     * than admitting a shorter check.
     */
    fun frameCountFor(perFrameCostMs: Long): Int {
        if (perFrameCostMs <= 0L) return MAX_FRAMES
        val affordable = (FRAME_BUDGET_MS / perFrameCostMs).toInt()
        return affordable.coerceIn(MIN_FRAMES, MAX_FRAMES)
    }

    /**
     * Strain floor for a displacement noise of [sigmaPx] at a gauge of [vsgPx].
     *
     * `σ_ε ≈ √2·σ_u / L_vsg` for a two-point gauge: two independent position
     * estimates, each carrying σ, differenced over the gauge length.
     */
    fun microstrainFor(sigmaPx: Double, vsgPx: Double): Double =
        if (vsgPx <= 0.0) Double.POSITIVE_INFINITY else SQRT_2 * sigmaPx / vsgPx * MICRO

    /**
     * Median of the values that are real numbers, or NaN when none are; a
     * median so one disturbed frame cannot decide the outcome.
     *
     * Non-finite inputs are dropped rather than sorted: Kotlin orders NaN above
     * every number, so a frame whose grey window would not decode would
     * otherwise sit at the top of the list and drag the median up — or become
     * the median outright on a short burst.
     *
     * Nothing to take a median of comes back NaN and not 0.0, because here the
     * two mean opposite things: zero image noise or zero neighbour correlation
     * describes a perfect camera, and "could not be measured" must never be
     * reported as one.
     */
    internal fun median(values: List<Double>): Double {
        val sorted = values.filter { it.isFinite() }.sorted()
        if (sorted.isEmpty()) return Double.NaN
        val mid = sorted.size / 2
        return if (sorted.size % 2 == 1) {
            sorted[mid]
        } else {
            (sorted[mid - 1] + sorted[mid]) / 2.0
        }
    }

    /**
     * Disagreement between estimates, as a median absolute deviation relative to
     * the median.
     *
     * It has to be robust, or it contradicts the median test standing next to
     * it: a full range would fire on exactly the one disturbed frame the median
     * exists to absorb, and every momentary bump would read as an unsettled rig.
     * A MAD has the same 50% breakdown point as the median, so one bad estimate
     * moves it barely at all while a burst with no tight cluster — which is what
     * something intermittent actually looks like — moves it a lot.
     */
    internal fun spreadOf(values: List<Double>, median: Double): Double = when {
        values.size < 2 -> 0.0
        median <= 0.0 -> 0.0
        else -> median(values.map { abs(it - median) }) / median
    }

    /**
     * True when [values] rise or fall throughout — the signature of drift rather
     * than noise. Strict, because a single repeat is enough to make a run
     * ordinary; see the `2/k!` reasoning on [NoiseFloorStats].
     *
     * [values] are *estimates*, so the guard is `FRAMES_FOR_DRIFT - 1`; anything
     * shorter returns false, and drift is reported as possible, not asserted.
     */
    internal fun isMonotone(values: List<Double>): Boolean {
        if (values.size < FRAMES_FOR_DRIFT - 1) return false
        val rising = values.zipWithNext().all { (a, b) -> b > a }
        val falling = values.zipWithNext().all { (a, b) -> b < a }
        return rising || falling
    }

    /**
     * Brightness scatter as a fraction of mean level.
     *
     * The specimen is static and the exposure is locked, so any change in
     * brightness across the burst is the light or the camera — which makes this
     * the direct empirical test of whether the flicker-safe exposure held,
     * rather than an assumption that it did.
     *
     * A full span, deliberately, where every other statistic in this class is a
     * median or a MAD. Those are robust because they estimate a *level* that one
     * disturbed frame must not move. Flicker is not a level — it is an
     * oscillation, and its whole signature lives in the extremes, so a robust
     * estimator here would suppress precisely the thing being measured. Nothing
     * gates on it, so a genuinely disturbed frame costs only a high reading.
     */
    @Suppress("ReturnCount") // too few frames, then a level no ratio can divide by
    internal fun brightnessScatter(levels: List<Double>): Double {
        val usable = levels.filter { it.isFinite() && it > 0.0 }
        if (usable.size < 2) return 0.0
        val mean = usable.average()
        if (mean <= 0.0) return 0.0
        return (usable.max() - usable.min()) / mean
    }

    /**
     * The limit [Verdict.floorExceeded] is judged against, widened when the
     * burst was short: fewer estimates means less confidence that the median is
     * the real floor, so a short burst must be further past the limit before it
     * reports [Outcome.HIGH_FLOOR] on a setup that may be fine.
     */
    private fun effectiveLimit(limitMicrostrain: Double, frameCount: Int): Double = when {
        frameCount >= FRAMES_FOR_DRIFT -> limitMicrostrain
        frameCount >= FRAMES_FOR_SPREAD -> limitMicrostrain * SHORT_BURST_MARGIN
        else -> limitMicrostrain * SHORTEST_BURST_MARGIN
    }

    private fun insufficient(sampleCount: Int) = Verdict(
        outcome = Outcome.INSUFFICIENT,
        floorMicrostrain = Double.NaN,
        sigmaPx = Double.NaN,
        noiseVariance = Double.NaN,
        noiseCorrelation = Double.NaN,
        frameCount = if (sampleCount > 0) sampleCount + 1 else 0,
        spread = 0.0,
        driftPx = 0.0,
        brightnessScatter = 0.0,
        blocking = false,
        floorExceeded = false,
        averagingCredited = false,
    )

    /** 1 mε — below this the run would not show the strain being applied. */
    const val DEFAULT_LIMIT_MICROSTRAIN = 1_000.0

    /** Six frames (five estimates): where the drift test becomes reliable. */
    const val MAX_FRAMES = 6

    /** Two frames still yields one estimate, which reports but never blocks. */
    const val MIN_FRAMES = 2

    /** Three frames (two estimates) is the floor for a meaningful median. */
    const val FRAMES_FOR_MEDIAN = 3

    /** Four frames (three estimates) is the floor for judging spread. */
    const val FRAMES_FOR_SPREAD = 4

    /**
     * Six frames (five estimates): a monotone run is then 1.7% likely from noise
     * alone. In frames, as [Verdict.frameCount] carries them; [isMonotone]
     * converts. It is [MAX_FRAMES] on purpose: only a full burst gets the
     * unwidened [effectiveLimit].
     */
    const val FRAMES_FOR_DRIFT = 6

    /**
     * A robust scatter above this fraction of the median means no tight cluster
     * of agreeing estimates exists, so no single number describes the setup.
     * Comfortably above what one disturbed frame in a full burst produces.
     */
    private const val SPREAD_LIMIT = 0.5
    private const val SHORT_BURST_MARGIN = 1.25
    private const val SHORTEST_BURST_MARGIN = 1.5

    /**
     * A test shot may spend this long on the burst and still feel like one.
     * Three seconds, not 2.5: first-still cost measured 277-551 ms on both test
     * devices, and [MAX_FRAMES] must fit on the slower one too, or it cannot
     * assert drift at all.
     */
    private const val FRAME_BUDGET_MS = 3_000L

    private const val SQRT_2 = 1.4142135623730951
    private const val MICRO = 1_000_000.0
}
