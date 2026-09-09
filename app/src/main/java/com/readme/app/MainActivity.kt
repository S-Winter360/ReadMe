package com.readme.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.readme.app.ui.ReadMeApp
import com.readme.app.ui.theme.ReadMeTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        com.readme.app.reading.service.ReadMeReadingSessionRuntime.getInstance(applicationContext).setAppForeground(true)

        setContent {
            ReadMeTheme {
                ReadMeApp()
            }
        }
    }

    override fun onResume() {
        super.onResume()
        com.readme.app.reading.service.ReadMeReadingSessionRuntime.getInstance(applicationContext).setAppForeground(true)
    }

    override fun onPause() {
        super.onPause()
        com.readme.app.reading.service.ReadMeReadingSessionRuntime.getInstance(applicationContext).setAppForeground(false)
    }
}
