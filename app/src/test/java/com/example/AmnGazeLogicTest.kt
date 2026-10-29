package com.amngaze

import android.app.Application
import android.content.Context
import android.graphics.Bitmap
import android.os.Build
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AmnGazeLogicTest {

    private lateinit var context: Context
    private lateinit var viewModel: AmnGazeViewModel

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().clear().commit()

        viewModel = AmnGazeViewModel(context as Application)
    }

    @Test
    fun testDefaultSettings() = runBlocking {
        val state = viewModel.state.first()
        assertFalse(state.isSetupComplete)
        assertFalse(state.hasAcceptedAccessibilityDisclosure)
        assertEquals(AppTheme.SUNSET_GLOW, state.currentTheme)
        assertEquals(EnforcementMode.CURTAIN_SHIELD, state.enforcementMode)
        assertEquals(BlurCensorStyle.FROSTED, state.amnGazeCensorStyle)
        assertEquals(0.5f, state.amnGazeBlurSensitivity, 0.001f)
        assertTrue(state.amnGazeShowBadge)
        assertTrue(state.amnGazeShowBorder)
        assertTrue(state.blockIncognitoEnabled)
        assertTrue(state.urlFastPathEnabled)
        assertEquals(ModerationModelType.DEFAULT_NSFW, state.amnGazeModelType)
    }

    @Test
    fun testEnforcementAndModelPersistence() = runBlocking {
        viewModel.updateEnforcementMode(EnforcementMode.HARD_EJECT)
        viewModel.updateCensorStyle(BlurCensorStyle.MOSAIC)
        viewModel.updateModelType(ModerationModelType.QUANTIZED_NSFW)
        viewModel.acceptAccessibilityDisclosure()

        val inMemory = viewModel.state.first()
        assertEquals(EnforcementMode.HARD_EJECT, inMemory.enforcementMode)
        assertTrue(inMemory.amnGazeHardBlockEnabled)
        assertEquals(BlurCensorStyle.MOSAIC, inMemory.amnGazeCensorStyle)
        assertEquals(ModerationModelType.QUANTIZED_NSFW, inMemory.amnGazeModelType)
        assertTrue(inMemory.hasAcceptedAccessibilityDisclosure)

        val reloadedViewModel = AmnGazeViewModel(context as Application)
        val reloaded = reloadedViewModel.state.first()
        assertEquals(EnforcementMode.HARD_EJECT, reloaded.enforcementMode)
        assertEquals(BlurCensorStyle.MOSAIC, reloaded.amnGazeCensorStyle)
        assertEquals(ModerationModelType.QUANTIZED_NSFW, reloaded.amnGazeModelType)
        assertTrue(reloaded.hasAcceptedAccessibilityDisclosure)
    }

    @Test
    fun testStrikeCounterEscalatesTo15MinLockoutAndExpires() {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val manager = StrikeLockoutManager(prefs)
        val pkg = "com.android.chrome"
        val t0 = 100_000L

        // Strike 1 -> not locked
        val s1 = manager.recordViolation(pkg, t0)
        assertEquals(1, s1.strikeCountInWindow)
        assertFalse(s1.isLockedOut)
        assertFalse(manager.isPackageLocked(pkg, t0 + 1_000L))

        // Strike 2 (2 minutes later) -> not locked
        val s2 = manager.recordViolation(pkg, t0 + 2 * 60_000L)
        assertEquals(2, s2.strikeCountInWindow)
        assertFalse(s2.isLockedOut)

        // Strike 3 (4 minutes after t0, within 5-minute window) -> 15-minute lockout
        val tStrike3 = t0 + 4 * 60_000L
        val s3 = manager.recordViolation(pkg, tStrike3)
        assertEquals(3, s3.strikeCountInWindow)
        assertTrue(s3.isLockedOut)
        assertEquals(15 * 60_000L, s3.remainingLockMs)

        // Verify locked at +14 minutes
        assertTrue(manager.isPackageLocked(pkg, tStrike3 + 14 * 60_000L))
        assertEquals(60_000L, manager.getRemainingLockoutMs(pkg, tStrike3 + 14 * 60_000L))

        // Verify unlocked after 15 minutes expire
        val afterExpiry = tStrike3 + 15 * 60_000L + 1L
        assertFalse(manager.isPackageLocked(pkg, afterExpiry))
        assertEquals(0L, manager.getRemainingLockoutMs(pkg, afterExpiry))
    }

    @Test
    fun testRolling5MinuteStrikeWindowPrunesOldStrikes() {
        val manager = StrikeLockoutManager()
        val pkg = "com.reddit.frontpage"
        val t0 = 50_000L

        manager.recordViolation(pkg, t0)
        manager.recordViolation(pkg, t0 + 60_000L)

        // 6 minutes after t0 (first two strikes expired outside the 5-minute window)
        val sAfterWindow = manager.recordViolation(pkg, t0 + 6 * 60_000L + 100L)
        assertEquals(1, sAfterWindow.strikeCountInWindow)
        assertFalse(sAfterWindow.isLockedOut)
    }

    @Test
    fun testFalsePositive30SecondWhitelist() {
        val manager = StrikeLockoutManager()
        val pkg = "com.instagram.android"
        val t0 = 200_000L

        assertFalse(manager.isPackageWhitelisted(pkg, t0))
        manager.whitelistPackageTemporarily(pkg, t0)

        assertTrue(manager.isPackageWhitelisted(pkg, t0 + 15_000L))
        assertFalse(manager.isPackageWhitelisted(pkg, t0 + 30_001L))
    }

    @Test
    fun testFastPathUrlAndKeywordMatcherAccuracy() {
        val scanner = FastPathUrlAndKeywordScanner(
            initialBlockedDomains = setOf("000sexxx.com", "adult-example.org", "av.net")
        )

        // Flag explicit domains & URLs
        assertTrue(scanner.containsBlockedTerm("https://www.pornhub.com/view_video.php?viewkey=123", isUrlBarNode = true))
        assertTrue(scanner.containsBlockedTerm("m.000sexxx.com/gallery", isUrlBarNode = true))
        assertTrue(scanner.containsBlockedTerm("sub.adult-example.org", isUrlBarNode = true))
        assertTrue(scanner.containsBlockedTerm("onlyfans.com/creator", isUrlBarNode = true))

        // Flag standalone explicit keywords
        assertTrue(scanner.containsBlockedTerm("Watch free porno and hentai clips", isUrlBarNode = false))
        assertTrue(scanner.containsBlockedTerm("Watch free hentai stream", isUrlBarNode = false))

        // Ignore safe substrings & ambiguous everyday words that previously caused false positives
        assertFalse(scanner.containsBlockedTerm("https://www.scunthorpe.gov.uk/council", isUrlBarNode = true))
        assertFalse(scanner.containsBlockedTerm("Self assessment tax return document", isUrlBarNode = false))
        assertFalse(scanner.containsBlockedTerm("Classic literature analysis and password manager", isUrlBarNode = false))
        assertFalse(scanner.containsBlockedTerm("Shop nude lipstick and Naked eyeshadow palette", isUrlBarNode = false))
        assertFalse(scanner.containsBlockedTerm("Hide NSFW content toggle in settings", isUrlBarNode = false))
        assertFalse(scanner.containsBlockedTerm("Order #XXX-12345 shipped. Check av.net status in app.", isUrlBarNode = false))
        assertFalse(scanner.containsBlockedTerm("https://developer.android.com/reference/android/accessibilityservice/AccessibilityService", isUrlBarNode = true))
        assertEquals(0.60f, ContentModerationEngine.confidenceThresholdForSensitivity(0.5f), 0.001f)
    }

    @Test
    fun testFiveClassNsfwScoringAndSafeUiGuard() {
        // [0]=drawings, [1]=hentai, [2]=neutral, [3]=porn, [4]=sexy

        // 1. Plain text UI / settings screen where neutral + drawings >= 0.50f -> SAFE (0.0f)
        val safeUiProbs = floatArrayOf(0.15f, 0.02f, 0.70f, 0.05f, 0.08f) // neutral + drawings = 0.85f >= 0.50f
        assertEquals(0.0f, ContentModerationEngine.computeFiveClassNsfwScore(safeUiProbs, includeSexyScore = true), 0.001f)

        // 2. Real-world nudity / revealing frame: porn (0.30) and sexy (0.50), hentai (0.05 < 0.65 excluded)
        //    explicitScore = 0.30
        //    combinedNsfwScore = 0.30 + (0.50 * 0.85) = 0.725
        val splitNsfwProbs = floatArrayOf(0.03f, 0.05f, 0.12f, 0.30f, 0.50f)
        assertEquals(
            0.725f,
            ContentModerationEngine.computeFiveClassNsfwScore(splitNsfwProbs, includeSexyScore = true),
            0.001f
        )
        // When user disables revealing/sexy class, only explicit porn (0.30) is counted
        assertEquals(
            0.30f,
            ContentModerationEngine.computeFiveClassNsfwScore(splitNsfwProbs, includeSexyScore = false),
            0.001f
        )
    }

    @Test
    fun testSensitivitySliderThresholdMapping() {
        // Fix 2: Calibrated thresholds: 20% -> 0.78f, 25% -> 0.74f, 50% -> 0.60f, 75% -> 0.48f, 95% -> 0.38f
        assertEquals(0.78f, ContentModerationEngine.confidenceThresholdForSensitivity(0.20f), 0.005f)
        assertEquals(0.74f, ContentModerationEngine.confidenceThresholdForSensitivity(0.25f), 0.005f)
        assertEquals(0.60f, ContentModerationEngine.confidenceThresholdForSensitivity(0.50f), 0.005f)
        assertEquals(0.48f, ContentModerationEngine.confidenceThresholdForSensitivity(0.75f), 0.005f)
        assertEquals(0.38f, ContentModerationEngine.confidenceThresholdForSensitivity(0.95f), 0.005f)
    }

    @Test
    fun testMultiCropExtractorOnTallPortraitBitmap() {
        val tallBitmap = android.graphics.Bitmap.createBitmap(
            1080,
            2400,
            android.graphics.Bitmap.Config.ARGB_8888
        )
        try {
            val crops = ContentModerationEngine.extractMultiCrops(tallBitmap, targetSize = 224)
            assertEquals(3, crops.size)
            for (crop in crops) {
                assertFalse(crop.isRecycled)
                assertEquals(224, crop.width)
                assertEquals(224, crop.height)
                crop.recycle()
            }
        } finally {
            tallBitmap.recycle()
        }
    }

    @Test
    fun testScrollSettleDebounceAndContinuousScrollCeiling() {
        val controller = ScrollSettleDebounceController(
            settleDelayMs = 180L,
            maxContinuousScrollWaitMs = 750L
        )
        assertEquals(900L, controller.followUpVerificationDelayMs)
        assertEquals(1500L, controller.videoHeartbeatIntervalMs)

        // Initial scroll events within 750ms schedule the 180ms settle timer
        assertEquals(ScrollScheduleAction.SCHEDULE_SETTLE_TIMER, controller.onScrollEvent(1_000L))
        assertEquals(ScrollScheduleAction.SCHEDULE_SETTLE_TIMER, controller.onScrollEvent(1_300L))
        assertEquals(ScrollScheduleAction.SCHEDULE_SETTLE_TIMER, controller.onScrollEvent(1_600L))

        // At 1_750L (750ms of continuous scrolling without settling), force immediate scan
        assertEquals(
            ScrollScheduleAction.EXECUTE_IMMEDIATELY_MAX_WAIT_REACHED,
            controller.onScrollEvent(1_750L)
        )

        // Verify AOSP version-aware screenshot interval limits
        assertEquals(1050L, ScrollSettleDebounceController.minScreenshotIntervalMsForSdk(Build.VERSION_CODES.R))
        assertEquals(380L, ScrollSettleDebounceController.minScreenshotIntervalMsForSdk(Build.VERSION_CODES.S))
        assertEquals(380L, ScrollSettleDebounceController.minScreenshotIntervalMsForSdk(Build.VERSION_CODES.UPSIDE_DOWN_CAKE))

        // Verify rate-limit enforcement after a dispatched scan
        controller.onScanDispatched(2_000L)
        assertFalse(controller.canExecuteScreenshot(2_250L, Build.VERSION_CODES.S))
        assertTrue(controller.canExecuteScreenshot(2_380L, Build.VERSION_CODES.S))
        assertFalse(controller.canExecuteScreenshot(2_900L, Build.VERSION_CODES.R))
        assertTrue(controller.canExecuteScreenshot(3_050L, Build.VERSION_CODES.R))
    }

    @Test
    fun testLauncherImmunityAndAppScopedOverlayDismissal() {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val manager = StrikeLockoutManager(prefs)
        val launcherPkg = "com.alhaq.amniquist"
        val browserPkg = "com.android.chrome"

        // 1. Custom launcher (com.alhaq.amniquist) is recognized as a protected launcher
        assertTrue(
            FastPathUrlAndKeywordScanner.isProtectedLauncherOrSystemPackage(
                pkg = launcherPkg,
                selfPackageName = "com.amngaze"
            )
        )

        // 2. Dynamic browser registration adds custom browsers but NEVER adds protected launchers
        FastPathUrlAndKeywordScanner.registerDynamicBrowsers(
            discoveredBrowserPackages = setOf("org.custom.browser.app", launcherPkg),
            selfPackageName = "com.amngaze",
            detectedHomePackages = setOf(launcherPkg)
        )
        assertTrue("org.custom.browser.app" in FastPathUrlAndKeywordScanner.BROWSER_PACKAGES)
        assertTrue("com.google.android.apps.photos" in FastPathUrlAndKeywordScanner.HIGH_RISK_MEDIA_PACKAGES)
        assertFalse(launcherPkg in FastPathUrlAndKeywordScanner.BROWSER_PACKAGES)
        assertFalse(launcherPkg in FastPathUrlAndKeywordScanner.HIGH_RISK_MEDIA_PACKAGES)

        // 3. Launcher can NEVER record strikes or be locked out even after 10 attempts
        for (i in 1..10) {
            val decision = manager.recordViolation(launcherPkg, 10_000L + i * 1_000L)
            assertEquals(0, decision.strikeCountInWindow)
            assertFalse(decision.isLockedOut)
        }
        assertFalse(manager.isPackageLocked(launcherPkg, 25_000L))

        // 4. Overlay scoped to Chrome immediately dismisses when navigating to launcher or another app,
        //    but does NOT flicker when the soft keyboard appears inside Chrome
        assertFalse(
            FastPathUrlAndKeywordScanner.shouldDismissOverlayForPackageChange(
                shieldedPackage = browserPkg,
                foregroundPackage = browserPkg
            )
        )
        assertFalse(
            FastPathUrlAndKeywordScanner.shouldDismissOverlayForPackageChange(
                shieldedPackage = browserPkg,
                foregroundPackage = "com.google.android.inputmethod.latin"
            )
        )
        assertTrue(
            FastPathUrlAndKeywordScanner.shouldDismissOverlayForPackageChange(
                shieldedPackage = browserPkg,
                foregroundPackage = launcherPkg
            )
        )
        assertTrue(
            FastPathUrlAndKeywordScanner.shouldDismissOverlayForPackageChange(
                shieldedPackage = browserPkg,
                foregroundPackage = "com.amngaze"
            )
        )
    }

    @Test
    fun testMultiCropExtractorOnWideLandscapeBitmap() {
        val wideBitmap = android.graphics.Bitmap.createBitmap(
            2400,
            1080,
            android.graphics.Bitmap.Config.ARGB_8888
        )
        try {
            val crops = ContentModerationEngine.extractMultiCrops(wideBitmap, targetSize = 224)
            assertEquals(3, crops.size)
            for (crop in crops) {
                assertFalse(crop.isRecycled)
                assertEquals(224, crop.width)
                assertEquals(224, crop.height)
                crop.recycle()
            }
        } finally {
            wideBitmap.recycle()
        }
    }

    @Test
    fun testMasterTogglesAndDropdownStateIsolation() = runBlocking {
        // Initially Module 1 is enabled & expanded; Module 2 (HaramBlur) is disabled & collapsed
        val initial = viewModel.state.first()
        assertTrue(initial.isExplicitBlockerEnabled)
        assertTrue(initial.isExplicitSettingsExpanded)
        assertFalse(initial.isHaramBlurEnabled)
        assertFalse(initial.isHaramBlurSettingsExpanded)

        // Toggling Module 2 ON expands Module 2 settings without mutating Module 1 state
        viewModel.setHaramBlurEnabled(true)
        viewModel.updateHaramBlurTargetGender(HaramBlurTargetGender.BOTH)
        viewModel.updateHaramBlurSensitivity(HaramBlurSensitivity.HIGH)
        viewModel.updateHaramBlurOverlayStyle(HaramBlurOverlayStyle.PIXELATE)
        viewModel.updateHaramBlurEffectStrength(9)

        val afterHaramBlurOn = viewModel.state.first()
        assertTrue(afterHaramBlurOn.isHaramBlurEnabled)
        assertTrue(afterHaramBlurOn.isHaramBlurSettingsExpanded)
        assertEquals(HaramBlurTargetGender.BOTH, afterHaramBlurOn.haramBlurTargetGender)
        assertEquals(HaramBlurSensitivity.HIGH, afterHaramBlurOn.haramBlurSensitivity)
        assertEquals(HaramBlurOverlayStyle.PIXELATE, afterHaramBlurOn.haramBlurOverlayStyle)
        assertEquals(9, afterHaramBlurOn.haramBlurEffectStrength)
        // Module 1 remains completely untouched
        assertTrue(afterHaramBlurOn.isExplicitBlockerEnabled)
        assertEquals(EnforcementMode.CURTAIN_SHIELD, afterHaramBlurOn.enforcementMode)

        // Toggling Module 1 OFF collapses Module 1 settings without turning off Module 2
        viewModel.setExplicitBlockerEnabled(false)
        val afterExplicitOff = viewModel.state.first()
        assertFalse(afterExplicitOff.isExplicitBlockerEnabled)
        assertFalse(afterExplicitOff.isExplicitSettingsExpanded)
        assertTrue(afterExplicitOff.isHaramBlurEnabled)
        assertTrue(afterExplicitOff.isHaramBlurSettingsExpanded)
    }

    @Test
    fun testHaramBlurFilterLogicAndEffectStrengthScaling() {
        // Sensitivity thresholds
        assertEquals(0.70f, HaramBlurSensitivity.LOW.threshold, 0.001f)
        assertEquals(0.52f, HaramBlurSensitivity.MEDIUM.threshold, 0.001f)
        assertEquals(0.35f, HaramBlurSensitivity.HIGH.threshold, 0.001f)

        // Effect strength 1..10 -> 12dp..38dp pixelation block size, 6px..42px blur radius
        assertEquals(12f, HaramBlurFilterLogic.computePixelationBlockSizeDp(1), 0.1f)
        assertEquals(38f, HaramBlurFilterLogic.computePixelationBlockSizeDp(10), 0.1f)
        assertEquals(6f, HaramBlurFilterLogic.computeBlurRadiusPx(1), 0.1f)
        assertEquals(42f, HaramBlurFilterLogic.computeBlurRadiusPx(10), 0.1f)

        // Target gender matching (FEMALE only vs MALE only vs BOTH)
        val femaleCandidate = CandidateBlurBox(
            normalizedRect = android.graphics.RectF(0.2f, 0.2f, 0.6f, 0.6f),
            gender = DetectedGender.FEMALE,
            genderConfidence = 0.82f,
            nsfwConfidence = 0.20f
        )
        val maleCandidate = CandidateBlurBox(
            normalizedRect = android.graphics.RectF(0.2f, 0.2f, 0.6f, 0.6f),
            gender = DetectedGender.MALE,
            genderConfidence = 0.85f,
            nsfwConfidence = 0.10f
        )

        assertTrue(
            HaramBlurFilterLogic.shouldBlurCandidate(
                candidate = femaleCandidate,
                targetGender = HaramBlurTargetGender.FEMALE,
                sensitivity = HaramBlurSensitivity.MEDIUM
            )
        )
        assertFalse(
            HaramBlurFilterLogic.shouldBlurCandidate(
                candidate = femaleCandidate,
                targetGender = HaramBlurTargetGender.MALE,
                sensitivity = HaramBlurSensitivity.MEDIUM
            )
        )
        assertTrue(
            HaramBlurFilterLogic.shouldBlurCandidate(
                candidate = maleCandidate,
                targetGender = HaramBlurTargetGender.MALE,
                sensitivity = HaramBlurSensitivity.MEDIUM
            )
        )
        assertTrue(
            HaramBlurFilterLogic.shouldBlurCandidate(
                candidate = maleCandidate,
                targetGender = HaramBlurTargetGender.BOTH,
                sensitivity = HaramBlurSensitivity.MEDIUM
            )
        )
    }

    @Test
    fun testAntiFlickerRegionTrackerPreventsSelfOcclusionUnblurAndClearsOnScroll() {
        val tracker = AntiFlickerRegionTracker(
            stationaryExteriorDiffThreshold = 0.04f,
            ttlMs = 350L
        )

        val candidate = CandidateBlurBox(
            normalizedRect = android.graphics.RectF(0.20f, 0.25f, 0.80f, 0.75f),
            gender = DetectedGender.FEMALE,
            genderConfidence = 0.88f,
            nsfwConfidence = 0.25f
        )

        // Frame 1 at t=1000ms: candidate detected -> 1 active region
        val frame1Regions = tracker.updateRegions(
            candidates = listOf(candidate),
            exteriorDiff = 0.20f,
            scrollOccurred = false,
            nowMs = 1_000L,
            targetGender = HaramBlurTargetGender.FEMALE,
            sensitivity = HaramBlurSensitivity.MEDIUM
        )
        assertEquals(1, frame1Regions.size)

        // Frame 2 at t=1500ms (past 350ms TTL): MediaProjection captures its own blur box so
        // candidates is empty, BUT exterior pixels outside the blur box are stationary (exteriorDiff = 0.01f < 0.04f).
        // The region MUST remain locked with zero flicker!
        val frame2Regions = tracker.updateRegions(
            candidates = emptyList(),
            exteriorDiff = 0.01f,
            scrollOccurred = false,
            nowMs = 1_500L,
            targetGender = HaramBlurTargetGender.FEMALE,
            sensitivity = HaramBlurSensitivity.MEDIUM
        )
        assertEquals(1, frame2Regions.size)
        assertTrue(frame2Regions[0].rectF.left <= 0.20f)

        // Frame 3 at t=1900ms: User scrolls / switches page so exterior pixels change significantly (exteriorDiff = 0.35f > 0.04f)
        val frame3Regions = tracker.updateRegions(
            candidates = emptyList(),
            exteriorDiff = 0.35f,
            scrollOccurred = false,
            nowMs = 1_900L,
            targetGender = HaramBlurTargetGender.FEMALE,
            sensitivity = HaramBlurSensitivity.MEDIUM
        )
        assertTrue(frame3Regions.isEmpty())

        // Verify Stage 3.4: Accessibility TYPE_VIEW_SCROLLED with 0f delta RETAINS active regions (anti-wipeout)
        tracker.updateRegions(
            candidates = listOf(candidate),
            exteriorDiff = 0.20f,
            scrollOccurred = false,
            nowMs = 2_000L,
            targetGender = HaramBlurTargetGender.FEMALE,
            sensitivity = HaramBlurSensitivity.MEDIUM
        )
        assertEquals(1, tracker.getActiveRegions().size)
        tracker.onScrollDetected(0f)
        assertEquals(1, tracker.getActiveRegions().size)

        // Large page jump (>= 0.45f) clears regions
        tracker.onScrollDetected(0.55f)
        assertTrue(tracker.getActiveRegions().isEmpty())
    }

    @Test
    fun testAutoRunReminderControllerWithLauncherImmunity() {
        val controller = AutoRunReminderController(reminderCooldownMs = 60_000L)
        val autoRunApps = setOf("com.instagram.android", "com.alhaq.amniquist")

        // Protected launcher (com.alhaq.amniquist) NEVER triggers auto-run reminder even if in set
        assertFalse(
            controller.shouldEmitAutoRunReminder(
                foregroundPackage = "com.alhaq.amniquist",
                autoRunPackages = autoRunApps,
                isHaramBlurEnabled = false,
                nowMs = 10_000L,
                selfPackageName = "com.amngaze",
                detectedHomePackages = setOf("com.alhaq.amniquist")
            )
        )

        // Entering Instagram when HaramBlur is OFF triggers reminder once, then respects cooldown
        assertTrue(
            controller.shouldEmitAutoRunReminder(
                foregroundPackage = "com.instagram.android",
                autoRunPackages = autoRunApps,
                isHaramBlurEnabled = false,
                nowMs = 10_000L,
                selfPackageName = "com.amngaze"
            )
        )
        assertFalse(
            controller.shouldEmitAutoRunReminder(
                foregroundPackage = "com.instagram.android",
                autoRunPackages = autoRunApps,
                isHaramBlurEnabled = false,
                nowMs = 30_000L,
                selfPackageName = "com.amngaze"
            )
        )
        assertTrue(
            controller.shouldEmitAutoRunReminder(
                foregroundPackage = "com.instagram.android",
                autoRunPackages = autoRunApps,
                isHaramBlurEnabled = false,
                nowMs = 75_000L,
                selfPackageName = "com.amngaze"
            )
        )
    }

    @Test
    fun testDualProtectionModeConnectedPlatformsAndParentalPin() = runBlocking {
        // Switch top-level mode to IN_APP_PROTECTION and verify persistence
        viewModel.updateAppProtectionMode(AppProtectionMode.IN_APP_PROTECTION)
        viewModel.addOrUpdateCustomPlatform("Reddit Mobile", "reddit.com")
        viewModel.toggleConnectedPlatformEnabled("facebook")
        assertTrue(viewModel.enableParentalPin("4826"))

        val state = viewModel.state.first()
        assertEquals(AppProtectionMode.IN_APP_PROTECTION, state.appProtectionMode)
        assertTrue(state.connectedPlatforms.any { it.name == "Reddit Mobile" && it.url == "https://reddit.com" })
        assertFalse(state.connectedPlatforms.first { it.id == "facebook" }.enabled)
        assertTrue(state.parentalPinEnabled)
        assertTrue(viewModel.verifyParentalPin("4826"))
        assertFalse(viewModel.verifyParentalPin("0000"))

        // Reload ViewModel from SharedPreferences and confirm persistence
        val reloaded = AmnGazeViewModel(context as Application).state.first()
        assertEquals(AppProtectionMode.IN_APP_PROTECTION, reloaded.appProtectionMode)
        assertTrue(reloaded.connectedPlatforms.any { it.name == "Reddit Mobile" })
        assertTrue(reloaded.parentalPinEnabled)
    }

    @Test
    fun testWebViewModerationBridgeCoordinateMappingFeedCssAndTimeLimits() {
        // 1. CSS rect -> 480px-wide bitmap rect mapping
        val mediaItem = ViewportMediaItem(
            id = "ag_m_1",
            tagName = "IMG",
            left = 90f,
            top = 160f,
            width = 180f,
            height = 160f,
            viewportWidth = 360f,
            viewportHeight = 640f
        )
        val rect = WebViewModerationBridge.mapCssRectToBitmapRect(
            item = mediaItem,
            bitmapWidth = 480,
            bitmapHeight = 853
        )
        assertNotNull(rect)
        assertEquals(120, rect!!.left)
        assertEquals(360, rect.right)

        // 2. JSON batch parsing & verdict serialization
        val batchJson = """
            [{"id":"ag_m_1","tag":"img","left":10,"top":20,"width":200,"height":200,"vw":360,"vh":640}]
        """.trimIndent()
        val parsed = WebViewModerationBridge.parseVisibleMediaBatchJson(batchJson)
        assertEquals(1, parsed.size)
        assertEquals("ag_m_1", parsed[0].id)

        val verdictsJson = WebViewModerationBridge.buildVerdictsJson(
            verdicts = listOf(MediaVerdict(id = "ag_m_1", shouldBlur = true, reason = "NSFW", confidence = 0.91f)),
            style = HaramBlurOverlayStyle.BLUR,
            strengthPx = 12
        )
        assertTrue(verdictsJson.contains("\"blur\":true"))
        assertTrue(verdictsJson.contains("\"style\":\"BLUR\""))

        // 3. Feed Control CSS generation hides Shorts, Reels, Algorithmic Feeds & Comments
        val css = WebViewModerationBridge.generateFeedControlCss(
            FeedControlConfig(
                hideYoutubeShorts = true,
                hideInstagramReels = true,
                hideAlgorithmicFeeds = true,
                hideComments = true
            )
        )
        assertTrue(css.contains("ytd-reel-shelf-renderer"))
        assertTrue(css.contains("a[href*=\"/reels/\"]"))
        assertTrue(css.contains("a[href*=\"/explore/\"]"))

        // 4. Daily platform usage limit tracker
        val usageMap = mapOf("youtube" to 15 * 60)
        assertFalse(
            PlatformUsageTracker.isDailyLimitReached(
                platformId = "youtube",
                dailyLimitMinutes = 20,
                usageSecondsMap = usageMap
            )
        )
        assertTrue(
            PlatformUsageTracker.isDailyLimitReached(
                platformId = "youtube",
                dailyLimitMinutes = 15,
                usageSecondsMap = usageMap
            )
        )
    }

    @Test
    fun testEffectStrengthSolidnessAndDensityCurveReachesOpaque255() = runBlocking {
        // Fix 1: 100% solid opaque alpha (255) across all levels 1..10 so raw pixels never bleed through
        for (lvl in 1..10) {
            assertEquals("Strength $lvl must be 100% solid opaque (alpha=255)", 255, HaramBlurFilterLogic.computeOverlayAlpha(lvl))
            assertEquals(100, HaramBlurFilterLogic.computeOverlaySolidnessPercent(lvl))
        }

        // Pixelation block size scales from 12dp at 1 up to 38dp at 10
        assertEquals(12f, HaramBlurFilterLogic.computePixelationBlockSizeDp(1), 0.01f)
        assertEquals(38f, HaramBlurFilterLogic.computePixelationBlockSizeDp(10), 0.01f)

        // Verify all overlay styles have alpha 0xFF (255) across all strength levels
        for (style in HaramBlurOverlayStyle.entries) {
            for (lvl in listOf(1, 5, 10)) {
                val color = HaramBlurFilterLogic.overlayColorArgb(style, lvl)
                val alpha = (color ushr 24) and 0xFF
                assertEquals("Style $style at level $lvl must have alpha 255", 255, alpha)
            }
        }

        // Verify ViewModel synchronizes 1..10 strength and 1..20px in-app strength
        viewModel.updateHaramBlurEffectStrength(10)
        assertEquals(10, viewModel.state.first().haramBlurEffectStrength)
        assertEquals(20, viewModel.state.first().inAppBlurStrengthPx)

        viewModel.updateInAppBlurStrengthPx(6)
        assertEquals(6, viewModel.state.first().inAppBlurStrengthPx)
        assertEquals(3, viewModel.state.first().haramBlurEffectStrength)
    }

    @Test
    fun testCalibratedSensitivityThresholdsAndSafeUiGating() {
        // Fix 2: Calibrated sensitivity-to-threshold mapping
        assertEquals(0.78f, ContentModerationEngine.confidenceThresholdForSensitivity(0.20f), 0.005f)
        assertEquals(0.74f, ContentModerationEngine.confidenceThresholdForSensitivity(0.25f), 0.005f)
        assertEquals(0.60f, ContentModerationEngine.confidenceThresholdForSensitivity(0.50f), 0.005f)
        assertEquals(0.48f, ContentModerationEngine.confidenceThresholdForSensitivity(0.75f), 0.005f)
        assertEquals(0.38f, ContentModerationEngine.confidenceThresholdForSensitivity(0.95f), 0.005f)

        // Fix 2: Text/UI protection: skinRatio < 0.05f and pornProb < 0.82f returns 0.0f (SAFE)
        val lowSkinTextScreen = floatArrayOf(0.10f, 0.05f, 0.40f, 0.35f, 0.10f)
        assertEquals(
            0.0f,
            ContentModerationEngine.computeFiveClassNsfwScore(lowSkinTextScreen, includeSexyScore = true, skinRatio = 0.02f),
            0.001f
        )

        // Fix 2: Safe UI guard: neutral + drawings >= 0.50f returns 0.0f (SAFE)
        val uiChromeScreen = floatArrayOf(0.20f, 0.0f, 0.35f, 0.25f, 0.20f) // neutral(0.35) + drawings(0.20) = 0.55 >= 0.50
        assertEquals(
            0.0f,
            ContentModerationEngine.computeFiveClassNsfwScore(uiChromeScreen, includeSexyScore = true, skinRatio = 0.15f),
            0.001f
        )

        // Fix 2: Hentai requirement: only included when hentai >= 0.65f AND neutral < 0.20f AND skinRatio >= 0.08f
        val weakHentai = floatArrayOf(0.10f, 0.55f, 0.15f, 0.0f, 0.0f) // hentai 0.55 < 0.65
        assertEquals(
            0.0f,
            ContentModerationEngine.computeFiveClassNsfwScore(weakHentai, includeSexyScore = true, skinRatio = 0.20f),
            0.001f
        )
        val strongHentai = floatArrayOf(0.05f, 0.72f, 0.10f, 0.0f, 0.0f) // hentai 0.72 >= 0.65, neutral 0.10 < 0.20
        assertTrue(
            ContentModerationEngine.computeFiveClassNsfwScore(strongHentai, includeSexyScore = true, skinRatio = 0.20f) >= 0.70f
        )

        // When includeSexyScore == false, sexy class is excluded
        val sexyOnly = floatArrayOf(0.05f, 0.0f, 0.10f, 0.10f, 0.75f)
        val scoreWithoutSexy = ContentModerationEngine.computeFiveClassNsfwScore(sexyOnly, includeSexyScore = false, skinRatio = 0.30f)
        assertEquals(0.10f, scoreWithoutSexy, 0.001f)
    }

    @Test
    fun testHaramBlurGridCellAndBodyDetectionWithoutFace() {
        // Fix 3: Swimwear/lingerie body region without face anchor (gender == UNKNOWN)
        val swimwearBody = CandidateBlurBox(
            normalizedRect = android.graphics.RectF(0.2f, 0.3f, 0.8f, 0.8f),
            gender = DetectedGender.UNKNOWN,
            genderConfidence = 0f,
            nsfwConfidence = 0.10f,
            explicitPornNudityConfidence = 0.10f,
            revealingSkinConfidence = 0.55f,
            isGridCell = true
        )
        // Body visual score = 0.10 + 0.55 = 0.65 >= 0.50 threshold -> blurred in FEMALE, MALE, and BOTH!
        assertTrue(
            HaramBlurFilterLogic.shouldBlurCandidate(
                candidate = swimwearBody,
                targetGender = HaramBlurTargetGender.FEMALE,
                sensitivity = HaramBlurSensitivity.MEDIUM
            )
        )
        assertTrue(
            HaramBlurFilterLogic.shouldBlurCandidate(
                candidate = swimwearBody,
                targetGender = HaramBlurTargetGender.MALE,
                sensitivity = HaramBlurSensitivity.MEDIUM
            )
        )
        assertTrue(
            HaramBlurFilterLogic.shouldBlurCandidate(
                candidate = swimwearBody,
                targetGender = HaramBlurTargetGender.BOTH,
                sensitivity = HaramBlurSensitivity.MEDIUM
            )
        )

        // Fix 3: Safe background cell without skin (gender == UNKNOWN, revealingSkin = 0.05f, porn = 0f)
        val safeBackgroundCell = CandidateBlurBox(
            normalizedRect = android.graphics.RectF(0.05f, 0.10f, 0.50f, 0.40f),
            gender = DetectedGender.UNKNOWN,
            genderConfidence = 0f,
            nsfwConfidence = 0.0f,
            explicitPornNudityConfidence = 0.0f,
            revealingSkinConfidence = 0.05f,
            isGridCell = true
        )
        assertFalse(
            HaramBlurFilterLogic.shouldBlurCandidate(
                candidate = safeBackgroundCell,
                targetGender = HaramBlurTargetGender.FEMALE,
                sensitivity = HaramBlurSensitivity.HIGH
            )
        )

        // Fix 3: Calling classifyCropForHaramBlur(cropBitmap, isGridCell = true) on a face-free background bitmap
        // returns gender == DetectedGender.UNKNOWN and shouldBlurCandidate == false
        val engineForGrid = ContentModerationEngine(context)
        val faceFreeBackgroundBmp = Bitmap.createBitmap(128, 128, Bitmap.Config.ARGB_8888)
        faceFreeBackgroundBmp.eraseColor(android.graphics.Color.rgb(30, 50, 90))
        try {
            val gridCandidate = engineForGrid.classifyCropForHaramBlur(
                cropBitmap = faceFreeBackgroundBmp,
                isGridCell = true
            )
            assertEquals(DetectedGender.UNKNOWN, gridCandidate.gender)
            assertTrue(gridCandidate.isGridCell)
            assertFalse(
                HaramBlurFilterLogic.shouldBlurCandidate(
                    candidate = gridCandidate,
                    targetGender = HaramBlurTargetGender.FEMALE,
                    sensitivity = HaramBlurSensitivity.HIGH
                )
            )
        } finally {
            faceFreeBackgroundBmp.recycle()
            engineForGrid.close()
        }

        // Fix 3: Candidate merging for overlapping body regions
        val boxA = CandidateBlurBox(
            normalizedRect = android.graphics.RectF(0.20f, 0.20f, 0.60f, 0.60f),
            gender = DetectedGender.UNKNOWN,
            genderConfidence = 0f,
            revealingSkinConfidence = 0.60f
        )
        val boxB = CandidateBlurBox(
            normalizedRect = android.graphics.RectF(0.30f, 0.40f, 0.70f, 0.80f),
            gender = DetectedGender.UNKNOWN,
            genderConfidence = 0f,
            revealingSkinConfidence = 0.70f
        )
        val merged = ContentModerationEngine.mergeOverlappingCandidates(listOf(boxA, boxB))
        assertEquals(1, merged.size)
        assertEquals(0.20f, merged[0].normalizedRect.left, 0.01f)
        assertEquals(0.20f, merged[0].normalizedRect.top, 0.01f)
        assertEquals(0.70f, merged[0].normalizedRect.right, 0.01f)
        assertEquals(0.80f, merged[0].normalizedRect.bottom, 0.01f)
    }

    @Test
    fun testStrictGenderIsolationPreventsOppositeGenderSkinBlurring() {
        // Shirtless / exposed-skin MALE subject (revealingSkinConfidence = 0.80f, explicitPornNudityConfidence = 0.10f)
        val maleWithExposedSkin = CandidateBlurBox(
            normalizedRect = android.graphics.RectF(0.2f, 0.2f, 0.7f, 0.8f),
            gender = DetectedGender.MALE,
            genderConfidence = 0.84f,
            nsfwConfidence = 0.10f,
            explicitPornNudityConfidence = 0.10f,
            revealingSkinConfidence = 0.80f
        )

        // When targetGender = FEMALE, a MALE face/figure/exposed-skin MUST NOT be blurred
        assertFalse(
            HaramBlurFilterLogic.shouldBlurCandidate(
                candidate = maleWithExposedSkin,
                targetGender = HaramBlurTargetGender.FEMALE,
                sensitivity = HaramBlurSensitivity.HIGH
            )
        )
        // When targetGender = MALE or BOTH, the MALE subject IS blurred
        assertTrue(
            HaramBlurFilterLogic.shouldBlurCandidate(
                candidate = maleWithExposedSkin,
                targetGender = HaramBlurTargetGender.MALE,
                sensitivity = HaramBlurSensitivity.MEDIUM
            )
        )
        assertTrue(
            HaramBlurFilterLogic.shouldBlurCandidate(
                candidate = maleWithExposedSkin,
                targetGender = HaramBlurTargetGender.BOTH,
                sensitivity = HaramBlurSensitivity.MEDIUM
            )
        )

        // Exposed-skin FEMALE subject (revealingSkinConfidence = 0.76f, explicitPornNudityConfidence = 0.08f)
        val femaleWithExposedSkin = CandidateBlurBox(
            normalizedRect = android.graphics.RectF(0.2f, 0.2f, 0.7f, 0.8f),
            gender = DetectedGender.FEMALE,
            genderConfidence = 0.45f,
            nsfwConfidence = 0.08f,
            explicitPornNudityConfidence = 0.08f,
            revealingSkinConfidence = 0.76f
        )

        // When targetGender = FEMALE, female exposed skin IS blurred even if face confidence alone was 0.45f
        assertTrue(
            HaramBlurFilterLogic.shouldBlurCandidate(
                candidate = femaleWithExposedSkin,
                targetGender = HaramBlurTargetGender.FEMALE,
                sensitivity = HaramBlurSensitivity.MEDIUM
            )
        )
        // When targetGender = MALE, female non-pornographic skin MUST NOT be blurred
        assertFalse(
            HaramBlurFilterLogic.shouldBlurCandidate(
                candidate = femaleWithExposedSkin,
                targetGender = HaramBlurTargetGender.MALE,
                sensitivity = HaramBlurSensitivity.HIGH
            )
        )

        // Mandatory hardcore pornography / explicit nudity is ALWAYS blurred regardless of targetGender
        val explicitPornMale = CandidateBlurBox(
            normalizedRect = android.graphics.RectF(0.1f, 0.1f, 0.9f, 0.9f),
            gender = DetectedGender.MALE,
            genderConfidence = 0.80f,
            nsfwConfidence = 0.88f,
            explicitPornNudityConfidence = 0.88f,
            revealingSkinConfidence = 0.90f
        )
        assertTrue(
            HaramBlurFilterLogic.shouldBlurCandidate(
                candidate = explicitPornMale,
                targetGender = HaramBlurTargetGender.FEMALE,
                sensitivity = HaramBlurSensitivity.LOW
            )
        )
        assertTrue(
            HaramBlurFilterLogic.shouldBlurCandidate(
                candidate = explicitPornMale,
                targetGender = HaramBlurTargetGender.MALE,
                sensitivity = HaramBlurSensitivity.LOW
            )
        )
    }

    @Test
    fun testPreBlurNewMediaSettingPersistenceAndWebViewScript() = runBlocking {
        assertFalse(viewModel.state.first().preBlurNewMediaUntilScanned)
        viewModel.setPreBlurNewMediaUntilScanned(true)
        assertTrue(viewModel.state.first().preBlurNewMediaUntilScanned)

        val reloaded = AmnGazeViewModel(context as Application).state.first()
        assertTrue(reloaded.preBlurNewMediaUntilScanned)

        val scriptWithPreBlur = WebViewModerationBridge.generateDomObserverJavascript(
            whatToProtect = setOf(WhatToProtectItem.IMAGES, WhatToProtectItem.VIDEOS),
            blurStyle = HaramBlurOverlayStyle.BLUR,
            strengthPx = 20,
            feedControlConfig = FeedControlConfig(),
            preBlurNewMediaUntilScanned = true
        )
        assertTrue(scriptWithPreBlur.contains("window.__amngazePreBlurEnabled = true"))
        assertTrue(scriptWithPreBlur.contains("PREBLUR"))
    }

    @Test
    fun testAutomaticModelRoutingAndChildSafe30sKeywordOnlySnooze() {
        // 1. Stage 1 & Fix 2: Automatic Model Routing — Adult Content Blocker exclusively uses QUANTIZED_NSFW
        assertEquals(
            ModerationModelType.QUANTIZED_NSFW,
            ContentModerationEngine.resolveAdultBlockerModel(ModerationModelType.GENDER_FILTER)
        )
        assertEquals(
            ModerationModelType.QUANTIZED_NSFW,
            ContentModerationEngine.resolveAdultBlockerModel(ModerationModelType.QUANTIZED_NSFW)
        )
        assertEquals(
            ModerationModelType.QUANTIZED_NSFW,
            ContentModerationEngine.resolveAdultBlockerModel(ModerationModelType.DEFAULT_NSFW)
        )

        val engine = ContentModerationEngine(context)
        val bmp = Bitmap.createBitmap(224, 224, Bitmap.Config.ARGB_8888)
        try {
            val verdict = engine.classifyBitmap(
                bitmap = bmp,
                sensitivity = 0.5f,
                modelType = ModerationModelType.GENDER_FILTER
            )
            // Even if GENDER_FILTER was passed in from legacy prefs, it is routed to QUANTIZED_NSFW
            assertNotEquals(ModerationModelType.GENDER_FILTER, verdict.modelType)
            assertEquals(ModerationModelType.QUANTIZED_NSFW, verdict.modelType)
        } finally {
            bmp.recycle()
            engine.close()
        }

        // 2. Stage 2: Child-Safe 30s Keyword-Only Snooze
        assertTrue(StrikeLockoutManager.canSnoozeViolationFor30Seconds(ViolationTriggerSource.TEXT_KEYWORD_MATCH))
        assertFalse(StrikeLockoutManager.canSnoozeViolationFor30Seconds(ViolationTriggerSource.ADULT_DOMAIN_URL))
        assertFalse(StrikeLockoutManager.canSnoozeViolationFor30Seconds(ViolationTriggerSource.VISUAL_NSFW_IMAGE_OR_VIDEO))

        val lockoutMgr = StrikeLockoutManager()
        val scanner = FastPathUrlAndKeywordScanner()
        scanner.updateBlockedDomains(setOf("pornhub.com", "xvideos.com"))

        // Before snooze: standalone keyword triggers TEXT_KEYWORD_MATCH
        val keywordViolationBefore = scanner.findViolation(
            rawInput = "Watch free porno videos",
            isUrlBarNode = false,
            isKeywordSnoozed = lockoutMgr.isKeywordTextSnoozed("com.android.chrome", 10_000L)
        )
        assertNotNull(keywordViolationBefore)
        assertEquals(ViolationTriggerSource.TEXT_KEYWORD_MATCH, keywordViolationBefore!!.triggerSource)

        // Snooze keyword text for 30s on Chrome (expires at 40_000L)
        lockoutMgr.snoozeKeywordTextOnly("com.android.chrome", 10_000L, 30_000L)
        assertTrue(lockoutMgr.isKeywordTextSnoozed("com.android.chrome", 25_000L))

        // During 30s keyword snooze (at 25_000L): standalone text keyword is suppressed...
        val keywordViolationDuringSnooze = scanner.findViolation(
            rawInput = "Watch free porno videos",
            isUrlBarNode = false,
            isKeywordSnoozed = lockoutMgr.isKeywordTextSnoozed("com.android.chrome", 25_000L)
        )
        assertNull(keywordViolationDuringSnooze)

        // ...BUT explicit adult domain URLs (e.g., pornhub.com) are STILL IMMEDIATELY BLOCKED!
        val adultUrlDuringSnooze = scanner.findViolation(
            rawInput = "https://www.pornhub.com/view_video.php?viewkey=123",
            isUrlBarNode = true,
            isKeywordSnoozed = lockoutMgr.isKeywordTextSnoozed("com.android.chrome", 25_000L)
        )
        assertNotNull("Adult domain URLs must NEVER be bypassed during a 30s keyword snooze", adultUrlDuringSnooze)
        assertEquals(ViolationTriggerSource.ADULT_DOMAIN_URL, adultUrlDuringSnooze!!.triggerSource)

        // After 30s expiry (at 41_000L): keyword snooze expires and keywords trigger again
        assertFalse(lockoutMgr.isKeywordTextSnoozed("com.android.chrome", 41_000L))
        val keywordAfterExpiry = scanner.findViolation(
            rawInput = "Watch free porno videos",
            isUrlBarNode = false,
            isKeywordSnoozed = lockoutMgr.isKeywordTextSnoozed("com.android.chrome", 41_000L)
        )
        assertNotNull(keywordAfterExpiry)
    }
}


