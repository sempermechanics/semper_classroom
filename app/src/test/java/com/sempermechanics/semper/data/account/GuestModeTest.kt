package com.sempermechanics.semper.data.account

import android.annotation.SuppressLint
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.work.impl.WorkManagerImpl
import androidx.work.testing.WorkManagerTestInitHelper
import com.sempermechanics.semper.data.cloud.CloudSync
import com.sempermechanics.semper.data.cloud.SessionMetadataSync
import com.sempermechanics.semper.data.net.AccountCache
import com.sempermechanics.semper.data.net.AppRemoteConfig
import com.sempermechanics.semper.data.net.SemperApi
import com.sempermechanics.semper.data.session.SessionStore
import com.sempermechanics.semper.fixtures.CleanAppState
import com.sempermechanics.semper.fixtures.sessionRecord
import com.sempermechanics.semper.ui.auth.AuthMode
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Continue as guest (ADR-036): Demo's limits with no account, the cloud off,
 * and the guest's analyses kept apart from any account's.
 */
@RunWith(RobolectricTestRunner::class)
class GuestModeTest {

    @get:Rule
    val clean = CleanAppState()

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val queued = mutableListOf<String>()

    @Before
    fun setUp() {
        WorkManagerTestInitHelper.initializeTestWorkManager(context)
        CloudSync.queueUpload = { _, id -> queued += id }
        CloudSync.queueMetadata = { _, id -> queued += "meta:$id" }
    }

    @SuppressLint("RestrictedApi")
    @After
    fun tearDown() {
        CloudSync.queueUpload = CloudSync::enqueueUpload
        CloudSync.queueMetadata = SessionMetadataSync::enqueue
        WorkManagerTestInitHelper.closeWorkDatabase()
        WorkManagerImpl.setDelegate(null)
    }

    private fun ids() = SessionStore.list(context).map { it.id }.sorted()

    private fun save(id: String) {
        assertEquals(SessionStore.UpsertOutcome.SAVED, SessionStore.save(context, sessionRecord(id = id)))
    }

    @Test
    fun `a guest runs on Demo's limits, approved, with no backup or Share`() {
        GuestMode.start(context)

        assertTrue(GuestMode.active(context))
        assertEquals(AccessStatus.APPROVED, AccountCache.cachedStatus(context))
        assertFalse(AccountCache.isAdmin(context))
        assertEquals(LicenseEntitlements.MODE_DEMO, LicenseEntitlements.mode(context))
        assertEquals(LicenseEntitlements.DEMO_MAX_ANALYSES, LicenseEntitlements.analysisCap(context))
        assertEquals(LicenseEntitlements.DEMO_MAX_ANALYSES, LicenseEntitlements.shownAnalysisCap(context))
        assertTrue(AppRemoteConfig.isKnown(context))
        assertFalse(LicenseEntitlements.cloudBackupEnabled(context))
        assertFalse(LicenseEntitlements.shareEnabled(context))
    }

    @Test
    fun `a guest still accepts the Terms`() {
        GuestMode.start(context)

        assertTrue(LegalTerms.needsAcceptance(context))
    }

    @Test
    fun `a guest who comes back after an account is not asked for the Terms again`() {
        GuestMode.start(context)
        AccountCache.setTermsAccepted(context, LegalTerms.TERMS_VERSION, synced = false)
        AccountCache.setImprovementConsent(context, false)

        // Sign in from Settings (a sign-out), an account uses the phone, then signs out.
        AccountSwitch.clearLocalState(context)
        AccountSwitch.signedIn(context, UID_A, "a@example.com")
        assertTrue(LegalTerms.needsAcceptance(context))
        AccountSwitch.clearLocalState(context)

        GuestMode.start(context)
        assertFalse(LegalTerms.needsAcceptance(context))
        assertEquals(false, AccountCache.improvementConsent(context))
    }

    @Test
    fun `a guest who accepted older Terms is asked for the Terms in force`() {
        GuestMode.start(context)
        AccountCache.setTermsAccepted(context, "2020-01-01", synced = false)
        AccountSwitch.clearLocalState(context)

        GuestMode.start(context)
        assertTrue(LegalTerms.needsAcceptance(context))
    }

    @Test
    fun `the cloud is off while the phone is the guest`() {
        val api = SemperApi(context, "https://api.test", null)
        assertTrue(api.enabled)

        GuestMode.start(context)
        assertFalse(api.enabled)

        GuestMode.leave(context)
        assertTrue(api.enabled)
    }

    @Test
    fun `leaving forgets the guest, and does nothing for an account`() {
        AccountSwitch.signedIn(context, UID_A, "a@example.com")
        GuestMode.leave(context)
        assertEquals(UID_A, AccountCache.cachedUid(context))

        AccountSwitch.clearLocalState(context)
        GuestMode.start(context)
        GuestMode.leave(context)
        assertFalse(GuestMode.active(context))
        assertEquals(null, AccountCache.cachedUid(context))
    }

    @Test
    fun `starting as guest over a cached account drops that account's cache`() {
        AccountSwitch.signedIn(context, UID_A, "a@example.com")
        AccountCache.setTermsAccepted(context, LegalTerms.TERMS_VERSION, synced = true)

        GuestMode.start(context)

        assertEquals(null, AccountCache.cachedEmail(context))
        assertTrue(LegalTerms.needsAcceptance(context))
    }

    @Test
    fun `the guest's analyses are its own, and back when the phone is the guest again`() {
        GuestMode.start(context)
        save("g1")
        assertEquals(listOf("g1"), ids())
        assertEquals(1, AccountCache.localCount(context))

        GuestMode.leave(context)
        AccountSwitch.signedIn(context, UID_A, "a@example.com")
        assertEquals(emptyList<String>(), ids())
        save("a1")

        AccountSwitch.clearLocalState(context)
        GuestMode.start(context)
        assertEquals(listOf("g1"), ids())
        assertEquals(1, AccountCache.localCount(context))
    }

    @Test
    fun `the first account adopts rows from before owners, not the guest`() {
        SessionStore.save(context, sessionRecord(id = "old1"))

        GuestMode.start(context)
        assertEquals(emptyList<String>(), ids())

        GuestMode.leave(context)
        AccountSwitch.signedIn(context, UID_A, "a@example.com")
        assertEquals(listOf("old1"), ids())
    }

    @Test
    fun `the sign-in screen offers guest only when signing in or registering`() {
        assertEquals(
            setOf(AuthMode.SIGN_IN, AuthMode.REGISTER),
            AuthMode.entries.filter { it.offersGuest }.toSet(),
        )
    }

    private companion object {
        const val UID_A = "uid-a"
    }
}
