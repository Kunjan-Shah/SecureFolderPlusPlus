package com.securefolderplusplus.app.ui

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.Bundle
import android.os.UserManager
import android.view.View
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.securefolderplusplus.app.R
import com.securefolderplusplus.app.model.PolicyResult
import com.securefolderplusplus.app.profile.ProfileManager
import com.securefolderplusplus.app.security.BankingAppInstaller
import com.securefolderplusplus.app.security.HealthCheckEngine
import com.securefolderplusplus.app.security.SecureLauncher
import com.securefolderplusplus.app.security.SecureMonitorService

class MainActivity : AppCompatActivity() {

    private val REQUEST_CODE_PROVISION  = 1001
    private val REQUEST_CODE_PICK_APK   = 1002

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

    // ─────────────────────────────────────────────────────────────────────────
    //  WORK PROFILE UI
    //  This runs when user opens SecureFolder++ with the briefcase icon
    // ─────────────────────────────────────────────────────────────────────────

    private fun setupWorkProfileUI() {
        // Hide personal-profile-only buttons
        btnSetup.visibility       = View.GONE
        btnHealthCheck.visibility = View.GONE
        btnLaunch.visibility      = View.GONE

        tvStatus.text = "🔧 Work Profile — Secure Folder++ Installer\n\n" +
                "This is the work profile instance of the app.\n" +
                "Use this to install the banking app into the secure profile."

        btnInstallBanking.visibility = View.VISIBLE
        btnInstallBanking.text = "Pick Banking App APK to Install"
        btnInstallBanking.setOnClickListener { pickApkFile() }
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
        refreshPersonalUI()

        btnSetup.setOnClickListener       { startProvisioning() }
        btnHealthCheck.setOnClickListener { runHealthCheck() }
        btnLaunch.setOnClickListener      { launchBankingApp() }
    }

    private fun refreshPersonalUI() {
        if (profileManager.isProfileReady()) {
            tvStatus.text = "✅ Secure Folder++ is active.\n\n" +
                    "To install the banking app: open the Secure Folder++ " +
                    "app with the 🏢 briefcase icon in your app drawer. " +
                    "That is the work profile version — install from there."
            btnSetup.visibility       = View.GONE
            btnHealthCheck.visibility = View.VISIBLE
            btnLaunch.visibility      = View.GONE
        } else {
            val blocked = profileManager.checkProvisioningBlocked()
            if (blocked != null) {
                tvStatus.text = "⚠️ Cannot set up:"
                showError(blocked.userMessage)
                btnSetup.visibility = View.GONE
            } else {
                tvStatus.text = "Secure Folder++ is not set up yet."
                btnSetup.visibility       = View.VISIBLE
                btnHealthCheck.visibility = View.GONE
                btnLaunch.visibility      = View.GONE
            }
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
        }
    }

    override fun onPause() {
        super.onPause()
        if (!isWorkProfile()) {
            unregisterReceiver(violationReceiver)
        }
    }

    private fun showError(message: String) {
        tvError.text       = message
        tvError.visibility = View.VISIBLE
    }
}