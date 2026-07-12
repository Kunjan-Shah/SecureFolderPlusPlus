package com.securefolderplusplus.app.security

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.widget.Toast
import timber.log.Timber

class InstallResultReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, -1)
        val message = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)

        when (status) {
            PackageInstaller.STATUS_SUCCESS -> {
                Timber.i("InstallResultReceiver: banking app installed successfully.")
                Toast.makeText(
                    context,
                    "✅ Banking app installed in secure profile!",
                    Toast.LENGTH_LONG
                ).show()
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
                Toast.makeText(
                    context,
                    "❌ Install failed: $message",
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }
}