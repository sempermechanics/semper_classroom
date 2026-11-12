package com.sempermechanics.semper.ui.settings

import androidx.core.content.ContextCompat
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.sempermechanics.semper.BuildConfig
import com.sempermechanics.semper.R
import com.sempermechanics.semper.data.account.AuthRepository
import com.sempermechanics.semper.data.account.GuestMode
import com.sempermechanics.semper.databinding.ViewSettingsScrollContentBinding
import com.sempermechanics.semper.ui.common.auth.AuthRoute
import com.sempermechanics.semper.ui.common.auth.ExternalLinks
import com.sempermechanics.semper.ui.common.auth.SignOutRun
import com.sempermechanics.semper.ui.common.auth.confirm

/** The foot of Settings: About (version, privacy, terms) and Sign out (Sign in for a guest). */
internal class SettingsFooterSection(
    private val activity: SettingsActivity,
    private val views: ViewSettingsScrollContentBinding,
) {

    fun wire() {
        views.btnAbout.setOnClickListener {
            MaterialAlertDialogBuilder(activity)
                .setTitle(R.string.about_title)
                .setMessage(
                    activity.getString(
                        R.string.about_message_fmt,
                        BuildConfig.VERSION_NAME,
                        BuildConfig.VERSION_CODE,
                    ),
                )
                .setPositiveButton(android.R.string.ok, null)
                .setNeutralButton(R.string.legal_privacy) { _, _ ->
                    ExternalLinks.open(activity, activity.getString(R.string.legal_privacy_url))
                }
                .setNegativeButton(R.string.legal_terms) { _, _ ->
                    ExternalLinks.open(activity, activity.getString(R.string.legal_terms_url))
                }
                .show()
        }
        val app = activity.applicationContext
        if (GuestMode.active(activity)) {
            // The guest leaves for sign-in: nothing on the phone is lost (its
            // analyses come back as guest), so no "Sign out?" to confirm.
            views.btnSignOut.setText(R.string.auth_sign_in)
            views.btnSignOut.setTextColor(ContextCompat.getColor(activity, R.color.sky_primary))
            views.btnSignOut.strokeColor = ContextCompat.getColorStateList(activity, R.color.sky_primary)
            views.btnSignOut.setOnClickListener {
                SignOutRun.start(activity.javaClass) { AuthRepository(app).signOut() }
            }
        } else {
            views.btnSignOut.setOnClickListener {
                // Outside this screen, so a rotation cannot half sign out; the
                // observer below routes once it is done.
                SignOutRun.confirm(activity) { AuthRepository(app).signOut() }
            }
        }
        SignOutRun.observe(activity) { AuthRoute.toSignIn(activity) }
    }
}
