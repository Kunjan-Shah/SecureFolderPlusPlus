package com.securefolder.app

import android.app.Application
import timber.log.Timber

class SecureFolderApp : Application() {
    override fun onCreate() {
        super.onCreate()
        // Timber logging — strip in release via ProGuard
        if (BuildConfig.DEBUG) {
            Timber.plant(Timber.DebugTree())
        }
    }
}