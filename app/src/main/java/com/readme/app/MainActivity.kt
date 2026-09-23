package com.readme.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.readme.app.diagnostics.ReadMeCrashLogger
import com.readme.app.reading.service.ReadMeReadingService
import com.readme.app.reading.service.ReadMeReadingSessionRuntime
import com.readme.app.ui.ReadMeApp
import com.readme.app.ui.theme.ReadMeTheme

class MainActivity : ComponentActivity() {

    private val sessionRuntime by lazy {
        ReadMeReadingSessionRuntime.getInstance(applicationContext)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ReadMeCrashLogger.currentLifecycleState = "ActivityCreated"
        enableEdgeToEdge()
        sessionRuntime.setAppForeground(true)

        setContent {
            ReadMeTheme {
                ReadMeApp()
            }
        }
    }

    override fun onStart() {
        super.onStart()
        ReadMeCrashLogger.currentLifecycleState = "ActivityStarted"
        sessionRuntime.setAppForeground(true)
        ReadMeReadingService.syncService(applicationContext)
    }

    override fun onResume() {
        super.onResume()
        ReadMeCrashLogger.currentLifecycleState = "ActivityResumed"
        sessionRuntime.setAppForeground(true)
        sessionRuntime.setDocumentPickerActive(false)
    }

    override fun onPause() {
        super.onPause()
        ReadMeCrashLogger.currentLifecycleState = "ActivityPaused"
        if (!sessionRuntime.isDocumentPickerActive.value) {
            sessionRuntime.setAppForeground(false)
        }
    }

    override fun onStop() {
        super.onStop()
        ReadMeCrashLogger.currentLifecycleState = "ActivityStopped"
        if (!sessionRuntime.isDocumentPickerActive.value) {
            sessionRuntime.setAppForeground(false)
            ReadMeReadingService.syncService(applicationContext)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        ReadMeCrashLogger.currentLifecycleState = "ActivityDestroyed"
    }
}
