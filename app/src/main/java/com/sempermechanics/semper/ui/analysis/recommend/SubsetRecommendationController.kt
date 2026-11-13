package com.sempermechanics.semper.ui.analysis.recommend

import android.animation.ValueAnimator
import android.graphics.Rect
import androidx.annotation.VisibleForTesting
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.sempermechanics.semper.R
import com.sempermechanics.semper.SemperNativeLib
import com.sempermechanics.semper.databinding.ActivityStaticAnalysisBinding
import com.sempermechanics.semper.databinding.WizardStepSettingsContentBinding
import com.sempermechanics.semper.field.DicParams
import com.sempermechanics.semper.field.toRect
import com.sempermechanics.semper.ui.analysis.wizard.AnalysisViewModel
import com.sempermechanics.semper.ui.analysis.wizard.AnalysisWizardHost
import com.sempermechanics.semper.ui.analysis.wizard.WizardInputs
import com.sempermechanics.semper.ui.analysis.wizard.glideTo
import com.sempermechanics.semper.ui.analysis.wizard.snapToSlider
import com.sempermechanics.semper.ui.common.InlineBusy
import com.sempermechanics.semper.ui.common.dialog.WarnChip
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber

/**
 * The subset slider's range and starting size, from the reference speckle,
 * measured whenever the reference image or the ROI changes.
 *
 * The SSSIG size (Pan et al., Opt. Express 16, 7037 (2008)) — see
 * [SubsetRecommender] — is the least the accuracy target allows, so it is the
 * slider's lower end, and the upper end is [SubsetSliderRange.SPAN]
 * above it. The slider starts at the size that spans three measured speckles
 * ([startingSubset]), and stops being seeded
 * once the user sets a size of their own. The range holds for every size: a
 * typed, pasted or Re-run's size outside it is moved to the nearer end, and so
 * is the user's own when a new measurement moves the range.
 *
 * Owns the speckle feedback too: the low-texture and speckle-size chips on
 * page 1; on page 2 the speckle chip beside the subset size, the band of
 * sizes that hold enough speckle shaded behind the slider ([SubsetBand]; the
 * subset's ⓘ says what it means), and the span chip. The speckle chip says
 * "Measuring speckle…" while a measurement lasts past 300 ms, and the slider
 * glides to the size the measurement chose.
 */
class SubsetRecommendationController(
    private val activity: AppCompatActivity,
    private val viewModel: AnalysisViewModel,
    binding: ActivityStaticAnalysisBinding,
    private val settings: WizardStepSettingsContentBinding,
    private val host: AnalysisWizardHost,
    /** Where the measurement runs: the engine's thread. A function, so the engine loads only when one is asked. */
    private val measureOn: () -> CoroutineDispatcher = { engineThread() },
) {
    /** The wizard's inputs as the view model holds them now. */
    private val inputs: WizardInputs get() = viewModel.inputs.value

    private val lowTextureChip = WarnChip(binding.lowTextureWarnRow.root, host::confirmOpenFaq)
        .apply { setFaq(activity.getString(R.string.url_faq_speckle)) }
    private val speckleChip = WarnChip(binding.speckleWarnRow.root, host::confirmOpenFaq)
    private val speckleSpanChip = WarnChip(settings.speckleSpanWarnRow.root, host::confirmOpenFaq)
    private val measuring = InlineBusy(activity, settings.chipSpeckle)
    private val band = SubsetBand.attach(settings.sliderSubsetSize)

    /** The slider's glide to a new recommendation, while it runs. */
    private var seeding: ValueAnimator? = null

    /**
     * Measures the reference over the ROI, unless that measurement is already
     * held, then seeds the slider with it. Nothing to measure without a reference.
     */
    fun request() {
        val asked = inputs
        val bytes = asked.refBytes
        val roi = samplingRoi
        if (bytes == null || roi == null) return
        val key = "${asked.refName}|${bytes.size}|${roi.toShortString()}"
        if (key == asked.subsetRecommendationKey) {
            apply()
            return
        }
        viewModel.edit { it.copy(subsetRecommendationKey = key, subsetRecommendation = null) }

        // The full range, not the slider's: a measured slider is narrowed to
        // the last measurement, and the next must be free to land outside it.
        val tuning = SubsetRecommender.Tuning()

        val started = measuring.start { SubsetBand.showMeasuring(settings.chipSpeckle) }
        activity.lifecycleScope.launch(measureOn()) {
            val result = runCatching {
                SubsetRecommender.recommend(
                    refBytes = bytes,
                    imgW = asked.refSize.width,
                    imgH = asked.refSize.height,
                    roi = roi,
                    tuning = tuning,
                )
            }.onFailure { Timber.w(it, "Subset recommendation failed") }.getOrNull()

            // A newer reference/ROI landed while we were measuring: read the inputs as they are now.
            withContext(Dispatchers.Main) {
                measuring.stop(started)
                if (inputs.subsetRecommendationKey != key) return@withContext
                viewModel.edit { it.copy(subsetRecommendation = result) }
                apply()
            }
        }
    }

    /**
     * The subset size an untouched form shows: the speckle-span start inside
     * the SSSIG range for the loaded reference image, or the historical 41 px
     * before one exists.
     */
    fun defaultSubsetSize(): Int {
        val rec = inputs.subsetRecommendation ?: return DicParams.DEFAULT_SUBSET
        return snapToSlider(settings.sliderSubsetSize, rec.startingSubset)
    }

    /**
     * Sets the slider's ends: the measurement's [sliderRange],
     * or the full range without one. A size outside them — the user's own
     * included — is moved to the nearer end at once: the slider will not draw
     * a value outside its ends.
     */
    private fun fitRange() {
        val slider = settings.sliderSubsetSize
        val range = inputs.subsetRecommendation?.sliderRange ?: FULL_RANGE
        if (slider.valueFrom.toInt() == range.first && slider.valueTo.toInt() == range.last) return
        val held = slider.value.toInt()
        slider.valueFrom = range.first.toFloat()
        slider.valueTo = range.last.toFloat()
        slider.value = snapToSlider(slider, held).toFloat()
    }

    /** Lands a glide still running, so whoever reads the slider next reads the recommendation. */
    fun settle() {
        seeding?.takeIf { it.isStarted }?.end()
    }

    /** Fits the slider to the recommendation, and seeds it until the user overrides it. */
    fun apply() {
        settle()
        fitRange()
        val rec = inputs.subsetRecommendation ?: run {
            lowTextureChip.hide()
            speckleChip.hide()
            speckleSpanChip.hide()
            showReadout()
            return
        }
        // The one thing the measurement knows that the slider cannot show: even
        // the largest allowed subset misses the accuracy target on this pattern.
        lowTextureChip.showOrHide(activity.getString(R.string.subset_low_texture).takeIf { rec.lowTexture })
        if (!inputs.subsetUserModified) {
            val snapped = snapToSlider(settings.sliderSubsetSize, rec.startingSubset)
            if (settings.sliderSubsetSize.value.toInt() != snapped) {
                host.commitParamFields()
                // The band and chip show now; the rest waits for the slider to get there.
                showReadout()
                seeding = settings.sliderSubsetSize.glideTo(snapped, { inputs.subsetUserModified }) {
                    seeding = null
                    afterSeeding()
                }
                return
            }
        }
        afterSeeding()
    }

    private fun afterSeeding() {
        // A new recommendation re-seeds the sweep's suggested inputs (unless the
        // user has already set their own).
        host.onSweepInputsChanged()
        // After the slider has been seeded, so the span check judges the size
        // that will actually be run.
        showSpeckleFeedback()
    }

    /**
     * Reports the measured speckle size against the iDICs *Good Practices
     * Guide* band, so the user learns something about their specimen rather
     * than only about the slider.
     *
     * Three surfaces, each placed where its fix is. The readout chip beside the
     * subset size shows whenever a measurement exists, good news included: it
     * is the number the recommendation rests on. The size chip sits on step 1
     * with the images, because a pattern outside the band is fixed by another
     * photograph. The span chip sits under the slider, because that slider is
     * its fix and a warning the user cannot watch clear is one they will not trust.
     *
     * The size and span chips never show together: a pattern too fine or too
     * coarse to resolve makes the span question moot, so the size verdict wins.
     */
    fun showSpeckleFeedback() {
        val diameter = showReadout()
        if (diameter == null) {
            // No measurable pattern in any sample patch. The low-texture chip
            // already covers the case where that is the user's problem; saying
            // nothing here is better than reporting a number we do not have.
            speckleChip.hide()
            speckleSpanChip.hide()
            return
        }

        val sizeMessage = sizeMessage(diameter)
        // Read off the slider, not the recommendation: the user may have moved
        // it since. Suppressed while the size chip is up (see the KDoc).
        val spanMessage = if (sizeMessage != null) null else spanMessage()

        val faqUrl = activity.getString(R.string.url_faq_speckle)
        speckleChip.showOrHide(sizeMessage, faqUrl)
        speckleSpanChip.showOrHide(spanMessage, faqUrl)
    }

    /**
     * The band behind the slider, and the speckle chip beside the size, amber
     * outside the good-practice band. Returns the measured diameter, or null
     * without one.
     */
    private fun showReadout(): Double? {
        val rec = inputs.subsetRecommendation
        val slider = settings.sliderSubsetSize
        band.range = rec?.let { SubsetBand.rangeFor(it, slider.valueFrom.toInt()..slider.valueTo.toInt()) }
        val diameter = rec?.speckleDiameterPx
        SubsetBand.showChip(settings.chipSpeckle, diameter)
        return diameter
    }

    private fun sizeMessage(diameter: Double): String? = when (DicGoodPractice.verdictFor(diameter)) {
        DicGoodPractice.Verdict.UNDER_RESOLVED -> activity.getString(
            R.string.speckle_under_resolved_fmt,
            diameter,
            DicGoodPractice.MIN_SPECKLE_PX.toInt(),
        )
        DicGoodPractice.Verdict.OVER_RESOLVED -> activity.getString(
            R.string.speckle_over_resolved_fmt,
            diameter,
            DicGoodPractice.MAX_SPECKLE_PX.toInt(),
        )
        DicGoodPractice.Verdict.USABLE -> null
    }

    private fun spanMessage(): String? {
        val inUse = settings.sliderSubsetSize.value.toInt()
        val wanted = inputs.subsetRecommendation?.subsetSpanningSpeckles
        return if (wanted != null && wanted > inUse) {
            activity.getString(R.string.speckle_subset_span_fmt, inUse, wanted)
        } else {
            null
        }
    }

    /** Region the recommendation samples: the ROI when set, else the frame. */
    private val samplingRoi: Rect?
        get() = inputs.let { it.roi.orFullFrame(it.hasCustomRoi, it.refSize)?.toRect() }

    internal companion object {
        /** The slider's ends before a measurement, as the layout sets them. */
        private val FULL_RANGE = SubsetRecommender.MIN_SUBSET..SubsetRecommender.MAX_SUBSET

        /**
         * The engine's thread, for a controller the wizard builds itself. A
         * Robolectric test of the whole wizard, which has no native library,
         * swaps in a thread of its own and puts this back after.
         */
        @VisibleForTesting
        var engineThread: () -> CoroutineDispatcher = { SemperNativeLib.nativeDispatcher }
    }
}
