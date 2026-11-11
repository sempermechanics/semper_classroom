package com.sempermechanics.semper.ui.viewer

import android.view.View
import android.view.inputmethod.EditorInfo
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import com.sempermechanics.semper.ui.common.SerialJob
import com.sempermechanics.semper.ui.common.commitOnDone
import com.sempermechanics.semper.ui.common.hideKeyboard
import com.sempermechanics.semper.ui.viewer.inspect.ViewerInspectController
import com.sempermechanics.semper.ui.viewer.summary.ViewerSummaryController
import kotlinx.coroutines.delay
import java.io.File

/** What [FrameJumpController] needs from the viewer; its lifecycle scopes the debounce. */
internal interface FrameJumpHost :
    ViewerViews,
    LifecycleOwner {
    /** Holds the frame on screen ([ResultViewerViewModel.currentFrameIndex]); this moves it. */
    val viewModel: ResultViewerViewModel

    /** The opened frame set's `.dat` files; empty until it is read. */
    val batchFiles: List<File>

    /** True when the frames are a sweep's combinations: no summary slot. */
    val isSweep: Boolean
}

/**
 * Moving between the viewer's frames: Prev / Next (debounced, so a burst
 * decodes only the frame it settles on), the summary slot before frame 1, and
 * the typed frame number. A move loads through [frames], brings back
 * [chrome], and swaps the frame for [summary] (dropping [inspect]'s probe,
 * titled by [captions]) at the slot.
 *
 * Constructed before onCreate; reads [ViewerViews.binding] lazily.
 */
internal class FrameJumpController(
    private val host: FrameJumpHost,
    private val chrome: ViewerChromeController,
    private val frames: ViewerFrameLoader,
    private val summary: ViewerSummaryController,
    private val inspect: ViewerInspectController,
    private val captions: ViewerCaptions,
) {

    private val binding get() = host.binding

    private val scrubDebounceJob = SerialJob()

    /**
     * True while the summary animation is up instead of a frame. It sits before
     * frame 1: Prev from frame 1 reaches it, Next leaves it.
     */
    var showingSummary = false

    /** Advance or retreat one frame (or leave/enter the summary). Used by buttons and fling. */
    fun stepFrame(delta: Int) {
        chrome.bumpChrome()
        if (delta == 0) return
        if (delta > 0) {
            if (showingSummary) {
                leaveSummary()
            } else if (host.viewModel.currentFrameIndex < host.batchFiles.size - 1) {
                host.viewModel.currentFrameIndex++
                updateNavButtons()
                requestFrameLoad(debounced = true)
            }
            return
        }
        when {
            showingSummary -> Unit
            host.viewModel.currentFrameIndex == 0 -> if (!host.isSweep) enterSummary()
            else -> {
                host.viewModel.currentFrameIndex--
                updateNavButtons()
                requestFrameLoad(debounced = true)
            }
        }
    }

    /** Debounce rapid Next/Prev so only the settled frame is decoded. */
    private fun requestFrameLoad(debounced: Boolean) {
        scrubDebounceJob.cancel()
        if (!debounced) {
            frames.loadFrameData(host.viewModel.currentFrameIndex)
            return
        }
        scrubDebounceJob.launch(host.lifecycleScope) {
            delay(SCRUB_DEBOUNCE_MS)
            frames.loadFrameData(host.viewModel.currentFrameIndex)
        }
    }

    fun updateNavButtons() {
        val prev = binding.btnPrevFrame
        val next = binding.btnNextFrame
        val batchFiles = host.batchFiles
        // Sweep: no summary slot, so Prev is inert on the first combination.
        prev.isEnabled =
            !showingSummary &&
            batchFiles.isNotEmpty() &&
            (host.viewModel.currentFrameIndex > 0 || !host.isSweep)
        next.isEnabled = showingSummary || host.viewModel.currentFrameIndex < batchFiles.size - 1

        prev.alpha = if (prev.isEnabled) 1.0f else DISABLED_ALPHA
        next.alpha = if (next.isEnabled) 1.0f else DISABLED_ALPHA
        // The number tracks the buttons, not the decode: a debounced scrub would
        // otherwise leave it a frame behind for as long as the load takes.
        syncFrameNumber()
    }

    // ── Summary slot ─────────────────────────────────────────────────────

    fun wireSummaryGestures() {
        val gif = binding.imgSummary
        gif.onScrubListener = { stepFrame(it) }
        gif.onCenterDoubleTapShowChrome = { chrome.showChromeIfHidden() }
        gif.onChromeSwipeListener = { show -> if (show) chrome.bumpChrome() }
        gif.onTapListener = { _, _ -> chrome.bumpChrome() }
    }

    fun enterSummary() {
        if (host.isSweep) return
        showingSummary = true
        inspect.dismissProbe()
        summary.show()
        binding.tvFrameCounter.text = summary.counterText()
        binding.tvFrameCounter.visibility = View.VISIBLE
        // Hiding the field takes its focus, and losing focus commits what it
        // holds: its "1" would leave the summary at once. Empty it first so that
        // commit restores nothing; Prev asked for the summary, not a typed number.
        syncFrameNumber()
        binding.etFrameNumber.clearFocus()
        binding.layoutFrameJump.visibility = View.GONE
        chrome.syncFieldBars()
        binding.tvFinding.text = captions.title()
        updateNavButtons()
        chrome.bumpChrome()
    }

    private fun leaveSummary() {
        showingSummary = false
        summary.hide()
        chrome.syncFieldBars()
        // On a frame the jump field ("3 / 5") is the count; the pill is the summary's.
        binding.tvFrameCounter.visibility = View.GONE
        binding.layoutFrameJump.visibility = View.VISIBLE
        updateNavButtons()
        // Re-apply the frame's own labels and heatmap after the summary's.
        requestFrameLoad(debounced = false)
        chrome.bumpChrome()
    }

    // ── Typed frame jump ─────────────────────────────────────────────────

    /**
     * The frame field applies what is typed in it on Go and when it loses
     * focus. Anything unparseable or outside the batch restores the current
     * number rather than jumping somewhere the user did not ask for.
     */
    fun wireFrameJump() {
        val field = binding.etFrameNumber
        val commit = {
            chrome.bumpChrome()
            val typed = field.text?.toString()?.trim()?.toIntOrNull()
            if (typed == null || !jumpTo(typed - 1)) syncFrameNumber()
            field.clearFocus()
            field.hideKeyboard()
        }
        field.commitOnDone(EditorInfo.IME_ACTION_GO) { commit() }
        field.setOnFocusChangeListener { _, hasFocus ->
            if (!hasFocus) commit()
        }
    }

    /**
     * Goes straight to the frame at [position], leaving the summary if it is
     * up: a typed number, or a point tapped on the Results curve. False, and
     * nothing moves, when there is no such frame or it is the one on screen.
     */
    fun jumpTo(position: Int): Boolean {
        val moves = position in host.batchFiles.indices &&
            (position != host.viewModel.currentFrameIndex || showingSummary)
        if (!moves) return false
        host.viewModel.currentFrameIndex = position
        if (showingSummary) {
            // Leaving the summary loads the frame that is now current.
            leaveSummary()
        } else {
            updateNavButtons()
            // A settled destination, unlike a Next/Prev burst.
            requestFrameLoad(debounced = false)
        }
        return true
    }

    fun syncFrameNumber() {
        val shown = if (showingSummary) "" else (host.viewModel.currentFrameIndex + 1).toString()
        val field = binding.etFrameNumber
        if (field.text?.toString() != shown) field.setText(shown)
    }

    /** Drops a debounced load still waiting. */
    fun cancel() {
        scrubDebounceJob.cancel()
    }

    private companion object {
        const val SCRUB_DEBOUNCE_MS = 70L
        const val DISABLED_ALPHA = 0.5f
    }
}
