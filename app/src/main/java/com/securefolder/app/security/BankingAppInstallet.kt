package com.securefolderplusplus.app.security

import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.net.Uri
import android.os.Build
import android.app.PendingIntent
import com.securefolderplusplus.app.SecurityConstants
import java.io.InputStream
import timber.log.Timber

class BankingAppInstaller(private val context: Context) {

    fun installFromUri(apkUri: Uri, onError: (String) -> Unit) {
        val input = context.contentResolver.openInputStream(apkUri)
        if (input == null) {
            onError("Could not read APK file. Try picking it again.")
            return
        }
        input.use { install(it, onError) }
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
     * Called automatically from SecureFolderAdminReceiver.onEnabled() right after
     * the work profile is provisioned — no Play Store, no user file picker.
     *
     * On API 31+, this app being the profile owner lets us skip the install
     * confirmation dialog entirely (setRequireUserAction). On older API levels,
     * Android still shows one confirmation tap via InstallResultReceiver.
     */
    fun installFromAssets(assetFileName: String, onError: (String) -> Unit) {
        val input = try {
            context.assets.open(assetFileName)
        } catch (e: Exception) {
            Timber.e(e, "BankingAppInstaller: bundled APK asset not found: $assetFileName")
            onError("Bundled banking app APK not found: $assetFileName")
            return
        }
        input.use { install(it, onError) }
    }

    private fun install(input: InputStream, onError: (String) -> Unit) {
        try {
            val packageInstaller = context.packageManager.packageInstaller
            val params = PackageInstaller.SessionParams(
                PackageInstaller.SessionParams.MODE_FULL_INSTALL
            )
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                // Profile/device owner: install without a user confirmation dialog.
                params.setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
            }

            val sessionId = packageInstaller.createSession(params)
            Timber.e("SFPP-DIAG: session $sessionId created, starting copy.")
            packageInstaller.openSession(sessionId).use { session ->
                session.openWrite("base.apk", 0, -1).use { output ->
                    // Kotlin's default copyTo() buffer is 8KB — far too small for a
                    // 150MB+ APK on slow flash storage. 1MB cuts syscall overhead a lot.
                    val bytesCopied = input.copyTo(output, bufferSize = 1 shl 20)
                    Timber.e("SFPP-DIAG: copy finished, $bytesCopied bytes written. Calling fsync.")
                    session.fsync(output)
                    Timber.e("SFPP-DIAG: fsync done.")
                }

                // Commit — result comes back to InstallResultReceiver
                val intent = Intent(context, InstallResultReceiver::class.java)
                val pendingIntent = PendingIntent.getBroadcast(
                    context,
                    sessionId,
                    intent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
                session.commit(pendingIntent.intentSender)
            }

            Timber.e("SFPP-DIAG: session $sessionId committed.")
        } catch (e: Exception) {
            Timber.e(e, "BankingAppInstaller: install failed.")
            onError("Installation failed: ${e.message}")
        }
    }
}
