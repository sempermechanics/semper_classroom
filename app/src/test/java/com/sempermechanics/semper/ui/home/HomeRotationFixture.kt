package com.sempermechanics.semper.ui.home

import android.annotation.SuppressLint
import android.content.Context
import android.os.Looper
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ApplicationProvider
import androidx.work.impl.WorkManagerImpl
import androidx.work.testing.WorkManagerTestInitHelper
import com.sempermechanics.semper.data.net.AccountCache
import com.sempermechanics.semper.data.prefs.AppSettings
import org.junit.After
import org.junit.Before
import org.robolectric.Robolectric
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController

/**
 * Home for a job that outlives a rotation (the erase, rename and restore
 * tests): a Home with no first-run prompt over it, its [HomeViewModel], and
 * every screen built closed after the test.
 */
abstract class HomeRotationFixture {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val screens = mutableListOf<ActivityController<HomeActivity>>()

    @Before
    fun setUpHome() {
        // Home observes transfer work in onCreate; the app starts WorkManager itself.
        WorkManagerTestInitHelper.initializeTestWorkManager(context)
        // No first-run prompt over the screen.
        AccountCache.setBetaNoticeAcked(context)
        AppSettings.setDiagnosticsEnabled(context, false)
    }

    @SuppressLint("RestrictedApi")
    @After
    fun tearDownHome() {
        screens.forEach { it.close() }
        WorkManagerTestInitHelper.closeWorkDatabase()
        WorkManagerImpl.setDelegate(null)
    }

    protected fun idle() = shadowOf(Looper.getMainLooper()).idle()

    protected fun home(): ActivityController<HomeActivity> =
        Robolectric.buildActivity(HomeActivity::class.java).setup().also {
            screens += it
            idle()
        }

    protected fun viewModelOf(activity: HomeActivity): HomeViewModel =
        ViewModelProvider(activity)[HomeViewModel::class.java]
}
