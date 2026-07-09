package com.securefolder.app.security

import android.content.Context
import android.content.Intent
import android.content.pm.LauncherApps
import android.os.Build
import android.os.Process
import android.os.UserHandle
import android.os.UserManager
import com.securefolder.app.SecurityConstants
import com.securefolder.app.model.HealthCheckReport
import com.securefolder.app.profile.ProfileManager
import timber.log.Timber

class SecureLauncher(private val context: Context) {

    private val healthCheckEngine = HealthCheckEngine(context)
    private val profileManager    = ProfileManager(context)

    // ── What attemptLaunch() returns ─────────────────────────────────────────

    sealed class LaunchResult {
        object Success : LaunchResult()
        data class Blocked(val report: HealthCheckReport) : LaunchResult()
        data class Error(val message: String) : LaunchResult()
    }

    // ── Main entry point ──────────────────────────────────────────────────────

    fun attemptLaunch(): LaunchResult {

        // 1. Run the 4 critical health checks
        val report = healthCheckEngine.runAllChecks()
        if (!report.isLaunchAllowed) {
            Timber.w("SecureLauncher: blocked — ${report.summary()}")
            return LaunchResult.Blocked(report)
        }

        // 2. Unsuspend the banking app so it can be launched
        profileManager.unsuspendBankingApp()

        // 3. Launch banking app inside work profile
        val launched = launchBankingApp()
        if (!launched) {
            // Re-suspend if launch failed — don't leave it unsuspended
            profileManager.suspendBankingApp()
            return LaunchResult.Error(
                "Could not find ${SecurityConstants.BANKING_APP_PACKAGE} " +
                        "in the work profile.\n\n" +
                        "Make sure the banking app is installed inside the work profile. " +
                        "Go to your work profile apps and install it from the Play Store there."
            )
        }

        // 4. Start the background security monitor for this session
        startMonitorService()

        Timber.i("SecureLauncher: banking app launched successfully.")
        return LaunchResult.Success
    }

    // ── Launch banking app via LauncherApps API ───────────────────────────────

    private fun launchBankingApp(): Boolean {
        return try {
            val workHandle = getWorkProfileHandle()
                ?: return false.also {
                    Timber.e("SecureLauncher: work profile user handle not found.")
                }

            val launcherApps = context.getSystemService(Context.LAUNCHER_APPS_SERVICE)
                    as LauncherApps

            // Get the list of launchable activities for the banking app
            // inside the work profile
            val activities = launcherApps.getActivityList(
                SecurityConstants.BANKING_APP_PACKAGE,
                workHandle
            )

            if (activities.isEmpty()) {
                Timber.e("SecureLauncher: ${SecurityConstants.BANKING_APP_PACKAGE} " +
                        "has no launchable activities in work profile.")
                return false
            }

            // Launch the main activity of the banking app in the work profile context
            launcherApps.startMainActivity(
                activities[0].componentName,
                workHandle,
                null,
                null
            )
            Timber.i("SecureLauncher: launched ${activities[0].componentName} in work profile.")
            true

        } catch (e: SecurityException) {
            Timber.e(e, "SecureLauncher: permission denied launching work profile app.")
            false
        } catch (e: Exception) {
            Timber.e(e, "SecureLauncher: failed to launch banking app.")
            false
        }
    }

    // ── Get the work profile UserHandle ──────────────────────────────────────

    private fun getWorkProfileHandle(): UserHandle? {
        return try {
            val userManager = context.getSystemService(Context.USER_SERVICE) as UserManager
            // userProfiles contains personal + work profiles
            // The work profile is the one that is NOT our current process user
            userManager.userProfiles.firstOrNull { it != Process.myUserHandle() }
        } catch (e: Exception) {
            Timber.e(e, "SecureLauncher: could not get work profile user handle.")
            null
        }
    }

    // ── Start the runtime security monitor ───────────────────────────────────

    private fun startMonitorService() {
        try {
            val intent = Intent(context, SecureMonitorService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
            Timber.d("SecureLauncher: monitor service started.")
        } catch (e: Exception) {
            Timber.e(e, "SecureLauncher: failed to start monitor service.")
        }
    }
}