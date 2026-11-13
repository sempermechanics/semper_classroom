package com.sempermechanics.semper.ui.analysis.run

import android.content.Context
import androidx.annotation.WorkerThread
import com.sempermechanics.semper.ProgressCallback
import com.sempermechanics.semper.SemperNativeLib
import com.sempermechanics.semper.data.cloud.CloudSync
import com.sempermechanics.semper.data.prefs.AppSettings
import com.sempermechanics.semper.data.session.RunInput
import com.sempermechanics.semper.data.session.RunMetrics
import com.sempermechanics.semper.data.session.RunOutcome
import com.sempermechanics.semper.data.session.RunReference
import com.sempermechanics.semper.data.session.SessionPaths
import com.sempermechanics.semper.data.session.SessionRecord
import com.sempermechanics.semper.data.session.SessionStore
import com.sempermechanics.semper.data.session.originalNameOr
import com.sempermechanics.semper.diagnostics.EngineDebug
import com.sempermechanics.semper.diagnostics.SemperAnalytics
import com.sempermechanics.semper.field.DicResult
import com.sempermechanics.semper.field.RunStop
import com.sempermechanics.semper.field.ValueRange
import com.sempermechanics.semper.report.EngineStats
import com.sempermechanics.semper.report.FieldRangesStore
import com.sempermechanics.semper.report.VisualizationEngine
import com.sempermechanics.semper.report.correlatedPoints
import com.sempermechanics.semper.report.newMetrics
import com.sempermechanics.semper.ui.analysis.frames.FrameImportHelper
import com.sempermechanics.semper.ui.analysis.recommend.RunEstimate
import com.sempermechanics.semper.ui.analysis.rerun.keepBatchRerun
import com.sempermechanics.semper.ui.analysis.wizard.AnalysisViewModel
import com.sempermechanics.semper.ui.analysis.wizard.BatchAnalysisOutcome
import com.sempermechanics.semper.ui.analysis.wizard.BatchProgressUpdate
import com.sempermechanics.semper.ui.analysis.wizard.RunAdmission
import com.sempermechanics.semper.ui.analysis.wizard.RunKind
import com.sempermechanics.semper.ui.analysis.wizard.admitRun
import com.sempermechanics.semper.ui.analysis.wizard.openRunSession
import com.sempermechanics.semper.ui.analysis.wizard.repointDeformedPathsOnMain
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import timber.log.Timber
import java.io.File

/**
 * The batch DIC run: one loop over the deformed frames that calls JNI
 * [SemperNativeLib.computeFullFieldDirect] (incremental: [SemperNativeLib.computeIncrementalFrame])
 * and writes each frame's `.dat` through [DicFieldIo]. The JNI call stays in this one loop and the
 * loop stays whole: its size, jumps, per-frame catch and the cancel's early return are inherent.
 */
@Suppress(
    "CyclomaticComplexMethod",
    "LongMethod",
    "LoopWithTooManyJumpStatements",
    "MagicNumber",
    "TooGenericExceptionCaught",
    "NestedBlockDepth",
    "ReturnCount",
)
@WorkerThread
internal fun AnalysisViewModel.runBatchAnalysisBody(
    appContext: Context,
    run: BatchRun,
    onProgress: (BatchProgressUpdate) -> Unit,
): BatchAnalysisOutcome {
    val spec = run.spec
    // The images as they were at launch: the wizard's fields are Main's (TD-171).
    val reference = run.inputs.reference
    val frames = run.inputs.frames
    // Admitted here, the run saves its session even if the cap fills meanwhile.
    val admission = admitRun(appContext, frames.paths.size)
    if (admission is RunAdmission.Blocked) {
        SemperAnalytics.event(
            appContext,
            SemperAnalytics.ANALYSIS_FAILED,
            mapOf("mode" to "batch", "reason" to "session_limit"),
        )
        return admission.outcome
    }

    // Results live in app-private persistent storage (NOT cacheDir, which
    // the OS may evict): one directory per Home-list session, never a sweep's.
    // The loop writes them to the session's staging folder; only a run that
    // is not cancelled moves them into the session (RunSession.commit).
    val session = openRunSession(appContext, run.inputs.working, RunKind.BATCH)
    val localSessionId = session.id
    val batchDir = session.dir
    val resultsDir = session.staging

    EngineDebug.attach(spec.debugDir)

    val plannedFrames = frames.paths.size
    // A video's frame times, one per planned frame, as the session row keeps them.
    val frameTimesMs = frames.timesMs?.takeIf { it.size == plannedFrames }
    val videoName = frames.videoName
    val refBytes = reference.bytes ?: error("Reference missing")

    var firstFrameValidPoints = 0
    var firstFrameCorrelatedPoints = -1
    var firstFrameAvgIters = 0f
    // The first frame's engine stats, which its saved record keeps.
    var engineStatsArray: FloatArray? = null
    var stop: RunStop = RunStop.Finished

    onProgress(BatchProgressUpdate(0f, "Caching reference in engine…", plannedFrames = plannedFrames))
    // An incremental run opens its session just before the loop, below.
    if (!spec.incremental) {
        SemperNativeLib.initializeReference(
            refBytes,
            spec.mask,
            reference.size.width,
            reference.size.height,
        )
    }

    val roi = spec.roi
    val dic = spec.params
    val gridW = roi.w / dic.step
    val gridH = roi.h / dic.step
    val maxPoints = gridW * gridH
    val outputBuffer = DicFieldIo.allocateDirect(maxPoints)

    // The cancel flag was cleared at launch (launchBatchAnalysis), not here: a
    // Cancel confirmed while the reference cached must still stop the run.
    var lastConvergence = -1f
    val convergenceGate = ConvergenceGate()
    var failedFrameIndex = -1
    var solvedFrames = 0
    // Each frame's convergence as the gate saw it, NaN until then; the overlay
    // draws it and warns one low frame before the gate stops the run.
    val frameConvergence = FloatArray(plannedFrames) { Float.NaN }

    // The raw deformed originals sit alongside the reference so exports (and
    // reopened sessions) can bundle them. They are MOVED in from the import
    // cache, not copied, so one set of images exists on disk instead of two —
    // which makes this directory the next run's own input when this one saves
    // nothing. Stale frames are therefore pruned after the loop, never wiped
    // before it. (A frame in another session is copied instead: keepDeformedFrame.)
    val rawDeformedDir = File(batchDir, SessionPaths.RAW_DEFORMED_SUBDIR).apply { mkdirs() }

    // The filenames actually written into raw_deformed/, index-aligned with
    // the frames. These (not the cache-copy paths) are what the session index
    // and the cloud upload look the images up by. Blank = persist failed.
    val persistedRawNames = MutableList(plannedFrames) { "" }

    // Where each frame's image ended up, so the view model can follow the move.
    val resolvedDefPaths = frames.paths.toMutableList()

    // Each written frame's sigma-clamped (p02, p98) per field, taken while outputBuffer holds it, so
    // SummaryAnimation.globalRanges (viewer open, every backup) never re-decodes the batch. A frame
    // with no points gets no .dat and no entry.
    val summaryFieldIndices = intArrayOf(
        DicResult.IDX_U,
        DicResult.IDX_V,
        DicResult.IDX_EXX,
        DicResult.IDX_EYY,
        DicResult.IDX_EXY,
    )
    val perFrameSummaryRanges = mutableListOf<Map<Int, ValueRange?>>()

    // An incremental run opens a session that moves the reference forward as it
    // goes, and holds two images until the finally below releases it, however
    // the run ends. Its init failure is reported at the first frame, where a
    // fixed run's engine would report the same code.
    val incrementalInfo = IntArray(SemperNativeLib.INC_INFO_LEN)
    val incrementalInit = if (spec.incremental) {
        SemperNativeLib.initializeIncremental(
            refBytes, spec.mask,
            reference.size.width, reference.size.height,
            roi.x, roi.y, roi.w, roi.h,
            dic.step, dic.subset, dic.strainWindow, spec.use6x6,
            spec.refUpdateInterval,
        )
    } else {
        0
    }

    // A throw before the commit (a full disk under DicFieldIo.write, say) is undone as a cancel
    // is (undoFailedBatch); one after it, below (undoCommittedBatch).
    try {
        for ((frameIndex, defPath) in frames.paths.withIndex()) {
            // A job cancelled with its view model is a cancel too: it keeps nothing
            // either, and the cancel below rethrows it once the run is undone.
            if (cancelRequested || !run.job.isActive) {
                stop = RunStop.Cancelled
                break
            }
            onProgress(
                BatchProgressUpdate(
                    percent = (frameIndex.toFloat() / plannedFrames) * 100,
                    status = if (plannedFrames > 1) {
                        "Processing frame ${frameIndex + 1} of $plannedFrames"
                    } else {
                        "Correlating & solving…"
                    },
                    frameIndex = frameIndex,
                    plannedFrames = plannedFrames,
                    perFrameConvergence = frameConvergence.copyOf(),
                ),
            )

            val source = File(defPath)
            // One read feeds both the JNI buffer and the copy fallback below
            // (avoids Files.copy + a second heap read).
            val defBytes = try {
                source.readBytes()
            } catch (e: Exception) {
                Timber.e(e, "Could not read deformed frame %d", frameIndex)
                continue
            }
            if (source.parentFile?.absolutePath == rawDeformedDir.absolutePath) {
                // Moved here by a run in this wizard that saved nothing: the image
                // already lives where it belongs, so there is nothing to move, and
                // the prune below must not treat it as stale.
                persistedRawNames[frameIndex] = source.name
            } else {
                try {
                    // Persist the untouched original under the user's own filename
                    // so exports keep default names.
                    val rawName = frames.originalNames.originalNameOr(frameIndex, source.name).baseName()
                    val target = keepDeformedFrame(source, defBytes, rawDeformedDir, rawName, frameIndex)
                    // Record the name we ACTUALLY wrote: the session index (and the
                    // cloud upload) must be able to find these files again.
                    persistedRawNames[frameIndex] = target.name
                    resolvedDefPaths[frameIndex] = target.absolutePath
                } catch (e: Exception) {
                    Timber.w(e, "Could not persist raw deformed frame %d", frameIndex)
                }
            }
            val callback = object : ProgressCallback {
                override fun onProgressUpdate(percentage: Int) {
                    val frameProgress = (frameIndex.toFloat() / plannedFrames) * 100
                    val overallProgress = frameProgress + (percentage.toFloat() / plannedFrames)
                    onProgress(
                        BatchProgressUpdate(
                            percent = overallProgress,
                            status = if (plannedFrames > 1) {
                                "Processing frame ${frameIndex + 1} of $plannedFrames"
                            } else {
                                "Correlating & solving…"
                            },
                            frameIndex = frameIndex,
                            plannedFrames = plannedFrames,
                            framePercent = percentage.toFloat(),
                        ),
                    )
                }
            }

            outputBuffer.clear()
            val metricsCatcher = EngineStats.newMetrics(EngineStats.ENGINE_SLOT_COUNT)

            // Incremental: the total from the first image, on its grid, in the same layout.
            val validPointsCount = when {
                incrementalInit < 0 -> incrementalInit
                spec.incremental -> SemperNativeLib.computeIncrementalFrame(
                    defBytes,
                    outputBuffer,
                    callback,
                    metricsCatcher,
                    incrementalInfo,
                )
                else -> SemperNativeLib.computeFullFieldDirect(
                    refBytes, defBytes, spec.mask,
                    roi.x, roi.y, roi.w, roi.h,
                    dic.step, dic.subset, dic.strainWindow, spec.use6x6,
                    outputBuffer, callback, metricsCatcher,
                )
            }

            if (validPointsCount < 0) {
                stop = RunStop.fromWireCode(validPointsCount)
                failedFrameIndex = frameIndex
                break
            }

            // Defensive bound: today the engine cannot report more than maxPoints. Its grid is
            // (rect_w / step) x (rect_h / step) (engine/src/pipeline/full_field_solver.cpp, gridW /
            // gridH) and it stops packing at the buffer's capacity (full_field_solver_stats.cpp).
            // Should either change, get() below would throw BufferUnderflowException: an init failure.
            if (DicFieldIo.wouldOverrun(validPointsCount, outputBuffer)) {
                Timber.e(
                    "Engine returned %d points but the buffer holds %d (frame %d) — failing frame",
                    validPointsCount,
                    DicFieldIo.capacityPoints(outputBuffer),
                    frameIndex,
                )
                stop = RunStop.InitFailed
                failedFrameIndex = frameIndex
                break
            }

            if (frameIndex == 0) {
                firstFrameValidPoints = validPointsCount
                firstFrameCorrelatedPoints = EngineStats.correlatedPoints(metricsCatcher)
                // Stored stats stay SLOT_COUNT long; the slots past it are read here only.
                engineStatsArray = metricsCatcher.copyOf(EngineStats.SLOT_COUNT)
                firstFrameAvgIters = metricsCatcher[EngineStats.SLOT_AVG_ITERS]
            }

            if (validPointsCount == 0) continue

            DicFieldIo.write(outputBuffer, validPointsCount, SessionPaths.frameDat(resultsDir, frameIndex))
            run {
                // The bytes just written, read as the floats VisualizationEngine.valueRanges takes.
                val floatData = FloatArray(validPointsCount * DicResult.STRIDE)
                outputBuffer.position(0)
                outputBuffer.asFloatBuffer().get(floatData, 0, floatData.size)
                perFrameSummaryRanges.add(VisualizationEngine.valueRanges(floatData, summaryFieldIndices))
            }

            solvedFrames++
            lastConvergence = metricsCatcher[EngineStats.SLOT_CONVERGENCE]
            if (spec.incremental) {
                lastConvergence = ConvergenceGate.incremental(
                    lastConvergence,
                    incrementalInfo[SemperNativeLib.INC_INFO_TRACKED_POINTS],
                    incrementalInfo[SemperNativeLib.INC_INFO_FRAME0_POINTS],
                )
            }
            frameConvergence[frameIndex] = lastConvergence
            if (convergenceGate.record(lastConvergence)) {
                stop = RunStop.LowConvergence
                failedFrameIndex = frameIndex
                break
            }
            onProgress(
                BatchProgressUpdate(
                    percent = ((frameIndex + 1).toFloat() / plannedFrames) * 100,
                    status = "Processing frame ${frameIndex + 1} of $plannedFrames",
                    frameIndex = frameIndex,
                    plannedFrames = plannedFrames,
                    framePercent = 100f,
                    perFrameConvergence = frameConvergence.copyOf(),
                ),
            )
        }

        // A cancel keeps nothing: no frames, no row, no upload; its results never
        // left the staging. One confirmed while the last frame solved counts too.
        if (cancelRequested) stop = RunStop.Cancelled
        // Any other end moves this run's results into its session, whether or not
        // it then saves a record (a partial stop keeps its frames). The session is
        // never a saved analysis (openRunSession): at most the folder of a run in
        // this wizard that saved nothing.
        if (stop != RunStop.Cancelled) {
            // One entry per frame written, as batchFiles lists them, so globalRanges' frame-count
            // check accepts the cache (a partial run's shorter list matches its fewer .dat files).
            // Best-effort: a failed write costs only the speedup, as globalRanges falls back to
            // decoding. Staged with the frames, so the commit's record manifest covers it (ADR-028).
            FieldRangesStore.writeBestEffort(resultsDir, summaryFieldIndices, perFrameSummaryRanges)
            session.commitRecord(appContext, spec.recordSettings())
        }
    } catch (e: Exception) {
        undoFailedBatch(appContext, session, frames.paths, resolvedDefPaths, e)
    } finally {
        if (spec.incremental) SemperNativeLib.releaseIncremental()
    }
    if (stop == RunStop.Cancelled) {
        discardCancelledBatch(appContext, session, frames.paths, resolvedDefPaths)
        run.job.ensureActive()
        return cancelledOutcome((System.currentTimeMillis() - run.startedAtMs).toInt())
    }

    // The frames are in the session, but the wizard names their old paths until it follows them
    // below; a throw before then is undone with its committed results (undoCommittedBatch).
    try {
        // Every frame solved: the overlay says so while the run saves.
        if (stop == RunStop.Finished) {
            onProgress(
                BatchProgressUpdate(
                    percent = 100f,
                    status = "Saving…",
                    frameIndex = plannedFrames,
                    plannedFrames = plannedFrames,
                    framePercent = 100f,
                    perFrameConvergence = frameConvergence.copyOf(),
                ),
            )
        }

        // Images an earlier run left behind that this one no longer has; pruned after the loop,
        // so it never deletes the run's own inputs.
        val keptRawNames = persistedRawNames.filterTo(HashSet()) { it.isNotBlank() }
        rawDeformedDir.listFiles()?.forEach { if (it.name !in keptRawNames) it.delete() }

        // A run that will save keeps what Home's Re-run starts from, the frames it
        // stopped before included, which the wizard then follows.
        if (firstFrameValidPoints > 0) keepBatchRerun(batchDir, run.inputs, spec, resolvedDefPaths)
    } catch (e: Exception) {
        undoCommittedBatch(appContext, session, frames.paths, resolvedDefPaths, e)
    }

    // On Main: the wizard's fields are not this thread's to write.
    repointDeformedPathsOnMain(resolvedDefPaths)

    // With every frame moved out, the committed import is dead weight that
    // would otherwise survive until the next import. A run that saved nothing
    // and stopped partway leaves it for CacheJanitor, since its un-processed
    // frames are still only there.
    File(run.cacheDir, FrameImportHelper.COMMITTED_DIR_NAME)
        .takeIf { it.isDirectory && it.list()?.isEmpty() == true }
        ?.delete()

    val executionTimeMs = (System.currentTimeMillis() - run.startedAtMs).toInt()
    // The parameters page estimates a run's time from what this phone has solved.
    if (solvedFrames > 0) {
        val points = RunEstimate.gridPoints(spec.roi.w, spec.roi.h, spec.params.step)
        val previous = AppSettings.runPointsPerSecond(appContext)
        val rate = RunEstimate.nextRate(previous, points, solvedFrames, executionTimeMs.toLong())
        AppSettings.setRunPointsPerSecond(appContext, rate)
    }
    // The row the run saved, as the index holds it: what its viewer opens on.
    var savedRecord: SessionRecord? = null
    var indexUnavailable = false
    // The names actually on disk in raw_deformed/ — reopening a session,
    // exporting and cloud upload resolve images by these.
    val defNames = persistedRawNames.mapIndexed { i, persisted ->
        persisted.ifBlank { frames.originalNames.originalNameOr(i, resolvedDefPaths[i]).baseName() }
    }

    // A cancelled run never gets here (it returned above). A first frame that
    // kept no points saves no record.
    if (firstFrameValidPoints > 0) {
        // Persist a viewable copy of the reference next to the frames —
        // the Home list and reopened sessions depend on it surviving.
        val refPngPath = sessions.writeReferenceCopy(batchDir, refBytes, reference.size.width, reference.size.height)

        val cloudEnabled = CloudSync.uploadsEnabled(appContext)
        val input = RunInput(
            localSessionId = localSessionId,
            dir = batchDir,
            reference = RunReference(refPngPath, reference.name, reference.size),
            settings = spec.recordSettings(),
            videoName = videoName,
            frameTimesMs = frameTimesMs,
        )
        val outcome = RunOutcome(
            frameCount = solvedFrames,
            defNames = defNames,
            metrics = RunMetrics(
                pointsConverged = firstFrameValidPoints,
                avgIterations = firstFrameAvgIters,
                executionTimeMs = executionTimeMs,
                engineStats = engineStatsArray?.toList().orEmpty(),
            ),
            stopCode = stop.wireCode,
            plannedFrameCount = plannedFrames,
        )
        val record = sessions.buildSessionRecord(appContext, input, outcome, cloudEnabled, spec.mechanical)
        val saved = afterSave(stop, saveRunRecord(appContext, record, cloudEnabled, admission.admitted))
        stop = saved.stop
        if (saved.recordSaved) savedRecord = SessionStore.get(appContext, localSessionId) ?: record
        indexUnavailable = saved.indexUnavailable
    }

    val outcome = BatchAnalysisOutcome(
        engineErrorCode = stop.wireCode,
        firstFrameValidPoints = firstFrameValidPoints,
        totalFrames = solvedFrames,
        executionTimeMs = executionTimeMs,
        batchDirPath = batchDir.absolutePath,
        failedFrameIndex = failedFrameIndex,
        failedFrameName = failedFrameIndex
            .takeIf { it >= 0 }
            ?.let { resolvedDefPaths.getOrNull(it)?.substringAfterLast('/') },
        firstFrameCorrelatedPoints = firstFrameCorrelatedPoints,
        record = savedRecord,
        indexUnavailable = indexUnavailable,
        // Once saved, that analysis is finished: the next run in this wizard
        // is a new one (applyRunOutcome).
        session = session.working,
        spec = spec,
    )
    batchEndEvent(appContext, outcome, stop)
    return outcome
}

/** How a batch run ends once its record's save came back as [result]. */
internal data class AfterSave(val stop: RunStop, val recordSaved: Boolean, val indexUnavailable: Boolean)

/**
 * What the save [result] of a run that stopped with [stop] makes of it. A full
 * quota stops the run as [RunStop.SessionLimit], whatever stopped it; only a
 * save no start check admitted can meet one, since an admitted run saves past
 * the cap ([RunAdmission]). An index that could not be read or written is not
 * the quota, and leaves [stop] as it was.
 */
internal fun afterSave(stop: RunStop, result: SessionStore.UpsertOutcome): AfterSave = when (result) {
    SessionStore.UpsertOutcome.SAVED -> AfterSave(stop, recordSaved = true, indexUnavailable = false)
    SessionStore.UpsertOutcome.QUOTA_FULL ->
        AfterSave(RunStop.SessionLimit, recordSaved = false, indexUnavailable = false)
    SessionStore.UpsertOutcome.INDEX_UNAVAILABLE -> AfterSave(stop, recordSaved = false, indexUnavailable = true)
}

/**
 * The analytics event a batch run ends with: completed, or why it failed.
 * None for a cancel, unless the cancelled run's record could not be saved.
 */
internal fun batchEndEvent(appContext: Context, outcome: BatchAnalysisOutcome, stop: RunStop) {
    val indexUnavailable = outcome.indexUnavailable
    val completed = outcome.firstFrameValidPoints > 0 &&
        !indexUnavailable &&
        stop != RunStop.Cancelled &&
        stop != RunStop.SessionLimit
    val duration = "duration" to SemperAnalytics.durationBucket(outcome.executionTimeMs.toLong())
    if (completed) {
        SemperAnalytics.event(
            appContext,
            SemperAnalytics.ANALYSIS_COMPLETED,
            mapOf(
                "mode" to "batch",
                "frames" to SemperAnalytics.frameCountBucket(outcome.totalFrames),
                duration,
            ),
        )
    } else if (stop != RunStop.Cancelled || indexUnavailable) {
        SemperAnalytics.event(
            appContext,
            SemperAnalytics.ANALYSIS_FAILED,
            mapOf(
                "mode" to "batch",
                "reason" to when {
                    indexUnavailable -> "index_unavailable"
                    stop == RunStop.SessionLimit -> "session_limit"
                    stop == RunStop.Finished -> "no_points"
                    else -> "engine"
                },
                duration,
            ),
        )
    }
}
