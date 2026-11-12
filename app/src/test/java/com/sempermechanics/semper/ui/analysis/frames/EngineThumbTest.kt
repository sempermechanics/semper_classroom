package com.sempermechanics.semper.ui.analysis.frames

import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * The engine-drawn tile for a frame the platform cannot decode. A file that
 * is gone, or a folder, has no tile and never reaches the engine; what the
 * engine draws from a real TIFF is checked on a device.
 */
class EngineThumbTest {

    @get:Rule
    val temp = TemporaryFolder()

    @Test
    fun `a missing file has no tile`() {
        assertNull(EngineThumb.decode(File(temp.root, "gone.tif").path, 160))
    }

    @Test
    fun `a folder has no tile`() {
        assertNull(EngineThumb.decode(temp.newFolder("frames").path, 160))
    }
}
