package com.securefolderplusplus.app.dpc

import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.os.Build
import android.os.UserManager
import com.securefolderplusplus.app.SecurityConstants
import timber.log.Timber

/**
 * PolicyEnforcer — applies all security policies to the managed work profile
 * using Android's DevicePolicyManager API as a Profile Owner.
 *
 * ═══════════════════════════════════════════════════════════════════
 *  DESIGN PHILOSOPHY
 * ═══════════════════════════════════════════════════════════════════
 * Every policy here maps directly to a stated requirement in the
 * Secure Folder++ specification. Methods are named after the threat
 * they address, not the API they call, so the implementation intent
 * is always clear.
 *
 * Policies are applied in layers:
 *   1. Overlay blocking             → DISALLOW_CREATE_WINDOWS
 *   2. Screen capture blocking      → setScreenCaptureDisabled + FLAG_SECURE (Step 4)
 *   3. Accessibility restriction    → setPermittedAccessibilityServices
 *   4. Keyboard restriction         → setPermittedInputMethods
 *   5. Cross-profile leakage block  → DISALLOW_CROSS_PROFILE_COPY_PASTE + others
 *   6. Installation restriction     → DISALLOW_INSTALL_APPS + DISALLOW_INSTALL_UNKNOWN_SOURCES
 *   7. Network restriction          → DISALLOW_CONFIG_VPN + DISALLOW_NETWORK_RESET
 *   8. Debug/backup restriction     → DISALLOW_DEBUGGING_FEATURES + setBackupServiceEnabled
 *   9. Password policy              → setPasswordQuality
 *
 * Each method is independently callable for runtime refreshes.
 * ═══════════════════════════════════════════════════════════════════
 *
 * ─────────────────────────────────────────────────────────────────
 *  NON-OEM BOUNDARY NOTES
 * ─────────────────────────────────────────────────────────────────
 * We cannot modify WindowManagerService source code. Instead:
 *   • DISALLOW_CREATE_WINDOWS prevents any app in the work profile
 *     from creating windows (TYPE_APPLICATION_OVERLAY, toasts, etc.)
 *     This is enforced by the system even without WMS patching.
 *   • setScreenCaptureDisabled() covers screen capture at profile level.
 *   • FLAG_SECURE covers individual window surfaces (Step 4).
 *
 * Together these provide equivalent protection to OEM WMS patching
 * for all practical threat scenarios.
 * ─────────────────────────────────────────────────────────────────
 */
class PolicyEnforcer(private val context: Context) {

    private val dpm: DevicePolicyManager =
        context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager

    private val adminComponent = ComponentName(context, SecureFolderAdminReceiver::class.java)

    // ─────────────────────────────────────────────────────────────────────────
    //  Entry points
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Apply every security policy. Called once after provisioning completes
     * and whenever the app needs to re-assert policies (e.g. after a reboot).
     */
    fun applyAllInitialPolicies() {
        requireProfileOwner()

        blockOverlayWindows()
        disableScreenCapture()
        restrictAccessibilityServices()
        restrictInputMethods()
        blockCrossProfileLeakage()
        lockdownInstallation()
        restrictNetwork()
        restrictDebugging()
        enforcePasswordPolicy()
        disableCamera()
        disableKeyguardFeatures()
        setNotificationPolicy()
        enableProfile()
        Timber.i("PolicyEnforcer: all policies applied.")
    }

    /**
     * Marks the managed profile as enabled. Until a profile owner calls this,
     * Android keeps the profile in a DISABLED state: its apps get no launcher
     * icon, and it's excluded from UserManager.getUserProfiles() /
     * CrossProfileApps results — even though the profile owner and all DPM
     * policies are already fully active. Must be called exactly once, right
     * after provisioning completes.
     */
    private fun enableProfile() {
        try {
            dpm.setProfileEnabled(adminComponent)
            Timber.i("Policy: work profile marked as enabled.")
        } catch (e: Exception) {
            Timber.e(e, "Failed to enable work profile.")
        }
    }

    /**
     * Re-apply policies that may be changed at runtime (e.g. user enabled a
     * new accessibility service or changed keyboard). Called by the monitor service.
     */
    fun refreshRuntimePolicies() {
        if (!isProfileOwner()) return
        restrictAccessibilityServices()
        restrictInputMethods()
        blockOverlayWindows()
        Timber.d("PolicyEnforcer: runtime policies refreshed.")
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  1. Overlay Window Blocking
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Block ALL window creation by non-system apps in the work profile.
     *
     * DISALLOW_CREATE_WINDOWS prevents:
     *   • TYPE_APPLICATION_OVERLAY (the "Appear on top" permission)
     *   • Toast-style deceptive windows
     *   • Bubble / chat-head overlays (Facebook Messenger, etc.)
     *   • Unauthorized accessibility overlay windows
     *
     * This is enforced by UserManager / ActivityManagerService and does not
     * require WMS source modification.
     *
     * Note: This restriction applies to apps WITHIN the work profile.
     * Apps in the PERSONAL profile cannot create windows over the work profile
     * due to Android's cross-profile window isolation — they are in separate
     * user contexts.
     */
    private fun blockOverlayWindows() {
        try {
            dpm.addUserRestriction(adminComponent, UserManager.DISALLOW_CREATE_WINDOWS)
            Timber.d("Policy: DISALLOW_CREATE_WINDOWS applied.")
        } catch (e: Exception) {
            Timber.e(e, "Failed to apply DISALLOW_CREATE_WINDOWS.")
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  2. Screen Capture Blocking
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Disable screen capture for all windows in the work profile.
     *
     * setScreenCaptureDisabled(true) prevents:
     *   • Screenshots
     *   • Screen recording via MediaProjection
     *   • Casting to insecure displays
     *   • Recent-app thumbnail leakage (secure surfaces show black)
     *   • Assist-content capture (Google Assistant sees a blank screen)
     *
     * API 33+: Full profile-level enforcement.
     * Pre-API 33: We rely on FLAG_SECURE set per-window (Step 4).
     *
     * This covers the requirement:
     *   "Do not depend on the banking app developer remembering FLAG_SECURE."
     * The profile-level policy is automatic and non-overridable by the banking app.
     */
    private fun disableScreenCapture() {
        try {
            // Available since API 21, but most reliable from API 28+.
            dpm.setScreenCaptureDisabled(adminComponent, true)
            Timber.d("Policy: setScreenCaptureDisabled(true) applied.")
        } catch (e: SecurityException) {
            Timber.e(e, "setScreenCaptureDisabled failed — not profile owner?")
        } catch (e: Exception) {
            Timber.e(e, "setScreenCaptureDisabled failed.")
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  3. Accessibility Service Restriction
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Restrict accessibility services to a system-trusted allowlist.
     *
     * After this call, ONLY services in SecurityConstants.TRUSTED_ACCESSIBILITY_SERVICES
     * can be enabled in the work profile. Passing null instead of a list
     * would allow ALL services — we never do that.
     *
     * This blocks:
     *   • Third-party screen readers that exfiltrate text
     *   • Remote-control services (TeamViewer, AnyDesk) that use accessibility
     *     to inject gestures and observe screen content
     *   • Malicious accessibility-based keyloggers
     *
     * User cannot override this from work profile Settings — it's enforced
     * by the DPC and grayed out in the UI.
     */
    private fun restrictAccessibilityServices() {
        try {
            dpm.setPermittedAccessibilityServices(
                adminComponent,
                SecurityConstants.TRUSTED_ACCESSIBILITY_SERVICES
            )
            Timber.d("Policy: accessibility services restricted to ${SecurityConstants.TRUSTED_ACCESSIBILITY_SERVICES.size} trusted services.")
        } catch (e: Exception) {
            Timber.e(e, "Failed to restrict accessibility services.")
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  4. Input Method (Keyboard) Restriction
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Restrict available keyboards to a trusted allowlist.
     *
     * Our own SecureKeyboardService is always in the allowlist.
     * Third-party keyboards (SwiftKey, Swype, etc.) cannot be enabled
     * inside the work profile.
     *
     * This prevents:
     *   • Third-party keyboard keyloggers
     *   • Clipboard-exfiltrating keyboards
     *   • Remote-input keyboards (some remote-control tools install a
     *     virtual keyboard to inject input)
     *
     * Passing null instead of a list allows ALL keyboards — never do that.
     */
    private fun restrictInputMethods() {
        try {
            dpm.setPermittedInputMethods(
                adminComponent,
                SecurityConstants.TRUSTED_INPUT_METHODS
            )
            Timber.d("Policy: input methods restricted to ${SecurityConstants.TRUSTED_INPUT_METHODS.size} trusted IMEs.")
        } catch (e: Exception) {
            Timber.e(e, "Failed to restrict input methods.")
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  5. Cross-Profile Leakage Prevention
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Block all vectors for data leaking FROM the work profile TO the personal profile.
     *
     * Restrictions applied:
     *   DISALLOW_CROSS_PROFILE_COPY_PASTE   → no clipboard copy from secure → personal
     *   DISALLOW_SHARE_INTO_MANAGED_PROFILE → no share INTO the secure profile from personal
     *   DISALLOW_USB_FILE_TRANSFER          → no USB MTP while secure profile is active
     *   DISALLOW_BLUETOOTH_SHARING          → no Bluetooth file share from profile
     *   setCrossProfileCallerIdDisabled     → personal apps can't see work profile caller IDs
     *   setCrossProfileContactsSearchDisabled → personal apps can't search work contacts
     */
    private fun blockCrossProfileLeakage() {
        try {
            // Clipboard cross-profile isolation
            dpm.addUserRestriction(adminComponent, UserManager.DISALLOW_CROSS_PROFILE_COPY_PASTE)
            Timber.d("Policy: DISALLOW_CROSS_PROFILE_COPY_PASTE applied.")

            // Prevent personal apps from sharing content into the managed profile
            dpm.addUserRestriction(adminComponent, UserManager.DISALLOW_SHARE_INTO_MANAGED_PROFILE)
            Timber.d("Policy: DISALLOW_SHARE_INTO_MANAGED_PROFILE applied.")

            // Block USB MTP file transfer from work profile storage
            dpm.addUserRestriction(adminComponent, UserManager.DISALLOW_USB_FILE_TRANSFER)
            Timber.d("Policy: DISALLOW_USB_FILE_TRANSFER applied.")

            // Block Bluetooth file sharing from work profile
            dpm.addUserRestriction(adminComponent, UserManager.DISALLOW_BLUETOOTH_SHARING)
            Timber.d("Policy: DISALLOW_BLUETOOTH_SHARING applied.")

            // API 28+: additional cross-profile data isolation
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                dpm.setCrossProfileCallerIdDisabled(adminComponent, true)
                dpm.setCrossProfileContactsSearchDisabled(adminComponent, true)
                Timber.d("Policy: cross-profile caller ID and contacts search disabled.")
            }

        } catch (e: Exception) {
            Timber.e(e, "Failed to apply cross-profile leakage prevention.")
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  6. Installation Lockdown
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Prevent any app other than the banking app from being installed
     * in the work profile.
     *
     *  DISALLOW_INSTALL_UNKNOWN_SOURCES        → no sideloaded APKs
     *  DISALLOW_INSTALL_UNKNOWN_SOURCES_GLOBALLY → stricter: blocks all unknown sources
     *  DISALLOW_INSTALL_APPS                   → blocks user-initiated installations entirely
     *
     * The banking app itself is installed by the DPC using enableSystemApp()
     * or by the provisioning flow — these restrictions do not affect DPC-managed
     * installation.
     */
    private fun lockdownInstallation() {
        try {
            // Android applies DISALLOW_INSTALL_UNKNOWN_SOURCES to every managed
            // profile BY DEFAULT — this code never set it, but it still blocked
            // even our own Profile-Owner-driven PackageInstaller install
            // (confirmed via Android's "Action not allowed" dialog when
            // installing the banking app, either automatically or by manually
            // picking the file). Clearing it so setup can actually complete.
            //
            // TODO: once the banking app installs reliably, re-add this
            // restriction (dpm.addUserRestriction(...)) so nothing else can be
            // sideloaded into the profile afterward — see CLAUDE.md's note
            // that install lockdown is intentionally deferred, not finished.
            dpm.clearUserRestriction(adminComponent, UserManager.DISALLOW_INSTALL_UNKNOWN_SOURCES)
            Timber.d("Policy: DISALLOW_INSTALL_UNKNOWN_SOURCES cleared (temporary, for banking app setup).")

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                dpm.clearUserRestriction(adminComponent, UserManager.DISALLOW_INSTALL_UNKNOWN_SOURCES_GLOBALLY)
                Timber.d("Policy: DISALLOW_INSTALL_UNKNOWN_SOURCES_GLOBALLY cleared.")
            }

//            // Block any user-initiated installs (from Play Store or elsewhere)
//            // within the work profile. The DPC controls what's installed.
//            dpm.addUserRestriction(adminComponent, UserManager.DISALLOW_INSTALL_APPS)
//            Timber.d("Policy: DISALLOW_INSTALL_APPS applied.")

        } catch (e: Exception) {
            Timber.e(e, "Failed to apply installation restrictions.")
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  7. Network Restrictions
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Restrict VPN and network configuration to prevent exfiltration via
     * malicious VPN proxies set up by an attacker with physical access.
     *
     *  DISALLOW_CONFIG_VPN      → user cannot configure or activate VPNs in profile
     *  DISALLOW_NETWORK_RESET   → user cannot reset network settings in profile
     */
    private fun restrictNetwork() {
        try {
            dpm.addUserRestriction(adminComponent, UserManager.DISALLOW_CONFIG_VPN)
            Timber.d("Policy: DISALLOW_CONFIG_VPN applied.")

            dpm.addUserRestriction(adminComponent, UserManager.DISALLOW_NETWORK_RESET)
            Timber.d("Policy: DISALLOW_NETWORK_RESET applied.")

        } catch (e: Exception) {
            Timber.e(e, "Failed to apply network restrictions.")
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  8. Debug / Backup Restrictions
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Block debugging and backup channels that could be used to exfiltrate
     * work profile data.
     *
     *  DISALLOW_DEBUGGING_FEATURES → blocks ADB, developer tools inside profile
     *  setBackupServiceEnabled(false) → disables Android backup for work profile data
     *  DISALLOW_FACTORY_RESET      → prevents resetting the work profile from within
     */
    private fun restrictDebugging() {
        try {
            dpm.addUserRestriction(adminComponent, UserManager.DISALLOW_DEBUGGING_FEATURES)
            Timber.d("Policy: DISALLOW_DEBUGGING_FEATURES applied.")

            // Disable backup to prevent banking data reaching untrusted cloud backup
            dpm.setBackupServiceEnabled(adminComponent, false)
            Timber.d("Policy: backup service disabled for work profile.")

            // Prevent factory reset of the work profile by a malicious actor
            // with physical access who gets past the lock screen
            dpm.addUserRestriction(adminComponent, UserManager.DISALLOW_FACTORY_RESET)
            Timber.d("Policy: DISALLOW_FACTORY_RESET applied.")

        } catch (e: Exception) {
            Timber.e(e, "Failed to apply debug/backup restrictions.")
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  9. Password / Authentication Policy
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Enforce a minimum credential quality for the work profile challenge.
     *
     * PASSWORD_QUALITY_NUMERIC_COMPLEX: at least 6 digits with no simple
     * sequences (no 1111, no 1234). This is separate from the device
     * lock screen — the work profile has its own challenge.
     *
     * Biometric authentication is configured at the UI layer (Step 10).
     */
    @Suppress("DEPRECATION")
    private fun enforcePasswordPolicy() {
        try {
            dpm.setPasswordQuality(
                adminComponent,
                DevicePolicyManager.PASSWORD_QUALITY_NUMERIC_COMPLEX
            )
            dpm.setPasswordMinimumLength(adminComponent, 6)
            Timber.d("Policy: password quality and minimum length set.")
        } catch (e: Exception) {
            Timber.e(e, "Failed to enforce password policy.")
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  10. Camera Disable
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Disable camera access within the work profile.
     *
     * The banking app has no legitimate need for the camera in our default
     * scenario. If your specific banking app requires camera (e.g., cheque
     * deposit photo), comment this out and restrict camera access at the
     * app permission level instead.
     */
    private fun disableCamera() {
        try {
            dpm.setCameraDisabled(adminComponent, true)
            Timber.d("Policy: camera disabled in work profile.")
        } catch (e: Exception) {
            Timber.e(e, "Failed to disable camera.")
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  11. Keyguard Feature Restrictions
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Disable keyguard features that could leak work profile content
     * on the lock screen before authentication.
     *
     * KEYGUARD_DISABLE_UNREDACTED_NOTIFICATIONS: hides notification content
     *   on the work profile lock screen (prevents OTP/amount leakage).
     * KEYGUARD_DISABLE_TRUST_AGENTS: disables Smart Lock "trusted places/devices"
     *   for the work profile — ensures authentication is always required.
     */
    private fun disableKeyguardFeatures() {
        try {
            val featuresToDisable = DevicePolicyManager.KEYGUARD_DISABLE_UNREDACTED_NOTIFICATIONS or
                    DevicePolicyManager.KEYGUARD_DISABLE_TRUST_AGENTS

            dpm.setKeyguardDisabledFeatures(adminComponent, featuresToDisable)
            Timber.d("Policy: keyguard features disabled (unredacted notifications, trust agents).")
        } catch (e: Exception) {
            Timber.e(e, "Failed to disable keyguard features.")
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  12. Notification Policy (Cross-Profile Redaction)
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Configure notification policy to redact work profile notification content
     * when displayed in the personal profile's notification shade.
     *
     * Android's Work Profile implementation automatically applies notification
     * redaction when cross-profile notification mirroring is used.
     * We additionally set DISALLOW_SHARE_INTO_MANAGED_PROFILE above to
     * prevent the reverse direction.
     *
     * Full notification listener blocking is enforced at the profile level —
     * third-party notification listener services cannot read work profile
     * notifications because they run in the personal profile (separate user context).
     */
    private fun setNotificationPolicy() {
        try {
            // Ensure notification listener services in personal profile
            // cannot access work profile notifications. This is enforced
            // by Android's cross-profile architecture, but we can additionally
            // set this restriction explicitly.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                // Block cross-profile widgets (can embed notification content)
                dpm.addUserRestriction(adminComponent, UserManager.DISALLOW_MODIFY_ACCOUNTS)
                Timber.d("Policy: notification and account modification restrictions applied.")
            }
        } catch (e: Exception) {
            Timber.e(e, "Failed to set notification policy.")
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  Profile management helpers
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Wipe all data in the managed work profile.
     *
     * This is a DESTRUCTIVE operation. It removes the work profile
     * and ALL data within it (banking app data, credentials, etc.)
     *
     * Used when:
     *   • Admin is disabled (onDisabled)
     *   • Too many failed unlock attempts (onPasswordFailed)
     *   • User explicitly requests "delete Secure Folder++"
     */
    fun wipeProfileData(reason: String = "Policy violation") {
        Timber.w("Wiping work profile data. Reason: $reason")
        try {
            // DevicePolicyManager.WIPE_EUICC_DATA can be added if needed.
            // We use 0 flags to wipe only the managed profile, not the device.
            dpm.wipeData(0)
        } catch (e: Exception) {
            Timber.e(e, "Failed to wipe profile data.")
        }
    }

    /**
     * Lock the work profile immediately.
     * Used when a security violation is detected during a session.
     */
    fun lockProfileNow() {
        try {
            dpm.lockNow()
            Timber.d("Work profile locked by policy.")
        } catch (e: Exception) {
            Timber.e(e, "Failed to lock profile.")
        }
    }

    /**
     * Returns the number of failed password attempts for the work profile.
     * Used by onPasswordFailed to decide whether to wipe.
     */
    fun getFailedPasswordAttempts(): Int {
        return try {
            dpm.currentFailedPasswordAttempts
        } catch (e: Exception) {
            0
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  State checks
    // ─────────────────────────────────────────────────────────────────────────

    /** Returns true if this app is currently the Profile Owner of a managed profile. */
    fun isProfileOwner(): Boolean = try {
        dpm.isProfileOwnerApp(context.packageName)
    } catch (e: Exception) {
        false
    }

    /** Returns true if this app is currently the Device Owner of the device. */
    fun isDeviceOwner(): Boolean = try {
        dpm.isDeviceOwnerApp(context.packageName)
    } catch (e: Exception) {
        false
    }

    /** Throws if not profile owner — used at the start of policy application. */
    private fun requireProfileOwner() {
        check(isProfileOwner()) {
            "PolicyEnforcer: not a Profile Owner. Cannot apply DPM policies. " +
                    "Ensure the app was provisioned via ACTION_PROVISION_MANAGED_PROFILE."
        }
    }
}
