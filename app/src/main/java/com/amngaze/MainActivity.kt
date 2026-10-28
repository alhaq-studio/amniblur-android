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

    val shortcutPlatformId = intent?.getStringExtra(ShortcutHelper.EXTRA_TARGET_PLATFORM_ID)
    val shortcutPlatformUrl = intent?.getStringExtra(ShortcutHelper.EXTRA_TARGET_PLATFORM_URL)
    val deepLinkUrl = shortcutPlatformUrl ?: intent?.dataString

    setContent {
      AmnGazeApp(
        initialDeepLinkUrl = deepLinkUrl,
        initialDeepLinkPlatformId = shortcutPlatformId
      )
    }
  }
}


