package com.amngaze

import android.app.Application
import android.content.Context
import android.os.SystemClock
import androidx.lifecycle.AndroidViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

class AmnGazeViewModel(application: Application) : AndroidViewModel(application) {
    private val prefs = application.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val strikeLockoutManager = StrikeLockoutManager(prefs)

    private val _state = MutableStateFlow(loadInitialState())
    val state: StateFlow<AmnGazeState> = _state.asStateFlow()

    private fun loadInitialState(): AmnGazeState {
        val now = SystemClock.elapsedRealtime()
        val lockedCount = strikeLockoutManager.getActiveLockedPackages(now).size
        val explicitEnabled = prefs.getBoolean(PREFS_KEY_EXPLICIT_BLOCKER_ENABLED, true)
        val haramBlurEnabled = prefs.getBoolean(PREFS_KEY_HARAMBLUR_ENABLED, false)

        return AmnGazeState(
            isSetupComplete = prefs.getBoolean(PREFS_KEY_SETUP_COMPLETE, false),
            hasAcceptedAccessibilityDisclosure = prefs.getBoolean(PREFS_KEY_DISCLOSURE_ACCEPTED, false),
            appProtectionMode = AppProtectionMode.entries.getOrElse(
                prefs.getInt(PREFS_KEY_APP_PROTECTION_MODE, AppProtectionMode.SYSTEM_WIDE_PROTECTION.ordinal)
            ) { AppProtectionMode.SYSTEM_WIDE_PROTECTION },
            currentTheme = AppTheme.entries.getOrElse(
                prefs.getInt(PREFS_KEY_APP_THEME, AppTheme.SUNSET_GLOW.ordinal)
            ) { AppTheme.SUNSET_GLOW },

            // Module 1: Explicit & Adult Content Blocker
            isExplicitBlockerEnabled = explicitEnabled,
            isExplicitSettingsExpanded = prefs.getBoolean(PREFS_KEY_EXPLICIT_SETTINGS_EXPANDED, explicitEnabled),
            enforcementMode = EnforcementMode.entries.getOrElse(
                prefs.getInt(PREFS_KEY_ENFORCEMENT_MODE, EnforcementMode.CURTAIN_SHIELD.ordinal)
            ) { EnforcementMode.CURTAIN_SHIELD },
            amnGazeBlurMode = BlurTargetMode.entries.getOrElse(
                prefs.getInt(PREFS_KEY_BLUR_TARGET_MODE, BlurTargetMode.FULL_SCREEN.ordinal)
            ) { BlurTargetMode.FULL_SCREEN },
            amnGazeCensorStyle = BlurCensorStyle.entries.getOrElse(
                prefs.getInt(PREFS_KEY_BLUR_CENSOR_STYLE, BlurCensorStyle.FROSTED.ordinal)
            ) { BlurCensorStyle.FROSTED },
            amnGazeBlurSensitivity = prefs.getFloat(PREFS_KEY_BLUR_SENSITIVITY, 0.5f),
            amnGazeShowBadge = prefs.getBoolean(PREFS_KEY_BLUR_SHOW_BADGE, true),
            amnGazeShowBorder = prefs.getBoolean(PREFS_KEY_BLUR_SHOW_BORDER, true),
            amnGazeModelType = ModerationModelType.entries.getOrElse(
                prefs.getInt(PREFS_KEY_MODEL_TYPE, ModerationModelType.DEFAULT_NSFW.ordinal)
            ) { ModerationModelType.DEFAULT_NSFW },
            amnGazeHardBlockEnabled = prefs.getBoolean(PREFS_KEY_HARD_BLOCK_ENABLED, false),
            amnGazeHardBlockThreshold = prefs.getFloat(PREFS_KEY_HARD_BLOCK_THRESHOLD, 0.85f),
            blockIncognitoEnabled = prefs.getBoolean(PREFS_KEY_BLOCK_INCOGNITO, true),
            urlFastPathEnabled = prefs.getBoolean(PREFS_KEY_URL_FAST_PATH, true),
            videoHeartbeatEnabled = prefs.getBoolean(PREFS_KEY_VIDEO_HEARTBEAT, true),
            includeSexyInNsfwScore = prefs.getBoolean(PREFS_KEY_INCLUDE_SEXY_SCORE, true),
            activeLockedPackageCount = lockedCount,
            totalDetectionsCount = prefs.getInt(PREFS_KEY_TOTAL_DETECTIONS, 0),

            // Module 2: HaramBlur Real-Time Regional Blurring
            isHaramBlurEnabled = haramBlurEnabled,
            isHaramBlurSettingsExpanded = prefs.getBoolean(PREFS_KEY_HARAMBLUR_SETTINGS_EXPANDED, haramBlurEnabled),
            haramBlurProtectionScope = HaramBlurProtectionScope.entries.getOrElse(
                prefs.getInt(PREFS_KEY_HARAMBLUR_SCOPE, HaramBlurProtectionScope.SYSTEM_WIDE.ordinal)
            ) { HaramBlurProtectionScope.SYSTEM_WIDE },
            autoRunPackages = loadStringSet(PREFS_KEY_HARAMBLUR_AUTO_RUN_PACKAGES),
            haramBlurTargetGender = HaramBlurTargetGender.entries.getOrElse(
                prefs.getInt(PREFS_KEY_HARAMBLUR_TARGET_GENDER, HaramBlurTargetGender.FEMALE.ordinal)
            ) { HaramBlurTargetGender.FEMALE },
            haramBlurSensitivity = HaramBlurSensitivity.entries.getOrElse(
                prefs.getInt(PREFS_KEY_HARAMBLUR_SENSITIVITY, HaramBlurSensitivity.MEDIUM.ordinal)
            ) { HaramBlurSensitivity.MEDIUM },
            haramBlurOverlayStyle = HaramBlurOverlayStyle.entries.getOrElse(
                prefs.getInt(PREFS_KEY_HARAMBLUR_OVERLAY_STYLE, HaramBlurOverlayStyle.BLUR.ordinal)
            ) { HaramBlurOverlayStyle.BLUR },
            haramBlurEffectStrength = prefs.getInt(PREFS_KEY_HARAMBLUR_EFFECT_STRENGTH, 10).coerceIn(1, 10),
            preBlurNewMediaUntilScanned = prefs.getBoolean(PREFS_KEY_PRE_BLUR_NEW_MEDIA, false),

            // In-App Protected WebView & Parental Controls
            inAppBlurStrengthPx = prefs.getInt(PREFS_KEY_IN_APP_BLUR_STRENGTH_PX, 10).coerceIn(1, 20),
            whatToProtect = loadWhatToProtect(),
            connectedPlatforms = loadConnectedPlatforms(),
            activePlatformId = prefs.getString(PREFS_KEY_ACTIVE_PLATFORM_ID, "google") ?: "google",
            feedControlConfig = FeedControlConfig(
                hideYoutubeShorts = prefs.getBoolean(PREFS_KEY_FEED_HIDE_SHORTS, true),
                hideInstagramReels = prefs.getBoolean(PREFS_KEY_FEED_HIDE_REELS, true),
                hideAlgorithmicFeeds = prefs.getBoolean(PREFS_KEY_FEED_HIDE_ALGORITHMIC, false),
                hideComments = prefs.getBoolean(PREFS_KEY_FEED_HIDE_COMMENTS, false)
            ),
            parentalPinEnabled = prefs.getBoolean(PREFS_KEY_PARENTAL_PIN_ENABLED, false),
            parentalPinCode = prefs.getString(PREFS_KEY_PARENTAL_PIN_CODE, "") ?: "",
            platformDailyUsageSeconds = loadUsageSecondsMap(),
            selectedLanguage = prefs.getString(PREFS_KEY_SELECTED_LANGUAGE, "English") ?: "English"
        )
    }

    fun updateTheme(theme: AppTheme) {
        _state.update { it.copy(currentTheme = theme) }
        prefs.edit().putInt(PREFS_KEY_APP_THEME, theme.ordinal).apply()
    }

    fun updateAppProtectionMode(mode: AppProtectionMode) {
        _state.update { it.copy(appProtectionMode = mode) }
        prefs.edit().putInt(PREFS_KEY_APP_PROTECTION_MODE, mode.ordinal).apply()
    }

    fun completeSetup() {
        _state.update { it.copy(isSetupComplete = true) }
        prefs.edit().putBoolean(PREFS_KEY_SETUP_COMPLETE, true).apply()
    }

    fun acceptAccessibilityDisclosure() {
        _state.update { it.copy(hasAcceptedAccessibilityDisclosure = true) }
        prefs.edit().putBoolean(PREFS_KEY_DISCLOSURE_ACCEPTED, true).apply()
    }

    fun toggleBackgroundPermission() {
        _state.update { it.copy(permissionBackgroundDone = !it.permissionBackgroundDone) }
    }

    fun toggleNotificationsPermission() {
        _state.update { it.copy(permissionNotificationsDone = !it.permissionNotificationsDone) }
    }

    fun toggleOverlayPermission() {
        _state.update { it.copy(permissionOverlayDone = !it.permissionOverlayDone) }
    }

    // --- Module 1: Explicit & Adult Content Blocker (3-Layer Accessibility Shield) ---

    /**
     * Enables/disables Master Toggle 1 and automatically expands/collapses its dropdown
     * while leaving Module 2 (HaramBlur) completely untouched.
     */
    fun setExplicitBlockerEnabled(enabled: Boolean) {
        _state.update {
            it.copy(
                isExplicitBlockerEnabled = enabled,
                isExplicitSettingsExpanded = enabled
            )
        }
        prefs.edit()
            .putBoolean(PREFS_KEY_EXPLICIT_BLOCKER_ENABLED, enabled)
            .putBoolean(PREFS_KEY_EXPLICIT_SETTINGS_EXPANDED, enabled)
            .apply()
    }

    fun toggleExplicitSettingsDropdown() {
        val updated = !_state.value.isExplicitSettingsExpanded
        _state.update { it.copy(isExplicitSettingsExpanded = updated) }
        prefs.edit().putBoolean(PREFS_KEY_EXPLICIT_SETTINGS_EXPANDED, updated).apply()
    }

    fun updateEnforcementMode(mode: EnforcementMode) {
        val hardBlock = (mode == EnforcementMode.HARD_EJECT)
        if (mode == EnforcementMode.CURTAIN_SHIELD) {
            strikeLockoutManager.clearAllLockouts()
        }
        val now = SystemClock.elapsedRealtime()
        val lockedCount = strikeLockoutManager.getActiveLockedPackages(now).size
        _state.update {
            it.copy(
                enforcementMode = mode,
                amnGazeHardBlockEnabled = hardBlock,
                activeLockedPackageCount = lockedCount
            )
        }
        prefs.edit()
            .putInt(PREFS_KEY_ENFORCEMENT_MODE, mode.ordinal)
            .putBoolean(PREFS_KEY_HARD_BLOCK_ENABLED, hardBlock)
            .apply()
    }

    fun updateBlurMode(mode: BlurTargetMode) {
        _state.update { it.copy(amnGazeBlurMode = mode) }
        prefs.edit().putInt(PREFS_KEY_BLUR_TARGET_MODE, mode.ordinal).apply()
    }

    fun updateCensorStyle(style: BlurCensorStyle) {
        _state.update { it.copy(amnGazeCensorStyle = style) }
        prefs.edit().putInt(PREFS_KEY_BLUR_CENSOR_STYLE, style.ordinal).apply()
    }

    fun updateBlurSensitivity(value: Float) {
        val clamped = value.coerceIn(0.2f, 0.95f)
        _state.update { it.copy(amnGazeBlurSensitivity = clamped) }
        prefs.edit().putFloat(PREFS_KEY_BLUR_SENSITIVITY, clamped).apply()
    }

    fun toggleBlurBadge() {
        val updated = !_state.value.amnGazeShowBadge
        _state.update { it.copy(amnGazeShowBadge = updated) }
        prefs.edit().putBoolean(PREFS_KEY_BLUR_SHOW_BADGE, updated).apply()
    }

    fun toggleBlurBorder() {
        val updated = !_state.value.amnGazeShowBorder
        _state.update { it.copy(amnGazeShowBorder = updated) }
        prefs.edit().putBoolean(PREFS_KEY_BLUR_SHOW_BORDER, updated).apply()
    }

    fun updateModelType(type: ModerationModelType) {
        _state.update { it.copy(amnGazeModelType = type) }
        prefs.edit().putInt(PREFS_KEY_MODEL_TYPE, type.ordinal).apply()
    }

    fun toggleHardBlock(enabled: Boolean) {
        val mode = if (enabled) EnforcementMode.HARD_EJECT else EnforcementMode.CURTAIN_SHIELD
        updateEnforcementMode(mode)
    }

    fun updateHardBlockThreshold(threshold: Float) {
        val clamped = threshold.coerceIn(0.5f, 0.95f)
        _state.update { it.copy(amnGazeHardBlockThreshold = clamped) }
        prefs.edit().putFloat(PREFS_KEY_HARD_BLOCK_THRESHOLD, clamped).apply()
    }

    fun toggleBlockIncognito() {
        val updated = !_state.value.blockIncognitoEnabled
        _state.update { it.copy(blockIncognitoEnabled = updated) }
        prefs.edit().putBoolean(PREFS_KEY_BLOCK_INCOGNITO, updated).apply()
    }

    fun toggleUrlFastPath() {
        val updated = !_state.value.urlFastPathEnabled
        _state.update { it.copy(urlFastPathEnabled = updated) }
        prefs.edit().putBoolean(PREFS_KEY_URL_FAST_PATH, updated).apply()
    }

    fun toggleVideoHeartbeat() {
        val updated = !_state.value.videoHeartbeatEnabled
        _state.update { it.copy(videoHeartbeatEnabled = updated) }
        prefs.edit().putBoolean(PREFS_KEY_VIDEO_HEARTBEAT, updated).apply()
    }

    fun toggleIncludeSexyScore() {
        val updated = !_state.value.includeSexyInNsfwScore
        _state.update { it.copy(includeSexyInNsfwScore = updated) }
        prefs.edit().putBoolean(PREFS_KEY_INCLUDE_SEXY_SCORE, updated).apply()
    }

    fun refreshProtectionMetrics() {
        val now = SystemClock.elapsedRealtime()
        val lockedCount = strikeLockoutManager.getActiveLockedPackages(now).size
        val totalDetections = prefs.getInt(PREFS_KEY_TOTAL_DETECTIONS, 0)
        _state.update {
            it.copy(
                activeLockedPackageCount = lockedCount,
                totalDetectionsCount = totalDetections
            )
        }
    }

    fun clearAllLockouts() {
        strikeLockoutManager.clearAllLockouts()
        _state.update { it.copy(activeLockedPackageCount = 0) }
    }

    // --- Module 2: HaramBlur Real-Time Regional Blurring (MediaProjection + Accessibility) ---

    /**
     * Enables/disables Master Toggle 2 and automatically expands/collapses its dropdown
     * while leaving Module 1 (Explicit Content Blocker) and its lockouts completely untouched.
     */
    fun setHaramBlurEnabled(enabled: Boolean) {
        _state.update {
            it.copy(
                isHaramBlurEnabled = enabled,
                isHaramBlurSettingsExpanded = enabled
            )
        }
        prefs.edit()
            .putBoolean(PREFS_KEY_HARAMBLUR_ENABLED, enabled)
            .putBoolean(PREFS_KEY_HARAMBLUR_SETTINGS_EXPANDED, enabled)
            .apply()
    }

    fun toggleHaramBlurSettingsDropdown() {
        val updated = !_state.value.isHaramBlurSettingsExpanded
        _state.update { it.copy(isHaramBlurSettingsExpanded = updated) }
        prefs.edit().putBoolean(PREFS_KEY_HARAMBLUR_SETTINGS_EXPANDED, updated).apply()
    }

    fun updateHaramBlurProtectionScope(scope: HaramBlurProtectionScope) {
        _state.update { it.copy(haramBlurProtectionScope = scope) }
        prefs.edit().putInt(PREFS_KEY_HARAMBLUR_SCOPE, scope.ordinal).apply()
    }

    fun updateAutoRunPackages(packages: Set<String>) {
        // Ensure protected launchers & self are never added to Auto-Run
        val filtered = packages.filterNot { pkg ->
            FastPathUrlAndKeywordScanner.isProtectedLauncherOrSystemPackage(pkg)
        }.toSet()
        _state.update { it.copy(autoRunPackages = filtered) }
        saveStringSet(PREFS_KEY_HARAMBLUR_AUTO_RUN_PACKAGES, filtered)
    }

    fun toggleAutoRunPackage(packageName: String) {
        if (FastPathUrlAndKeywordScanner.isProtectedLauncherOrSystemPackage(packageName)) return
        val current = _state.value.autoRunPackages.toMutableSet()
        if (!current.add(packageName)) {
            current.remove(packageName)
        }
        updateAutoRunPackages(current)
    }

    fun updateHaramBlurTargetGender(targetGender: HaramBlurTargetGender) {
        _state.update { it.copy(haramBlurTargetGender = targetGender) }
        prefs.edit().putInt(PREFS_KEY_HARAMBLUR_TARGET_GENDER, targetGender.ordinal).apply()
    }

    fun updateHaramBlurSensitivity(sensitivity: HaramBlurSensitivity) {
        _state.update { it.copy(haramBlurSensitivity = sensitivity) }
        prefs.edit().putInt(PREFS_KEY_HARAMBLUR_SENSITIVITY, sensitivity.ordinal).apply()
    }

    fun updateHaramBlurOverlayStyle(style: HaramBlurOverlayStyle) {
        _state.update { it.copy(haramBlurOverlayStyle = style) }
        prefs.edit().putInt(PREFS_KEY_HARAMBLUR_OVERLAY_STYLE, style.ordinal).apply()
    }

    fun updateHaramBlurEffectStrength(strength: Int) {
        val clamped = strength.coerceIn(1, 10)
        val syncedInAppPx = (clamped * 2).coerceIn(1, 20)
        _state.update {
            it.copy(
                haramBlurEffectStrength = clamped,
                inAppBlurStrengthPx = syncedInAppPx
            )
        }
        prefs.edit()
            .putInt(PREFS_KEY_HARAMBLUR_EFFECT_STRENGTH, clamped)
            .putInt(PREFS_KEY_IN_APP_BLUR_STRENGTH_PX, syncedInAppPx)
            .apply()
    }

    fun setPreBlurNewMediaUntilScanned(enabled: Boolean) {
        _state.update { it.copy(preBlurNewMediaUntilScanned = enabled) }
        prefs.edit().putBoolean(PREFS_KEY_PRE_BLUR_NEW_MEDIA, enabled).apply()
    }

    fun togglePreBlurNewMediaUntilScanned() {
        setPreBlurNewMediaUntilScanned(!_state.value.preBlurNewMediaUntilScanned)
    }

    // --- In-App Protected WebView Hub, Connected Platforms & Parental Controls ---

    fun updateInAppBlurStrengthPx(strengthPx: Int) {
        val clamped = strengthPx.coerceIn(1, 20)
        val syncedStrength10 = ((clamped + 1) / 2).coerceIn(1, 10)
        _state.update {
            it.copy(
                inAppBlurStrengthPx = clamped,
                haramBlurEffectStrength = syncedStrength10
            )
        }
        prefs.edit()
            .putInt(PREFS_KEY_IN_APP_BLUR_STRENGTH_PX, clamped)
            .putInt(PREFS_KEY_HARAMBLUR_EFFECT_STRENGTH, syncedStrength10)
            .apply()
    }

    fun toggleWhatToProtectItem(item: WhatToProtectItem) {
        val current = _state.value.whatToProtect.toMutableSet()
        if (!current.add(item)) {
            if (current.size > 1) {
                current.remove(item)
            }
        }
        _state.update { it.copy(whatToProtect = current.toSet()) }
        val serialized = current.joinToString(",") { it.name }
        prefs.edit().putString(PREFS_KEY_WHAT_TO_PROTECT, serialized).apply()
    }

    fun setActivePlatform(platformId: String) {
        _state.update { it.copy(activePlatformId = platformId) }
        prefs.edit().putString(PREFS_KEY_ACTIVE_PLATFORM_ID, platformId).apply()
    }

    fun toggleConnectedPlatformEnabled(platformId: String) {
        val updatedList = _state.value.connectedPlatforms.map { platform ->
            if (platform.id == platformId) {
                platform.copy(enabled = !platform.enabled)
            } else {
                platform
            }
        }
        saveConnectedPlatforms(updatedList)
    }

    fun addOrUpdateCustomPlatform(name: String, rawUrl: String, customId: String? = null): ConnectedPlatform? {
        val trimmedName = name.trim()
        val trimmedUrl = rawUrl.trim()
        if (trimmedName.isEmpty() || trimmedUrl.isEmpty()) return null
        val normalizedUrl = if (trimmedUrl.startsWith("http://") || trimmedUrl.startsWith("https://")) {
            trimmedUrl
        } else {
            "https://$trimmedUrl"
        }
        val id = customId ?: trimmedName.lowercase().replace(Regex("[^a-z0-9]+"), "_").trim('_')
            .ifEmpty { "custom_${System.currentTimeMillis()}" }

        val current = _state.value.connectedPlatforms.toMutableList()
        val existingIdx = current.indexOfFirst { it.id == id || it.url.equals(normalizedUrl, ignoreCase = true) }
        val entry = ConnectedPlatform(
            id = if (existingIdx >= 0) current[existingIdx].id else id,
            name = trimmedName,
            url = normalizedUrl,
            isBuiltIn = if (existingIdx >= 0) current[existingIdx].isBuiltIn else false,
            enabled = true,
            dailyLimitMinutes = if (existingIdx >= 0) current[existingIdx].dailyLimitMinutes else 0
        )
        if (existingIdx >= 0) {
            current[existingIdx] = entry
        } else {
            current.add(entry)
        }
        saveConnectedPlatforms(current)
        return entry
    }

    fun updatePlatformDailyLimit(platformId: String, dailyLimitMinutes: Int) {
        val clamped = dailyLimitMinutes.coerceAtLeast(0)
        val updatedList = _state.value.connectedPlatforms.map { platform ->
            if (platform.id == platformId) {
                platform.copy(dailyLimitMinutes = clamped)
            } else {
                platform
            }
        }
        saveConnectedPlatforms(updatedList)
    }

    fun recordPlatformUsageSeconds(platformId: String, deltaSeconds: Int) {
        if (deltaSeconds <= 0) return
        val currentMap = _state.value.platformDailyUsageSeconds.toMutableMap()
        val updatedTotal = (currentMap[platformId] ?: 0) + deltaSeconds
        currentMap[platformId] = updatedTotal
        _state.update { it.copy(platformDailyUsageSeconds = currentMap.toMap()) }
        saveUsageSecondsMap(currentMap)
    }

    fun resetDailyUsageForPlatform(platformId: String) {
        val currentMap = _state.value.platformDailyUsageSeconds.toMutableMap()
        currentMap.remove(platformId)
        _state.update { it.copy(platformDailyUsageSeconds = currentMap.toMap()) }
        saveUsageSecondsMap(currentMap)
    }

    fun updateFeedControl(config: FeedControlConfig) {
        _state.update { it.copy(feedControlConfig = config) }
        prefs.edit()
            .putBoolean(PREFS_KEY_FEED_HIDE_SHORTS, config.hideYoutubeShorts)
            .putBoolean(PREFS_KEY_FEED_HIDE_REELS, config.hideInstagramReels)
            .putBoolean(PREFS_KEY_FEED_HIDE_ALGORITHMIC, config.hideAlgorithmicFeeds)
            .putBoolean(PREFS_KEY_FEED_HIDE_COMMENTS, config.hideComments)
            .apply()
    }

    fun enableParentalPin(pinCode: String): Boolean {
        val clean = pinCode.trim()
        if (clean.length != 4 || !clean.all { it.isDigit() }) return false
        _state.update {
            it.copy(
                parentalPinEnabled = true,
                parentalPinCode = clean
            )
        }
        prefs.edit()
            .putBoolean(PREFS_KEY_PARENTAL_PIN_ENABLED, true)
            .putString(PREFS_KEY_PARENTAL_PIN_CODE, clean)
            .apply()
        return true
    }

    fun disableParentalPin(enteredPin: String): Boolean {
        if (!verifyParentalPin(enteredPin)) return false
        _state.update {
            it.copy(
                parentalPinEnabled = false,
                parentalPinCode = ""
            )
        }
        prefs.edit()
            .putBoolean(PREFS_KEY_PARENTAL_PIN_ENABLED, false)
            .putString(PREFS_KEY_PARENTAL_PIN_CODE, "")
            .apply()
        return true
    }

    fun verifyParentalPin(enteredPin: String): Boolean {
        val st = _state.value
        if (!st.parentalPinEnabled || st.parentalPinCode.isEmpty()) return true
        return st.parentalPinCode == enteredPin.trim()
    }

    // --- Persistence Helpers ---

    private fun saveStringSet(key: String, values: Set<String>) {
        prefs.edit().putString(key, values.joinToString(";")).apply()
    }

    private fun loadStringSet(key: String): Set<String> {
        val raw = prefs.getString(key, null) ?: return emptySet()
        if (raw.isBlank()) return emptySet()
        return raw.split(";").map { it.trim() }.filter { it.isNotEmpty() }.toSet()
    }

    private fun loadWhatToProtect(): Set<WhatToProtectItem> {
        val raw = prefs.getString(PREFS_KEY_WHAT_TO_PROTECT, null)
            ?: return setOf(
                WhatToProtectItem.IMAGES,
                WhatToProtectItem.VIDEOS,
                WhatToProtectItem.EXPLICIT_TEXT_URLS
            )
        val parsed = raw.split(",").mapNotNull { token ->
            runCatching { WhatToProtectItem.valueOf(token.trim()) }.getOrNull()
        }.toSet()
        return parsed.ifEmpty {
            setOf(WhatToProtectItem.IMAGES, WhatToProtectItem.VIDEOS)
        }
    }

    private fun saveConnectedPlatforms(platforms: List<ConnectedPlatform>) {
        _state.update { it.copy(connectedPlatforms = platforms) }
        val serialized = platforms.joinToString(";;") { p ->
            "${p.id}|${p.name.replace("|", " ")}|${p.url}|${p.isBuiltIn}|${p.enabled}|${p.dailyLimitMinutes}"
        }
        prefs.edit().putString(PREFS_KEY_CONNECTED_PLATFORMS, serialized).apply()
    }

    private fun loadConnectedPlatforms(): List<ConnectedPlatform> {
        val raw = prefs.getString(PREFS_KEY_CONNECTED_PLATFORMS, null)
        if (raw.isNullOrBlank()) return ConnectedPlatform.defaultPlatforms()
        val parsed = raw.split(";;").mapNotNull { row ->
            val parts = row.split("|")
            if (parts.size >= 5) {
                ConnectedPlatform(
                    id = parts[0],
                    name = parts[1],
                    url = parts[2],
                    isBuiltIn = parts[3].toBooleanStrictOrNull() ?: false,
                    enabled = parts[4].toBooleanStrictOrNull() ?: true,
                    dailyLimitMinutes = parts.getOrNull(5)?.toIntOrNull() ?: 0
                )
            } else {
                null
            }
        }
        return parsed.ifEmpty { ConnectedPlatform.defaultPlatforms() }
    }

    private fun saveUsageSecondsMap(map: Map<String, Int>) {
        val serialized = map.entries.joinToString(";") { "${it.key}=${it.value}" }
        prefs.edit().putString(PREFS_KEY_PLATFORM_USAGE_SECONDS, serialized).apply()
    }

    private fun loadUsageSecondsMap(): Map<String, Int> {
        val raw = prefs.getString(PREFS_KEY_PLATFORM_USAGE_SECONDS, null) ?: return emptyMap()
        if (raw.isBlank()) return emptyMap()
        val result = mutableMapOf<String, Int>()
        raw.split(";").forEach { entry ->
            val parts = entry.split("=")
            if (parts.size == 2) {
                val id = parts[0].trim()
                val secs = parts[1].trim().toIntOrNull()
                if (id.isNotEmpty() && secs != null && secs >= 0) {
                    result[id] = secs
                }
            }
        }
        return result
    }
}
