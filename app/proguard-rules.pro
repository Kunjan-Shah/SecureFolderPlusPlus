# Keep DPC receiver — system must be able to call it by name
-keep class com.securefolderplusplus.app.dpc.SecureFolderAdminReceiver { *; }
-keep class com.securefolderplusplus.app.dpc.BootReceiver { *; }
-keep class com.securefolderplusplus.app.keyboard.SecureKeyboardService { *; }
-keep class com.securefolderplusplus.app.security.SecureMonitorService { *; }

# Keep Timber in debug, strip in release
-assumenosideeffects class timber.log.Timber {
    public static *** d(...);
    public static *** v(...);
    public static *** i(...);
}