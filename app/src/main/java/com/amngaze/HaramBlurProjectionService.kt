package com.amngaze

import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import androidx.core.app.NotificationCompat
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Module 2 Foreground Service (`foregroundServiceType="mediaProjection"`) powering
 * HaramBlur-style Real-Time Regional Blurring (`VirtualDisplay` + `ImageReader` at `480p`,
 * throttled to `6–10 fps` max, synchronized with [HaramBlurRuntimeBus] and [AntiFlickerRegionTracker]).
 */
class HaramBlurProjectionService : Service() {

    companion object {
        private const val TAG = "HaramBlurService"
        private const val NOTIFICATION_ID = 4202
        private const val CHANNEL_ID = "amngaze_haramblur_channel"

        const val ACTION_START_PROJECTION = "com.amngaze.action.START_HARAMBLUR"
        const val ACTION_STOP_PROJECTION = "com.amngaze.action.STOP_HARAMBLUR"
        const val EXTRA_RESULT_CODE = "extra_result_code"
        const val EXTRA_RESULT_DATA = "extra_result_data"

        private const val CAPTURE_WIDTH = 360
        private const val CAPTURE_HEIGHT = 640
        private const val MIN_FRAME_INTERVAL_MS = 85L // ~12 FPS responsive real-time cadence
        private const val SCROLL_FRAME_INTERVAL_MS = 50L

        fun startProjection(context: Context, resultCode: Int, data: Intent) {
            val intent = Intent(context, HaramBlurProjectionService::class.java).apply {
                action = ACTION_START_PROJECTION
                putExtra(EXTRA_RESULT_CODE, resultCode)
                putExtra(EXTRA_RESULT_DATA, data)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stopProjection(context: Context) {
            val intent = Intent(context, HaramBlurProjectionService::class.java).apply {
                action = ACTION_STOP_PROJECTION
            }
            context.startService(intent)
        }
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private var workerThread: HandlerThread? = null
    private var workerHandler: Handler? = null

    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null

    private lateinit var moderationEngine: ContentModerationEngine
    private lateinit var regionalWindowManager: RegionalBlurWindowManager
    private val regionTracker = AntiFlickerRegionTracker()

    private val isFrameProcessing = AtomicBoolean(false)
    private var lastFrameProcessedTimeMs: Long = 0L
    private var previousExteriorSig: FloatArray? = null
    private var lastUnoccludedFrame: Bitmap? = null
    private val patchRestoreCanvas = android.graphics.Canvas()
    private val patchRect = android.graphics.Rect()

    override fun onCreate() {
        super.onCreate()
        moderationEngine = ContentModerationEngine(this)
        regionalWindowManager = RegionalBlurWindowManager(this)
        workerThread = HandlerThread("HaramBlurFrameWorker").apply { start() }
        workerHandler = Handler(workerThread!!.looper)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP_PROJECTION -> {
                stopSelfCleanly()
                return START_NOT_STICKY
            }
            ACTION_START_PROJECTION -> {
                val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, Activity.RESULT_CANCELED)
                val resultData: Intent? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableExtra(EXTRA_RESULT_DATA, Intent::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra(EXTRA_RESULT_DATA)
                }
                if (resultCode == Activity.RESULT_OK && resultData != null) {
                    startForegroundWithProjectionType()
                    initializeMediaProjection(resultCode, resultData)
                } else {
                    stopSelfCleanly()
                }
            }
        }
        return START_NOT_STICKY
    }

    private fun startForegroundWithProjectionType() {
        createNotificationChannel()
        val notification = buildForegroundNotification()
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

    private fun initializeMediaProjection(resultCode: Int, resultData: Intent) {
        releaseProjectionResources()
        try {
            val mgr = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            val projection = mgr.getMediaProjection(resultCode, resultData) ?: run {
                stopSelfCleanly()
                return
            }
            projection.registerCallback(
                object : MediaProjection.Callback() {
                    override fun onStop() {
                        mainHandler.post { stopSelfCleanly() }
                    }
                },
                mainHandler
            )

            val densityDpi = resources.displayMetrics.densityDpi
            val reader = ImageReader.newInstance(
                CAPTURE_WIDTH,
                CAPTURE_HEIGHT,
                PixelFormat.RGBA_8888,
                2
            )
            reader.setOnImageAvailableListener(
                { imgReader -> processLatestFrame(imgReader) },
                workerHandler
            )

            val vDisplay = projection.createVirtualDisplay(
                "AmnGazeHaramBlurDisplay",
                CAPTURE_WIDTH,
                CAPTURE_HEIGHT,
                densityDpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                reader.surface,
                null,
                workerHandler
            )

            mediaProjection = projection
            imageReader = reader
            virtualDisplay = vDisplay
            regionalWindowManager.attachIfPermitted()
            HaramBlurRuntimeBus.setProjectionRunning(true)

            getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .putBoolean(PREFS_KEY_HARAMBLUR_ENABLED, true)
                .apply()

            Log.i(TAG, "HaramBlur real-time MediaProjection pipeline started at ${CAPTURE_WIDTH}x${CAPTURE_HEIGHT}.")
        } catch (t: Throwable) {
            Log.e(TAG, "Failed to start HaramBlur MediaProjection", t)
            stopSelfCleanly()
        }
    }

    private fun processLatestFrame(reader: ImageReader) {
        val image = try {
            reader.acquireLatestImage()
        } catch (_: Throwable) {
            null
        } ?: return

        val now = SystemClock.elapsedRealtime()
        val scrollOccurred = HaramBlurRuntimeBus.consumeScrollFlag()
        val minInterval = if (scrollOccurred) SCROLL_FRAME_INTERVAL_MS else MIN_FRAME_INTERVAL_MS
        if ((now - lastFrameProcessedTimeMs) < minInterval ||
            !isFrameProcessing.compareAndSet(false, true)
        ) {
            image.close()
            return
        }
        lastFrameProcessedTimeMs = now

        var frameBitmap: Bitmap? = null
        try {
            val fgPkg = HaramBlurRuntimeBus.activeForegroundPackage.value
            val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val scope = HaramBlurProtectionScope.entries.getOrElse(
                prefs.getInt(PREFS_KEY_HARAMBLUR_SCOPE, HaramBlurProtectionScope.SYSTEM_WIDE.ordinal)
            ) { HaramBlurProtectionScope.SYSTEM_WIDE }

            // Check if foreground package is exempt (Home Launcher, SystemUI, Settings, AmnGaze)
            val isExemptLauncherOrSelf = fgPkg.isNotEmpty() &&
                FastPathUrlAndKeywordScanner.isProtectedLauncherOrSystemPackage(
                    pkg = fgPkg,
                    selfPackageName = packageName
                )
            if (isExemptLauncherOrSelf) {
                regionTracker.clearAll()
                previousExteriorSig = null
                mainHandler.post {
                    HaramBlurRuntimeBus.updateActiveRegions(emptyList())
                    regionalWindowManager.hideAll()
                }
                return
            }

            // If Mode B (Selected Apps Only) is active, pause when outside selected auto-run apps
            if (scope == HaramBlurProtectionScope.SELECTED_APPS_ONLY && fgPkg.isNotEmpty()) {
                val rawAutoRun = prefs.getString(PREFS_KEY_HARAMBLUR_AUTO_RUN_PACKAGES, "") ?: ""
                val autoRunSet = rawAutoRun.split(";").filter { it.isNotBlank() }.toSet()
                if (autoRunSet.isNotEmpty() && fgPkg !in autoRunSet) {
                    regionTracker.clearAll()
                    previousExteriorSig = null
                    mainHandler.post {
                        HaramBlurRuntimeBus.updateActiveRegions(emptyList())
                        regionalWindowManager.hideAll()
                    }
                    return
                }
            }

            val plane = image.planes.firstOrNull() ?: return
            val buffer = plane.buffer
            val pixelStride = plane.pixelStride
            val rowStride = plane.rowStride
            val rowPadding = rowStride - pixelStride * CAPTURE_WIDTH

            val paddedWidth = CAPTURE_WIDTH + (rowPadding / pixelStride).coerceAtLeast(0)
            val rawBitmap = Bitmap.createBitmap(paddedWidth, CAPTURE_HEIGHT, Bitmap.Config.ARGB_8888)
            buffer.rewind()
            rawBitmap.copyPixelsFromBuffer(buffer)
            frameBitmap = if (paddedWidth != CAPTURE_WIDTH) {
                val cropped = Bitmap.createBitmap(rawBitmap, 0, 0, CAPTURE_WIDTH, CAPTURE_HEIGHT)
                rawBitmap.recycle()
                cropped
            } else {
                rawBitmap
            }

            val targetGender = HaramBlurTargetGender.entries.getOrElse(
                prefs.getInt(PREFS_KEY_HARAMBLUR_TARGET_GENDER, HaramBlurTargetGender.FEMALE.ordinal)
            ) { HaramBlurTargetGender.FEMALE }
            val sensitivity = HaramBlurSensitivity.entries.getOrElse(
                prefs.getInt(PREFS_KEY_HARAMBLUR_SENSITIVITY, HaramBlurSensitivity.MEDIUM.ordinal)
            ) { HaramBlurSensitivity.MEDIUM }
            val overlayStyle = HaramBlurOverlayStyle.entries.getOrElse(
                prefs.getInt(PREFS_KEY_HARAMBLUR_OVERLAY_STYLE, HaramBlurOverlayStyle.BLUR.ordinal)
            ) { HaramBlurOverlayStyle.BLUR }
            val effectStrength = prefs.getInt(PREFS_KEY_HARAMBLUR_EFFECT_STRENGTH, 10).coerceIn(1, 10)

            val scrollDeltaY = HaramBlurRuntimeBus.lastScrollDeltaY
            if (scrollOccurred && kotlin.math.abs(scrollDeltaY) > 0.005f) {
                regionTracker.onScrollDetected(scrollDeltaY)
            }

            val currentLockedRects = regionTracker.getActiveRegions().map { it.rectF }
            val currentExteriorSig = regionTracker.computeExteriorLumaSignature(frameBitmap, currentLockedRects)
            val exteriorDiff = regionTracker.computeExteriorLumaDiff(previousExteriorSig, currentExteriorSig)
            previousExteriorSig = currentExteriorSig

            // Restore unoccluded pixels under existing active overlay boxes when exterior scene is relatively steady
            // so RegionalBlurOverlayView never blinds the AI detector to the face/skin underneath
            val cleanRef = lastUnoccludedFrame
            if (cleanRef != null && !cleanRef.isRecycled && currentLockedRects.isNotEmpty() && !scrollOccurred && exteriorDiff < 0.14f) {
                patchRestoreCanvas.setBitmap(frameBitmap)
                val bw = frameBitmap.width
                val bh = frameBitmap.height
                for (r in currentLockedRects) {
                    patchRect.set(
                        (r.left * bw).toInt().coerceIn(0, bw - 1),
                        (r.top * bh).toInt().coerceIn(0, bh - 1),
                        (r.right * bw).toInt().coerceIn(1, bw),
                        (r.bottom * bh).toInt().coerceIn(1, bh)
                    )
                    if (patchRect.width() > 4 && patchRect.height() > 4) {
                        patchRestoreCanvas.drawBitmap(cleanRef, patchRect, patchRect, null)
                    }
                }
            }

            // Cache current clean/restored frame for next cycle's unoccluded patch restoration
            if (lastUnoccludedFrame == null || lastUnoccludedFrame?.isRecycled == true) {
                lastUnoccludedFrame = frameBitmap.copy(Bitmap.Config.ARGB_8888, true)
            } else {
                patchRestoreCanvas.setBitmap(lastUnoccludedFrame!!)
                patchRestoreCanvas.drawBitmap(frameBitmap, 0f, 0f, null)
            }

            val candidates = if (currentLockedRects.isNotEmpty() &&
                !scrollOccurred &&
                exteriorDiff < HaramBlurFilterLogic.EXTERIOR_STATIONARY_DIFF_THRESHOLD
            ) {
                emptyList()
            } else {
                moderationEngine.detectRegionalBlurCandidates(frameBitmap)
            }

            val updatedRegions = regionTracker.updateRegions(
                candidates = candidates,
                exteriorDiff = exteriorDiff,
                scrollOccurred = scrollOccurred,
                nowMs = now,
                targetGender = targetGender,
                sensitivity = sensitivity
            )

            mainHandler.post {
                HaramBlurRuntimeBus.updateActiveRegions(updatedRegions)
                regionalWindowManager.renderRegions(updatedRegions, overlayStyle, effectStrength)
            }
        } catch (t: Throwable) {
            Log.e(TAG, "Error processing HaramBlur projection frame", t)
        } finally {
            try {
                frameBitmap?.recycle()
            } catch (_: Throwable) {
            }
            try {
                image.close()
            } catch (_: Throwable) {
            }
            isFrameProcessing.set(false)
        }
    }

    private fun releaseProjectionResources() {
        try {
            virtualDisplay?.release()
        } catch (_: Throwable) {
        }
        virtualDisplay = null

        try {
            imageReader?.setOnImageAvailableListener(null, null)
            imageReader?.close()
        } catch (_: Throwable) {
        }
        imageReader = null

        try {
            mediaProjection?.stop()
        } catch (_: Throwable) {
        }
        mediaProjection = null

        try {
            lastUnoccludedFrame?.recycle()
        } catch (_: Throwable) {
        }
        lastUnoccludedFrame = null

        regionTracker.clearAll()
        previousExteriorSig = null
        regionalWindowManager.hideAll()
    }

    private fun stopSelfCleanly() {
        releaseProjectionResources()
        regionalWindowManager.detach()
        HaramBlurRuntimeBus.setProjectionRunning(false)
        getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(PREFS_KEY_HARAMBLUR_ENABLED, false)
            .apply()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        releaseProjectionResources()
        regionalWindowManager.detach()
        moderationEngine.close()
        workerThread?.quitSafely()
        workerThread = null
        HaramBlurRuntimeBus.setProjectionRunning(false)
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "AmnGaze Real-Time Regional Blur",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Active while real-time face & figure blurring is running."
            }
            val nm = getSystemService(NotificationManager::class.java)
            nm?.createNotificationChannel(channel)
        }
    }

    private fun buildForegroundNotification(): Notification {
        val openAppIntent = Intent(this, MainActivity::class.java)
        val openPending = PendingIntent.getActivity(
            this,
            0,
            openAppIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val stopIntent = Intent(this, HaramBlurProjectionService::class.java).apply {
            action = ACTION_STOP_PROJECTION
        }
        val stopPending = PendingIntent.getService(
            this,
            1,
            stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_secure)
            .setContentTitle("AmnGaze Real-Time Blur Active")
            .setContentText("Regional face and figure protection is running.")
            .setContentIntent(openPending)
            .setOngoing(true)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Stop Blur", stopPending)
            .build()
    }
}
