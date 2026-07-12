package com.securefolderplusplus.app.dpc

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
// import android.os.UserManager // re-enable with the automated install block below
import com.securefolderplusplus.app.profile.ProfileManager
// import com.securefolderplusplus.app.security.BankingAppInstaller // re-enable with the automated install block below
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

                // AUTOMATED BUNDLED-APK INSTALL FALLBACK — TEMPORARILY DISABLED.
                // Banking app is being installed manually via the work-profile
                // "Pick Banking App APK to Install" button for now. Re-enable by
                // uncommenting this block once the automated path is ready again.
                //
                // SecureFolderAdminReceiver.onEnabled() is supposed to be the
                // trigger for the one-time bundled banking app install, but on
                // at least one tested device it never fires in-profile at all
                // during provisioning. This boot broadcast reliably fires
                // per-profile (including the very first boot right after a
                // fresh profile is created), so it's the safety-net trigger.
                // installBundledIfNeeded() guards against double-installing
                // if onEnabled() also fired correctly.
                /*
                val isManagedProfile =
                    context.getSystemService(UserManager::class.java)?.isManagedProfile == true
                val isOwner = PolicyEnforcer(context).isProfileOwner()
                Timber.e("SFPP-DIAG: BootReceiver isManagedProfile=$isManagedProfile isProfileOwner=$isOwner")
                if (isManagedProfile && isOwner) {
                    val pendingResult = goAsync()
                    Timber.e("SFPP-DIAG: BootReceiver launching install thread.")
                    Thread {
                        try {
                            BankingAppInstaller(context).installBundledIfNeeded()
                        } catch (e: Throwable) {
                            Timber.e(e, "Uncaught exception installing bundled banking app on boot.")
                        } finally {
                            Timber.e("SFPP-DIAG: BootReceiver install thread finishing, releasing wakelock.")
                            pendingResult.finish()
                        }
                    }.start()
                }
                */
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
