package com.securefolderplusplus.app.security

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.securefolderplusplus.app.SecurityConstants
import kotlinx.coroutines.*
import timber.log.Timber

class SecureMonitorService : Service() {

    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private lateinit var healthCheckEngine: HealthCheckEngine

    override fun onCreate() {
        super.onCreate()
        healthCheckEngine = HealthCheckEngine(this)
        createNotificationChannel()
        startForeground(
            SecurityConstants.MONITOR_NOTIFICATION_ID,
            buildNotification()
        )
        startMonitoringLoop()
        Timber.i("SecureMonitorService: started.")
    }

    // ── Poll every 2 seconds for violations ──────────────────────────────────

    private fun startMonitoringLoop() {
        serviceScope.launch {
            while (isActive) {
                delay(SecurityConstants.MONITOR_POLL_INTERVAL_MS)
                runCatching { checkForViolations() }
                    .onFailure { Timber.w(it, "Monitor poll error.") }
            }
        }
    }

    private fun checkForViolations() {
        val report = healthCheckEngine.runAllChecks()
        if (!report.isLaunchAllowed) {
            Timber.w("SecureMonitorService: VIOLATION — ${report.primaryBlockReason}")

            // Notify MainActivity so it can update its UI and show the reason
            val broadcast = Intent(ACTION_SECURITY_VIOLATION).apply {
                putExtra(EXTRA_REASON, report.primaryBlockReason ?: "Security violation detected.")
                setPackage(packageName)
            }
            sendBroadcast(broadcast)

            // Stop monitoring — session is terminated
            stopSelf()
        }
    }

    // ── Foreground notification ───────────────────────────────────────────────

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            SecurityConstants.MONITOR_CHANNEL_ID,
            "Secure Session",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Running while your banking session is active"
            setShowBadge(false)
        }
        getSystemService(NotificationManager::class.java)
            .createNotificationChannel(channel)
    }

    private fun buildNotification() =
        NotificationCompat.Builder(this, SecurityConstants.MONITOR_CHANNEL_ID)
            .setContentTitle("Secure Folder++ Active")
            .setContentText("Your banking session is protected")
            .setSmallIcon(android.R.drawable.ic_lock_lock)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        serviceScope.cancel()
        Timber.i("SecureMonitorService: stopped.")
        super.onDestroy()
    }

    companion object {
        const val ACTION_SECURITY_VIOLATION = "com.securefolderplusplus.app.SECURITY_VIOLATION"
        const val EXTRA_REASON = "reason"
    }
}