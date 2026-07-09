package com.securefolder.app.security

import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.net.Uri
import android.app.PendingIntent
import timber.log.Timber

class BankingAppInstaller(private val context: Context) {

    fun installFromUri(apkUri: Uri, onError: (String) -> Unit) {
        try {
            val packageInstaller = context.packageManager.packageInstaller
            val params = PackageInstaller.SessionParams(
                PackageInstaller.SessionParams.MODE_FULL_INSTALL
            )

            val sessionId = packageInstaller.createSession(params)
            packageInstaller.openSession(sessionId).use { session ->

                // Write APK bytes into the installer session
                context.contentResolver.openInputStream(apkUri)?.use { input ->
                    session.openWrite("base.apk", 0, -1).use { output ->
                        input.copyTo(output)
                        session.fsync(output)
                    }
                } ?: run {
                    onError("Could not read APK file. Try picking it again.")
                    return
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

            Timber.i("BankingAppInstaller: session committed.")
        } catch (e: Exception) {
            Timber.e(e, "BankingAppInstaller: install failed.")
            onError("Installation failed: ${e.message}")
        }
    }
}