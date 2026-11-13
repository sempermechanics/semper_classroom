package com.sempermechanics.semper.ui.home

import com.sempermechanics.semper.R
import com.sempermechanics.semper.data.cloud.CloudSync
import com.sempermechanics.semper.fixtures.sessionRecord
import kotlinx.coroutines.CompletableDeferred
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.shadows.ShadowToast

/**
 * A phone erase Home asks for runs in [HomeViewModel]: a rotation part-way
 * through a multi-row delete no longer stops it with some rows left and no
 * toast, and what it came to is said once, on the screen that is there.
 */
@RunWith(RobolectricTestRunner::class)
class HomeEraseRotationTest : HomeRotationFixture() {

    private val rows = listOf(sessionRecord(id = "s1", name = "Beam test"), sessionRecord(id = "s2", name = "Coupon"))

    /** The first row's erase waits on [gate]; the rest answer at once with [outcomes]. */
    private fun HomeViewModel.holdFirstErase(
        gate: CompletableDeferred<Unit>,
        erased: MutableList<String>,
        outcomes: Map<String, CloudSync.EraseOutcome> = emptyMap(),
    ) {
        eraseEverywhere = { id ->
            if (erased.isEmpty()) gate.await()
            erased += id
            outcomes[id] ?: CloudSync.EraseOutcome.ERASED_EVERYWHERE
        }
    }

    @Test
    fun `a multi-row erase that outlives a rotation erases every row and says so on the new screen`() {
        val controller = home()
        val vm = viewModelOf(controller.get())
        val erased = mutableListOf<String>()
        val gate = CompletableDeferred<Unit>()
        vm.holdFirstErase(gate, erased)
        vm.eraseLocally(rows, deviceOnly = false)
        idle()

        val rebuilt = controller.recreate().get()
        idle()
        assertSame("the erase's view model outlives the screen", vm, viewModelOf(rebuilt))
        assertEquals("nothing erased while the first row waits", emptyList<String>(), erased)

        ShadowToast.reset()
        gate.complete(Unit)
        idle()

        assertEquals(listOf("s1", "s2"), erased)
        assertEquals(
            rebuilt.resources.getQuantityString(R.plurals.delete_multi_done, 2, 2),
            ShadowToast.getTextOfLatestToast(),
        )
    }

    @Test
    fun `a row whose backup could not be reached is counted as kept after a rotation`() {
        val controller = home()
        val vm = viewModelOf(controller.get())
        val erased = mutableListOf<String>()
        val gate = CompletableDeferred<Unit>()
        vm.holdFirstErase(gate, erased, mapOf("s2" to CloudSync.EraseOutcome.LOCAL_ONLY_CLOUD_UNREACHABLE))
        vm.eraseLocally(rows, deviceOnly = false)
        idle()

        val rebuilt = controller.recreate().get()
        idle()
        ShadowToast.reset()
        gate.complete(Unit)
        idle()

        assertEquals(
            rebuilt.resources.getQuantityString(R.plurals.delete_multi_partial, 1, 1, 1),
            ShadowToast.getTextOfLatestToast(),
        )
    }

    @Test
    fun `a phone-only erase drops every phone copy across a rotation`() {
        val controller = home()
        val vm = viewModelOf(controller.get())
        val dropped = mutableListOf<String>()
        val gate = CompletableDeferred<Unit>()
        vm.eraseLocalOnly = { id ->
            if (dropped.isEmpty()) gate.await()
            dropped += id
        }
        vm.eraseLocally(rows, deviceOnly = true)
        idle()

        controller.recreate()
        idle()
        gate.complete(Unit)
        idle()

        assertEquals(listOf("s1", "s2"), dropped)
    }

    @Test
    fun `an outcome is said once, not again after the next rotation`() {
        val controller = home()
        val vm = viewModelOf(controller.get())
        vm.eraseEverywhere = { CloudSync.EraseOutcome.ERASED_EVERYWHERE }
        ShadowToast.reset()
        vm.eraseLocally(rows.take(1), deviceOnly = false)
        idle()
        assertEquals(1, ShadowToast.shownToastCount())
        assertEquals(
            controller.get().getString(R.string.delete_done_fmt, "Beam test"),
            ShadowToast.getTextOfLatestToast(),
        )

        ShadowToast.reset()
        controller.recreate()
        idle()

        assertEquals(0, ShadowToast.shownToastCount())
    }
}
