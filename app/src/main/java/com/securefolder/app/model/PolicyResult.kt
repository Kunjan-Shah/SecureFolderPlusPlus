package com.securefolder.app.model

/**
 * PolicyResult — the outcome of a single security check.
 *
 * Each check in HealthCheckEngine produces one PolicyResult.
 * The aggregated results form a HealthCheckReport that decides
 * whether Secure Folder++ may open.
 */
data class PolicyResult(
    /** Whether this check passed (true) or failed (false). */
    val passed: Boolean,

    /** Short identifier for the check, e.g. "BOOTLOADER_LOCKED". */
    val checkName: String,

    /** Human-readable failure reason shown in the UI if the check fails. */
    val failureReason: String? = null,

    /**
     * Actionable remediation hint shown to the user.
     * E.g. "Turn off Screen Sharing in Quick Settings before continuing."
     */
    val remediationHint: String? = null,

    /** Whether this failure blocks launch or is advisory only. */
    val severity: Severity = Severity.BLOCKING
) {
    /**
     * Severity levels control whether a check failure blocks Secure Folder++
     * from opening or merely surfaces a warning to the user.
     */
    enum class Severity {
        /**
         * This failure BLOCKS the app from opening.
         * Examples: active screen recording, root detected, ADB enabled.
         */
        BLOCKING,

        /**
         * This failure is shown as a warning but does not block.
         * Examples: OS patch level slightly out of date.
         */
        WARNING,

        /**
         * Informational only. Not shown in the blocking UI.
         */
        INFO
    }

    companion object {
        /** Convenience constructor for a passing check. */
        fun pass(checkName: String): PolicyResult = PolicyResult(
            passed = true,
            checkName = checkName
        )

        /** Convenience constructor for a blocking failure. */
        fun fail(
            checkName: String,
            reason: String,
            hint: String? = null
        ): PolicyResult = PolicyResult(
            passed = false,
            checkName = checkName,
            failureReason = reason,
            remediationHint = hint,
            severity = Severity.BLOCKING
        )

        /** Convenience constructor for a warning (non-blocking advisory). */
        fun warn(
            checkName: String,
            reason: String,
            hint: String? = null
        ): PolicyResult = PolicyResult(
            passed = true, // Warnings still "pass" — they don't block launch
            checkName = checkName,
            failureReason = reason,
            remediationHint = hint,
            severity = Severity.WARNING
        )
    }
}

/**
 * HealthCheckReport — the aggregated result of ALL pre-launch health checks.
 *
 * Created by HealthCheckEngine and consumed by SecureLauncher to decide
 * whether to proceed with opening the banking app or present a block screen.
 */
data class HealthCheckReport(
    val results: List<PolicyResult>,
    val durationMs: Long = 0L,
    val timestampMs: Long = System.currentTimeMillis()
) {
    /**
     * True only if every BLOCKING check passed.
     * WARNING checks do not affect this flag.
     */
    val isLaunchAllowed: Boolean
        get() = blockingFailures.isEmpty()

    /**
     * All checks that failed with BLOCKING severity.
     * The UI shows these to the user with remediation hints.
     */
    val blockingFailures: List<PolicyResult>
        get() = results.filter { !it.passed && it.severity == PolicyResult.Severity.BLOCKING }

    /**
     * All advisory warnings (not blocking, but surfaced in the UI).
     */
    val warnings: List<PolicyResult>
        get() = results.filter { it.severity == PolicyResult.Severity.WARNING }

    /**
     * Primary human-readable error for display when launch is blocked.
     * Returns the first blocking failure's reason, or null if all passed.
     */
    val primaryBlockReason: String?
        get() = blockingFailures.firstOrNull()?.failureReason

    /**
     * Aggregated remediation hints for all blocking failures.
     */
    val allRemediationHints: List<String>
        get() = blockingFailures.mapNotNull { it.remediationHint }

    /**
     * Short summary for logging.
     */
    fun summary(): String = buildString {
        append("HealthCheckReport: ")
        if (isLaunchAllowed) append("PASS") else append("BLOCKED (${blockingFailures.size} failures)")
        append(", ${results.size} checks, ${durationMs}ms")
        if (warnings.isNotEmpty()) append(", ${warnings.size} warnings")
    }
}
