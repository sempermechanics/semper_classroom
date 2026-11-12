# ADR-036: Continue as guest — Demo's limits with no account

**Status:** Accepted, built (not merged or released)
**Date:** 2026-11-12
**Deciders:** app owner
**Supersedes in part:** [ADR-024](ADR-024-account-anchored-entitlements.md) decision 1 ("Every user signs in")

## Context

ADR-024 made every user sign in, with Demo as the default entitlement. For a
first-semester student trying the app in a lab, or a visitor at a seminar demo,
the account is the slowest step: an address, a verification mail or a Google
account on the phone, and, for a pending account, an approval. Nothing Demo
allows needs the cloud. Analysis, the viewer, the lab report and the exports
Demo opens all run on the phone; Demo's cloud entitlement is only that an
analysis is *recorded* (ADR-024: "a demo account's frames and results are
stored, and what a licence adds is getting them back"), which no Demo user can
read back.

The app already had an offline, accountless mode for development: the emulator
bypass (`DevAuth`) and a build with no `SEMPER_API_BASE_URL`, both of which
switch the cloud off through `SemperApi.enabled`.

## Decision

1. **The sign-in screen offers "Continue as guest"** (sign-in and create-account
   forms only, not re-auth or password reset), as a plain link with no
   explanatory line under it (owner's call, 2026-11-12).
2. **A guest is a local owner, not an account.** `GuestMode` (`data/account/GuestMode.kt`)
   caches the uid `guest` in `AccountCache`, approved, role `user`, and applies
   Demo's `/v1/config` locally (`GuestMode.demoConfig`, mirroring
   `resolve_user_config` for an unlicensed account: 25 analyses stored at once,
   500 frames, 600 files, no backup, no Share). No Firebase user, no device
   registration, no backend call.
3. **The cloud is off while the phone is the guest**: `SemperApi.enabled` is
   false, as under `DevAuth`. Uploads, reconcile, licence refresh, seats and
   phone sync do not run.
4. **The Terms gate still runs.** Continue as guest routes through
   `AccessRouter.intentFor`, so the clickwrap is accepted (on the phone,
   unsynced) before Home. Declining signs the guest out to sign-in. The
   guest's acceptance and improvement answer are kept outside the account
   cache (`semper_onboarding`'s `guest_terms_accepted`,
   `guest_improvement_consent`) when it leaves, and put back when the phone
   continues as guest again, so a returning guest is asked only for a newer
   Terms version.
5. **The guest's analyses are its own** (`SessionOwner`). An account that signs
   in later does not see them; they come back when the phone continues as guest
   again. Rows from before owners are not adopted by the guest; they wait for
   the first account.
6. **Leaving.** Settings' footer reads **Sign in** for a guest (no "Sign out?"
   confirmation: nothing is lost) and returns to sign-in. Any sign-in leaves
   guest mode first (`AuthRepository.firebaseThen`), so the cloud is on for it.
   Settings hides Refresh licence, Export cloud data and Delete account for a guest.
7. **Splash** reopens a guest at Home (through the Terms gate), re-applying
   Demo's limits, ahead of the debug no-backend shortcut.

## Consequences

- A student can run a test with no account. The Demo cap is enforced on the
  phone only: clearing app data resets it. Demo's limits are a taste of the app,
  not a licence boundary, and every licensed feature stays account-only, so
  this is accepted.
- A guest's analyses are never recorded in the cloud, so the backend's demo
  device cap (TD-50) does not count them, and staff see no guest usage.
- A guest never fetches `/v1/config`, so remote kill switches and the minimum
  supported version gate do not reach it (TD-282).
- Moving a guest's analyses into an account at sign-in is not done; it would
  count them against that account's cap and upload them. Owner's call if wanted.
- Play's Data safety form: a guest sends nothing to the backend; diagnostics
  still follow `AppSettings.diagnosticsEnabled`.

## Alternatives

- **Firebase anonymous auth.** Gives a real uid and a token, so the backend
  could count and cap guests, but every guest becomes a backend account that
  needs approval rules, erasure and quota; rejected for now as more surface
  than a trial needs.
- **A shared demo account.** Credentials in the APK; rejected.
