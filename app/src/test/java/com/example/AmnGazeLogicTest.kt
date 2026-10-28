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
        assertEquals(0.75f, ContentModerationEngine.confidenceThresholdForSensitivity(0.5f), 0.001f)
    }

    @Test
    fun testScrollSettleDebounceAndContinuousScrollCeiling() {
        val controller = ScrollSettleDebounceController(
            settleDelayMs = 180L,
            maxContinuousScrollWaitMs = 750L
        )

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

        // 2. Launcher can NEVER record strikes or be locked out even after 10 attempts
        for (i in 1..10) {
            val decision = manager.recordViolation(launcherPkg, 10_000L + i * 1_000L)
            assertEquals(0, decision.strikeCountInWindow)
            assertFalse(decision.isLockedOut)
        }
        assertFalse(manager.isPackageLocked(launcherPkg, 25_000L))

        // 3. Overlay scoped to Chrome immediately dismisses when navigating to launcher or another app,
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
}
