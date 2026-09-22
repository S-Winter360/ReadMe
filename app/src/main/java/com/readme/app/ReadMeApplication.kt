package com.readme.app

import android.app.Application
import com.readme.app.diagnostics.ReadMeCrashLogger

class ReadMeApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        ReadMeCrashLogger.install(this)
    }
}
