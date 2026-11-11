package com.sempermechanics.semper.ui.analysis.frames

import android.graphics.Bitmap
import androidx.annotation.WorkerThread
import com.sempermechanics.semper.SemperNativeLib
import java.io.File
import java.util.concurrent.Callable

/**
 * A frame tile for a file the platform cannot decode, TIFF above all: the
 * engine's decoder draws it, as it does the reference preview
 * (`ReferencePreviewLoader`). Without it a TIFF frame showed the photos icon
 * beside a reference that showed its picture.
 */
internal object EngineThumb {

    /** Above this a file is not read whole for a tile; it keeps the icon. */
    private const val MAX_BYTES = 96L * 1024 * 1024

    /**
     * The picture at [path] no larger than [maxEdge], or null when the engine
     * cannot read it either. Every JNI call is pinned to the engine's one
     * thread, so the caller's worker thread hands the file over and waits; the
     * read happens there too, so only one file is held in memory at a time.
     */
    @WorkerThread
    fun decode(path: String, maxEdge: Int): Bitmap? = runCatching {
        val file = File(path)
        if (!file.isFile || file.length() > MAX_BYTES) return null
        SemperNativeLib.nativeExecutor
            .submit(Callable { SemperNativeLib.getPreviewFromBytes(file.readBytes(), maxEdge) })
            .get()
    }.getOrNull()
}
