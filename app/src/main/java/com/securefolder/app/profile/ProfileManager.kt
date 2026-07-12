package com.securefolderplusplus.app.profile

import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.UserManager
import com.securefolderplusplus.app.SecurityConstants
import com.securefolderplusplus.app.dpc.PolicyEnforcer
import com.securefolderplusplus.app.dpc.SecureFolderAdminReceiver
import timber.log.Timber

/**
 * ProfileManager — manages the lifecycle of the Secure Folder++ managed work profile.
 *
 * ═══════════════════════════════════════════════════════════════════
 *  RESPONSIBILITIES
 * ═══════════════════════════════════════════════════════════════════
 *  1. Check if a work profile already exists.
 *  2. Determine if this device supports managed profile creation.
 *  3. Build the provisioning Intent and hand it to the caller activity.
 *  4. Install the banking app within the work profile.
 *  5. Enable/disable the work profile on lock/unlock.
 *  6. Destroy (wipe) the work profile when the user requests it.
 *
 * ═══════════════════════════════════════════════════════════════════
 *  HOW WORK PROFILE CREATION WORKS (overview)
 * ═══════════════════════════════════════════════════════════════════
 *  Caller activity does:
 *    val intent = profileManager.buildProvisioningIntent() ?: return
 *    startActivityForResult(intent, REQUEST_PROVISION_PROFILE)
 *
 *  Android shows the system provisioning UI (explaining what a work
 *  profile is). On acceptance, Android:
 *    a) Creates a new managed user (the work profile).
 *    b) Copies this DPC app into the work profile.
 *    c) Grants this DPC Profile Owner status in the work profile.
 *    d) Fires ACTION_PROFILE_PROVISIONING_COMPLETE in the primary profile.
 *    e) Fires onEnabled() in the work profile context.
 *
 *  SecureFolderAdminReceiver.onProfileProvisioningComplete() then calls
 *  PolicyEnforcer.applyAllInitialPolicies().
 *
 * ═══════════════════════════════════════════════════════════════════
 *  LIMITATIONS (non-OEM)
 * ═══════════════════════════════════════════════════════════════════
 *  • A device can only have ONE managed profile at a time.
 *  • If the device already has a work profile (from an employer MDM,
 *    for example), provisioning will fail. We detect and surface this.
 *  • The provisioning UI cannot be suppressed — the user must go through
 *    the system-provided acceptance flow.
 * ═══════════════════════════════════════════════════════════════════
 */
class ProfileManager(private val context: Context) {

    private val dpm: DevicePolicyManager =
        context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager

    private val userManager: UserManager =
        context.getSystemService(Context.USER_SERVICE) as UserManager

    private val prefs =
        context.getSharedPreferences(SecurityConstants.PREF_NAME, Context.MODE_PRIVATE)

    private val adminComponent =
        ComponentName(context, SecureFolderAdminReceiver::class.java)

    // ─────────────────────────────────────────────────────────────────────────
    //  State checks
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Returns true if the Secure Folder++ work profile is active and policies
     * are in effect. This is the "ready to launch" check.
     */
    fun isProfileReady(): Boolean {
        // Do NOT use isProfileOwner() here — always false from personal profile.
        // Use hasExistingManagedProfile() as the source of truth instead.
        val hasProfile = hasExistingManagedProfile()
        if (hasProfile && !prefs.getBoolean(SecurityConstants.PREF_PROFILE_CREATED, false)) {
            // Reinstall wiped SharedPreferences but profile still exists — restore flag
            prefs.edit().putBoolean(SecurityConstants.PREF_PROFILE_CREATED, true).apply()
        }
        return hasProfile
    }

    /**
     * Returns true if the work profile has been provisioned at least once
     * (even if the DPC is not currently the profile owner, e.g. after a
     * factory reset without re-provisioning — used to detect partial state).
     */
    fun wasProfileEverCreated(): Boolean {
        return prefs.getBoolean(SecurityConstants.PREF_PROFILE_CREATED, false)
    }

    /**
     * Returns a human-readable explanation of why profile creation is not possible,
     * or null if creation is possible.
     */
    fun checkProvisioningBlocked(): ProvisioningBlockReason? {
        if (!canSystemProvisionManagedProfile()) {
            return ProvisioningBlockReason.SYSTEM_UNSUPPORTED
        }
        // A managed profile already exists — do not attempt to create another
        if (hasExistingManagedProfile()) {
            return ProvisioningBlockReason.ALREADY_PROVISIONED
        }
        return null
    }

    /**
     * Returns true if Android's ManagedProvisioning component is available
     * and supports managed profile creation on this device.
     *
     * Some devices (Android Go, some entry-level OEMs) disable or omit
     * managed profile support.
     */
    private fun canSystemProvisionManagedProfile(): Boolean {
        return try {
            val intent = Intent(DevicePolicyManager.ACTION_PROVISION_MANAGED_PROFILE)
            val resolveInfo = context.packageManager.resolveActivity(intent, 0)
            resolveInfo != null
        } catch (e: Exception) {
            Timber.w(e, "Cannot determine if managed profile provisioning is available.")
            false
        }
    }

    /**
     * Returns true if the device already has any managed profile
     * (from this DPC or another one).
     */
    private fun hasExistingManagedProfile(): Boolean {
        return try {
            // Primary check: userProfiles includes personal + any work profiles
            val profiles = userManager.userProfiles
            if (profiles.size > 1) return true

            // Fallback: if provisioning is not allowed, a profile likely already exists
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                !dpm.isProvisioningAllowed(
                    DevicePolicyManager.ACTION_PROVISION_MANAGED_PROFILE
                )
            } else {
                false
            }
        } catch (e: Exception) {
            Timber.w(e, "hasExistingManagedProfile check failed — using stored flag")
            prefs.getBoolean(SecurityConstants.PREF_PROFILE_CREATED, false)
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  Provisioning
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Build the Intent that starts Android's managed profile provisioning flow.
     *
     * The caller Activity should use:
     *   startActivityForResult(intent, REQUEST_PROVISION_PROFILE)
     *
     * On RESULT_OK in onActivityResult(), call markProfileCreated().
     * The actual policy application happens in SecureFolderAdminReceiver
     * via the PROFILE_PROVISIONING_COMPLETE broadcast.
     *
     * Returns null if provisioning cannot start (see checkProvisioningBlocked()).
     */
    fun buildProvisioningIntent(): Intent? {
        if (checkProvisioningBlocked() != null) {
            Timber.w("Provisioning blocked: ${checkProvisioningBlocked()}")
            return null
        }

        return Intent(DevicePolicyManager.ACTION_PROVISION_MANAGED_PROFILE).apply {
            // Identify our DPC receiver as the admin for the new profile
            putExtra(
                DevicePolicyManager.EXTRA_PROVISIONING_DEVICE_ADMIN_COMPONENT_NAME,
                adminComponent
            )

            // Skip the "add account" step — we don't need a Google account in the profile
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                putExtra("android.app.extra.SKIP_USER_SETUP", true)
            }
        }
    }

    /**
     * Mark the work profile as successfully created.
     * Called from the UI after the provisioning Intent returns RESULT_OK.
     */
    fun markProfileCreated() {
        prefs.edit()
            .putBoolean(SecurityConstants.PREF_PROFILE_CREATED, true)
            .apply()
        Timber.i("Work profile marked as created.")
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  Banking app management
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Install (enable) the banking app in the managed work profile.
     *
     * If the banking app is available as a system app or is already installed
     * on the device, enableSystemApp() makes it available in the work profile.
     *
     * For apps that must be downloaded fresh into the work profile, use
     * the PackageInstaller API with the work profile user handle.
     *
     * Call this AFTER PolicyEnforcer.applyAllInitialPolicies() and AFTER
     * DISALLOW_INSTALL_APPS is set — the DPC is exempt from that restriction
     * and can still install apps programmatically.
     */
    fun installBankingAppInProfile() {
        val enforcer = PolicyEnforcer(context)
        if (!enforcer.isProfileOwner()) {
            Timber.e("Cannot install banking app — not profile owner.")
            return
        }

        try {
            // enableSystemApp: makes a system app that's hidden in the profile
            // visible and runnable. If the banking app is a Play Store app,
            // this will fail (it's not a system app) — use Play-managed install instead.
            dpm.enableSystemApp(adminComponent, SecurityConstants.BANKING_APP_PACKAGE)
            Timber.i("Banking app enabled in work profile: ${SecurityConstants.BANKING_APP_PACKAGE}")
        } catch (e: IllegalArgumentException) {
            // Not a system app — handle via PackageInstaller (see Step 9)
            Timber.w("Banking app is not a system app. Install via PackageInstaller.")
        } catch (e: Exception) {
            Timber.e(e, "Failed to enable banking app in work profile.")
        }
    }

    /**
     * Verify that the installed banking app's signing certificate matches
     * the expected SHA-256 fingerprint in SecurityConstants.
     *
     * This prevents a malicious lookalike APK from being installed
     * with the same package name but a different certificate.
     *
     * Returns true if the certificate is valid and matches, false otherwise.
     */
    fun isBankingAppCertificateValid(): Boolean {
        return try {
            val signingInfo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                context.packageManager
                    .getPackageInfo(
                        SecurityConstants.BANKING_APP_PACKAGE,
                        PackageManager.GET_SIGNING_CERTIFICATES
                    )
                    .signingInfo
            } else {
                null // Legacy path handled below
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P && signingInfo != null) {
                val signatures = signingInfo.apkContentsSigners
                signatures.any { signature ->
                    val digest = java.security.MessageDigest.getInstance("SHA-256")
                        .digest(signature.toByteArray())
                    val fingerprint = digest.joinToString(":") { "%02X".format(it) }
                    fingerprint == SecurityConstants.BANKING_APP_CERT_SHA256
                }
            } else {
                @Suppress("DEPRECATION")
                val packageInfo = context.packageManager
                    .getPackageInfo(
                        SecurityConstants.BANKING_APP_PACKAGE,
                        PackageManager.GET_SIGNATURES
                    )
                @Suppress("DEPRECATION")
                packageInfo.signatures?.any { signature ->
                    val digest = java.security.MessageDigest.getInstance("SHA-256")
                        .digest(signature.toByteArray())
                    val fingerprint = digest.joinToString(":") { "%02X".format(it) }
                    fingerprint == SecurityConstants.BANKING_APP_CERT_SHA256
                } ?: false
            }
        } catch (e: PackageManager.NameNotFoundException) {
            Timber.w("Banking app not installed: ${SecurityConstants.BANKING_APP_PACKAGE}")
            false
        } catch (e: Exception) {
            Timber.e(e, "Failed to verify banking app certificate.")
            false
        }
    }

    /**
     * Returns true if the banking app is installed in the current profile context.
     */
    fun isBankingAppInstalled(): Boolean {
        return try {
            context.packageManager.getApplicationInfo(
                SecurityConstants.BANKING_APP_PACKAGE, 0
            )
            true
        } catch (e: PackageManager.NameNotFoundException) {
            false
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  Profile enable / disable
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Suspend the banking app so it cannot be launched.
     * Called when locking Secure Folder++ (user exits the session).
     * The app is still installed but frozen.
     */
    fun suspendBankingApp() {
        val enforcer = PolicyEnforcer(context)
        if (!enforcer.isProfileOwner()) return
        try {
            dpm.setPackagesSuspended(
                adminComponent,
                arrayOf(SecurityConstants.BANKING_APP_PACKAGE),
                true
            )
            Timber.d("Banking app suspended.")
        } catch (e: Exception) {
            Timber.e(e, "Failed to suspend banking app.")
        }
    }

    /**
     * Unsuspend the banking app so it can be launched.
     * Called immediately before launching the banking app in a secure session.
     */
    fun unsuspendBankingApp() {
        val enforcer = PolicyEnforcer(context)
        if (!enforcer.isProfileOwner()) return
        try {
            dpm.setPackagesSuspended(
                adminComponent,
                arrayOf(SecurityConstants.BANKING_APP_PACKAGE),
                false
            )
            Timber.d("Banking app unsuspended.")
        } catch (e: Exception) {
            Timber.e(e, "Failed to unsuspend banking app.")
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  Teardown
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Permanently destroy the Secure Folder++ work profile.
     *
     * This wipes ALL data in the work profile, including:
     *   • The banking app and its stored credentials/session data
     *   • All policy settings
     *
     * The device's personal profile data is NOT touched.
     *
     * After this call, the app must go through the provisioning flow again
     * to use Secure Folder++.
     */
    fun destroyProfile() {
        Timber.w("Destroying Secure Folder++ work profile.")
        PolicyEnforcer(context).wipeProfileData(reason = "User requested deletion")
        prefs.edit()
            .remove(SecurityConstants.PREF_PROFILE_CREATED)
            .remove(SecurityConstants.PREF_PROFILE_USER_ID)
            .remove(SecurityConstants.PREF_AUTH_SETUP_COMPLETE)
            .apply()
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  Supporting types
    // ─────────────────────────────────────────────────────────────────────────

    enum class ProvisioningBlockReason(val userMessage: String) {
        SYSTEM_UNSUPPORTED(
            "This device does not support isolated work profiles. " +
                    "Secure Folder++ requires Android 8.0 or higher with managed profile support."
        ),
        EXISTING_PROFILE_FROM_ANOTHER_DPC(
            "This device already has a work profile managed by another app " +
                    "(such as your employer's MDM). Only one work profile is allowed per device. " +
                    "Ask your IT administrator to remove the existing work profile, or use a separate device."
        ),
        ALREADY_PROVISIONED(
            "Secure Folder++ is already set up on this device."
        )
    }
}
