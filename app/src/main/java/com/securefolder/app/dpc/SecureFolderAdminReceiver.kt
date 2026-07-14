package com.securefolderplusplus.app.dpc

import android.app.admin.DeviceAdminReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.UserHandle
import com.securefolderplusplus.app.security.BankingAppInstaller
import timber.log.Timber

/**
 * SecureFolderAdminReceiver — the Device Policy Controller (DPC) for Secure Folder++.
 *
 * This class is the Profile Owner of the managed work profile. It receives
 * lifecycle events from the Android system and delegates policy enforcement
 * to PolicyEnforcer.
 *
 * ═══════════════════════════════════════════════════════════════════
 *  HOW THIS BECOMES PROFILE OWNER
 * ═══════════════════════════════════════════════════════════════════
 * The app uses ACTION_PROVISION_MANAGED_PROFILE to trigger the system
 * provisioning flow. The system creates the work profile and grants
 * this receiver Profile Owner status. After that, all DPM calls with
 * this component's admin token are executed at profile-owner privilege.
 *
 * This is the same mechanism Samsung Secure Folder uses internally.
 * ═══════════════════════════════════════════════════════════════════
 *
 * ─────────────────────────────────────────────────────────────────
 *  SECURITY NOTES
 * ─────────────────────────────────────────────────────────────────
 * • The receiver is protected by android.permission.BIND_DEVICE_ADMIN.
 *   Only the system can send it intents — third-party apps cannot spoof
 *   admin lifecycle events.
 * • onDisabled() is a critical event: if device admin is revoked, many
 *   policies silently stop working. We log it and can optionally wipe
 *   the work profile.
 * • All heavy work is delegated off the main thread via PolicyEnforcer.
 */
class SecureFolderAdminReceiver : DeviceAdminReceiver() {

    /**
     * Called in the PERSONAL profile when managed profile provisioning completes.
     *
     * At this point:
     *   • The work profile exists.
     *   • This DPC is the Profile Owner of that work profile.
     *   • The work profile is empty (banking app not yet installed).
     *
     * We apply all DPM policies immediately, then trigger banking app installation.
     */
    override fun onProfileProvisioningComplete(context: Context, intent: Intent) {
        super.onProfileProvisioningComplete(context, intent)
        Timber.i("Profile provisioning complete. Applying Secure Folder++ policies.")

        val enforcer = PolicyEnforcer(context)
        if (enforcer.isProfileOwner()) {
            enforcer.applyAllInitialPolicies()
            Timber.i("Initial policies applied successfully.")
        } else {
            // This can happen if the provisioning flow was interrupted or
            // the app was set up without going through managed provisioning.
            Timber.e("Not a profile owner after provisioning — policies NOT applied.")
        }
    }

    /**
     * Called inside the WORK PROFILE after it is fully provisioned and started.
     *
     * This is the right place to do any work that needs to run in the
     * work profile user context (e.g. installing the banking app).
     */
    override fun onEnabled(context: Context, intent: Intent) {
        super.onEnabled(context, intent)
        // Timber.e survives the -assumenosideeffects strip that removes .i/.d/.v
        // in this build's ProGuard config — temporary checkpoints so the next
        // provisioning attempt is actually observable in logcat instead of blind.
        Timber.e("SFPP-DIAG: onEnabled() fired.")

        // If this is a fresh setup, apply policies.
        // (onProfileProvisioningComplete fires first in the primary profile,
        // so onEnabled here catches the work-profile context specifically.)
        val enforcer = PolicyEnforcer(context)
        val isOwner = enforcer.isProfileOwner()
        Timber.e("SFPP-DIAG: onEnabled() isProfileOwner=$isOwner")
        if (isOwner) {
            // Re-assert policies in the work profile context (belt and suspenders)
            enforcer.applyAllInitialPolicies()
            Timber.e("SFPP-DIAG: policies re-asserted, starting install kickoff.")

            // Copying a large bundled APK can take well longer than a
            // BroadcastReceiver's ~10s execution budget, and onReceive() runs
            // on the main thread — doing this inline risks the system killing
            // the receiver mid-copy with no exception logged at all. goAsync()
            // + a background thread avoids both problems.
            //
            // We only COPY the APK to Downloads here, not install it — every
            // attempt at a silent PackageInstaller install hit Android
            // confirmation requirements we couldn't reliably satisfy from a
            // background context. Installing is left to the user via "Pick
            // Banking App APK to Install", a normal foreground action Android
            // handles correctly.
            //
            // NOTE: on at least one tested device (Android Go / JioPhoneNext),
            // onEnabled() never fires at all in-profile during provisioning —
            // confirmed via the SFPP-DIAG checkpoints above never appearing in
            // logcat. BootReceiver's first-boot handling is the reliable
            // fallback trigger for the same copy (see BootReceiver.kt), and
            // copyBundledApkToDownloadsIfNeeded() guards against both firing
            // at once.
            val pendingResult = goAsync()
            Thread {
                try {
                    BankingAppInstaller(context).copyBundledApkToDownloadsIfNeeded()
                } catch (e: Throwable) {
                    Timber.e(e, "Uncaught exception copying bundled banking app.")
                } finally {
                    pendingResult.finish()
                }
            }.start()
        }
    }

    /**
     * Called when Device Admin is disabled (e.g. user manually removes admin via Settings).
     *
     * CRITICAL: If admin is removed, DISALLOW_CREATE_WINDOWS, accessibility
     * restrictions, screen capture disable, and all other DPM-enforced policies
     * are immediately lifted. The banking app is no longer isolated.
     *
     * Policy: We wipe the work profile immediately to prevent data from being
     * accessible in an unprotected state. The user must re-provision to use
     * Secure Folder++ again.
     */
    override fun onDisabled(context: Context, intent: Intent) {
        super.onDisabled(context, intent)
        Timber.w("CRITICAL: Device admin disabled. Wiping work profile to protect data.")

        // Wipe the managed profile data.
        // This only removes the work profile — personal data is untouched.
        // Note: after wipeData, this receiver itself is gone, so this is
        // a one-way operation.
        try {
            val enforcer = PolicyEnforcer(context)
            enforcer.wipeProfileData(reason = "Admin disabled by user")
        } catch (e: Exception) {
            Timber.e(e, "Failed to wipe profile data on admin disable.")
        }
    }

    /**
     * Called when a password attempt for the work profile challenge fails.
     * We track failures and can initiate a wipe after too many attempts.
     */
    override fun onPasswordFailed(context: Context, intent: Intent, user: UserHandle) {
        super.onPasswordFailed(context, intent, user)
        Timber.w("Password attempt failed for user: $user")

        val enforcer = PolicyEnforcer(context)
        val failedAttempts = enforcer.getFailedPasswordAttempts()
        Timber.w("Total failed attempts: $failedAttempts")

        if (failedAttempts >= com.securefolderplusplus.app.SecurityConstants.MAX_FAILED_UNLOCK_ATTEMPTS) {
            Timber.e("Max failed attempts ($failedAttempts) reached. Wiping profile.")
            enforcer.wipeProfileData(reason = "Too many failed unlock attempts")
        }
    }

    /**
     * Called when a correct password is entered after one or more failures.
     */
    override fun onPasswordSucceeded(context: Context, intent: Intent, user: UserHandle) {
        super.onPasswordSucceeded(context, intent, user)
        Timber.d("Password succeeded for user: $user")
    }
}
