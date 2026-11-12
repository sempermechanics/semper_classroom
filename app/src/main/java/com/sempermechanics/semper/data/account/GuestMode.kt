package com.sempermechanics.semper.data.account

import android.content.Context
import androidx.core.content.edit
import com.sempermechanics.semper.data.net.AccountCache
import com.sempermechanics.semper.data.net.AppConfigDto
import com.sempermechanics.semper.data.net.AppRemoteConfig
import com.sempermechanics.semper.data.prefs.AppSettings
import com.sempermechanics.semper.data.prefs.PrefFiles.Onboarding
import com.sempermechanics.semper.data.prefs.get
import com.sempermechanics.semper.data.prefs.privatePrefs
import com.sempermechanics.semper.data.prefs.put
import com.sempermechanics.semper.data.session.SessionStore

/**
 * "Continue as guest" (ADR-036):
 * the app with Demo's limits and no account.
 *
 * A guest has no Firebase user, so no ID token and no backend: while [active],
 * [com.sempermechanics.semper.data.net.SemperApi.enabled] reports the cloud as
 * off, exactly as a build with no `SEMPER_API_BASE_URL` does. Analysis, the
 * viewer and the exports that Demo allows all run on the phone; backup,
 * restore, Share and anything licensed stay off.
 *
 * The guest is an owner like an account ([GUEST_UID] in [AccountCache]), so
 * its analyses stay its own: an account that signs in later does not see them,
 * and they come back when the phone continues as guest again
 * ([com.sempermechanics.semper.data.session.SessionOwner]).
 *
 * Leaving is a sign-out ([AccountSwitch.clearLocalState]); a sign-in leaves
 * first ([AuthRepository.firebaseThen]), so the cloud is on for it.
 */
object GuestMode {

    /** The guest's owner id. A Firebase uid is 28 characters, so none is this. */
    const val GUEST_UID = "guest"

    /** True while this phone runs as the guest. */
    fun active(context: Context): Boolean = AccountCache.cachedUid(context) == GUEST_UID

    /**
     * Become the guest: approved, a plain user, Demo's limits ([demoConfig]).
     * Called from the sign-in screen, and again on every launch (Splash), so a
     * build with new Demo limits applies them.
     */
    fun start(context: Context) {
        val returning = !active(context)
        if (returning) AccountSwitch.clearLocalState(context)
        AccountCache.saveIdentity(context, GUEST_UID, null)
        if (returning) restoreAnswers(context)
        AccountCache.setStatus(context, AccessStatus.APPROVED)
        AccountCache.setRole(context, "user")
        AppRemoteConfig.apply(context, demoConfig())
        // The guest's own rows (SessionStore lists the current owner's only).
        AccountCache.refreshSessionLimit(context, SessionStore.list(context).size)
    }

    /** Stop being the guest; the next screen is sign-in. No-op when not [active]. */
    fun leave(context: Context) {
        if (active(context)) AccountSwitch.clearLocalState(context)
    }

    /**
     * Keeps the guest's Terms acceptance and improvement answer outside
     * [AccountCache], which a sign-out clears ([AccountSwitch.clearLocalState]
     * calls this first). No-op when not [active].
     */
    internal fun keepAnswers(context: Context) {
        if (!active(context)) return
        privatePrefs(context, Onboarding.NAME).edit {
            put(Onboarding.GUEST_TERMS_ACCEPTED, AccountCache.termsAcceptedVersion(context))
            put(Onboarding.GUEST_IMPROVEMENT_CONSENT, AccountCache.improvementConsent(context)?.toString())
        }
    }

    /**
     * Puts back what [keepAnswers] kept. The Terms gate still compares the
     * version with the one in force, so a newer Terms is asked for.
     */
    private fun restoreAnswers(context: Context) {
        val kept = privatePrefs(context, Onboarding.NAME)
        kept[Onboarding.GUEST_TERMS_ACCEPTED]?.let { AccountCache.setTermsAccepted(context, it, synced = false) }
        kept[Onboarding.GUEST_IMPROVEMENT_CONSENT]?.toBooleanStrictOrNull()?.let {
            AccountCache.setImprovementConsent(context, it)
        }
    }

    /**
     * What `/v1/config` answers an account on Demo (`resolve_user_config`,
     * `backend/app/repo/user_config.py`), with the fleet defaults: 25 analyses,
     * no backup, no Share.
     */
    internal fun demoConfig(): AppConfigDto = AppConfigDto(
        maxSessions = LicenseEntitlements.DEMO_MAX_ANALYSES,
        maxFilesPerSession = DEMO_MAX_FILES_PER_SESSION,
        maxFrames = AppSettings.MAX_MAX_FRAMES,
        mode = LicenseEntitlements.MODE_DEMO,
        cloudBackupEnabled = false,
        shareEnabled = false,
    )

    /** `MAX_FILES_PER_SESSION`'s default in `backend/app/config.py`. */
    private const val DEMO_MAX_FILES_PER_SESSION = 600
}
