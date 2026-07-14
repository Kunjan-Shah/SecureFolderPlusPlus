package com.securefolderplusplus.app.ui

import android.Manifest
import android.app.Activity
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.CrossProfileApps
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.os.UserManager
import android.view.View
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.securefolderplusplus.app.R
import com.securefolderplusplus.app.SecurityConstants
import com.securefolderplusplus.app.model.PolicyResult
import com.securefolderplusplus.app.profile.ProfileManager
import com.securefolderplusplus.app.security.BankingAppInstaller
import com.securefolderplusplus.app.security.HealthCheckEngine
import com.securefolderplusplus.app.security.SecureLauncher
import com.securefolderplusplus.app.security.SecureMonitorService

class MainActivity : AppCompatActivity() {

    private val REQUEST_CODE_PROVISION  = 1001
    private val REQUEST_CODE_PICK_APK   = 1002
    private val REQUEST_CODE_NOTIFICATION_PERMISSION = 1004

    private lateinit var profileManager:    ProfileManager
    private lateinit var healthCheckEngine: HealthCheckEngine
    private lateinit var secureLauncher:    SecureLauncher
    private lateinit var bankingAppInstaller: BankingAppInstaller

    private lateinit var tvStatus:          TextView
    private lateinit var tvError:           TextView
    private lateinit var tvHealthResults:   TextView
    private lateinit var btnSetup:          Button
    private lateinit var btnHealthCheck:    Button
    private lateinit var btnLaunch:         Button
    private lateinit var btnInstallBanking: Button
    private lateinit var btnOpenWorkProfile: Button
    private lateinit var btnConfirmInstall: Button

    private val statusRefreshHandler = Handler(Looper.getMainLooper())
    private val statusRefreshRunnable = object : Runnable {
        override fun run() {
            refreshWorkProfileStatus()
            val prefs = getSharedPreferences(SecurityConstants.PREF_NAME, Context.MODE_PRIVATE)
            val copyStatus = prefs.getString(SecurityConstants.PREF_BANKING_COPY_STATUS, null)
            val installStatus = prefs.getString(SecurityConstants.PREF_BANKING_INSTALL_STATUS, null)
            // Keep polling only while the copy or an install is in flight or
            // awaiting a confirmation tap — a terminal state (success/failed/
            // cert_mismatch) or "never run" won't change again on its own, so
            // there's nothing left to watch for.
            if (copyStatus == SecurityConstants.BANKING_COPY_STATUS_COPYING ||
                installStatus == SecurityConstants.BANKING_INSTALL_STATUS_INSTALLING ||
                installStatus == SecurityConstants.BANKING_INSTALL_STATUS_NEEDS_CONFIRMATION
            ) {
                statusRefreshHandler.postDelayed(this, 1000)
            }
        }
    }

    private val violationReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == SecureMonitorService.ACTION_SECURITY_VIOLATION) {
                val reason = intent.getStringExtra(SecureMonitorService.EXTRA_REASON)
                    ?: "Unknown security violation."
                tvStatus.text = "🚫 Session terminated."
                showError("Security violation:\n$reason")
                btnLaunch.visibility      = View.GONE
                btnHealthCheck.visibility = View.VISIBLE
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        profileManager      = ProfileManager(this)
        healthCheckEngine   = HealthCheckEngine(this)
        secureLauncher      = SecureLauncher(this)
        bankingAppInstaller = BankingAppInstaller(this)

        tvStatus          = findViewById(R.id.tvStatus)
        tvError           = findViewById(R.id.tvError)
        tvHealthResults   = findViewById(R.id.tvHealthResults)
        btnSetup          = findViewById(R.id.btnSetup)
        btnHealthCheck    = findViewById(R.id.btnHealthCheck)
        btnLaunch         = findViewById(R.id.btnLaunch)
        btnInstallBanking = findViewById(R.id.btnInstallBanking)
        btnOpenWorkProfile = findViewById(R.id.btnOpenWorkProfile)
        btnConfirmInstall = findViewById(R.id.btnConfirmInstall)

        ensureNotificationPermission()

        if (isWorkProfile()) {
            setupWorkProfileUI()
        } else {
            setupPersonalProfileUI()
        }
    }

    // ── Detect which profile we are running in ────────────────────────────────

    private fun isWorkProfile(): Boolean {
        val um = getSystemService(UserManager::class.java)
        return um.isManagedProfile
    }

    // ── Notification permission ─────────────────────────────────────────────
    //  Android 13+ requires an explicit runtime grant for POST_NOTIFICATIONS —
    //  declaring it in the manifest alone does nothing. Without this grant,
    //  InstallResultReceiver's "confirm the install" notification silently
    //  never appears (no crash, no error — NotificationManager.notify() just
    //  no-ops), which is why the install looked permanently stuck. This is
    //  granted per-profile, so it must be requested here in both the personal
    //  and work-profile instances of this Activity.

    private fun ensureNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED
            ) {
                ActivityCompat.requestPermissions(
                    this,
                    arrayOf(Manifest.permission.POST_NOTIFICATIONS),
                    REQUEST_CODE_NOTIFICATION_PERMISSION
                )
            }
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  WORK PROFILE UI
    //  This runs when user opens SecureFolder++ with the briefcase icon
    // ─────────────────────────────────────────────────────────────────────────

    private fun setupWorkProfileUI() {
        // Hide personal-profile-only buttons
        btnSetup.visibility          = View.GONE
        btnHealthCheck.visibility    = View.GONE
        btnLaunch.visibility         = View.GONE
        btnOpenWorkProfile.visibility = View.GONE

        btnInstallBanking.visibility = View.VISIBLE
        btnInstallBanking.text = "Pick Banking App APK to Install"
        btnInstallBanking.setOnClickListener { pickApkFile() }
        btnConfirmInstall.setOnClickListener { confirmPendingInstall() }
        // Polling loop is started from onResume(), which always follows onCreate().
    }

    /**
     * Launches the saved PackageInstaller confirmation Intent directly from
     * this button tap — a genuine foreground, user-initiated startActivity()
     * call, so it isn't subject to the background-activity-launch
     * restrictions that can silently block the same call from
     * InstallResultReceiver's BroadcastReceiver context.
     */
    private fun confirmPendingInstall() {
        val prefs = getSharedPreferences(SecurityConstants.PREF_NAME, Context.MODE_PRIVATE)
        val uriString = prefs.getString(SecurityConstants.PREF_BANKING_INSTALL_CONFIRM_INTENT_URI, null)
        if (uriString == null) {
            showError("No pending install confirmation found.")
            return
        }
        try {
            val confirmIntent = Intent.parseUri(uriString, Intent.URI_INTENT_SCHEME)
            confirmIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivity(confirmIntent)
        } catch (e: Exception) {
            showError("Could not open the install confirmation: ${e.message}")
        }
    }

    /**
     * Shows the live status/progress of two separate operations:
     *   1. Copying the bundled APK into Downloads (automatic, via onEnabled()/BootReceiver).
     *   2. Actually installing it (manual, via "Pick Banking App APK to Install").
     */
    private fun refreshWorkProfileStatus() {
        val prefs = getSharedPreferences(SecurityConstants.PREF_NAME, Context.MODE_PRIVATE)

        val copyStatus  = prefs.getString(SecurityConstants.PREF_BANKING_COPY_STATUS, null)
        val copyMessage = prefs.getString(SecurityConstants.PREF_BANKING_COPY_MESSAGE, null)
        val copyPath    = prefs.getString(SecurityConstants.PREF_BANKING_COPY_PATH, null)
        val copyCopied  = prefs.getLong(SecurityConstants.PREF_BANKING_COPY_BYTES_COPIED, 0L)
        val copyTotal   = prefs.getLong(SecurityConstants.PREF_BANKING_COPY_TOTAL_BYTES, 0L)

        val copyLine = when (copyStatus) {
            SecurityConstants.BANKING_COPY_STATUS_COPYING -> {
                val copiedMb = copyCopied / (1024 * 1024)
                if (copyTotal > 0) {
                    val totalMb = copyTotal / (1024 * 1024)
                    val pct = (copyCopied * 100 / copyTotal)
                    "⏳ Copying banking app APK to Downloads: $copiedMb MB / $totalMb MB ($pct%)"
                } else {
                    "⏳ Copying banking app APK to Downloads: $copiedMb MB copied so far..."
                }
            }
            SecurityConstants.BANKING_COPY_STATUS_SUCCESS ->
                "✅ APK copied to: $copyPath\n\n" +
                        "Tap \"Pick Banking App APK to Install\" below, browse to Downloads, " +
                        "and select it to install."
            SecurityConstants.BANKING_COPY_STATUS_FAILED ->
                "❌ Copy to Downloads failed: ${copyMessage ?: "unknown error"}"
            else ->
                "Waiting for the APK to be copied to Downloads..."
        }

        val installStatus  = prefs.getString(SecurityConstants.PREF_BANKING_INSTALL_STATUS, null)
        val installMessage = prefs.getString(SecurityConstants.PREF_BANKING_INSTALL_MESSAGE, null)

        val installLine = when (installStatus) {
            SecurityConstants.BANKING_INSTALL_STATUS_INSTALLING ->
                "\n\n⏳ Installing the picked APK..."
            SecurityConstants.BANKING_INSTALL_STATUS_NEEDS_CONFIRMATION ->
                "\n\n⚠️ Android needs you to confirm this install — check your notifications " +
                        "for \"Confirm banking app install\", or tap \"Confirm Install Now\" below." +
                        (installMessage?.let { "\n\nDebug: $it" } ?: "")
            SecurityConstants.BANKING_INSTALL_STATUS_SUCCESS ->
                "\n\n✅ Banking app installed and certificate verified."
            SecurityConstants.BANKING_INSTALL_STATUS_CERT_MISMATCH ->
                "\n\n⚠️ Banking app installed but FAILED certificate verification — do not use it."
            SecurityConstants.BANKING_INSTALL_STATUS_FAILED ->
                "\n\n❌ Install failed: ${installMessage ?: "unknown error"}"
            else -> ""
        }

        btnConfirmInstall.visibility =
            if (installStatus == SecurityConstants.BANKING_INSTALL_STATUS_NEEDS_CONFIRMATION) View.VISIBLE else View.GONE

        tvStatus.text = "🔧 Work Profile — Secure Folder++ Installer\n\n$copyLine$installLine"
    }

    private fun pickApkFile() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "application/vnd.android.package-archive"
        }
        @Suppress("DEPRECATION")
        startActivityForResult(intent, REQUEST_CODE_PICK_APK)
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  PERSONAL PROFILE UI
    //  This is the main app the user interacts with
    // ─────────────────────────────────────────────────────────────────────────

    private fun setupPersonalProfileUI() {
        btnInstallBanking.visibility = View.GONE
        btnConfirmInstall.visibility = View.GONE
        refreshPersonalUI()

        btnSetup.setOnClickListener          { startProvisioning() }
        btnHealthCheck.setOnClickListener    { runHealthCheck() }
        btnLaunch.setOnClickListener         { launchBankingApp() }
        btnOpenWorkProfile.setOnClickListener { openWorkProfileInstance() }
    }

    private fun refreshPersonalUI() {
        if (profileManager.isProfileReady()) {
            tvStatus.text = "✅ Secure Folder++ is active.\n\n" +
                    "To install the banking app: open the Secure Folder++ " +
                    "app with the 🏢 briefcase icon in your app drawer, or tap " +
                    "\"Open Work Profile Setup\" below if you can't find that icon."
            btnSetup.visibility          = View.GONE
            btnHealthCheck.visibility    = View.VISIBLE
            btnLaunch.visibility         = View.GONE
            btnOpenWorkProfile.visibility =
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) View.VISIBLE else View.GONE
        } else {
            val blocked = profileManager.checkProvisioningBlocked()
            if (blocked != null) {
                tvStatus.text = "⚠️ Cannot set up:"
                showError(blocked.userMessage)
                btnSetup.visibility = View.GONE
            } else {
                tvStatus.text = "Secure Folder++ is not set up yet."
                btnSetup.visibility          = View.VISIBLE
                btnHealthCheck.visibility    = View.GONE
                btnLaunch.visibility         = View.GONE
                btnOpenWorkProfile.visibility = View.GONE
            }
        }
    }

    // ── Cross-profile fallback: open this app's own work-profile instance ──────
    //  Some OEM launchers (e.g. budget Android Go builds) don't surface a
    //  "Work" tab/section in the app drawer, so the cloned work-profile icon
    //  is never visible even though provisioning succeeded. CrossProfileApps
    //  is the public, non-privileged API for "same app installed in both
    //  profiles, launch my other instance" and doesn't depend on launcher UI.

    private fun openWorkProfileInstance() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) {
            showError("Opening the work profile directly requires Android 9 or higher.")
            return
        }
        try {
            val userManager = getSystemService(UserManager::class.java)
            val workHandle = userManager.userProfiles.firstOrNull { it != Process.myUserHandle() }
            if (workHandle == null) {
                showError("Could not find the work profile.")
                return
            }
            val crossProfileApps = getSystemService(CrossProfileApps::class.java)
            crossProfileApps.startMainActivity(ComponentName(this, MainActivity::class.java), workHandle)
        } catch (e: Exception) {
            showError("Could not open the work profile instance: ${e.message}")
        }
    }

    // ── Provisioning ──────────────────────────────────────────────────────────

    private fun startProvisioning() {
        val blocked = profileManager.checkProvisioningBlocked()
        if (blocked != null) { showError(blocked.userMessage); return }
        val intent = profileManager.buildProvisioningIntent()
        if (intent == null) { showError("Could not start setup."); return }
        @Suppress("DEPRECATION")
        startActivityForResult(intent, REQUEST_CODE_PROVISION)
    }

    // ── Health check ──────────────────────────────────────────────────────────

    private fun runHealthCheck() {
        tvStatus.text          = "⏳ Running security checks..."
        tvHealthResults.visibility = View.GONE
        tvError.visibility         = View.GONE
        btnLaunch.visibility       = View.GONE

        Thread {
            val report = healthCheckEngine.runAllChecks()
            runOnUiThread { displayReport(report) }
        }.start()
    }

    private fun displayReport(report: com.securefolderplusplus.app.model.HealthCheckReport) {
        val sb = StringBuilder()
        for (result in report.results) {
            val icon = when {
                result.passed && result.severity == PolicyResult.Severity.WARNING -> "⚠️"
                result.passed -> "✅"
                else          -> "❌"
            }
            sb.appendLine("$icon ${result.checkName}")
            if (!result.passed || result.severity == PolicyResult.Severity.WARNING) {
                result.failureReason?.let  { sb.appendLine("   → $it") }
                result.remediationHint?.let { sb.appendLine("   Fix: $it") }
            }
            sb.appendLine()
        }
        sb.appendLine("Duration: ${report.durationMs}ms")

        tvHealthResults.text       = sb.toString()
        tvHealthResults.visibility = View.VISIBLE

        if (report.isLaunchAllowed) {
            tvStatus.text      = "✅ All checks passed."
            tvError.visibility = View.GONE
            btnLaunch.visibility = View.VISIBLE
        } else {
            tvStatus.text = "🚫 ${report.blockingFailures.size} issue(s) must be fixed."
            showError(report.allRemediationHints.joinToString("\n"))
            btnLaunch.visibility = View.GONE
        }
    }

    // ── Launch banking app ────────────────────────────────────────────────────

    private fun launchBankingApp() {
        tvStatus.text      = "⏳ Launching secure session..."
        tvError.visibility = View.GONE

        Thread {
            val result = secureLauncher.attemptLaunch()
            runOnUiThread {
                when (result) {
                    is SecureLauncher.LaunchResult.Success -> {
                        tvStatus.text        = "✅ Banking app opened in secure profile."
                        btnLaunch.visibility = View.GONE
                    }
                    is SecureLauncher.LaunchResult.Blocked -> {
                        tvStatus.text = "🚫 Launch blocked."
                        displayReport(result.report)
                    }
                    is SecureLauncher.LaunchResult.Error -> {
                        tvStatus.text = "❌ Could not launch."
                        showError(result.message)
                    }
                }
            }
        }.start()
    }

    // ── onActivityResult — handles provisioning + APK picker ─────────────────

    @Suppress("DEPRECATION")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        when (requestCode) {
            REQUEST_CODE_PROVISION -> {
                if (resultCode == Activity.RESULT_OK) {
                    profileManager.markProfileCreated()
                    tvError.visibility = View.GONE
                    refreshPersonalUI()
                } else {
                    showError("Setup was cancelled or failed.")
                }
            }
            REQUEST_CODE_PICK_APK -> {
                if (resultCode == Activity.RESULT_OK) {
                    val uri: Uri? = data?.data
                    if (uri != null) {
                        installPickedApk(uri)
                    } else {
                        showError("No file selected.")
                    }
                }
            }
        }
    }

    private fun installPickedApk(uri: Uri) {
        tvStatus.text      = "⏳ Installing banking app..."
        tvError.visibility = View.GONE

        Thread {
            bankingAppInstaller.installFromUri(uri) { errorMessage ->
                runOnUiThread { showError(errorMessage) }
            }
            runOnUiThread {
                tvStatus.text = "⏳ Installation in progress — watch for a confirmation prompt."
            }
        }.start()
    }

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    override fun onResume() {
        super.onResume()
        if (!isWorkProfile()) {
            val filter = IntentFilter(SecureMonitorService.ACTION_SECURITY_VIOLATION)
            registerReceiver(violationReceiver, filter, RECEIVER_NOT_EXPORTED)
        } else {
            statusRefreshHandler.post(statusRefreshRunnable)
        }
    }

    override fun onPause() {
        super.onPause()
        if (!isWorkProfile()) {
            unregisterReceiver(violationReceiver)
        } else {
            statusRefreshHandler.removeCallbacks(statusRefreshRunnable)
        }
    }

    private fun showError(message: String) {
        tvError.text       = message
        tvError.visibility = View.VISIBLE
    }
}