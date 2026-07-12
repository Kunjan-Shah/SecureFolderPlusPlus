package com.securefolderplusplus.app.security

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.widget.Toast
import com.securefolderplusplus.app.SecurityConstants
import com.securefolderplusplus.app.profile.ProfileManager
import timber.log.Timber

class InstallResultReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, -1)
        val message = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)
        val prefs = context.getSharedPreferences(SecurityConstants.PREF_NAME, Context.MODE_PRIVATE)

        when (status) {
            PackageInstaller.STATUS_SUCCESS -> {
                Timber.i("InstallResultReceiver: banking app installed successfully.")

                // The whole point of this app is not trusting an unverified banking
                // APK — re-check the pinned cert on every install, including the
                // silent auto-install path, in case the bundled asset is stale/wrong.
                if (ProfileManager(context).isBankingAppCertificateValid()) {
                    prefs.edit()
                        .putString(SecurityConstants.PREF_BANKING_INSTALL_STATUS, SecurityConstants.BANKING_INSTALL_STATUS_SUCCESS)
                        .apply()
                    Toast.makeText(
                        context,
                        "✅ Banking app installed in secure profile!",
                        Toast.LENGTH_LONG
                    ).show()
                } else {
                    Timber.e("InstallResultReceiver: installed APK failed certificate pin check.")
                    prefs.edit()
                        .putString(SecurityConstants.PREF_BANKING_INSTALL_STATUS, SecurityConstants.BANKING_INSTALL_STATUS_CERT_MISMATCH)
                        .apply()
                    Toast.makeText(
                        context,
                        "⚠️ Installed app failed certificate verification — do not use it. " +
                                "Check SecurityConstants.BANKING_APP_CERT_SHA256 against the real app.",
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                // Android needs explicit user confirmation — show the dialog
                @Suppress("DEPRECATION")
                val confirmIntent = intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)
                confirmIntent?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                confirmIntent?.let {
                    context.startActivity(it)
                }
            }
            else -> {
                Timber.e("InstallResultReceiver: install failed — status=$status msg=$message")
                prefs.edit()
                    .putString(SecurityConstants.PREF_BANKING_INSTALL_STATUS, SecurityConstants.BANKING_INSTALL_STATUS_FAILED)
                    .putString(SecurityConstants.PREF_BANKING_INSTALL_MESSAGE, message ?: "status=$status")
                    .apply()
                Toast.makeText(
                    context,
                    "❌ Install failed: $message",
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }
}