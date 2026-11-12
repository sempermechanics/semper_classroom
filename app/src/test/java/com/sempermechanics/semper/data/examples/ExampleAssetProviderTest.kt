package com.sempermechanics.semper.data.examples

import android.app.Application
import android.net.Uri
import android.provider.OpenableColumns
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.FileNotFoundException

/** The read-only `content://` view of the bundled sets that Try it hands the wizard (ADR-031). */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class ExampleAssetProviderTest {

    private val app = ApplicationProvider.getApplicationContext<Application>()
    private lateinit var provider: ExampleAssetProvider

    @Before
    fun setUp() {
        provider = Robolectric.setupContentProvider(
            ExampleAssetProvider::class.java,
            ExampleAssetProvider.authority(app),
        )
    }

    private fun uri(id: String, file: String) = ExampleAssetProvider.uriFor(app, id, file)

    @Test
    fun `a listed file opens with its bytes, name and size`() {
        val uri = uri("dice_oht_series", "oht_cfrp_00.tiff")
        val expected = app.assets.open("examples/dice_oht_series/oht_cfrp_00.tiff").use { it.readBytes() }
        val read = provider.openAssetFile(uri, "r").createInputStream().use { it.readBytes() }
        assertEquals(expected.toList(), read.toList())
        provider.query(uri, null, null, null, null)!!.use { cursor ->
            cursor.moveToFirst()
            val name = cursor.getString(cursor.getColumnIndexOrThrow(OpenableColumns.DISPLAY_NAME))
            assertEquals("oht_cfrp_00.tiff", name)
            assertEquals(expected.size.toLong(), cursor.getLong(cursor.getColumnIndexOrThrow(OpenableColumns.SIZE)))
        }
        assertEquals("image/tiff", provider.getType(uri))
    }

    @Test
    fun `the load log opens as text`() {
        val uri = uri("steel_tensile", "steel_loads.csv")
        val text = provider.openAssetFile(uri, "r").createInputStream().use { it.readBytes().decodeToString() }
        assertEquals("Step,Load (kN)", text.lineSequence().first())
        assertEquals("text/csv", provider.getType(uri))
    }

    @Test
    fun `nothing is writable`() {
        val ref = uri("dice_oht_series", "oht_cfrp_00.tiff")
        assertThrows(FileNotFoundException::class.java) { provider.openAssetFile(ref, "w") }
        assertThrows(UnsupportedOperationException::class.java) { provider.delete(ref, null, null) }
        assertThrows(UnsupportedOperationException::class.java) { provider.insert(ref, null) }
        assertThrows(UnsupportedOperationException::class.java) { provider.update(ref, null, null, null) }
    }

    @Test
    fun `files a set does not list, other sets and paths out of the folder are refused`() {
        val unlisted = uri("dice_oht_series", "secret.txt")
        assertThrows(FileNotFoundException::class.java) { provider.openAssetFile(unlisted, "r") }
        assertThrows(FileNotFoundException::class.java) { provider.openAssetFile(uri("no_such_set", "ref.tif"), "r") }
        val root = "content://${ExampleAssetProvider.authority(app)}"
        val escape = Uri.parse("$root/dice_oht_series/..%2F..%2Flicences%2FEigen-MPL-2.0.txt")
        assertThrows(FileNotFoundException::class.java) { provider.openAssetFile(escape, "r") }
        val deep = Uri.parse("content://${ExampleAssetProvider.authority(app)}/licences/a/b")
        assertThrows(FileNotFoundException::class.java) { provider.openAssetFile(deep, "r") }
        assertNull(provider.query(uri("no_such_set", "ref.tif"), null, null, null, null))
    }

    @Test
    fun `MIME types follow the extension`() {
        assertEquals("image/png", ExampleAssetProvider.mimeTypeOf("a.PNG"))
        assertEquals("image/jpeg", ExampleAssetProvider.mimeTypeOf("a.jpg"))
        assertEquals("image/jpeg", ExampleAssetProvider.mimeTypeOf("a.jpeg"))
        assertEquals("image/tiff", ExampleAssetProvider.mimeTypeOf("a.tiff"))
        assertEquals("application/json", ExampleAssetProvider.mimeTypeOf("example.json"))
        assertEquals("text/markdown", ExampleAssetProvider.mimeTypeOf("README.md"))
        assertEquals("text/plain", ExampleAssetProvider.mimeTypeOf("CREDIT.txt"))
        // Not text/plain: MediaStore would save it as LICENSE.DICe.txt, and a second save would duplicate it.
        assertEquals("application/octet-stream", ExampleAssetProvider.mimeTypeOf("LICENSE.DICe"))
    }
}
