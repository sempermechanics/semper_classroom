package com.sempermechanics.semper.ui.analysis.run

import android.content.Context
import androidx.work.impl.WorkManagerImpl
import androidx.work.testing.WorkManagerTestInitHelper
import com.sempermechanics.semper.data.net.AccountCache
import com.sempermechanics.semper.data.net.AppConfigDto
import com.sempermechanics.semper.data.net.AppRemoteConfig
import com.sempermechanics.semper.data.session.SessionPaths
import com.sempermechanics.semper.field.Roi
import com.sempermechanics.semper.ui.analysis.frames.DeformedFrame
import com.sempermechanics.semper.ui.analysis.frames.FrameImportHelper
import com.sempermechanics.semper.ui.analysis.sweep.SweepStudy
import com.sempermechanics.semper.ui.analysis.sweep.SweepStudyRunner
import com.sempermechanics.semper.ui.analysis.wizard.AnalysisViewModel
import com.sempermechanics.semper.ui.analysis.wizard.RunSession
import com.sempermechanics.semper.ui.analysis.wizard.SweepSession
import java.io.File

/**
 * The wizard a run test drives and what its runner hands back
 * ([CancelDiscardsRunTest], [RecordManifestRunTest]): a [REF_SIDE]-pixel
 * reference, frames as an import commits them, and a two-combination sweep.
 */
internal object RunFixtures {

    const val REF_SIDE = 4

    /** The import's committed folder, emptied. */
    fun importDir(ctx: Context): File = File(ctx.cacheDir, FrameImportHelper.COMMITTED_DIR_NAME).apply {
        deleteRecursively()
        mkdirs()
    }

    /** A test WorkManager, an account allowed [maxSessions] analyses with none used, and a reference in [vm]. */
    fun setUpWizard(ctx: Context, vm: AnalysisViewModel, maxSessions: Int) {
        WorkManagerTestInitHelper.initializeTestWorkManager(ctx)
        AppRemoteConfig.apply(ctx, AppConfigDto(maxSessions = maxSessions, maxFilesPerSession = 600, maxFrames = 150))
        AccountCache.setQuota(ctx, used = 0)
        vm.edit {
            it.copy(
                refSize = it.refSize.copy(width = REF_SIDE, height = REF_SIDE),
                refName = "ref.png",
                refBytes = ByteArray(REF_SIDE * REF_SIDE * 4),
            )
        }
    }

    /** Undoes [setUpWizard], and empties [importDir]. */
    fun tearDownWizard(importDir: File) {
        importDir.deleteRecursively()
        WorkManagerTestInitHelper.closeWorkDatabase()
        WorkManagerImpl.setDelegate(null)
    }

    /** [count] frames as an import commits them to [importDir], in the wizard [vm]. */
    fun importFrames(vm: AnalysisViewModel, importDir: File, count: Int): List<File> {
        val files = (0 until count).map { i ->
            File(importDir, "000${i}_def_$i.png").apply { writeBytes(byteArrayOf(i.toByte(), 1, 2, 3)) }
        }
        val picked = files.map { DeformedFrame(it.absolutePath, it.name.substringAfter('_')) }
        vm.edit { it.copy(deformedFrames = picked) }
        return files
    }

    /**
     * A two-combination sweep against frame 0 that the runner left in
     * [session] with [code], each combination's `.dat` reading "[tag] N".
     */
    fun sweepRun(vm: AnalysisViewModel, session: RunSession, code: Int, tag: String): SweepSession {
        val plan = listOf(SweepStudy.Point(21, 5, 3), SweepStudy.Point(31, 5, 3))
        val sweepSpec = RunSpec.sweep(
            RunSpec.Sweep(plan, labels = listOf("a", "b"), lineCutHorizontal = true, frameIndex = 0),
            roi = Roi(0, 0, REF_SIDE, REF_SIDE),
            mask = null,
            use6x6 = false,
            debugDir = null,
        )
        val dats = plan.indices.map { SessionPaths.frameDat(session.staging, it).apply { writeText("$tag $it") } }
        val result = SweepStudyRunner.SweepResult(
            runs = plan.mapIndexed { i, p -> SweepStudyRunner.RunOutcome(p, dats[i], 400) },
            firstMetrics = null,
            engineErrorCode = code,
        )
        return SweepSession(session.id, session.dir, vm.snapshotRunInputs(), result, sweepSpec, 3_000)
    }
}
