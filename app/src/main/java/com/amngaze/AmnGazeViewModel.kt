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
        return AmnGazeState(
            isSetupComplete = prefs.getBoolean(PREFS_KEY_SETUP_COMPLETE, false),
            hasAcceptedAccessibilityDisclosure = prefs.getBoolean(PREFS_KEY_DISCLOSURE_ACCEPTED, false),
            currentTheme = AppTheme.entries.getOrElse(
                prefs.getInt(PREFS_KEY_APP_THEME, AppTheme.SUNSET_GLOW.ordinal)
            ) { AppTheme.SUNSET_GLOW },
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
            activeLockedPackageCount = lockedCount,
            totalDetectionsCount = prefs.getInt(PREFS_KEY_TOTAL_DETECTIONS, 0)
        )
    }

    fun updateTheme(theme: AppTheme) {
        _state.update { it.copy(currentTheme = theme) }
        prefs.edit().putInt(PREFS_KEY_APP_THEME, theme.ordinal).apply()
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

    // --- 3-Layer Accessibility Enforcement & Curtain Configuration ---

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
}
