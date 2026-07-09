# Keep DPC receiver — system must be able to call it by name
-keep class com.securefolder.app.dpc.SecureFolderAdminReceiver { *; }
-keep class com.securefolder.app.dpc.BootReceiver { *; }
-keep class com.securefolder.app.keyboard.SecureKeyboardService { *; }
-keep class com.securefolder.app.security.SecureMonitorService { *; }

# Keep Timber in debug, strip in release
-assumenosideeffects class timber.log.Timber {
    public static *** d(...);
    public static *** v(...);
    public static *** i(...);
}