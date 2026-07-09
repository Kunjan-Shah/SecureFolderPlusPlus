package com.securefolder.app.security

import android.app.ActivityManager
import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import com.securefolder.app.SecurityConstants
import com.securefolder.app.model.HealthCheckReport
import com.securefolder.app.model.PolicyResult
import timber.log.Timber
import java.io.File
import java.text.SimpleDateFormat
import java.util.Locale

class HealthCheckEngine(private val context: Context) {

    fun runAllChecks(): HealthCheckReport {
        val startMs = System.currentTimeMillis()
        val results = listOf(
            checkAdbEnabled(),
//            checkDeveloperOptions(),
            checkDeviceRooted(),
            checkBootloaderStatus(),
//            checkScreenRecording(),
//            checkKnownRiskyPackages(),
            checkPatchLevel(),
//            checkUntrustedAccessibilityServices(),
//            checkOverlayCapableApps(),
//            checkSuspiciousKeyboard()
        )
        val duration = System.currentTimeMillis() - startMs
        return HealthCheckReport(results, duration).also {
            Timber.i("HealthCheck: ${it.summary()}")
        }
    }

    // ── Check 1: USB Debugging ────────────────────────────────────────────────

    private fun checkAdbEnabled(): PolicyResult {
        val enabled = Settings.Global.getInt(
            context.contentResolver,
            Settings.Global.ADB_ENABLED, 0
        )
        return if (enabled != 0) {
            PolicyResult.fail(
                checkName = "ADB_ENABLED",
                reason = "USB Debugging is turned on.",
                hint = "Settings → Developer Options → turn off USB Debugging."
            )
        } else {
            PolicyResult.pass("ADB_ENABLED")
        }
    }

    // ── Check 2: Developer Options ────────────────────────────────────────────

    private fun checkDeveloperOptions(): PolicyResult {
        val enabled = Settings.Global.getInt(
            context.contentResolver,
            Settings.Global.DEVELOPMENT_SETTINGS_ENABLED, 0
        )
        return if (enabled != 0) {
            PolicyResult.warn(
                checkName = "DEVELOPER_OPTIONS",
                reason = "Developer options are enabled.",
                hint = "Settings → Developer Options → turn off."
            )
        } else {
            PolicyResult.pass("DEVELOPER_OPTIONS")
        }
    }

    // ── Check 3: Root Detection ───────────────────────────────────────────────

    private fun checkDeviceRooted(): PolicyResult {
        val rooted = checkSuBinaries() || checkRootPackages()
        return if (rooted) {
            PolicyResult.fail(
                checkName = "DEVICE_ROOTED",
                reason = "This device appears to be rooted.",
                hint = "Secure Folder++ cannot run on a rooted device."
            )
        } else {
            PolicyResult.pass("DEVICE_ROOTED")
        }
    }

    private fun checkSuBinaries(): Boolean {
        val paths = listOf(
            "/sbin/su",
            "/system/bin/su",
            "/system/xbin/su",
            "/data/local/bin/su",
            "/data/local/xbin/su",
            "/system/app/Superuser.apk",
            "/system/app/SuperSU.apk"
        )
        return paths.any { File(it).exists() }
    }

    private fun checkRootPackages(): Boolean {
        val rootPkgs = listOf(
            "com.topjohnwu.magisk",
            "eu.chainfire.supersu",
            "com.noshufou.android.su",
            "com.koushikdutta.superuser",
            "com.thirdparty.superuser"
        )
        return rootPkgs.any { pkg ->
            try {
                context.packageManager.getPackageInfo(pkg, 0)
                true
            } catch (e: PackageManager.NameNotFoundException) {
                false
            }
        }
    }

    // ── Check 4: Bootloader ───────────────────────────────────────────────────

    private fun checkBootloaderStatus(): PolicyResult {
        val tags = Build.TAGS ?: ""
        return if (tags.contains("test-keys") || tags.contains("dev-keys")) {
            PolicyResult.warn(
                checkName = "BOOTLOADER",
                reason = "Device may have an unlocked bootloader (tags: $tags).",
                hint = "A locked bootloader provides stronger security."
            )
        } else {
            PolicyResult.pass("BOOTLOADER")
        }
    }

    // ── Check 5: Screen Recording ─────────────────────────────────────────────

    private fun checkScreenRecording(): PolicyResult {
        try {
            val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            val processes = am.runningAppProcesses ?: emptyList()
            val runningPkgs = processes.map { it.processName }.toSet()

            val found = SecurityConstants.KNOWN_RISKY_PACKAGES.firstOrNull { pkg ->
                runningPkgs.contains(pkg)
            }
            if (found != null) {
                return PolicyResult.fail(
                    checkName = "SCREEN_RECORDING",
                    reason = "Screen recording app is active: $found",
                    hint = "Close the screen recording app before continuing."
                )
            }
        } catch (e: Exception) {
            Timber.w(e, "Screen recording check failed.")
        }
        return PolicyResult.pass("SCREEN_RECORDING")
    }

    // ── Check 6: Known Risky Packages ─────────────────────────────────────────

    private fun checkKnownRiskyPackages(): PolicyResult {
        val found = SecurityConstants.KNOWN_RISKY_PACKAGES.filter { pkg ->
            try {
                context.packageManager.getApplicationInfo(pkg, 0).enabled
            } catch (e: PackageManager.NameNotFoundException) {
                false
            }
        }
        return if (found.isNotEmpty()) {
            val display = found.take(2).joinToString(", ") +
                    if (found.size > 2) " + ${found.size - 2} more" else ""
            PolicyResult.fail(
                checkName = "RISKY_PACKAGES",
                reason = "Risky app(s) installed: $display",
                hint = "Uninstall or disable: ${found.joinToString(", ")}"
            )
        } else {
            PolicyResult.pass("RISKY_PACKAGES")
        }
    }

    // ── Check 7: Security Patch Level ─────────────────────────────────────────

    private fun checkPatchLevel(): PolicyResult {
        return try {
            val sdf = SimpleDateFormat("yyyy-MM-dd", Locale.US)
            val patchDate = sdf.parse(Build.VERSION.SECURITY_PATCH)
                ?: return PolicyResult.warn("PATCH_LEVEL", "Could not read patch date.", null)
            val minMonth = SecurityConstants.MIN_PATCH_LEVEL_MONTH
                .toString().padStart(2, '0')
            val minDate = sdf.parse(
                "${SecurityConstants.MIN_PATCH_LEVEL_YEAR}-$minMonth-01"
            )!!
            if (patchDate.before(minDate)) {
                PolicyResult.warn(
                    checkName = "PATCH_LEVEL",
                    reason = "Security patch (${Build.VERSION.SECURITY_PATCH}) is outdated.",
                    hint = "Settings → System → Software Update."
                )
            } else {
                PolicyResult.pass("PATCH_LEVEL")
            }
        } catch (e: Exception) {
            PolicyResult.warn("PATCH_LEVEL", "Could not verify patch level.", null)
        }
    }

    // ── Check 8: Untrusted Accessibility Services ─────────────────────────────

    private fun checkUntrustedAccessibilityServices(): PolicyResult {
        val raw = Settings.Secure.getString(
            context.contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return PolicyResult.pass("ACCESSIBILITY")

        val enabled = raw.split(":").filter { it.isNotBlank() }
        val untrusted = enabled.filter { svc ->
            val pkg = svc.substringBefore("/")
            SecurityConstants.TRUSTED_ACCESSIBILITY_SERVICES.none { it.startsWith(pkg) }
        }
        return if (untrusted.isNotEmpty()) {
            PolicyResult.fail(
                checkName = "ACCESSIBILITY",
                reason = "Untrusted accessibility service(s) active.",
                hint = "Settings → Accessibility → disable untrusted services."
            )
        } else {
            PolicyResult.pass("ACCESSIBILITY")
        }
    }

    // ── Check 9: Overlay-Capable Apps ─────────────────────────────────────────

    private fun checkOverlayCapableApps(): PolicyResult {
        val found = mutableListOf<String>()
        try {
            val apps = context.packageManager
                .getInstalledApplications(PackageManager.GET_META_DATA)
            for (app in apps) {
                // Skip our own app
                if (app.packageName == context.packageName) continue

                // Skip system apps AND updated system apps (Samsung updates
                // system apps via Play Store which can clear FLAG_SYSTEM)
                val isSystem = (app.flags and ApplicationInfo.FLAG_SYSTEM) != 0 ||
                        (app.flags and ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0
                if (isSystem) continue

                // Skip Samsung core packages — they are OEM trusted even if
                // FLAG_SYSTEM is missing after a Play Store update
                if (app.packageName.startsWith("com.sec.android") ||
                    app.packageName.startsWith("com.samsung.android") ||
                    app.packageName.startsWith("com.google.android")) continue

                val granted = context.packageManager.checkPermission(
                    "android.permission.SYSTEM_ALERT_WINDOW",
                    app.packageName
                )
                if (granted == PackageManager.PERMISSION_GRANTED) {
                    found.add(app.packageName)
                }
            }
        } catch (e: Exception) {
            Timber.w(e, "Overlay check failed.")
        }

        return if (found.isNotEmpty()) {
            val display = found.take(2).joinToString(", ") +
                    if (found.size > 2) " + ${found.size - 2} more" else ""
            PolicyResult.fail(
                checkName = "OVERLAY_APPS",
                reason = "App(s) with 'Appear on top' permission found: $display",
                hint = "Settings → Apps → Special app access → Appear on top → revoke."
            )
        } else {
            PolicyResult.pass("OVERLAY_APPS")
        }
    }


    // ── Check 10: Suspicious Keyboard ─────────────────────────────────────────

    private fun checkSuspiciousKeyboard(): PolicyResult {
        val current = Settings.Secure.getString(
            context.contentResolver,
            Settings.Secure.DEFAULT_INPUT_METHOD
        ) ?: return PolicyResult.pass("KEYBOARD")

        val pkg = current.substringBefore("/")
        val trusted = SecurityConstants.TRUSTED_INPUT_METHODS.any { it.startsWith(pkg) }
        return if (!trusted) {
            PolicyResult.fail(
                checkName = "KEYBOARD",
                reason = "Untrusted keyboard active: $pkg",
                hint = "Settings → General Management → Keyboard → switch to Gboard or Samsung Keyboard."
            )
        } else {
            PolicyResult.pass("KEYBOARD")
        }
    }
}