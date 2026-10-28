package com.amngaze

import android.accessibilityservice.AccessibilityService
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import android.view.Display
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.annotation.RequiresApi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.ArrayDeque
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Bulldog-Style 3-Layer Content Blocker & Full-Screen Curtain Shield powered by [AccessibilityService].
 *
 * - Layer 1 (<5ms): Package cooldown lockout check, 30s false-positive whitelist check,
 *   targeted browser URL bar lookup (`findAccessibilityNodeInfosByViewId`), and bounded
 *   75-node iterative BFS text/domain scan with strict `try ... finally` node cleanup.
 * - Layer 2 (API 30+): Hybrid Leading + Trailing Scroll-Settle Debounced `takeScreenshot()`
 *   (`180ms` settle delay + `900ms` lazy-image verification + `750ms` continuous-scroll ceiling
 *   + `1,500ms` active video/media heartbeat) guarded by `AtomicBoolean` and version-aware
 *   AOSP rate limits (`380ms` on API 31+, `1050ms` on API 30).
 * - Layer 3 (<2ms): Pre-allocated `TYPE_ACCESSIBILITY_OVERLAY` whole-screen shield supporting
 *   Curtain Shield Mode ("Leave Page Safely" / "False Positive - Unblur for 30s") and
 *   Bulldog Hard Eject Mode (Auto-Back on Strikes 1-2, 15-minute lockout + Home on Strike 3).
 */
class AmnGazeAccessibilityService : AccessibilityService() {

    companion object {
        private const val TAG = "AmnGazeA11yService"
        private const val MAX_BFS_NODES = 75
        private const val CONTENT_SCAN_THROTTLE_MS = 220L

        private val _isRunning = MutableStateFlow(false)
        val isRunning: StateFlow<Boolean> = _isRunning

        /**
         * Checks both the in-process connection state and Android's `ENABLED_ACCESSIBILITY_SERVICES`
         * secure setting so the dashboard accurately reflects whether the service is enabled.
         */
        fun isServiceEnabled(context: Context): Boolean {
            if (_isRunning.value) return true
            return try {
                val expected = ComponentName(context, AmnGazeAccessibilityService::class.java)
                val flatExpected = expected.flattenToString()
                val shortExpected = expected.flattenToShortString()
                val enabledServices = Settings.Secure.getString(
                    context.contentResolver,
                    Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
                ) ?: return false

                enabledServices.split(':').any { entry ->
                    entry.equals(flatExpected, ignoreCase = true) ||
                        entry.equals(shortExpected, ignoreCase = true)
                }
            } catch (_: Throwable) {
                _isRunning.value
            }
        }
    }

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val visionExecutor = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())

    private lateinit var moderationEngine: ContentModerationEngine
    private lateinit var overlayManager: BlurOverlayManager
    private lateinit var strikeLockoutManager: StrikeLockoutManager
    private var powerManager: PowerManager? = null
    private val urlAndKeywordScanner = FastPathUrlAndKeywordScanner()
    private val scrollDebounceController = ScrollSettleDebounceController()

    // SAFEGUARD 3: Thread-safe atomic guard preventing concurrent takeScreenshot() calls
    private val isScreenshotInFlight = AtomicBoolean(false)

    @Volatile
    private var activeForegroundPackage: String = ""
    private var lastContentFastPathScanMs: Long = 0L
    private var isVideoHeartbeatActive: Boolean = false

    @Volatile
    private var detectedHomePackages: Set<String> = setOf("com.alhaq.amniquist")

    private val settleScanRunnable = Runnable {
        val pkg = activeForegroundPackage
        if (pkg.isNotEmpty() && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && isEligibleForVisualScan(pkg)) {
            executeScreenshotScan(pkg)
        }
    }

    // FIX 5A: 900ms follow-up verification scan to catch lazy-loaded network images after scroll stops
    private val followUpVerificationScanRunnable = Runnable {
        val pkg = activeForegroundPackage
        if (pkg.isNotEmpty() && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && isEligibleForVisualScan(pkg)) {
            executeScreenshotScan(pkg)
        }
    }

    // FIX 5B: 1,500ms active video/media heartbeat while an eligible app is open and screen is interactive
    private val videoHeartbeatRunnable = object : Runnable {
        override fun run() {
            val pkg = activeForegroundPackage
            val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val heartbeatEnabled = prefs.getBoolean(PREFS_KEY_VIDEO_HEARTBEAT, true)
            val isScreenInteractive = powerManager?.isInteractive ?: true

            if (heartbeatEnabled &&
                isScreenInteractive &&
                pkg.isNotEmpty() &&
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.R &&
                isEligibleForVisualScan(pkg) &&
                !overlayManager.isShieldVisible
            ) {
                executeScreenshotScan(pkg)
                mainHandler.postDelayed(this, scrollDebounceController.videoHeartbeatIntervalMs)
            } else {
                isVideoHeartbeatActive = false
            }
        }
    }

    private val autoDismissLockoutBannerRunnable = Runnable {
        val currentPkg = activeForegroundPackage
        val shieldedPkg = overlayManager.shieldedPackage
        if (shieldedPkg == null || currentPkg != shieldedPkg || isIgnoredSystemOrSelfPackage(currentPkg)) {
            overlayManager.hide()
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        _isRunning.value = true
        Log.i(TAG, "AmnGaze 3-Layer AccessibilityService connected.")

        powerManager = getSystemService(Context.POWER_SERVICE) as? PowerManager
        refreshDetectedHomeLaunchers()
        discoverInstalledBrowsers()

        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        moderationEngine = ContentModerationEngine(this)
        strikeLockoutManager = StrikeLockoutManager(prefs)
        // Immediately purge any stale lockouts that may have been recorded on launchers or non-eligible apps
        strikeLockoutManager.purgeNonEligibleLockouts(FastPathUrlAndKeywordScanner.HIGH_RISK_MEDIA_PACKAGES)

        overlayManager = BlurOverlayManager(
            context = this,
            onLeaveSafelyClicked = { handleLeaveSafelyAction() },
            onUnblurFalsePositiveClicked = { handleFalsePositiveUnblurAction() }
        )
        overlayManager.attachPreAllocatedShield()

        // Load bundled 80,000 adult domains from assets/nsfw.txt asynchronously on IO dispatcher
        serviceScope.launch(Dispatchers.IO) {
            loadBundledNsfwDomains()
        }
    }

    private fun refreshDetectedHomeLaunchers() {
        try {
            val homeIntent = Intent(Intent.ACTION_MAIN).apply {
                addCategory(Intent.CATEGORY_HOME)
            }
            val resolveInfos = packageManager.queryIntentActivities(homeIntent, 0)
            val resolved = HashSet<String>()
            resolved.add("com.alhaq.amniquist")
            for (info in resolveInfos) {
                val pkg = info.activityInfo?.packageName
                if (!pkg.isNullOrBlank()) {
                    resolved.add(pkg)
                }
            }
            detectedHomePackages = resolved
            Log.i(TAG, "Detected protected Home Launcher packages: $detectedHomePackages")
        } catch (e: Exception) {
            Log.w(TAG, "Failed to query HOME launcher packages; using default protected set.", e)
        }
    }

    /**
     * FIX 6: Dynamically discovers all installed browsers (`http://` and `https://` `ACTION_VIEW`)
     * via `PackageManager` and merges them into [FastPathUrlAndKeywordScanner] while keeping
     * all Home Launchers (`com.alhaq.amniquist`, `detectedHomePackages`) strictly excluded.
     */
    private fun discoverInstalledBrowsers() {
        try {
            val discovered = HashSet<String>()
            for (scheme in listOf("https://www.example.com", "http://www.example.com")) {
                val browserIntent = Intent(Intent.ACTION_VIEW, Uri.parse(scheme)).apply {
                    addCategory(Intent.CATEGORY_BROWSABLE)
                }
                val flags = PackageManager.MATCH_ALL
                val resolveInfos = packageManager.queryIntentActivities(browserIntent, flags)
                for (info in resolveInfos) {
                    val pkg = info.activityInfo?.packageName
                    if (!pkg.isNullOrBlank() && !isIgnoredSystemOrSelfPackage(pkg)) {
                        discovered.add(pkg)
                    }
                }
            }
            if (discovered.isNotEmpty()) {
                FastPathUrlAndKeywordScanner.registerDynamicBrowsers(
                    discoveredBrowserPackages = discovered,
                    selfPackageName = packageName,
                    detectedHomePackages = detectedHomePackages
                )
                Log.i(TAG, "Dynamically registered ${discovered.size} installed browser packages: $discovered")
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to query installed browser packages; using expanded default set.", e)
        }
    }

    private fun stopAllVisualScanTimers() {
        mainHandler.removeCallbacks(settleScanRunnable)
        mainHandler.removeCallbacks(followUpVerificationScanRunnable)
        mainHandler.removeCallbacks(videoHeartbeatRunnable)
        isVideoHeartbeatActive = false
    }

    private fun schedulePostScrollVisualScans(primaryDelayMs: Long) {
        mainHandler.removeCallbacks(settleScanRunnable)
        mainHandler.removeCallbacks(followUpVerificationScanRunnable)
        mainHandler.postDelayed(settleScanRunnable, primaryDelayMs)
        // Schedule 900ms follow-up verification scan to catch lazy-loaded network images
        mainHandler.postDelayed(
            followUpVerificationScanRunnable,
            scrollDebounceController.followUpVerificationDelayMs
        )
    }

    private fun ensureVideoHeartbeatState(pkg: String, prefs: android.content.SharedPreferences) {
        val heartbeatEnabled = prefs.getBoolean(PREFS_KEY_VIDEO_HEARTBEAT, true)
        val isScreenInteractive = powerManager?.isInteractive ?: true
        if (!heartbeatEnabled || !isScreenInteractive || !isEligibleForVisualScan(pkg) || overlayManager.isShieldVisible) {
            mainHandler.removeCallbacks(videoHeartbeatRunnable)
            isVideoHeartbeatActive = false
            return
        }
        if (!isVideoHeartbeatActive) {
            isVideoHeartbeatActive = true
            mainHandler.removeCallbacks(videoHeartbeatRunnable)
            mainHandler.postDelayed(videoHeartbeatRunnable, scrollDebounceController.videoHeartbeatIntervalMs)
        }
    }

    private fun loadBundledNsfwDomains() {
        try {
            val domains = HashSet<String>(82_000)
            assets.open("nsfw.txt").use { stream ->
                BufferedReader(InputStreamReader(stream)).useLines { lines ->
                    lines.forEach { line ->
                        val trimmed = line.trim().lowercase()
                        if (trimmed.isNotEmpty() && !trimmed.startsWith("#")) {
                            domains.add(trimmed)
                        }
                    }
                }
            }
            urlAndKeywordScanner.updateBlockedDomains(domains)
            Log.i(TAG, "Loaded ${domains.size} adult domains into O(1) Layer 1 fast-path HashSet.")
        } catch (e: Exception) {
            Log.w(TAG, "Could not load assets/nsfw.txt; using built-in explicit domain/keyword set.", e)
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val rawEventPkg = event?.packageName?.toString() ?: return

        if (event.eventType == AccessibilityEvent.TYPE_VIEW_SCROLLED) {
            val screenH = resources.displayMetrics.heightPixels.coerceAtLeast(640).toFloat()
            val rawDeltaY = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                event.scrollDeltaY
            } else {
                0
            }
            val normDeltaY = if (rawDeltaY != 0) {
                (-rawDeltaY.toFloat() / screenH).coerceIn(-0.40f, 0.40f)
            } else {
                0f
            }
            HaramBlurRuntimeBus.onScrollDetected(normDeltaY)
        }

        // Ignore Accessibility events emitted by our own overlay window so we never self-trigger
        if (rawEventPkg == packageName && overlayManager.isShieldVisible) {
            val activeWindowPkg = try {
                rootInActiveWindow?.packageName?.toString()
            } catch (_: Throwable) {
                null
            }
            if (activeWindowPkg != null && activeWindowPkg != packageName) {
                handleForegroundPackageObservation(activeWindowPkg, event.eventType)
            }
            return
        }

        handleForegroundPackageObservation(rawEventPkg, event.eventType)
    }

    private val autoRunReminderController = AutoRunReminderController()

    private fun handleForegroundPackageObservation(pkg: String, eventType: Int) {
        val isTransientIme = FastPathUrlAndKeywordScanner.isTransientInputMethodOverlay(pkg)
        if (!isTransientIme) {
            activeForegroundPackage = pkg
            HaramBlurRuntimeBus.onForegroundPackageChanged(
                packageName = pkg,
                isIgnoredLauncherOrSystem = isIgnoredSystemOrSelfPackage(pkg)
            )
        }

        // 1. CRITICAL: Immediately remove the overlay whenever the user is outside the app that triggered it!
        //    For example, when navigating to the Home Launcher (e.g. com.alhaq.amniquist), switching apps,
        //    opening Recents/SystemUI, or opening AmnGaze itself.
        if (overlayManager.isShieldVisible &&
            FastPathUrlAndKeywordScanner.shouldDismissOverlayForPackageChange(
                shieldedPackage = overlayManager.shieldedPackage,
                foregroundPackage = pkg
            )
        ) {
            stopAllVisualScanTimers()
            mainHandler.removeCallbacks(autoDismissLockoutBannerRunnable)
            overlayManager.hide()
        }

        val now = SystemClock.elapsedRealtime()
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

        // Check HaramBlur Auto-Run reminder when a window state changes to a user-selected app
        if (!isTransientIme && eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            val isHaramBlurRunning = HaramBlurRuntimeBus.isProjectionRunning.value ||
                prefs.getBoolean(PREFS_KEY_HARAMBLUR_ENABLED, false)
            val rawAutoRun = prefs.getString(PREFS_KEY_HARAMBLUR_AUTO_RUN_PACKAGES, "") ?: ""
            val autoRunSet = rawAutoRun.split(";").map { it.trim() }.filter { it.isNotEmpty() }.toSet()
            if (autoRunReminderController.shouldEmitAutoRunReminder(
                    foregroundPackage = pkg,
                    autoRunPackages = autoRunSet,
                    isHaramBlurEnabled = isHaramBlurRunning,
                    nowMs = now,
                    selfPackageName = packageName,
                    detectedHomePackages = detectedHomePackages
                )
            ) {
                HaramBlurRuntimeBus.postAutoRunReminder(pkg)
            }
        }

        // Check Master Toggle 1 (isExplicitBlockerEnabled): if OFF, pause Module 1 scanning & hide Curtain Shield
        val isExplicitBlockerEnabled = prefs.getBoolean(PREFS_KEY_EXPLICIT_BLOCKER_ENABLED, true)
        if (!isExplicitBlockerEnabled) {
            stopAllVisualScanTimers()
            if (overlayManager.isShieldVisible) {
                overlayManager.hide()
            }
            return
        }

        // 2. Never scan, lock, or show an overlay on Home Launchers (com.alhaq.amniquist, etc.),
        //    SystemUI, Settings, Keyboards, or any non-monitored app.
        if (isIgnoredSystemOrSelfPackage(pkg) || !isEligibleForVisualScan(pkg)) {
            if (!isTransientIme) {
                stopAllVisualScanTimers()
                if (overlayManager.isShieldVisible) {
                    overlayManager.hide()
                }
            }
            return
        }

        val enforcementMode = EnforcementMode.entries.getOrElse(
            prefs.getInt(PREFS_KEY_ENFORCEMENT_MODE, EnforcementMode.CURTAIN_SHIELD.ordinal)
        ) { EnforcementMode.CURTAIN_SHIELD }

        // 3. Evaluate whether ambiguous text keywords are currently snoozed for 30s.
        //    CRITICAL CHILD-SAFETY INVARIANT: A 30-second keyword snooze NEVER pauses or disables
        //    explicit adult domain blocking (ADULT_DOMAIN_URL) or visual AI screenshot scanning
        //    (VISUAL_NSFW_IMAGE_OR_VIDEO).
        val isKeywordSnoozed = strikeLockoutManager.isKeywordTextSnoozed(pkg, now)

        // 4. Only enforce 15-minute 3-strike cooldown lockouts when the user explicitly selected
        //    Bulldog HARD_EJECT mode. In CURTAIN_SHIELD mode, never lock out or force-eject the app.
        if (enforcementMode == EnforcementMode.HARD_EJECT && strikeLockoutManager.isPackageLocked(pkg, now)) {
            val remainingMs = strikeLockoutManager.getRemainingLockoutMs(pkg, now)
            showLockoutCurtainAndRedirectHome(pkg, remainingMs, prefs)
            return
        }

        // If shield is already covering THIS specific monitored app, pause further scanning
        if (overlayManager.isShieldVisible && overlayManager.shieldedPackage == pkg) {
            return
        }

        // 5. LAYER 1: Zero-Latency UI Tree & URL Bar Fast-Path (<5ms)
        val urlFastPathEnabled = prefs.getBoolean(PREFS_KEY_URL_FAST_PATH, true)
        val shouldRunFastPath = urlFastPathEnabled && (
            eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED ||
                (eventType == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED &&
                    (now - lastContentFastPathScanMs) >= CONTENT_SCAN_THROTTLE_MS)
            )

        if (shouldRunFastPath) {
            lastContentFastPathScanMs = now
            val rootNode = try {
                rootInActiveWindow
            } catch (_: Throwable) {
                null
            }
            if (rootNode != null) {
                val rootPkg = rootNode.packageName?.toString() ?: pkg
                if (rootPkg == pkg && isEligibleForVisualScan(rootPkg)) {
                    val violationMatch = scanUiTreeFastPathSafely(
                        root = rootNode,
                        isBrowserApp = pkg in FastPathUrlAndKeywordScanner.BROWSER_PACKAGES,
                        isKeywordSnoozed = isKeywordSnoozed
                    )
                    if (violationMatch != null &&
                        strikeLockoutManager.shouldBlockViolation(pkg, violationMatch.triggerSource, now)
                    ) {
                        val category = when (violationMatch.triggerSource) {
                            ViolationTriggerSource.ADULT_DOMAIN_URL -> "ADULT_DOMAIN_URL"
                            ViolationTriggerSource.TEXT_KEYWORD_MATCH -> "TEXT_KEYWORD_MATCH"
                            ViolationTriggerSource.VISUAL_NSFW_IMAGE_OR_VIDEO -> "VISUAL_NSFW_IMAGE_OR_VIDEO"
                        }
                        enforceViolation(
                            packageName = pkg,
                            categoryName = category,
                            reasonDescription = violationMatch.reason,
                            confidence = 1.0f,
                            triggerSource = violationMatch.triggerSource
                        )
                        return
                    }
                }
            }
        }

        // 6. LAYER 2: Two-Stage Scroll-Settle + Video Heartbeat HardwareBuffer Vision Pipeline (API 30+)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            ensureVideoHeartbeatState(pkg, prefs)
            when (eventType) {
                AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> {
                    schedulePostScrollVisualScans(scrollDebounceController.windowChangeDelayMs)
                }
                AccessibilityEvent.TYPE_VIEW_SCROLLED,
                AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED -> {
                    when (scrollDebounceController.onScrollEvent(now)) {
                        ScrollScheduleAction.EXECUTE_IMMEDIATELY_MAX_WAIT_REACHED -> {
                            mainHandler.removeCallbacks(settleScanRunnable)
                            executeScreenshotScan(pkg)
                            mainHandler.removeCallbacks(followUpVerificationScanRunnable)
                            mainHandler.postDelayed(
                                followUpVerificationScanRunnable,
                                scrollDebounceController.followUpVerificationDelayMs
                            )
                        }
                        ScrollScheduleAction.SCHEDULE_SETTLE_TIMER -> {
                            schedulePostScrollVisualScans(scrollDebounceController.settleDelayMs)
                        }
                    }
                }
                else -> {}
            }
        }
    }

    /**
     * SAFEGUARD 6: Bounded iterative BFS traversal (max 75 nodes) with strict `try ... finally`
     * node recycling on API < 30 so native Binder references never leak during rapid scrolling.
     */
    private fun scanUiTreeFastPathSafely(
        root: AccessibilityNodeInfo,
        isBrowserApp: Boolean,
        isKeywordSnoozed: Boolean = false
    ): ViolationMatch? {
        val allocatedNodes = ArrayList<AccessibilityNodeInfo>(MAX_BFS_NODES + 16)
        allocatedNodes.add(root)
        try {
            // 1. Targeted O(1) lookup for known browser URL bars (ADULT_DOMAIN_URL is never snoozed)
            for (urlBarId in FastPathUrlAndKeywordScanner.KNOWN_BROWSER_URL_BAR_IDS) {
                val urlNodes = try {
                    root.findAccessibilityNodeInfosByViewId(urlBarId)
                } catch (_: Throwable) {
                    null
                }
                if (!urlNodes.isNullOrEmpty()) {
                    allocatedNodes.addAll(urlNodes)
                    for (node in urlNodes) {
                        val urlText = node.text?.toString()
                        val violation = urlAndKeywordScanner.findViolationMatch(
                            rawInput = urlText,
                            isUrlBarNode = true,
                            isKeywordSnoozed = isKeywordSnoozed
                        )
                        if (violation != null) return violation
                    }
                }
            }

            // 2. Only run bounded BFS text scanning inside web browsers so normal social/chat captions
            //    are handled by the visual AI model rather than triggering text false positives.
            if (!isBrowserApp) return null

            val queue = ArrayDeque<AccessibilityNodeInfo>()
            queue.add(root)
            var inspectedCount = 0

            while (!queue.isEmpty() && inspectedCount < MAX_BFS_NODES) {
                val current = queue.poll() ?: continue
                inspectedCount++

                val textMatch = urlAndKeywordScanner.findViolationMatch(
                    rawInput = current.text?.toString(),
                    isUrlBarNode = false,
                    isKeywordSnoozed = isKeywordSnoozed
                )
                if (textMatch != null) return textMatch

                val descMatch = urlAndKeywordScanner.findViolationMatch(
                    rawInput = current.contentDescription?.toString(),
                    isUrlBarNode = false,
                    isKeywordSnoozed = isKeywordSnoozed
                )
                if (descMatch != null) return descMatch

                val childCount = current.childCount
                for (i in 0 until childCount) {
                    if (allocatedNodes.size >= MAX_BFS_NODES * 2) break
                    val child = try {
                        current.getChild(i)
                    } catch (_: Throwable) {
                        null
                    }
                    if (child != null) {
                        allocatedNodes.add(child)
                        queue.add(child)
                    }
                }
            }
            return null
        } finally {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
                for (node in allocatedNodes) {
                    try {
                        @Suppress("DEPRECATION")
                        node.recycle()
                    } catch (_: Throwable) {
                    }
                }
            }
        }
    }

    /**
     * SAFEGUARDS 1, 2, 3 & 4: Rate-limit-aware, atomic-guarded `takeScreenshot()` pipeline.
     * Verifies both before and after capture that the user is still inside [targetPackage]
     * so an in-flight screenshot never triggers an overlay after the user returns to their launcher.
     *
     * Note: Visual NSFW screenshot scanning is NEVER disabled by a 30-second keyword snooze.
     */
    @RequiresApi(Build.VERSION_CODES.R)
    private fun executeScreenshotScan(targetPackage: String) {
        val now = SystemClock.elapsedRealtime()
        if (!isEligibleForVisualScan(targetPackage) || activeForegroundPackage != targetPackage) {
            return
        }
        if (overlayManager.isShieldVisible) {
            return
        }
        if (!scrollDebounceController.canExecuteScreenshot(now, Build.VERSION.SDK_INT)) {
            return
        }
        if (!isScreenshotInFlight.compareAndSet(false, true)) {
            return
        }

        scrollDebounceController.onScanDispatched(now)
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val sensitivity = prefs.getFloat(PREFS_KEY_BLUR_SENSITIVITY, 0.5f)
        val blockIncognitoEnabled = prefs.getBoolean(PREFS_KEY_BLOCK_INCOGNITO, false)
        val includeSexyScore = prefs.getBoolean(PREFS_KEY_INCLUDE_SEXY_SCORE, true)

        try {
            takeScreenshot(
                Display.DEFAULT_DISPLAY,
                visionExecutor,
                object : TakeScreenshotCallback {
                    override fun onSuccess(screenshot: ScreenshotResult) {
                        try {
                            val verdict = moderationEngine.analyzeHardwareBuffer(
                                hardwareBuffer = screenshot.hardwareBuffer,
                                colorSpace = screenshot.colorSpace,
                                sensitivity = sensitivity,
                                modelType = ModerationModelType.QUANTIZED_NSFW,
                                includeSexyScore = includeSexyScore
                            )
                            if (verdict.isViolation) {
                                val pct = (verdict.confidence * 100).toInt()
                                mainHandler.post {
                                    // Verify user is STILL inside targetPackage before showing overlay
                                    if (activeForegroundPackage == targetPackage && isEligibleForVisualScan(targetPackage)) {
                                        enforceViolation(
                                            packageName = targetPackage,
                                            categoryName = verdict.categoryName,
                                            reasonDescription = "On-device LiteRT model flagged explicit content ($pct% confidence).",
                                            confidence = verdict.confidence,
                                            triggerSource = ViolationTriggerSource.VISUAL_NSFW_IMAGE_OR_VIDEO
                                        )
                                    }
                                }
                            }
                        } catch (t: Throwable) {
                            Log.e(TAG, "Unexpected error during screenshot analysis", t)
                        } finally {
                            isScreenshotInFlight.set(false)
                        }
                    }

                    override fun onFailure(errorCode: Int) {
                        isScreenshotInFlight.set(false)
                        val isBrowser = targetPackage in FastPathUrlAndKeywordScanner.BROWSER_PACKAGES
                        if (errorCode == ERROR_TAKE_SCREENSHOT_SECURE_WINDOW && blockIncognitoEnabled && isBrowser) {
                            mainHandler.post {
                                if (activeForegroundPackage == targetPackage) {
                                    enforceViolation(
                                        packageName = targetPackage,
                                        categoryName = "INCOGNITO_OR_SECURE_WINDOW",
                                        reasonDescription = "Browser Incognito / Private tab blocked by AmnGaze protection policy.",
                                        confidence = 1.0f,
                                        triggerSource = ViolationTriggerSource.ADULT_DOMAIN_URL
                                    )
                                }
                            }
                        }
                    }
                }
            )
        } catch (t: Throwable) {
            isScreenshotInFlight.set(false)
            Log.e(TAG, "takeScreenshot invocation failed", t)
        }
    }

    /**
     * LAYER 3: App-Scoped Shield & Mode-Specific Enforcement.
     *
     * - In [EnforcementMode.CURTAIN_SHIELD]: Shows the Curtain Shield over [packageName] only.
     *   Only shows "Skip Keyword Alert for 30s" when [triggerSource] is [ViolationTriggerSource.TEXT_KEYWORD_MATCH]
     *   (and Parental PIN is not enabled). Explicit adult domains ([ViolationTriggerSource.ADULT_DOMAIN_URL])
     *   and visual AI detections ([ViolationTriggerSource.VISUAL_NSFW_IMAGE_OR_VIDEO]) NEVER show a 30s
     *   bypass button and can only be dismissed via "Leave Page Safely".
     * - In [EnforcementMode.HARD_EJECT]: Records strikes (3 strikes in 5m -> 15m lockout for that
     *   specific app), fires `GLOBAL_ACTION_BACK` or `GLOBAL_ACTION_HOME`, and hides the shield
     *   as soon as the app exits.
     */
    private fun enforceViolation(
        packageName: String,
        categoryName: String,
        reasonDescription: String,
        confidence: Float,
        triggerSource: ViolationTriggerSource = ViolationTriggerSource.VISUAL_NSFW_IMAGE_OR_VIDEO
    ) {
        if (!isEligibleForVisualScan(packageName) || activeForegroundPackage != packageName) {
            return
        }

        stopAllVisualScanTimers()

        val now = SystemClock.elapsedRealtime()
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

        // Increment total detection counter
        val updatedCount = prefs.getInt(PREFS_KEY_TOTAL_DETECTIONS, 0) + 1
        prefs.edit().putInt(PREFS_KEY_TOTAL_DETECTIONS, updatedCount).apply()

        val censorStyle = BlurCensorStyle.entries.getOrElse(
            prefs.getInt(PREFS_KEY_BLUR_CENSOR_STYLE, BlurCensorStyle.FROSTED.ordinal)
        ) { BlurCensorStyle.FROSTED }
        val enforcementMode = EnforcementMode.entries.getOrElse(
            prefs.getInt(PREFS_KEY_ENFORCEMENT_MODE, EnforcementMode.CURTAIN_SHIELD.ordinal)
        ) { EnforcementMode.CURTAIN_SHIELD }
        val showBadge = prefs.getBoolean(PREFS_KEY_BLUR_SHOW_BADGE, true)
        val showBorder = prefs.getBoolean(PREFS_KEY_BLUR_SHOW_BORDER, true)
        val parentalPinEnabled = prefs.getBoolean(PREFS_KEY_PARENTAL_PIN_ENABLED, false)
        val allowKeywordSnooze = !parentalPinEnabled &&
            StrikeLockoutManager.canSnoozeViolationFor30s(triggerSource)

        if (enforcementMode == EnforcementMode.CURTAIN_SHIELD) {
            overlayManager.showCurtainShield(
                targetPackage = packageName,
                censorStyle = censorStyle,
                showBadge = showBadge,
                showBorder = showBorder,
                title = "Explicit or Triggering Content Blocked",
                reason = reasonDescription,
                strikeStatus = "App: $packageName | Category: $categoryName",
                allowFalsePositiveUnblur = allowKeywordSnooze,
                primaryActionLabel = "Leave Page Safely",
                secondaryActionLabel = "Skip Keyword Alert for 30s"
            )
            return
        }

        // Bulldog HARD_EJECT mode: Record strike and escalate to 15-minute lockout on 3rd strike
        val strikeDecision = strikeLockoutManager.recordViolation(packageName, now, triggerSource)
        if (strikeDecision.isLockedOut) {
            showLockoutCurtainAndRedirectHome(packageName, strikeDecision.remainingLockMs, prefs)
            return
        }

        val strikeText = "Strike ${strikeDecision.strikeCountInWindow} of ${strikeLockoutManager.maxStrikesBeforeLockout} (5-min window) | App: $packageName"

        overlayManager.showCurtainShield(
            targetPackage = packageName,
            censorStyle = censorStyle,
            showBadge = showBadge,
            showBorder = showBorder,
            title = "Explicit or Triggering Content Blocked",
            reason = reasonDescription,
            strikeStatus = strikeText,
            allowFalsePositiveUnblur = allowKeywordSnooze,
            primaryActionLabel = "Leave Page Safely",
            secondaryActionLabel = "Skip Keyword Alert for 30s"
        )

        performGlobalAction(GLOBAL_ACTION_BACK)
        mainHandler.postDelayed({
            overlayManager.hide()
        }, 350L)
    }

    private fun showLockoutCurtainAndRedirectHome(
        packageName: String,
        remainingMs: Long,
        prefs: android.content.SharedPreferences
    ) {
        if (!isEligibleForVisualScan(packageName)) {
            overlayManager.hide()
            return
        }

        stopAllVisualScanTimers()

        val censorStyle = BlurCensorStyle.entries.getOrElse(
            prefs.getInt(PREFS_KEY_BLUR_CENSOR_STYLE, BlurCensorStyle.FROSTED.ordinal)
        ) { BlurCensorStyle.FROSTED }
        val showBadge = prefs.getBoolean(PREFS_KEY_BLUR_SHOW_BADGE, true)
        val showBorder = prefs.getBoolean(PREFS_KEY_BLUR_SHOW_BORDER, true)

        val totalSeconds = (remainingMs / 1000L).coerceAtLeast(1L)
        val minutes = totalSeconds / 60L
        val seconds = totalSeconds % 60L
        val formattedRemaining = String.format("%02d:%02d", minutes, seconds)

        overlayManager.showCurtainShield(
            targetPackage = packageName,
            censorStyle = censorStyle,
            showBadge = showBadge,
            showBorder = showBorder,
            title = "App Locked for 15-Minute Cooldown",
            reason = "3 content violations were recorded in $packageName within 5 minutes.",
            strikeStatus = "COOLDOWN ACTIVE - REMAINING: $formattedRemaining",
            allowFalsePositiveUnblur = false,
            primaryActionLabel = "Return to Safe Home",
            secondaryActionLabel = "Skip Keyword Alert for 30s"
        )

        performGlobalAction(GLOBAL_ACTION_HOME)
        // Automatically dismiss the shield once redirected to the Home Launcher so the launcher is NEVER blocked
        mainHandler.removeCallbacks(autoDismissLockoutBannerRunnable)
        mainHandler.postDelayed(autoDismissLockoutBannerRunnable, 450L)
    }

    private fun handleLeaveSafelyAction() {
        val now = SystemClock.elapsedRealtime()
        val pkg = overlayManager.shieldedPackage ?: activeForegroundPackage
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val enforcementMode = EnforcementMode.entries.getOrElse(
            prefs.getInt(PREFS_KEY_ENFORCEMENT_MODE, EnforcementMode.CURTAIN_SHIELD.ordinal)
        ) { EnforcementMode.CURTAIN_SHIELD }
        val isLocked = enforcementMode == EnforcementMode.HARD_EJECT &&
            pkg.isNotEmpty() &&
            strikeLockoutManager.isPackageLocked(pkg, now)

        stopAllVisualScanTimers()
        overlayManager.hide()
        if (isLocked) {
            performGlobalAction(GLOBAL_ACTION_HOME)
        } else {
            performGlobalAction(GLOBAL_ACTION_BACK)
        }
    }

    private fun handleFalsePositiveUnblurAction() {
        val now = SystemClock.elapsedRealtime()
        val pkg = overlayManager.shieldedPackage ?: activeForegroundPackage
        if (pkg.isNotEmpty()) {
            // Snooze ONLY ambiguous text keyword matches for 30s; explicit domains & visual AI remain 100% active
            strikeLockoutManager.snoozeKeywordTextOnly(pkg, now)
        }
        overlayManager.hide()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && pkg.isNotEmpty()) {
            val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            ensureVideoHeartbeatState(pkg, prefs)
            schedulePostScrollVisualScans(scrollDebounceController.settleDelayMs)
        }
    }

    private fun isIgnoredSystemOrSelfPackage(pkg: String): Boolean {
        return FastPathUrlAndKeywordScanner.isProtectedLauncherOrSystemPackage(
            pkg = pkg,
            selfPackageName = packageName,
            detectedHomePackages = detectedHomePackages
        )
    }

    private fun isEligibleForVisualScan(pkg: String): Boolean {
        if (isIgnoredSystemOrSelfPackage(pkg)) return false
        return pkg in FastPathUrlAndKeywordScanner.HIGH_RISK_MEDIA_PACKAGES
    }

    override fun onInterrupt() {
        stopAllVisualScanTimers()
        mainHandler.removeCallbacks(autoDismissLockoutBannerRunnable)
        overlayManager.hide()
    }

    override fun onDestroy() {
        Log.w(TAG, "Tearing down AmnGazeAccessibilityService.")
        mainHandler.removeCallbacksAndMessages(null)
        serviceScope.cancel()
        visionExecutor.shutdown()
        overlayManager.detach()
        moderationEngine.close()
        _isRunning.value = false
        super.onDestroy()
    }
}
