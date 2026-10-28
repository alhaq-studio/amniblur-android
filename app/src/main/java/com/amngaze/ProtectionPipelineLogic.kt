package com.amngaze

import android.content.SharedPreferences
import android.os.Build
import java.net.URI
import java.util.concurrent.ConcurrentHashMap

/**
 * Pure, deterministic state machines and matchers powering the 3-Layer Accessibility Pipeline:
 * 1. [StrikeLockoutManager]: Rolling 5-minute 3-strike tracker, 15-minute package lockout,
 *    and 30-second false-positive package whitelist.
 * 2. [FastPathUrlAndKeywordScanner]: O(1) domain HashSet lookup (backed by assets/nsfw.txt)
 *    for browser URL bars and high-precision explicit domain/keyword matching.
 * 3. [ScrollSettleDebounceController]: Hybrid Leading + Trailing scroll debounce state machine
 *    (180ms settle delay + 750ms continuous-scroll ceiling) and version-aware AOSP rate-limit guard.
 */
data class StrikeDecision(
    val packageName: String,
    val strikeCountInWindow: Int,
    val isLockedOut: Boolean,
    val lockExpiryMs: Long,
    val remainingLockMs: Long
)

class StrikeLockoutManager(
    private val prefs: SharedPreferences? = null,
    val strikeWindowMs: Long = DEFAULT_STRIKE_WINDOW_MS,
    val maxStrikesBeforeLockout: Int = DEFAULT_MAX_STRIKES,
    val lockoutDurationMs: Long = DEFAULT_LOCKOUT_DURATION_MS,
    val whitelistDurationMs: Long = DEFAULT_WHITELIST_DURATION_MS
) {
    companion object {
        const val DEFAULT_STRIKE_WINDOW_MS = 5 * 60_000L      // 5-minute rolling strike window
        const val DEFAULT_MAX_STRIKES = 3                     // 3 strikes trigger lockout
        const val DEFAULT_LOCKOUT_DURATION_MS = 15 * 60_000L  // 15-minute package cooldown
        const val DEFAULT_WHITELIST_DURATION_MS = 30_000L     // 30-second false-positive unblur
    }

    private val strikeTimestamps = ConcurrentHashMap<String, MutableList<Long>>()
    private val lockedPackages = ConcurrentHashMap<String, Long>()
    private val whitelistedPackages = ConcurrentHashMap<String, Long>()

    init {
        loadPersistedLockouts()
    }

    @Synchronized
    fun isPackageLocked(packageName: String, nowMs: Long): Boolean {
        if (packageName !in FastPathUrlAndKeywordScanner.HIGH_RISK_MEDIA_PACKAGES) {
            if (lockedPackages.remove(packageName) != null) {
                strikeTimestamps.remove(packageName)
                persistLockouts()
            }
            return false
        }
        val expiry = lockedPackages[packageName] ?: return false
        return if (nowMs < expiry) {
            true
        } else {
            lockedPackages.remove(packageName)
            strikeTimestamps.remove(packageName)
            persistLockouts()
            false
        }
    }

    @Synchronized
    fun getRemainingLockoutMs(packageName: String, nowMs: Long): Long {
        if (!isPackageLocked(packageName, nowMs)) return 0L
        val expiry = lockedPackages[packageName] ?: return 0L
        return (expiry - nowMs).coerceAtLeast(0L)
    }

    @Synchronized
    fun recordViolation(packageName: String, nowMs: Long): StrikeDecision {
        whitelistedPackages.remove(packageName)

        // Never record strikes or lockouts for launchers, system UI, or non-monitored packages
        if (packageName !in FastPathUrlAndKeywordScanner.HIGH_RISK_MEDIA_PACKAGES) {
            return StrikeDecision(
                packageName = packageName,
                strikeCountInWindow = 0,
                isLockedOut = false,
                lockExpiryMs = 0L,
                remainingLockMs = 0L
            )
        }

        if (isPackageLocked(packageName, nowMs)) {
            val expiry = lockedPackages[packageName] ?: nowMs
            return StrikeDecision(
                packageName = packageName,
                strikeCountInWindow = maxStrikesBeforeLockout,
                isLockedOut = true,
                lockExpiryMs = expiry,
                remainingLockMs = (expiry - nowMs).coerceAtLeast(0L)
            )
        }

        val history = strikeTimestamps.getOrPut(packageName) { mutableListOf() }
        val windowStart = nowMs - strikeWindowMs
        history.removeAll { it < windowStart }
        history.add(nowMs)

        val currentStrikes = history.size
        return if (currentStrikes >= maxStrikesBeforeLockout) {
            val expiry = nowMs + lockoutDurationMs
            lockedPackages[packageName] = expiry
            persistLockouts()
            StrikeDecision(
                packageName = packageName,
                strikeCountInWindow = currentStrikes,
                isLockedOut = true,
                lockExpiryMs = expiry,
                remainingLockMs = lockoutDurationMs
            )
        } else {
            StrikeDecision(
                packageName = packageName,
                strikeCountInWindow = currentStrikes,
                isLockedOut = false,
                lockExpiryMs = 0L,
                remainingLockMs = 0L
            )
        }
    }

    @Synchronized
    fun whitelistPackageTemporarily(packageName: String, nowMs: Long) {
        lockedPackages.remove(packageName)
        strikeTimestamps.remove(packageName)
        whitelistedPackages[packageName] = nowMs + whitelistDurationMs
        persistLockouts()
    }

    @Synchronized
    fun isPackageWhitelisted(packageName: String, nowMs: Long): Boolean {
        val expiry = whitelistedPackages[packageName] ?: return false
        return if (nowMs < expiry) {
            true
        } else {
            whitelistedPackages.remove(packageName)
            false
        }
    }

    @Synchronized
    fun purgeNonEligibleLockouts(eligiblePackages: Set<String> = FastPathUrlAndKeywordScanner.HIGH_RISK_MEDIA_PACKAGES) {
        val iterator = lockedPackages.entries.iterator()
        var changed = false
        while (iterator.hasNext()) {
            val entry = iterator.next()
            if (entry.key !in eligiblePackages) {
                iterator.remove()
                strikeTimestamps.remove(entry.key)
                changed = true
            }
        }
        if (changed) persistLockouts()
    }

    @Synchronized
    fun getActiveLockedPackages(nowMs: Long): Map<String, Long> {
        val iterator = lockedPackages.entries.iterator()
        var changed = false
        while (iterator.hasNext()) {
            val entry = iterator.next()
            if (nowMs >= entry.value || entry.key !in FastPathUrlAndKeywordScanner.HIGH_RISK_MEDIA_PACKAGES) {
                iterator.remove()
                strikeTimestamps.remove(entry.key)
                changed = true
            }
        }
        if (changed) persistLockouts()
        return lockedPackages.toMap()
    }

    @Synchronized
    fun clearAllLockouts() {
        lockedPackages.clear()
        strikeTimestamps.clear()
        whitelistedPackages.clear()
        persistLockouts()
    }

    private fun persistLockouts() {
        val sp = prefs ?: return
        val serialized = lockedPackages.entries
            .filter { it.key in FastPathUrlAndKeywordScanner.HIGH_RISK_MEDIA_PACKAGES }
            .joinToString(";") { "${it.key}=${it.value}" }
        sp.edit().putString(PREFS_KEY_LOCKED_PACKAGES, serialized).apply()
    }

    private fun loadPersistedLockouts() {
        val sp = prefs ?: return
        val raw = sp.getString(PREFS_KEY_LOCKED_PACKAGES, null) ?: return
        if (raw.isBlank()) return
        raw.split(";").forEach { token ->
            val parts = token.split("=")
            if (parts.size == 2) {
                val pkg = parts[0].trim()
                val expiry = parts[1].trim().toLongOrNull()
                if (pkg.isNotEmpty() && expiry != null && pkg in FastPathUrlAndKeywordScanner.HIGH_RISK_MEDIA_PACKAGES) {
                    lockedPackages[pkg] = expiry
                }
            }
        }
    }
}

/**
 * Layer 1 Fast-Path URL & Keyword Scanner (<5ms).
 *
 * Designed to avoid false positives:
 * 1. Only checks the 80k `assets/nsfw.txt` domain HashSet when inspecting an actual
 *    browser URL bar node (`isUrlBarNode == true`) or an explicit `http://` / `https://` / `www.` link,
 *    ignoring ultra-short (<7 chars) or misclassified non-adult entries in `nsfw.txt`.
 * 2. Removed ambiguous everyday words (`nude`, `nudes`, `naked`, `nsfw`, `xxx`) that appear in
 *    cosmetics, fashion, placeholder numbers (`XXX-XXX`), or settings toggles.
 * 3. Only flags unambiguous hardcore adult site names and explicit standalone tokens.
 */
class FastPathUrlAndKeywordScanner(
    initialBlockedDomains: Set<String> = emptySet()
) {
    companion object {
        val KNOWN_BROWSER_URL_BAR_IDS = setOf(
            "com.android.chrome:id/url_bar",
            "com.brave.browser:id/url_bar",
            "com.sec.android.app.sbrowser:id/location_bar_edit_text",
            "org.mozilla.firefox:id/mozac_browser_toolbar_url_view",
            "org.mozilla.fenix:id/mozac_browser_toolbar_url_view",
            "com.microsoft.emmx:id/url_bar",
            "com.opera.browser:id/url_field",
            "com.opera.mini.native:id/url_field",
            "com.duckduckgo.mobile.android:id/omnibarTextInput",
            "com.vivaldi.browser:id/url_bar",
            "com.kiwibrowser.browser:id/url_bar"
        )

        val DEFAULT_BROWSER_PACKAGES = setOf(
            "com.android.chrome",
            "com.chrome.beta",
            "com.chrome.dev",
            "com.chrome.canary",
            "org.mozilla.firefox",
            "org.mozilla.firefox_beta",
            "org.mozilla.fenix",
            "org.mozilla.focus",
            "com.brave.browser",
            "com.sec.android.app.sbrowser",
            "com.microsoft.emmx",
            "com.opera.browser",
            "com.opera.mini.native",
            "com.opera.gx",
            "com.duckduckgo.mobile.android",
            "com.vivaldi.browser",
            "com.kiwibrowser.browser",
            "com.UCMobile.intl",
            "com.yandex.browser",
            "org.torproject.torbrowser",
            "com.ecosia.android"
        )

        val DEFAULT_MEDIA_AND_SOCIAL_PACKAGES = setOf(
            // Social & Messaging
            "com.instagram.android",
            "com.twitter.android",
            "com.zhiliaoapp.musically",
            "com.ss.android.ugc.trill",
            "com.reddit.frontpage",
            "org.telegram.messenger",
            "org.thunderdog.challegram",
            "com.discord",
            "com.facebook.katana",
            "com.facebook.lite",
            "com.snapchat.android",
            "com.pinterest",
            "com.tumblr",
            "com.vkontakte.android",
            "com.whatsapp",
            // Search, Video & Media Players
            "com.google.android.googlequicksearchbox",
            "com.google.android.youtube",
            "app.revanced.android.youtube",
            "org.videolan.vlc",
            "com.mxtech.videoplayer.ad",
            "com.mxtech.videoplayer.pro",
            // Gallery & Photo Viewers
            "com.google.android.apps.photos",
            "com.google.android.apps.nbu.files",
            "com.sec.android.gallery3d",
            "com.miui.gallery",
            "com.coloros.gallery3d",
            "com.oneplus.gallery",
            "com.simplemobiletools.gallery.pro"
        )

        @Volatile
        var BROWSER_PACKAGES: Set<String> = DEFAULT_BROWSER_PACKAGES
            private set

        @Volatile
        var HIGH_RISK_MEDIA_PACKAGES: Set<String> = DEFAULT_BROWSER_PACKAGES + DEFAULT_MEDIA_AND_SOCIAL_PACKAGES
            private set

        /**
         * Dynamically registers installed browsers discovered via `PackageManager.queryIntentActivities`
         * at runtime while ensuring protected Home Launchers (`com.alhaq.amniquist`, etc.) are never added.
         */
        @Synchronized
        fun registerDynamicBrowsers(
            discoveredBrowserPackages: Collection<String>,
            selfPackageName: String = "com.amngaze",
            detectedHomePackages: Set<String> = emptySet()
        ) {
            val safeBrowsers = discoveredBrowserPackages.filterNot { pkg ->
                isProtectedLauncherOrSystemPackage(
                    pkg = pkg,
                    selfPackageName = selfPackageName,
                    detectedHomePackages = detectedHomePackages
                )
            }
            BROWSER_PACKAGES = DEFAULT_BROWSER_PACKAGES + safeBrowsers
            HIGH_RISK_MEDIA_PACKAGES = BROWSER_PACKAGES + DEFAULT_MEDIA_AND_SOCIAL_PACKAGES
        }

        /**
         * Launchers and core OS packages that must NEVER be scanned, locked, or covered by an overlay.
         * If any of these enter the foreground while a shield is visible for another app, the shield
         * is immediately dismissed so the user is never locked out of their launcher or phone.
         */
        val PROTECTED_LAUNCHER_AND_SYSTEM_PACKAGES = setOf(
            "com.alhaq.amniquist",
            "com.amngaze",
            "com.android.systemui",
            "com.android.settings",
            "com.android.permissioncontroller",
            "com.google.android.permissioncontroller",
            "com.google.android.apps.nexuslauncher",
            "com.sec.android.app.launcher",
            "com.miui.home",
            "com.huawei.android.launcher",
            "com.oppo.launcher",
            "com.android.launcher",
            "com.android.launcher3",
            "com.teslacoilsw.launcher",
            "app.lawnchair"
        )

        fun isProtectedLauncherOrSystemPackage(
            pkg: String,
            selfPackageName: String = "com.amngaze",
            detectedHomePackages: Set<String> = emptySet()
        ): Boolean {
            val lower = pkg.lowercase()
            if (pkg == selfPackageName || pkg in detectedHomePackages || lower in PROTECTED_LAUNCHER_AND_SYSTEM_PACKAGES) {
                return true
            }
            if (lower.contains("launcher") ||
                lower.contains("amniquist") ||
                lower.contains("trebuchet") ||
                lower.contains("lawnchair") ||
                lower.contains("systemui") ||
                lower.contains("permissioncontroller") ||
                lower.contains("inputmethod") ||
                lower.contains("keyboard")
            ) {
                return true
            }
            return false
        }

        fun isTransientInputMethodOverlay(pkg: String): Boolean {
            val lower = pkg.lowercase()
            return lower.contains("inputmethod") || lower.contains("keyboard") || lower.contains("gboard")
        }

        /**
         * Determines whether an active overlay scoped to [shieldedPackage] must be immediately
         * removed when [foregroundPackage] appears.
         *
         * Returns true whenever the user navigates outside [shieldedPackage] (e.g., to their Home
         * Launcher `com.alhaq.amniquist`, Recents/SystemUI, Settings, AmnGaze, or any other app).
         */
        fun shouldDismissOverlayForPackageChange(
            shieldedPackage: String?,
            foregroundPackage: String
        ): Boolean {
            if (shieldedPackage.isNullOrEmpty()) return true
            if (foregroundPackage.isEmpty()) return false
            if (isTransientInputMethodOverlay(foregroundPackage)) return false
            return foregroundPackage != shieldedPackage
        }

        /**
         * Known non-adult or benign domains that exist as noisy entries in third-party blocklists.
         */
        val SAFE_DOMAIN_ALLOWLIST = setOf(
            "google.com",
            "youtube.com",
            "reddit.com",
            "twitter.com",
            "x.com",
            "instagram.com",
            "facebook.com",
            "github.com",
            "wikipedia.org",
            "amazon.com",
            "apple.com",
            "microsoft.com",
            "en.1001cocktails.com",
            "1001cocktails.com",
            "explorer.01coin.io",
            "01coin.io"
        )

        /**
         * Unambiguous adult platform domain markers checked in URLs.
         */
        val EXPLICIT_DOMAIN_MARKERS = listOf(
            "pornhub.com",
            "xvideos.com",
            "xnxx.com",
            "onlyfans.com",
            "chaturbate.com",
            "redgifs.com",
            "xhamster.com",
            "youporn.com",
            "spankbang.com",
            "brazzers.com",
            "eporner.com",
            "stripchat.com",
            "cam4.com",
            "rule34.xxx",
            "nhentai.net",
            "hanime.tv"
        )

        /**
         * Strict, unambiguous explicit standalone tokens (word-boundary matched).
         * Ambiguous words like "nude", "naked", "nsfw", and "xxx" are intentionally excluded
         * to prevent false positives on shopping, cosmetics, masked numbers, or filter settings.
         */
        val EXPLICIT_STANDALONE_TOKENS = setOf(
            "pornhub",
            "xvideos",
            "xnxx",
            "xhamster",
            "youporn",
            "spankbang",
            "brazzers",
            "eporner",
            "chaturbate",
            "redgifs",
            "onlyfans",
            "nhentai",
            "hentai",
            "porno",
            "porn",
            "sexchat",
            "camgirl"
        )

        private val TOKEN_SPLIT_REGEX = Regex("[^a-z0-9]+")
    }

    @Volatile
    private var blockedDomains: Set<String> = sanitizeDomainSet(initialBlockedDomains)

    fun updateBlockedDomains(domains: Set<String>) {
        blockedDomains = sanitizeDomainSet(domains)
    }

    fun getBlockedDomainCount(): Int = blockedDomains.size

    private fun sanitizeDomainSet(rawDomains: Set<String>): Set<String> {
        if (rawDomains.isEmpty()) return emptySet()
        val cleaned = HashSet<String>(rawDomains.size)
        for (entry in rawDomains) {
            val d = entry.lowercase().trim().removePrefix("www.")
            // Ignore ultra-short domains (< 7 chars like "av.net", "60.ru", "1a.nl") and allowlisted domains
            if (d.length >= 7 && d.contains('.') && d !in SAFE_DOMAIN_ALLOWLIST) {
                cleaned.add(d)
            }
        }
        return cleaned
    }

    /**
     * Inspects a browser URL bar string or visible UI text node and returns the matched rule
     * description if explicit content is found, or null if safe.
     */
    fun findViolationReason(rawInput: String?, isUrlBarNode: Boolean = false): String? {
        if (rawInput.isNullOrBlank()) return null
        val normalized = rawInput.trim().lowercase()
        if (normalized.length < 4) return null

        // 1. Check unambiguous explicit domain markers
        for (marker in EXPLICIT_DOMAIN_MARKERS) {
            if (normalized.contains(marker)) {
                return "Blocked explicit domain ($marker)"
            }
        }

        // 2. Only check the 80k domain HashSet when reading an actual Browser URL bar node
        //    OR when the text explicitly starts with http://, https://, or www.
        val hasExplicitUrlPrefix = normalized.startsWith("http://") ||
            normalized.startsWith("https://") ||
            normalized.startsWith("www.")

        if (isUrlBarNode || hasExplicitUrlPrefix) {
            val candidateHost = extractCandidateHost(normalized, allowBareHost = isUrlBarNode)
            if (candidateHost != null && isHostInBlockedSet(candidateHost)) {
                return "Blocked adult domain ($candidateHost)"
            }
        }

        // 3. Word-boundary token scan against strict unambiguous adult tokens
        val words = normalized.split(TOKEN_SPLIT_REGEX)
        for (word in words) {
            if (word.length >= 4 && word in EXPLICIT_STANDALONE_TOKENS) {
                return "Blocked explicit keyword ($word)"
            }
        }

        return null
    }

    fun containsBlockedTerm(input: String?, isUrlBarNode: Boolean = false): Boolean {
        return findViolationReason(input, isUrlBarNode) != null
    }

    private fun extractCandidateHost(input: String, allowBareHost: Boolean): String? {
        val trimmed = input.trim()
        return try {
            val withScheme = if (trimmed.startsWith("http://") || trimmed.startsWith("https://")) {
                trimmed
            } else if (trimmed.startsWith("www.") || (allowBareHost && !trimmed.contains(' ') && trimmed.contains('.'))) {
                "https://$trimmed"
            } else {
                return null
            }
            val uri = URI(withScheme)
            uri.host?.lowercase()?.removePrefix("www.")
        } catch (_: Exception) {
            if (allowBareHost && !trimmed.contains(' ') && trimmed.contains('.')) {
                trimmed.substringBefore('/').substringBefore('?').removePrefix("www.")
            } else {
                null
            }
        }
    }

    private fun isHostInBlockedSet(host: String): Boolean {
        val cleanHost = host.removePrefix("www.").trim('.')
        if (cleanHost.length < 7 || cleanHost in SAFE_DOMAIN_ALLOWLIST) return false

        val currentSet = blockedDomains
        if (currentSet.isEmpty()) return false

        var cursor = cleanHost
        while (cursor.contains('.')) {
            if (cursor in SAFE_DOMAIN_ALLOWLIST) return false
            if (cursor.length >= 7 && cursor in currentSet) return true
            cursor = cursor.substringAfter('.')
        }
        return false
    }
}

enum class ScrollScheduleAction {
    SCHEDULE_SETTLE_TIMER,
    EXECUTE_IMMEDIATELY_MAX_WAIT_REACHED
}

class ScrollSettleDebounceController(
    val settleDelayMs: Long = DEFAULT_SETTLE_DELAY_MS,
    val followUpVerificationDelayMs: Long = DEFAULT_FOLLOW_UP_VERIFICATION_DELAY_MS,
    val videoHeartbeatIntervalMs: Long = DEFAULT_VIDEO_HEARTBEAT_INTERVAL_MS,
    val maxContinuousScrollWaitMs: Long = DEFAULT_MAX_SCROLL_WAIT_MS,
    val windowChangeDelayMs: Long = DEFAULT_WINDOW_CHANGE_DELAY_MS
) {
    companion object {
        const val DEFAULT_SETTLE_DELAY_MS = 180L
        const val DEFAULT_FOLLOW_UP_VERIFICATION_DELAY_MS = 900L
        const val DEFAULT_VIDEO_HEARTBEAT_INTERVAL_MS = 1500L
        const val DEFAULT_MAX_SCROLL_WAIT_MS = 750L
        const val DEFAULT_WINDOW_CHANGE_DELAY_MS = 250L

        fun minScreenshotIntervalMsForSdk(sdkInt: Int = Build.VERSION.SDK_INT): Long {
            return if (sdkInt >= Build.VERSION_CODES.S) 380L else 1050L
        }
    }

    private var scrollSequenceStartMs: Long = -1L
    private var lastCompletedOrTriggeredScanMs: Long = -10_000L

    @Synchronized
    fun onScrollEvent(nowMs: Long): ScrollScheduleAction {
        if (scrollSequenceStartMs < 0L) {
            scrollSequenceStartMs = nowMs
            return ScrollScheduleAction.SCHEDULE_SETTLE_TIMER
        }

        val elapsedScrolling = nowMs - scrollSequenceStartMs
        return if (elapsedScrolling >= maxContinuousScrollWaitMs) {
            scrollSequenceStartMs = nowMs
            ScrollScheduleAction.EXECUTE_IMMEDIATELY_MAX_WAIT_REACHED
        } else {
            ScrollScheduleAction.SCHEDULE_SETTLE_TIMER
        }
    }

    @Synchronized
    fun onScanDispatched(nowMs: Long) {
        scrollSequenceStartMs = -1L
        lastCompletedOrTriggeredScanMs = nowMs
    }

    @Synchronized
    fun canExecuteScreenshot(nowMs: Long, sdkInt: Int = Build.VERSION.SDK_INT): Boolean {
        val minInterval = minScreenshotIntervalMsForSdk(sdkInt)
        return (nowMs - lastCompletedOrTriggeredScanMs) >= minInterval
    }

    @Synchronized
    fun reset() {
        scrollSequenceStartMs = -1L
        lastCompletedOrTriggeredScanMs = -10_000L
    }
}
