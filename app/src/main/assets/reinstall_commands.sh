# 1. Find the current work profile's user ID
adb shell pm list users


# 2. Release Profile Owner admin status (replace <ID> with the number from step 1)
adb shell dpm remove-active-admin --user <ID> com.securefolderplusplus.app/.dpc.SecureFolderAdminReceiver
# 3. Delete the entire work profile (wipes all its data, including the admin registration)
adb shell pm remove-user <ID>
# 4. Uninstall the app from the personal profile too
adb uninstall com.securefolderplusplus.app
# 5. Rebuild (from the project root)
gradlew.bat assembleDebug
# 6. Reinstall the fresh APK
adb install "app\build\outputs\apk\debug\app-debug.apk"