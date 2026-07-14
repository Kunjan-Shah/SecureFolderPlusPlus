package com.securefolderplusplus.app.security

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.content.IntentCompat
import android.widget.Toast
import com.securefolderplusplus.app.SecurityConstants
import com.securefolderplusplus.app.profile.ProfileManager
import timber.log.Timber

class InstallResultReceiver : BroadcastReceiver() {

    // A default distinct from every real PackageInstaller status code
    // (0..7, or -1 for STATUS_PENDING_USER_ACTION) — using -1 as the
    // getIntExtra() default was a real bug: a broadcast with no EXTRA_STATUS
    // at all would silently masquerade as a genuine "needs confirmation".
    private val NO_STATUS_EXTRA = Int.MIN_VALUE

    override fun onReceive(context: Context, intent: Intent) {
        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, NO_STATUS_EXTRA)
        val message = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)
        val prefs = context.getSharedPreferences(SecurityConstants.PREF_NAME, Context.MODE_PRIVATE)

        if (status == NO_STATUS_EXTRA) {
            val availableKeys = intent.extras?.keySet()?.joinToString() ?: "(no extras bundle)"
            Timber.e("InstallResultReceiver: broadcast had no EXTRA_STATUS at all. Extras present: $availableKeys")
            prefs.edit()
                .putString(SecurityConstants.PREF_BANKING_INSTALL_STATUS, SecurityConstants.BANKING_INSTALL_STATUS_FAILED)
                .putString(SecurityConstants.PREF_BANKING_INSTALL_MESSAGE, "Broadcast carried no status. Extras: $availableKeys")
                .apply()
            return
        }

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
                // Android is refusing the silent-install request and needs an
                // explicit confirmation tap. Reflect that in our own tracked
                // status so the UI stops claiming "installing" forever.
                Timber.e("InstallResultReceiver: STATUS_PENDING_USER_ACTION — install needs manual confirmation.")
                prefs.edit()
                    .putString(SecurityConstants.PREF_BANKING_INSTALL_STATUS, SecurityConstants.BANKING_INSTALL_STATUS_NEEDS_CONFIRMATION)
                    .apply()

                // The deprecated single-arg getParcelableExtra(String) can silently
                // return null on API 33+ with a high targetSdk (a known Android
                // issue) — IntentCompat's type-safe overload is the fix.
                val confirmIntent = IntentCompat.getParcelableExtra(intent, Intent.EXTRA_INTENT, Intent::class.java)
                if (confirmIntent == null) {
                    val availableKeys = intent.extras?.keySet()?.joinToString() ?: "(no extras bundle)"
                    Timber.e("InstallResultReceiver: no confirmation Intent.EXTRA_INTENT present. Available extras: $availableKeys")
                    prefs.edit()
                        .putString(
                            SecurityConstants.PREF_BANKING_INSTALL_MESSAGE,
                            "No confirm intent found. Extras present: $availableKeys"
                        )
                        .apply()
                    return
                }
                confirmIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

                // Persist so MainActivity can launch this directly from a
                // foreground button tap if the notification below doesn't land
                // (e.g. notification permission wasn't granted yet at this
                // exact moment — the automated install can fire before the
                // user has had a chance to respond to that prompt).
                prefs.edit()
                    .putString(
                        SecurityConstants.PREF_BANKING_INSTALL_CONFIRM_INTENT_URI,
                        confirmIntent.toUri(Intent.URI_INTENT_SCHEME)
                    )
                    .apply()

                // Best-effort direct launch — works on some OS versions/states.
                try {
                    context.startActivity(confirmIntent)
                } catch (e: Exception) {
                    Timber.e(e, "InstallResultReceiver: direct startActivity for confirmation failed.")
                }

                // Reliable fallback: a notification tap is a genuine user-initiated
                // action, so it isn't subject to background-activity-launch
                // restrictions the way a plain startActivity() call from this
                // BroadcastReceiver can be (observed silently swallowed on at
                // least one Samsung/One UI build).
                showConfirmInstallNotification(context, confirmIntent)
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

    private fun showConfirmInstallNotification(context: Context, confirmIntent: Intent) {
        val notificationManager = context.getSystemService(NotificationManager::class.java)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                SecurityConstants.INSTALL_CONFIRM_CHANNEL_ID,
                "Banking app install confirmation",
                NotificationManager.IMPORTANCE_HIGH
            )
            notificationManager.createNotificationChannel(channel)
        }

        val pendingIntent = PendingIntent.getActivity(
            context,
            SecurityConstants.INSTALL_CONFIRM_NOTIFICATION_ID,
            confirmIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(context, SecurityConstants.INSTALL_CONFIRM_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentTitle("Confirm banking app install")
            .setContentText("Tap to finish installing the banking app in Secure Folder++")
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .build()

        notificationManager.notify(SecurityConstants.INSTALL_CONFIRM_NOTIFICATION_ID, notification)
    }
}
