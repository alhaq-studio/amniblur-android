package com.amngaze

import android.app.Application
import android.content.Context
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

        // 2. Real-world nudity / revealing frame splitting probability between porn (0.30) and sexy (0.50)
        //    hentaiProb (0.05f < 0.65f) is suppressed as UI noise -> explicitScore = pornProb = 0.30f
        //    combinedNsfwScore = 0.30 + (0.50 * 0.85) = 0.725f
        val splitNsfwProbs = floatArrayOf(0.03f, 0.05f, 0.12f, 0.30f, 0.50f)
        assertEquals(
            0.725f,
            ContentModerationEngine.computeFiveClassNsfwScore(splitNsfwProbs, includeSexyScore = true),
            0.001f
        )
        // When user disables revealing/sexy class, only porn (0.30) is counted
        assertEquals(
            0.30f,
            ContentModerationEngine.computeFiveClassNsfwScore(splitNsfwProbs, includeSexyScore = false),
            0.001f
        )

        // 3. Low skin-tone ratio (< 5%) on non-blatant porn always returns SAFE (0.0f)
        assertEquals(
            0.0f,
            ContentModerationEngine.computeFiveClassNsfwScore(splitNsfwProbs, includeSexyScore = true, skinRatio = 0.03f),
            0.001f
        )
    }

    @Test
    fun testSensitivitySliderThresholdMapping() {
        // 20% -> 0.78f, 25% -> 0.74f, 50% -> 0.60f, 75% -> 0.48f, 95% -> 0.38f
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

        // Verify onScrollDetected(0f) retains active regions (zero-delta scroll events must not wipe boxes),
        // while a large scroll (>= 0.35f) immediately clears active regions
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
        tracker.onScrollDetected(0.50f)
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
        // All strength levels 1..10 must be 100% solid opaque (alpha=255, zero see-through)
        for (s in 1..10) {
            assertEquals("Strength $s must be 100% opaque (alpha=255)", 255, HaramBlurFilterLogic.computeOverlayAlpha(s))
            assertEquals(100, HaramBlurFilterLogic.computeOverlaySolidnessPercent(s))
        }

        // Verify all overlay styles have alpha 0xFF (255) across all strengths 1..10
        for (style in HaramBlurOverlayStyle.entries) {
            for (s in 1..10) {
                val color = HaramBlurFilterLogic.overlayColorArgb(style, s)
                val alpha = (color ushr 24) and 0xFF
                assertEquals("Style $style must be 100% opaque (alpha=255) at strength $s", 255, alpha)
            }
        }

        // Verify computePixelationBlockSizeDp scales from 12dp at 1 up to 38dp at 10
        assertEquals(12f, HaramBlurFilterLogic.computePixelationBlockSizeDp(1), 0.001f)
        assertEquals(38f, HaramBlurFilterLogic.computePixelationBlockSizeDp(10), 0.001f)
        for (s in 1..9) {
            assertTrue(
                "Block size must increase monotonically",
                HaramBlurFilterLogic.computePixelationBlockSizeDp(s + 1) > HaramBlurFilterLogic.computePixelationBlockSizeDp(s)
            )
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
        assertTrue(scriptWithPreBlur.contains("__amngazePrepareCleanCapture"))
        assertTrue(scriptWithPreBlur.contains("MutationObserver"))
        assertTrue(scriptWithPreBlur.contains("IntersectionObserver"))
        assertTrue(scriptWithPreBlur.contains("amngazeSrc"))
        val prepareCallIdx = scriptWithPreBlur.indexOf("window.__amngazePrepareCleanCapture();")
        val batchCallIdx = scriptWithPreBlur.indexOf("window.AmnGazeBridge.onVisibleMediaBatch")
        assertTrue(
            "collectVisibleMedia must call __amngazePrepareCleanCapture() synchronously before onVisibleMediaBatch",
            prepareCallIdx in 0 until batchCallIdx
        )
    }

    @Test
    fun testMultiRegionPhotoVideoGridDetectionAndIndependentTracking() {
        // Construct a 2x3 grid bitmap (240x320) inside the active content area (top=0.08..0.92):
        // 3 tiles contain warm skin-tone portraits (R=225, G=165, B=135) and 3 tiles are safe dark blue (R=15, G=35, B=90)
        val w = 240
        val h = 320
        val gridBmp = android.graphics.Bitmap.createBitmap(w, h, android.graphics.Bitmap.Config.ARGB_8888)
        val safeBlue = android.graphics.Color.rgb(15, 35, 90)
        val warmSkin = android.graphics.Color.rgb(225, 165, 135)
        gridBmp.eraseColor(safeBlue)

        val cells = ContentModerationEngine.generateSpatialGridCells(
            rows = 2,
            cols = 3,
            topMargin = 0.08f,
            bottomMargin = 0.92f
        )
        assertEquals(6, cells.size)

        // Fill tiles 0 (top-left), 2 (top-right), and 4 (bottom-middle) with warm skin-tone pixels
        val skinTileIndices = listOf(0, 2, 4)
        for (idx in skinTileIndices) {
            val cell = cells[idx]
            val leftPx = (cell.left * w).toInt().coerceIn(0, w - 1)
            val rightPx = (cell.right * w).toInt().coerceIn(leftPx + 1, w)
            val topPx = (cell.top * h).toInt().coerceIn(0, h - 1)
            val bottomPx = (cell.bottom * h).toInt().coerceIn(topPx + 1, h)
            for (y in topPx until bottomPx) {
                for (x in leftPx until rightPx) {
                    gridBmp.setPixel(x, y, warmSkin)
                }
            }
        }

        val engine = ContentModerationEngine(context)
        try {
            val candidates = engine.detectRegionalBlurCandidates(
                frameBitmap = gridBmp,
                gridRows = 2,
                gridCols = 3
            )
            // Must detect the 3 skin tiles and NEVER flag the 3 safe dark-blue tiles
            assertEquals("Must emit exactly 3 independent candidate boxes for the 3 skin grid tiles", 3, candidates.size)

            val tracker = AntiFlickerRegionTracker()
            val trackedRegions = tracker.updateRegions(
                candidates = candidates,
                exteriorDiff = 0.25f,
                scrollOccurred = false,
                nowMs = 1_000L,
                targetGender = HaramBlurTargetGender.BOTH,
                sensitivity = HaramBlurSensitivity.MEDIUM
            )
            assertEquals("Tracker must maintain 3 separate blur regions simultaneously without merging across safe tiles", 3, trackedRegions.size)

            // Verify safe tile 1 (top-middle: x ~ 0.50, y ~ 0.29) is NOT covered by any blur box
            val safeTileCenter = cells[1]
            val safeCenterX = safeTileCenter.centerX()
            val safeCenterY = safeTileCenter.centerY()
            assertFalse(
                "Safe top-middle grid tile must remain unblurred",
                trackedRegions.any { it.rectF.contains(safeCenterX, safeCenterY) }
            )

            // Also verify tracker supports up to MAX_CONCURRENT_REGIONS = 16 independent non-overlapping boxes
            val sixteenCandidates = (0 until 16).map { i ->
                val row = i / 4
                val col = i % 4
                val l = col * 0.25f + 0.02f
                val t = row * 0.22f + 0.08f
                CandidateBlurBox(
                    normalizedRect = android.graphics.RectF(l, t, l + 0.20f, t + 0.18f),
                    gender = DetectedGender.FEMALE,
                    genderConfidence = 0.85f,
                    nsfwConfidence = 0.10f,
                    explicitPornNudityConfidence = 0.10f,
                    revealingSkinConfidence = 0.80f,
                    isGridCell = true
                )
            }
            val merged16 = HaramBlurFilterLogic.mergeAndCapCandidates(sixteenCandidates)
            assertEquals(16, merged16.size)
        } finally {
            engine.close()
            gridBmp.recycle()
        }
    }

    @Test
    fun testPerRegionPerimeterDiffAndIndependentLockedRegions() {
        val tracker = AntiFlickerRegionTracker()
        val prevBmp = android.graphics.Bitmap.createBitmap(120, 120, android.graphics.Bitmap.Config.ARGB_8888)
        val currBmp = android.graphics.Bitmap.createBitmap(120, 120, android.graphics.Bitmap.Config.ARGB_8888)
        prevBmp.eraseColor(android.graphics.Color.rgb(30, 40, 50))
        currBmp.eraseColor(android.graphics.Color.rgb(30, 40, 50))

        // Mutate bottom-right quadrant of currBmp while top-left region (0.10..0.40, 0.10..0.40) stays unchanged
        for (y in 70 until 115) {
            for (x in 70 until 115) {
                currBmp.setPixel(x, y, android.graphics.Color.rgb(240, 200, 180))
            }
        }

        val topLeftRect = android.graphics.RectF(0.10f, 0.10f, 0.40f, 0.40f)
        val bottomRightRect = android.graphics.RectF(0.55f, 0.55f, 0.90f, 0.90f)

        val topLeftPerimeterDiff = tracker.computeRegionPerimeterDiff(prevBmp, currBmp, topLeftRect, 4)
        val bottomRightPerimeterDiff = tracker.computeRegionPerimeterDiff(prevBmp, currBmp, bottomRightRect, 4)

        assertEquals(0f, topLeftPerimeterDiff, 0.001f)
        assertTrue(bottomRightPerimeterDiff > 0.10f)

        prevBmp.recycle()
        currBmp.recycle()
    }

    @Test
    fun testChildSafe30sSnoozeOnlyAppliesToTextKeywordMatchNeverAdultUrlOrVisualNsfw() {
        val lockoutManager = StrikeLockoutManager()
        val bundledDomains = FastPathUrlAndKeywordScanner.loadBundledDomainsFromAssets(context)
        assertTrue("Bundled assets/nsfw.txt domain set must load into memory", bundledDomains.size > 50_000)
        val scanner = FastPathUrlAndKeywordScanner(bundledDomains)
        val pkg = "com.android.chrome"
        val now = 50_000L

        // 1. Only TEXT_KEYWORD_MATCH is eligible for the 30-second snooze
        assertTrue(StrikeLockoutManager.canSnoozeViolationFor30s(ViolationTriggerSource.TEXT_KEYWORD_MATCH))
        assertFalse(StrikeLockoutManager.canSnoozeViolationFor30s(ViolationTriggerSource.ADULT_DOMAIN_URL))
        assertFalse(StrikeLockoutManager.canSnoozeViolationFor30s(ViolationTriggerSource.VISUAL_NSFW_IMAGE_OR_VIDEO))

        // 2. Activate 30-second keyword-only snooze for Chrome
        lockoutManager.snoozeKeywordTextOnly(pkg, now)
        assertTrue(lockoutManager.isKeywordTextSnoozed(pkg, now + 5_000L))

        // During the 30s snooze window:
        // - TEXT_KEYWORD_MATCH is suppressed
        assertFalse(lockoutManager.shouldBlockViolation(pkg, ViolationTriggerSource.TEXT_KEYWORD_MATCH, now + 5_000L))
        // - ADULT_DOMAIN_URL (e.g., pornhub.com) and VISUAL_NSFW_IMAGE_OR_VIDEO MUST STILL BE BLOCKED!
        assertTrue(lockoutManager.shouldBlockViolation(pkg, ViolationTriggerSource.ADULT_DOMAIN_URL, now + 5_000L))
        assertTrue(lockoutManager.shouldBlockViolation(pkg, ViolationTriggerSource.VISUAL_NSFW_IMAGE_OR_VIDEO, now + 5_000L))

        // 3. Verify FastPathUrlAndKeywordScanner categorizes adult domains vs text keywords properly
        val adultUrlMatch = scanner.findViolationMatch(
            rawInput = "https://www.pornhub.com/view_video.php?viewkey=123",
            isUrlBarNode = true,
            isKeywordSnoozed = true // Even while keyword snoozed!
        )
        assertNotNull("Adult domain URL must NEVER be suppressed by 30s keyword snooze", adultUrlMatch)
        assertEquals(ViolationTriggerSource.ADULT_DOMAIN_URL, adultUrlMatch!!.triggerSource)

        val snoozedKeywordMatch = scanner.findViolationMatch(
            rawInput = "Random article mentioning porn addiction in body text",
            isUrlBarNode = false,
            isKeywordSnoozed = true
        )
        assertNull("Standalone text keyword should be suppressed while keyword snooze is active", snoozedKeywordMatch)

        val activeKeywordMatch = scanner.findViolationMatch(
            rawInput = "Random article mentioning porn addiction in body text",
            isUrlBarNode = false,
            isKeywordSnoozed = false
        )
        assertNotNull(activeKeywordMatch)
        assertEquals(ViolationTriggerSource.TEXT_KEYWORD_MATCH, activeKeywordMatch!!.triggerSource)
    }

    @Test
    fun testAutomaticAdultBlockerModelRoutingNeverUsesGenderModelStandalone() {
        assertEquals(
            ModerationModelType.QUANTIZED_NSFW,
            ContentModerationEngine.resolveAdultBlockerModelType(ModerationModelType.GENDER_FILTER)
        )
        assertEquals(
            ModerationModelType.QUANTIZED_NSFW,
            ContentModerationEngine.resolveAdultBlockerModelType(ModerationModelType.QUANTIZED_NSFW)
        )
        assertEquals(
            ModerationModelType.QUANTIZED_NSFW,
            ContentModerationEngine.resolveAdultBlockerModelType(ModerationModelType.DEFAULT_NSFW)
        )

        val engine = ContentModerationEngine(context)
        val bmp = android.graphics.Bitmap.createBitmap(64, 64, android.graphics.Bitmap.Config.ARGB_8888)
        try {
            val verdict = engine.classifyBitmap(
                bitmap = bmp,
                sensitivity = 0.5f,
                modelType = ModerationModelType.GENDER_FILTER
            )
            assertEquals(ModerationModelType.QUANTIZED_NSFW, verdict.modelType)
        } finally {
            engine.close()
            bmp.recycle()
        }
    }

    @Test
    fun testTextAndUiCropsNeverFalseTriggerWithZeroSkinRatio() {
        // High neutral/drawings (>= 0.50f) and low skin ratio (< 0.05f) returns 0.0f (SAFE)
        val uiProbs = floatArrayOf(0.30f, 0.40f, 0.25f, 0.05f, 0.00f) // neutral + drawings = 0.55f >= 0.50f
        assertEquals(0.0f, ContentModerationEngine.computeFiveClassNsfwScore(uiProbs, includeSexyScore = true, skinRatio = 0.01f), 0.001f)

        // Hentai spike to 0.60f on UI page with neutral + drawings >= 0.50f returns 0.0f (SAFE)
        val hentai60Ui = floatArrayOf(0.20f, 0.60f, 0.35f, 0.02f, 0.01f) // neutral + drawings = 0.55f >= 0.50f
        assertEquals(0.0f, ContentModerationEngine.computeFiveClassNsfwScore(hentai60Ui, includeSexyScore = true, skinRatio = 0.06f), 0.001f)

        // Hentai spike to 0.60f with skinRatio < 0.05f returns 0.0f (SAFE) even if neutral + drawings < 0.50f
        val hentai60LowSkin = floatArrayOf(0.10f, 0.60f, 0.10f, 0.15f, 0.05f)
        assertEquals(0.0f, ContentModerationEngine.computeFiveClassNsfwScore(hentai60LowSkin, includeSexyScore = true, skinRatio = 0.03f), 0.001f)

        // Hentai spike without skin (skinRatio < 0.05f) returns SAFE
        val hentaiSpikeProbs = floatArrayOf(0.10f, 0.70f, 0.15f, 0.05f, 0.00f)
        assertEquals(0.0f, ContentModerationEngine.computeFiveClassNsfwScore(hentaiSpikeProbs, includeSexyScore = true, skinRatio = 0.02f), 0.001f)

        // With skin (skinRatio >= 0.08f) and hentai >= 0.65f and neutral < 0.20f, hentai is counted
        val realHentaiProbs = floatArrayOf(0.10f, 0.70f, 0.15f, 0.05f, 0.00f)
        val score = ContentModerationEngine.computeFiveClassNsfwScore(realHentaiProbs, includeSexyScore = false, skinRatio = 0.12f)
        assertTrue("Real hentai with skin must be detected: score=$score", score >= 0.70f)
    }

    @Test
    fun testFaceFreeGridCellsNeverRunGenderFilterAndBlurOnRevealingSkin() {
        val engine = ContentModerationEngine(context)
        val wallBmp = android.graphics.Bitmap.createBitmap(80, 80, android.graphics.Bitmap.Config.ARGB_8888)
        wallBmp.eraseColor(android.graphics.Color.rgb(40, 50, 60)) // Dark slate wall

        try {
            // Calling classifyCropForHaramBlur on a face-free background bitmap returns gender == UNKNOWN and shouldBlurCandidate == false
            val wallCandidate = engine.classifyCropForHaramBlur(wallBmp, isGridCell = true)
            assertEquals("Face-free background bitmap must have gender UNKNOWN", DetectedGender.UNKNOWN, wallCandidate.gender)
            assertEquals(0f, wallCandidate.genderConfidence, 0.001f)
            assertFalse(
                "Face-free background cell must NOT be blurred in FEMALE mode",
                HaramBlurFilterLogic.shouldBlurCandidate(wallCandidate, HaramBlurTargetGender.FEMALE, HaramBlurSensitivity.MEDIUM)
            )
            assertFalse(
                "Face-free background cell must NOT be blurred in BOTH mode",
                HaramBlurFilterLogic.shouldBlurCandidate(wallCandidate, HaramBlurTargetGender.BOTH, HaramBlurSensitivity.MEDIUM)
            )
        } finally {
            engine.close()
            wallBmp.recycle()
        }

        // 1. Grid cell without face anchor (isGridCell = true, gender = UNKNOWN)
        val lingerieGridCell = CandidateBlurBox(
            normalizedRect = android.graphics.RectF(0.33f, 0.08f, 0.66f, 0.38f),
            gender = DetectedGender.UNKNOWN,
            genderConfidence = 0f,
            nsfwConfidence = 0.15f,
            explicitPornNudityConfidence = 0.15f,
            revealingSkinConfidence = 0.62f,
            isGridCell = true
        )

        // Must blur in FEMALE and BOTH mode even without a face, because revealingSkin >= 0.25f and bodyVisualScore >= 0.42f
        assertTrue(
            "Lingerie/body grid cell without face must be blurred in FEMALE mode",
            HaramBlurFilterLogic.shouldBlurCandidate(
                candidate = lingerieGridCell,
                targetGender = HaramBlurTargetGender.FEMALE,
                sensitivity = HaramBlurSensitivity.MEDIUM
            )
        )
        assertTrue(
            "Lingerie/body grid cell without face must be blurred in BOTH mode",
            HaramBlurFilterLogic.shouldBlurCandidate(
                candidate = lingerieGridCell,
                targetGender = HaramBlurTargetGender.BOTH,
                sensitivity = HaramBlurSensitivity.MEDIUM
            )
        )

        // 2. Normal wall / background grid cell with zero skin and low porn
        val safeBackgroundCell = CandidateBlurBox(
            normalizedRect = android.graphics.RectF(0.02f, 0.08f, 0.33f, 0.38f),
            gender = DetectedGender.UNKNOWN,
            genderConfidence = 0f,
            nsfwConfidence = 0.02f,
            explicitPornNudityConfidence = 0.02f,
            revealingSkinConfidence = 0.00f,
            isGridCell = true
        )
        assertFalse(
            "Safe background grid cell must NOT be blurred",
            HaramBlurFilterLogic.shouldBlurCandidate(
                candidate = safeBackgroundCell,
                targetGender = HaramBlurTargetGender.FEMALE,
                sensitivity = HaramBlurSensitivity.MEDIUM
            )
        )
    }

    @Test
    fun testTightSkinBoundingBoxAndAdjacentBoxMerging() {
        // 1. Test tight bounding box on a 100x100 bitmap with a skin patch in the bottom-right quadrant
        val bmp = android.graphics.Bitmap.createBitmap(100, 100, android.graphics.Bitmap.Config.ARGB_8888)
        bmp.eraseColor(android.graphics.Color.rgb(10, 10, 10)) // black background
        val skinColor = android.graphics.Color.rgb(225, 165, 135)
        for (y in 50 until 95) {
            for (x in 50 until 95) {
                bmp.setPixel(x, y, skinColor)
            }
        }

        val cellRect = android.graphics.RectF(0.0f, 0.0f, 0.5f, 0.5f)
        val tightBox = ContentModerationEngine.computeTightSkinBoundingBox(bmp, cellRect)
        assertTrue("Tight box left must be >= cellRect left", tightBox.left >= cellRect.left)
        assertTrue("Tight box top must be >= cellRect top", tightBox.top >= cellRect.top)
        assertTrue("Tight box width must be smaller than full cell width", tightBox.width() < cellRect.width())
        bmp.recycle()

        // 2. Test shared edge overlap and merging
        val boxA = android.graphics.RectF(0.20f, 0.10f, 0.80f, 0.45f)
        val boxB = android.graphics.RectF(0.22f, 0.44f, 0.78f, 0.80f) // vertically adjacent with >50% horizontal overlap
        assertTrue(ContentModerationEngine.hasSharedEdgeOverlap(boxA, boxB))

        val candidateA = CandidateBlurBox(
            normalizedRect = boxA,
            gender = DetectedGender.FEMALE,
            genderConfidence = 0.85f,
            nsfwConfidence = 0.20f,
            isGridCell = true
        )
        val candidateB = CandidateBlurBox(
            normalizedRect = boxB,
            gender = DetectedGender.UNKNOWN,
            genderConfidence = 0f,
            revealingSkinConfidence = 0.75f,
            isGridCell = true
        )

        val merged = ContentModerationEngine.mergeAdjacentPersonBoxes(listOf(candidateA, candidateB))
        assertEquals("Adjacent person boxes must merge into 1 single box", 1, merged.size)
        assertEquals(DetectedGender.FEMALE, merged[0].gender)
        assertTrue("Merged box should span from top of boxA to bottom of boxB", merged[0].normalizedRect.top <= 0.10f && merged[0].normalizedRect.bottom >= 0.80f)
    }
}


