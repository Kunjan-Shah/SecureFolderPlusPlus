package com.securefolderplusplus.app

/**
 * SecurityConstants — the single source of truth for all security-relevant
 * configuration in Secure Folder++.
 *
 * IMPORTANT: Review every constant here before production deployment.
 * Changing any list (trusted services, trusted IMEs, risky packages)
 * requires a signed app update. Design this list conservatively:
 * it is better to allow too little than too much.
 *
 * All constants are compile-time values — no dynamic config from a
 * remote server is permitted, to prevent a compromised backend from
 * weakening the security posture.
 */
object SecurityConstants {

    // ─────────────────────────────────────────────────────────────────────────
    //  Target banking app
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * The ONLY app permitted to be installed and run inside Secure Folder++.
     * Replace with the actual package name of your banking app before build.
     *
     * The SHA-256 certificate fingerprint (colon-separated) is used to verify
     * the installed banking app has not been tampered with or replaced by a
     * lookalike. Obtain this via:
     *   keytool -printcert -jarfile yourbank.apk
     */
//    const val BANKING_APP_PACKAGE = "com.your.banking.app" // ← REPLACE THIS
    const val BANKING_APP_PACKAGE = "com.sbi.lotusintouch" // ← REPLACE THIS

    /**
     * SHA-256 certificate fingerprint of the official banking app APK.
     * Format: "AA:BB:CC:DD:..." (colon-separated hex, uppercase).
     * Used in the health check to ensure the installed banking app is genuine.
     */
//    const val BANKING_APP_CERT_SHA256 = "AA:BB:CC:DD:EE:FF:00:11:22:33:44:55:66:77:88:99" // ← REPLACE THIS
    const val BANKING_APP_CERT_SHA256 = "43:57:76:89:1F:2C:A2:14:E3:ED:16:7C:42:F6:0E:47:D9:6F:A4:9E:0C:9E:A7:6D:BD:99:36:FA:54:F1:5F:42" // ← REPLACE THIS

    /**
     * Filename of the banking app's APK bundled under app/src/main/assets/,
     * silently installed into the work profile right after provisioning
     * completes (see SecureFolderAdminReceiver.onEnabled()).
     *
     * You must place the real APK there yourself, pulled from your own
     * legitimately-installed copy of the app (e.g. `adb shell pm path
     * com.sbi.lotusintouch` then `adb pull`) — never fetched from a
     * third-party APK site. Keep it in sync with BANKING_APP_CERT_SHA256
     * above; a mismatch is treated as a certificate validation failure.
     */
    const val BANKING_APP_BUNDLED_APK_ASSET = "yono_sbi.apk"

    /**
     * Live status of the automatic bundled-APK install, written by
     * SecureFolderAdminReceiver/InstallResultReceiver (running inside the
     * work profile) and read back by MainActivity's work-profile UI, since
     * the install itself is silent and otherwise gives no visible feedback.
     */
    const val PREF_BANKING_INSTALL_STATUS  = "banking_install_status"
    const val PREF_BANKING_INSTALL_MESSAGE = "banking_install_message"

    const val BANKING_INSTALL_STATUS_INSTALLING    = "installing"
    const val BANKING_INSTALL_STATUS_SUCCESS       = "success"
    const val BANKING_INSTALL_STATUS_CERT_MISMATCH = "cert_mismatch"
    const val BANKING_INSTALL_STATUS_FAILED        = "failed"

    // ─────────────────────────────────────────────────────────────────────────
    //  Profile and storage keys
    // ─────────────────────────────────────────────────────────────────────────

    const val PREF_NAME                 = "sfpp_secure_prefs"
    const val PREF_PROFILE_CREATED      = "profile_created"
    const val PREF_PROFILE_USER_ID      = "managed_profile_user_id"
    const val PREF_AUTH_SETUP_COMPLETE  = "auth_setup_complete"
    const val PREF_LAST_HEALTH_CHECK_MS = "last_health_check_ms"

    // ─────────────────────────────────────────────────────────────────────────
    //  Trusted accessibility services (allowlist)
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Only services in this set are permitted to run inside the work profile.
     * DevicePolicyManager.setPermittedAccessibilityServices() enforces this.
     *
     * Format: "package.name/.ServiceClassName"
     *
     * Default: Google TalkBack and Samsung Voice Assistant only.
     * Expand ONLY for system accessibility services your users genuinely need.
     * Do NOT add any third-party accessibility services here.
     */
    val TRUSTED_ACCESSIBILITY_SERVICES: List<String> = listOf(
        // Google TalkBack — system screen reader (blind/low-vision users)
        "com.google.android.marvin.talkback/.TalkBackService",
        // Samsung Voice Assistant (Samsung devices)
        "com.samsung.android.accessibility.universalswitch/.UniversalSwitchService",
        // Samsung Extra Dim (Samsung accessibility)
        "com.samsung.android.accessibility.extradim/.ExtraDimAccessibilityService",
        // Add verified OEM system accessibility services here only.
        // Never add a service you do not control the source code for.
    )

    // ─────────────────────────────────────────────────────────────────────────
    //  Trusted input methods (allowlist)
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Only IMEs in this set are permitted inside the work profile.
     * DevicePolicyManager.setPermittedInputMethods() enforces this.
     *
     * Our own SecureKeyboardService is always the preferred IME.
     * The system keyboards are trusted fallbacks if the secure IME fails.
     *
     * Never include any third-party keyboard in this list.
     */
    val TRUSTED_INPUT_METHODS: List<String> = listOf(
        // Our own isolated secure keyboard — highest priority
        "com.securefolderplusplus.app/.keyboard.SecureKeyboardService",
        // Google Keyboard (Gboard) — verified system keyboard
        "com.google.android.inputmethod.latin/com.android.inputmethod.latin.LatinIME",
        // Samsung Keyboard (Samsung devices) — system keyboard
        "com.samsung.android.stt.ime/.SamsungIME",
        "com.samsung.android.honeyboard/.service.HoneyBoardService",
        // AOSP LatinIME (stock Android on some devices)
        "com.android.inputmethod.latin/.LatinIME",
    )

    // ─────────────────────────────────────────────────────────────────────────
    //  Known risky / blocked packages (blocklist)
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Known remote-control, screen-recording, and overlay-abusing package names.
     *
     * The health check scans for these packages. If any are installed AND active
     * on the device, Secure Folder++ refuses to open.
     *
     * Note: This list is not exhaustive. Determined attackers can repackage
     * under different names. The health check also checks for risky *permissions*
     * (see REMOTE_CONTROL_RISKY_PERMISSIONS) as a complementary signal.
     *
     * Keep this list updated as new remote-control tools emerge.
     */
    val KNOWN_RISKY_PACKAGES: Set<String> = setOf(
        // ── Remote Desktop / Control ──────────────────────────────────────
        "com.teamviewer.host.android",
        "com.teamviewer.teamviewer",
        "com.teamviewer.quicksupport.host",
        "com.anydesk.anydeskandroid",
        "com.anydesk.anydesk",
        "com.realvnc.viewer.android",
        "com.realvnc.androidsdk.demo",
        "org.remotedroid.app",
        "com.rsupport.mobizen.sec",
        "com.rsupport.rs.activity.rsupport.aas2",
        "com.bomgar.android.bomgar",
        "com.logmein.client",
        "net.logmein.rescue",
        "com.splashtop.remote.pad.v2",
        "com.splashtop.streamer",
        "com.google.android.apps.chromecast.app",  // Chrome Remote Desktop
        "com.chrome.dev",
        "com.parallels.client",
        "com.iobit.mobilecare",
        "com.netbus.android",
        "net.optile.android.chrome_remote_desktop",
        "com.airdroid.android",                    // AirDroid
        "com.sand.airdroid",

        // ── Screen Recorders (active during session = blocked) ────────────
        "com.mi.screenrecorder",
        "com.hecorat.screenrecorder.free",
        "com.hecorat.screenrecorder.pro",
        "com.kimcy929.screenrecorder",
        "com.duapps.recorder",
        "com.ilos.android.screensharing",
        "com.nll.screenrecorder",
        "com.mobizen.miui.screenrecord",
        "com.sec.android.screenrecorder",          // Samsung screen recorder

        // ── Chat-head / Overlay abusers ───────────────────────────────────
        // Note: we block at the policy level too (DISALLOW_CREATE_WINDOWS),
        // but detecting these upfront lets us show a clear user message.
        "com.facebook.orca",                       // FB Messenger chat heads
        "com.facebook.katana",                     // Facebook
    )

    /**
     * Permissions whose presence on a non-system, non-whitelisted app signals
     * remote-control or screen-capture risk. The health check inspects all
     * installed apps for these grants.
     */
    val REMOTE_CONTROL_RISKY_PERMISSIONS: Set<String> = setOf(
        "android.permission.SYSTEM_ALERT_WINDOW",          // Overlay
        "android.permission.CAPTURE_VIDEO_OUTPUT",         // Screen capture (system)
        "android.permission.CAPTURE_SECURE_VIDEO_OUTPUT",  // Secure surface capture
        "android.permission.READ_FRAME_BUFFER",            // Frame buffer read
        "android.permission.INJECT_EVENTS",                // Input injection
    )

    // ─────────────────────────────────────────────────────────────────────────
    //  Health check thresholds
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Minimum acceptable Android security patch level.
     * Devices more than ~6 months behind are considered elevated risk.
     * Adjust based on your organisation's patch policy.
     */
    const val MIN_PATCH_LEVEL_YEAR  = 2024
    const val MIN_PATCH_LEVEL_MONTH = 1   // January 2024 minimum

    /** Maximum number of failed profile unlock attempts before auto-wipe. */
    const val MAX_FAILED_UNLOCK_ATTEMPTS = 10

    // ─────────────────────────────────────────────────────────────────────────
    //  Runtime monitoring
    // ─────────────────────────────────────────────────────────────────────────

    /** How often the SecureMonitorService polls for security violations (ms). */
    const val MONITOR_POLL_INTERVAL_MS = 2_000L

    /** Notification channel ID for the foreground monitor notification. */
    const val MONITOR_CHANNEL_ID = "sfpp_secure_monitor"

    /** Persistent notification ID for the foreground monitor. */
    const val MONITOR_NOTIFICATION_ID = 9001

    // ─────────────────────────────────────────────────────────────────────────
    //  Notification redaction
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Text shown in the personal profile's notification shade for any
     * notification originating from the work profile.
     * Must contain NO financial or identity-related data.
     */
    const val REDACTED_NOTIFICATION_TITLE   = "Secure banking notification"
    const val REDACTED_NOTIFICATION_TEXT    = "Unlock Secure Folder++ to view"
    const val REDACTED_NOTIFICATION_TICKER  = "Secure notification"
}
