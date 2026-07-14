package com.securefolderplusplus.app.security

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.app.PendingIntent
import android.provider.MediaStore
import com.securefolderplusplus.app.SecurityConstants
import java.io.InputStream
import timber.log.Timber

class BankingAppInstaller(private val context: Context) {

    /**
     * Copies the bundled banking APK from assets into the work profile's
     * public Downloads folder, so the user can pick it manually via
     * "Pick Banking App APK to Install" — sidesteps every PackageInstaller
     * silent-install confirmation issue by leaving the actual install as a
     * normal, foreground, user-initiated action Android handles reliably.
     * Safe to call repeatedly — skips if already copying/copied.
     */
    fun copyBundledApkToDownloadsIfNeeded() {
        val prefs = context.getSharedPreferences(SecurityConstants.PREF_NAME, Context.MODE_PRIVATE)
        val status = prefs.getString(SecurityConstants.PREF_BANKING_COPY_STATUS, null)
        if (status == SecurityConstants.BANKING_COPY_STATUS_SUCCESS ||
            status == SecurityConstants.BANKING_COPY_STATUS_COPYING
        ) {
            return
        }
        prefs.edit()
            .putString(SecurityConstants.PREF_BANKING_COPY_STATUS, SecurityConstants.BANKING_COPY_STATUS_COPYING)
            .remove(SecurityConstants.PREF_BANKING_COPY_MESSAGE)
            .putLong(SecurityConstants.PREF_BANKING_COPY_BYTES_COPIED, 0L)
            .putLong(SecurityConstants.PREF_BANKING_COPY_TOTAL_BYTES, 0L)
            .apply()

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            failCopy(prefs, "Requires Android 10 or higher.")
            return
        }

        try {
            val assetName = SecurityConstants.BANKING_APP_BUNDLED_APK_ASSET
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, assetName)
                put(MediaStore.Downloads.MIME_TYPE, "application/vnd.android.package-archive")
                put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
            }
            val itemUri = context.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                ?: run { failCopy(prefs, "Could not create a Downloads entry."); return }

            context.assets.openFd(assetName).use { afd ->
                val total = afd.length
                afd.createInputStream().use { input ->
                    val output = context.contentResolver.openOutputStream(itemUri)
                        ?: run { failCopy(prefs, "Could not open the Downloads entry for writing."); return }
                    output.use {
                        val buffer = ByteArray(1 shl 20)
                        var copied = 0L
                        var lastReported = 0L
                        while (true) {
                            val read = input.read(buffer)
                            if (read == -1) break
                            it.write(buffer, 0, read)
                            copied += read
                            if (copied - lastReported >= (5 shl 20)) {
                                reportCopyProgress(copied, total)
                                lastReported = copied
                            }
                        }
                        reportCopyProgress(copied, total)
                    }
                }
            }

            prefs.edit()
                .putString(SecurityConstants.PREF_BANKING_COPY_STATUS, SecurityConstants.BANKING_COPY_STATUS_SUCCESS)
                .putString(SecurityConstants.PREF_BANKING_COPY_PATH, "Downloads/$assetName")
                .apply()
            Timber.e("SFPP-DIAG: copied bundled APK to Downloads/$assetName successfully.")
        } catch (e: Exception) {
            Timber.e(e, "copyBundledApkToDownloadsIfNeeded failed.")
            failCopy(prefs, e.message ?: "unknown error")
        }
    }

    private fun failCopy(prefs: android.content.SharedPreferences, message: String) {
        prefs.edit()
            .putString(SecurityConstants.PREF_BANKING_COPY_STATUS, SecurityConstants.BANKING_COPY_STATUS_FAILED)
            .putString(SecurityConstants.PREF_BANKING_COPY_MESSAGE, message)
            .apply()
    }

    private fun reportCopyProgress(copied: Long, total: Long) {
        val prefs = context.getSharedPreferences(SecurityConstants.PREF_NAME, Context.MODE_PRIVATE)
        prefs.edit()
            .putLong(SecurityConstants.PREF_BANKING_COPY_BYTES_COPIED, copied)
            .putLong(SecurityConstants.PREF_BANKING_COPY_TOTAL_BYTES, total)
            .apply()
    }

    fun installFromUri(apkUri: Uri, onError: (String) -> Unit) {
        val input = context.contentResolver.openInputStream(apkUri)
        if (input == null) {
            onError("Could not read APK file. Try picking it again.")
            return
        }
        input.use { install(it, -1L, onError) }
    }

    /**
     * Kicks off the bundled-APK install if it hasn't already succeeded or
     * isn't already in flight. Shared by every in-profile trigger point
     * (SecureFolderAdminReceiver.onEnabled() and BootReceiver's first-boot
     * fallback) so they can't both start a redundant/concurrent install.
     * Must be called from a background thread — this does the full I/O copy.
     */
    fun installBundledIfNeeded() {
        val prefs = context.getSharedPreferences(SecurityConstants.PREF_NAME, Context.MODE_PRIVATE)
        val status = prefs.getString(SecurityConstants.PREF_BANKING_INSTALL_STATUS, null)
        Timber.e("SFPP-DIAG: installBundledIfNeeded() called, current status=$status")
        if (status == SecurityConstants.BANKING_INSTALL_STATUS_SUCCESS ||
            status == SecurityConstants.BANKING_INSTALL_STATUS_INSTALLING
        ) {
            Timber.e("SFPP-DIAG: skipping — already $status.")
            return
        }
        prefs.edit()
            .putString(SecurityConstants.PREF_BANKING_INSTALL_STATUS, SecurityConstants.BANKING_INSTALL_STATUS_INSTALLING)
            .remove(SecurityConstants.PREF_BANKING_INSTALL_MESSAGE)
            .putLong(SecurityConstants.PREF_BANKING_INSTALL_BYTES_COPIED, 0L)
            .putLong(SecurityConstants.PREF_BANKING_INSTALL_TOTAL_BYTES, 0L)
            .apply()

        installFromAssets(SecurityConstants.BANKING_APP_BUNDLED_APK_ASSET) { error ->
            Timber.e("Bundled banking app install failed: $error")
            prefs.edit()
                .putString(SecurityConstants.PREF_BANKING_INSTALL_STATUS, SecurityConstants.BANKING_INSTALL_STATUS_FAILED)
                .putString(SecurityConstants.PREF_BANKING_INSTALL_MESSAGE, error)
                .apply()
        }
    }

    /**
     * Silently installs the banking app bundled at app/src/main/assets/<assetFileName>.
     * Called automatically from SecureFolderAdminReceiver.onEnabled() / BootReceiver
     * right after the work profile is provisioned — no Play Store, no user file picker.
     *
     * On API 31+, this app being the profile owner lets us skip the install
     * confirmation dialog entirely (setRequireUserAction). On older API levels,
     * Android still shows one confirmation tap via InstallResultReceiver.
     */
    fun installFromAssets(assetFileName: String, onError: (String) -> Unit) {
        // openFd() only works for assets stored uncompressed (see
        // androidResources.noCompress "apk" in build.gradle) — that's what
        // gives us an exact byte length up front for progress reporting.
        try {
            context.assets.openFd(assetFileName).use { afd ->
                afd.createInputStream().use { input ->
                    install(input, afd.length, onError)
                }
            }
        } catch (e: Exception) {
            Timber.e(e, "BankingAppInstaller: bundled APK asset not found or not stored uncompressed: $assetFileName")
            onError("Bundled banking app APK not found: $assetFileName")
        }
    }

    private fun install(input: InputStream, totalBytes: Long, onError: (String) -> Unit) {
        try {
            val packageInstaller = context.packageManager.packageInstaller
            val params = PackageInstaller.SessionParams(
                PackageInstaller.SessionParams.MODE_FULL_INSTALL
            )
            if (totalBytes > 0) {
                params.setSize(totalBytes)
            }
            // INSTALL_REASON_POLICY marks this as a policy-driven install rather
            // than a user-initiated sideload — the documented pairing with
            // setRequireUserAction(NOT_REQUIRED) for profile/device owners.
            params.setInstallReason(PackageManager.INSTALL_REASON_POLICY)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                // Profile/device owner: install without a user confirmation dialog.
                params.setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
            }

            val sessionId = packageInstaller.createSession(params)
            Timber.e("SFPP-DIAG: session $sessionId created, totalBytes=$totalBytes, starting copy.")
            reportProgress(0L, totalBytes)

            packageInstaller.openSession(sessionId).use { session ->
                session.openWrite("base.apk", 0, totalBytes).use { output ->
                    val buffer = ByteArray(1 shl 20) // 1MB — far less syscall overhead than the 8KB default.
                    var copied = 0L
                    var lastReported = 0L
                    while (true) {
                        val read = input.read(buffer)
                        if (read == -1) break
                        output.write(buffer, 0, read)
                        copied += read
                        if (copied - lastReported >= (5 shl 20)) {
                            reportProgress(copied, totalBytes)
                            lastReported = copied
                        }
                    }
                    reportProgress(copied, totalBytes)
                    Timber.e("SFPP-DIAG: copy finished, $copied bytes written. Calling fsync.")
                    session.fsync(output)
                    Timber.e("SFPP-DIAG: fsync done.")
                }

                // Commit — result comes back to InstallResultReceiver.
                // MUTABLE, not IMMUTABLE: PackageInstallerService fills in the
                // result extras (EXTRA_STATUS, EXTRA_STATUS_MESSAGE, EXTRA_INTENT)
                // onto this PendingIntent's Intent when it dispatches the
                // callback — an immutable PendingIntent blocks exactly that
                // fill-in, which is why every broadcast we received had zero
                // extras regardless of trigger. Safe here since the target
                // component is already fixed to our own receiver.
                val intent = Intent(context, InstallResultReceiver::class.java)
                val pendingIntent = PendingIntent.getBroadcast(
                    context,
                    sessionId,
                    intent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
                )
                session.commit(pendingIntent.intentSender)
            }

            Timber.e("SFPP-DIAG: session $sessionId committed.")
        } catch (e: Exception) {
            Timber.e(e, "BankingAppInstaller: install failed.")
            onError("Installation failed: ${e.message}")
        }
    }

    private fun reportProgress(copied: Long, total: Long) {
        val prefs = context.getSharedPreferences(SecurityConstants.PREF_NAME, Context.MODE_PRIVATE)
        prefs.edit()
            .putLong(SecurityConstants.PREF_BANKING_INSTALL_BYTES_COPIED, copied)
            .putLong(SecurityConstants.PREF_BANKING_INSTALL_TOTAL_BYTES, total)
            .apply()
    }
}
