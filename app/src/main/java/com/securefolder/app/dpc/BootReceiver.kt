package com.securefolder.app.dpc

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.securefolder.app.profile.ProfileManager
import timber.log.Timber

/**
 * BootReceiver — restores Secure Folder++ state after device reboot.
 *
 * On reboot:
 *   • DPM policies (DISALLOW_CREATE_WINDOWS, etc.) are automatically
 *     re-applied by Android — they survive reboots without any action.
 *   • setScreenCaptureDisabled() and setPermittedAccessibilityServices()
 *     also survive reboots automatically.
 *   • However, the banking app should be in a SUSPENDED state on boot
 *     (requiring the user to authenticate before unlocking it).
 *
 * We use LOCKED_BOOT_COMPLETED for Direct Boot awareness so we can
 * act before the user unlocks the device-level encryption.
 *
 * In this step (Step 1) we just ensure the app is suspended on boot.
 * The SecureMonitorService start-on-boot is added in Step 8.
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_LOCKED_BOOT_COMPLETED -> {
                Timber.i("Boot completed. Restoring Secure Folder++ state.")
                onBoot(context)
            }
        }
    }

    private fun onBoot(context: Context) {
        val profileManager = ProfileManager(context)

        if (!profileManager.isProfileReady()) {
            Timber.d("BootReceiver: profile not ready — nothing to restore.")
            return
        }

        // Ensure the banking app starts in suspended state after reboot.
        // The user must authenticate to Secure Folder++ before the banking
        // app becomes launchable. This prevents the app from being accessible
        // if the device is rebooted without unlocking.
        profileManager.suspendBankingApp()
        Timber.d("BootReceiver: banking app suspended. Authentication required to launch.")

        // Re-apply runtime policies (belt-and-suspenders — DPM policies
        // survive reboot automatically, but this catches any edge cases).
        val enforcer = PolicyEnforcer(context)
        if (enforcer.isProfileOwner()) {
            enforcer.refreshRuntimePolicies()
            Timber.d("BootReceiver: runtime policies refreshed.")
        }
    }
}
