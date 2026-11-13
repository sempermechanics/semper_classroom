package com.sempermechanics.semper.ui.home

import com.sempermechanics.semper.R
import kotlinx.coroutines.CompletableDeferred
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.shadows.ShadowToast

/**
 * A rename Home asks for runs in [HomeViewModel] (ADR-016 rule 1, readiness
 * AE1-111): it used to run on the screen's lifecycleScope, which the
 * activity-scope check flagged, and a rotation dropped its "saved as" toast.
 * Now it outlives the screen and is said once, on the screen that is there.
 */
@RunWith(RobolectricTestRunner::class)
class HomeRenameRotationTest : HomeRotationFixture() {

    @Test
    fun `a rename that outlives a rotation is applied and its new name said on the new screen`() {
        val controller = home()
        val vm = viewModelOf(controller.get())
        val gate = CompletableDeferred<Unit>()
        val applied = mutableListOf<Pair<String, String>>()
        vm.applyRename = { id, name ->
            gate.await()
            applied += id to name
            "$name (2)"
        }
        vm.rename("s1", "Beam test")
        idle()

        val rebuilt = controller.recreate().get()
        idle()
        assertSame("the rename's view model outlives the screen", vm, viewModelOf(rebuilt))
        ShadowToast.reset()
        gate.complete(Unit)
        idle()

        assertEquals(listOf("s1" to "Beam test"), applied)
        assertEquals(
            rebuilt.getString(R.string.rename_saved_as_fmt, "Beam test (2)"),
            ShadowToast.getTextOfLatestToast(),
        )
    }

    @Test
    fun `a rename kept as asked says nothing, and is not said again after a rotation`() {
        val controller = home()
        val vm = viewModelOf(controller.get())
        vm.applyRename = { _, name -> name }
        ShadowToast.reset()
        vm.rename("s1", "Coupon")
        idle()
        assertEquals(0, ShadowToast.shownToastCount())

        vm.applyRename = { _, name -> "$name (2)" }
        vm.rename("s1", "Coupon")
        idle()
        assertEquals(1, ShadowToast.shownToastCount())

        ShadowToast.reset()
        controller.recreate()
        idle()
        assertEquals(0, ShadowToast.shownToastCount())
    }
}
