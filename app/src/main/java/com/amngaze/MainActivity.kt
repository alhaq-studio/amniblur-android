package com.amngaze

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge

class MainActivity : ComponentActivity() {
  override fun onCreate(savedInstanceState: Bundle?) {
    // Register global crash logger
    Thread.setDefaultUncaughtExceptionHandler(CrashLogger(applicationContext))

    super.onCreate(savedInstanceState)
    enableEdgeToEdge()
    setContent {
      AmnGazeApp()
    }
  }
}

