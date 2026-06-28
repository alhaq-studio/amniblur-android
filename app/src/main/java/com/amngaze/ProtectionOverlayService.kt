package com.amngaze

import android.app.*
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.graphics.RectF
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.*
import android.util.DisplayMetrics
import android.util.Log
import android.view.WindowManager
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.nio.ByteBuffer

/**
 * Android Foreground Service managing MediaProjection, downscaled frame acquisition,
 * async LiteRT content analysis, and live WindowManager overlays.
 */
class ProtectionOverlayService : Service() {

    companion object {
        const val ACTION_START = "com.amngaze.action.START_DETECTION"
        const val ACTION_STOP = "com.amngaze.action.STOP_DETECTION"
        const val EXTRA_RESULT_CODE = "extra_result_code"
        const val EXTRA_DATA_INTENT = "extra_data_intent"
        private const val NOTIFICATION_ID = 4224
        private const val CHANNEL_ID = "amn_gaze_protection"

        private val _isRunning = MutableStateFlow(false)
        val isRunning: StateFlow<Boolean> = _isRunning
    }

    private val serviceJob = SupervisorJob()
    private val serviceScope = CoroutineScope(Dispatchers.Main + serviceJob)

    private var mediaProjectionManager: MediaProjectionManager? = null
    private var mediaProjection: MediaProjection? = null
    private var mediaProjectionCallback: MediaProjection.Callback? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private var handlerThread: HandlerThread? = null
    private var backgroundHandler: Handler? = null

    private lateinit var moderationEngine: ContentModerationEngine
    private lateinit var overlayManager: BlurOverlayManager

    // Screen dimensions
    private var targetWidth = 480
    private var targetHeight = 854
    private var screenWidth = 1080
    private var screenHeight = 1920
    private var screenDensity = 320

    private var processedFrameCount = 0

    @Volatile
    private var isAnalyzing = false

    override fun onCreate() {
        super.onCreate()
        _isRunning.value = true
        Log.i("ProtectionOverlayService", "Amn Gaze background orchestrator created.")
        moderationEngine = ContentModerationEngine(this)
        overlayManager = BlurOverlayManager(this)
        mediaProjectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager

        // Setup background thread for downsampling loop
        handlerThread = HandlerThread("AmnGazeFrameThread", Process.THREAD_PRIORITY_BACKGROUND).apply {
            start()
            backgroundHandler = Handler(looper)
        }


    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent == null) {
            stopSelf()
            return START_NOT_STICKY
        }

        when (intent.action) {
            ACTION_START -> {
                val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, Activity.RESULT_CANCELED)
                val dataIntent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableExtra(EXTRA_DATA_INTENT, Intent::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra(EXTRA_DATA_INTENT)
                }

                if (resultCode == Activity.RESULT_OK && dataIntent != null) {
                    createNotificationChannel()
                    startForegroundNotification()
                    startScreenCapture(resultCode, dataIntent)
                } else {
                    val errMsg = "Invalid MediaProjection credentials: resultCode=$resultCode, dataIntentIsNull=${dataIntent == null}"
                    Log.e("ProtectionOverlayService", errMsg)
                    ErrorReportManager.getInstance(this).logNonFatalError("ProtectionOverlayService", errMsg)
                    stopSelf()
                }
            }
            ACTION_STOP -> {
                Log.w("ProtectionOverlayService", "Stop command request received.")
                stopSelf()
            }
        }
        return START_NOT_STICKY
    }

    private fun startForegroundNotification() {
        // Build direct stop action intent
        val stopIntent = Intent(this, ProtectionOverlayService::class.java).apply {
            action = ACTION_STOP
        }
        val stopPendingIntent = PendingIntent.getService(
            this, 0, stopIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("AmnGaze Active Protection")
            .setContentText("Continuous AI-Powered Gaze Protection is active screen-wide.")
            .setSmallIcon(android.R.drawable.ic_menu_compass) // Standard system fallback icon
            .setOngoing(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "STOP PROTECTION", stopPendingIntent)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID, 
                notification, 
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun startScreenCapture(resultCode: Int, data: Intent) {
        // Collect exact screen dimensions
        val windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
            val windowMetrics = windowManager.currentWindowMetrics
            val bounds = windowMetrics.bounds
            screenWidth = bounds.width()
            screenHeight = bounds.height()
            screenDensity = resources.configuration.densityDpi
        } else {
            val metrics = DisplayMetrics()
            @Suppress("DEPRECATION")
            windowManager.defaultDisplay.getRealMetrics(metrics)
            screenWidth = metrics.widthPixels
            screenHeight = metrics.heightPixels
            screenDensity = metrics.densityDpi
        }

        // Optimize: Downscale stream source itself to 480-width to avoid high CPU usage
        targetWidth = 480
        targetHeight = (screenHeight * (480f / screenWidth)).toInt()

        try {
            mediaProjection = mediaProjectionManager?.getMediaProjection(resultCode, data)
            if (mediaProjection == null) {
                val errMsg = "Failed to retrieve MediaProjection token."
                Log.e("ProtectionOverlayService", errMsg)
                ErrorReportManager.getInstance(this).logNonFatalError("ProtectionOverlayService", errMsg)
                stopSelf()
                return
            }

            val callback = object : MediaProjection.Callback() {
                override fun onStop() {
                    Log.w("ProtectionOverlayService", "MediaProjection stopped by system.")
                    stopSelf()
                }
            }
            mediaProjectionCallback = callback
            mediaProjection?.registerCallback(callback, backgroundHandler)

            // RGBA_8888 is supported by virtual display streams
            imageReader = ImageReader.newInstance(targetWidth, targetHeight, PixelFormat.RGBA_8888, 2)
            
            virtualDisplay = mediaProjection?.createVirtualDisplay(
                "AmnGazeDisplayStream",
                targetWidth,
                targetHeight,
                screenDensity,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                imageReader?.surface,
                null,
                backgroundHandler
            )

            // Setup image feed loop
            imageReader?.setOnImageAvailableListener({ reader ->
                val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

                if (isAnalyzing) {
                    // Early exit if we are already analyzing a frame to prevent native TFLite concurrency SIGSEGVs
                    val rawImage = reader.acquireLatestImage()
                    rawImage?.close()
                    return@setOnImageAvailableListener
                }
                var rawImage: android.media.Image? = null
                try {
                    rawImage = reader.acquireLatestImage() ?: return@setOnImageAvailableListener
                    
                    // Throttling step: Do not run ML over every frame. Target ~15fps (every 4th frame capture)
                    processedFrameCount++
                    if (processedFrameCount % 4 != 0) {
                        rawImage.close()
                        rawImage = null
                        return@setOnImageAvailableListener
                    }

                    isAnalyzing = true

                    // Read current configurations dynamically to support live settings changes
                    val targetMode = BlurTargetMode.entries.getOrElse(
                        prefs.getInt(PREFS_KEY_BLUR_TARGET_MODE, BlurTargetMode.BODY_REGION.ordinal)
                    ) { BlurTargetMode.BODY_REGION }
                    val sensitivity = prefs.getFloat(PREFS_KEY_BLUR_SENSITIVITY, 0.5f)
                    val censorStyle = BlurCensorStyle.entries.getOrElse(
                        prefs.getInt(PREFS_KEY_BLUR_CENSOR_STYLE, BlurCensorStyle.MOSAIC.ordinal)
                    ) { BlurCensorStyle.MOSAIC }
                    val showBadge = prefs.getBoolean(PREFS_KEY_BLUR_SHOW_BADGE, true)
                    val showBorder = prefs.getBoolean(PREFS_KEY_BLUR_SHOW_BORDER, true)
                    val modelType = ModerationModelType.entries.getOrElse(
                        prefs.getInt(PREFS_KEY_MODEL_TYPE, ModerationModelType.DEFAULT_NSFW.ordinal)
                    ) { ModerationModelType.DEFAULT_NSFW }

                    // Extract color plane details
                    val planes = rawImage.planes
                    val buffer = planes[0].buffer
                    val pixelStride = planes[0].pixelStride
                    val rowStride = planes[0].rowStride
                    val rowPadding = rowStride - pixelStride * targetWidth

                    // Copy color buffer into local fast bitmap
                    val bitmapWidth = targetWidth + rowPadding / pixelStride
                    val bitmap = Bitmap.createBitmap(bitmapWidth, targetHeight, Bitmap.Config.ARGB_8888)
                    
                    val neededBytes = bitmapWidth * targetHeight * 4
                    val remainingBytes = buffer.remaining()
                    if (remainingBytes >= neededBytes) {
                        bitmap.copyPixelsFromBuffer(buffer)
                    } else {
                        // Safety copy: pad buffer if it's missing trailing bytes from the final row (highly common)
                        val cleanBuffer = java.nio.ByteBuffer.allocate(neededBytes)
                        cleanBuffer.order(java.nio.ByteOrder.nativeOrder())
                        val bytes = ByteArray(remainingBytes)
                        buffer.get(bytes)
                        cleanBuffer.put(bytes)
                        cleanBuffer.rewind()
                        bitmap.copyPixelsFromBuffer(cleanBuffer)
                    }
                    
                    rawImage.close() // Close ASAP to avoid VirtualDisplay blocking drops
                    rawImage = null

                    // Execute model evaluation over background coroutine thread pool
                    serviceScope.launch(Dispatchers.Default) {
                        try {
                            val (maxScore, boxes) = moderationEngine.detectExplicitContent(
                                bitmap,
                                targetMode = targetMode,
                                sensitivity = sensitivity,
                                modelType = modelType
                            )
                            bitmap.recycle() // Release memory allocation immediately

                            // Map bounding box results back to original high-res display bounds
                            val mappedBoxes = boxes.map { rect ->
                                RectF(
                                    (rect.left / targetWidth) * screenWidth,
                                    (rect.top / targetHeight) * screenHeight,
                                    (rect.right / targetWidth) * screenWidth,
                                    (rect.bottom / targetHeight) * screenHeight
                                )
                            }

                            // Check for hard block mode
                            val hardBlockEnabled = prefs.getBoolean(PREFS_KEY_HARD_BLOCK_ENABLED, false)
                            val hardBlockThreshold = prefs.getFloat(PREFS_KEY_HARD_BLOCK_THRESHOLD, 0.85f)
                            val triggerHardBlock = hardBlockEnabled && maxScore >= hardBlockThreshold && boxes.isNotEmpty()

                            // Post coordinates to main UI thread to refresh window managers shapes
                            withContext(Dispatchers.Main) {
                                overlayManager.updateConfig(censorStyle, showBadge, showBorder, hardBlock = triggerHardBlock)
                                if (triggerHardBlock) {
                                    // Hard Block overrides regions: cover full screen
                                    overlayManager.updateRegions(listOf(RectF(0f, 0f, screenWidth.toFloat(), screenHeight.toFloat())))
                                    
                                    // Fire Home Intent to redirect user out of the offensive app
                                    val homeIntent = Intent(Intent.ACTION_MAIN).apply {
                                        addCategory(Intent.CATEGORY_HOME)
                                        flags = Intent.FLAG_ACTIVITY_NEW_TASK
                                    }
                                    startActivity(homeIntent)
                                } else if (mappedBoxes.isNotEmpty()) {
                                    overlayManager.updateRegions(mappedBoxes)
                                } else {
                                    overlayManager.hide()
                                }
                            }
                        } finally {
                            isAnalyzing = false
                        }
                    }
                } catch (e: Exception) {
                    isAnalyzing = false
                    Log.e("ProtectionOverlayService", "Frame listener processing error: ", e)
                    ErrorReportManager.getInstance(this@ProtectionOverlayService).logNonFatalError("ProtectionOverlayService", "Frame listener processing error", e)
                } finally {
                    rawImage?.close()
                }
            }, backgroundHandler)

            overlayManager.show()
            Log.i("ProtectionOverlayService", "Amn Gaze screen projection stream started successfully.")
        } catch (e: Exception) {
            Log.e("ProtectionOverlayService", "Failed to start screen capture loop.", e)
            ErrorReportManager.getInstance(this).logNonFatalError("ProtectionOverlayService", "Failed to start screen capture loop", e)
            stopSelf()
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "AmnGaze AI Protection State",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Shows real-time OS-wide safety protection notifications."
            }
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(channel)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        Log.w("ProtectionOverlayService", "Tearing down background orchestrator and releasing media streams.")
        

        serviceJob.cancel() // Stop all active coroutines immediately
        
        virtualDisplay?.release()
        virtualDisplay = null

        imageReader?.close()
        imageReader = null

        mediaProjectionCallback?.let {
            try {
                mediaProjection?.unregisterCallback(it)
            } catch (e: Exception) {
                Log.e("ProtectionOverlayService", "Failed to unregister MediaProjection callback.", e)
            }
        }
        mediaProjectionCallback = null

        mediaProjection?.stop()
        mediaProjection = null

        overlayManager.hide()
        moderationEngine.close()

        handlerThread?.quitSafely()
        handlerThread = null

        _isRunning.value = false
        super.onDestroy()
    }
}
