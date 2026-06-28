package com.amngaze

import android.app.Application
import android.content.Context
import androidx.lifecycle.AndroidViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

class AmnGazeViewModel(application: Application) : AndroidViewModel(application) {
    private val prefs = application.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val _state = MutableStateFlow(
        AmnGazeState(
            isSetupComplete = prefs.getBoolean(PREFS_KEY_SETUP_COMPLETE, false),
            // AmnGaze Blur config
            amnGazeBlurMode = BlurTargetMode.entries.getOrElse(
                prefs.getInt(PREFS_KEY_BLUR_TARGET_MODE, BlurTargetMode.BODY_REGION.ordinal)
            ) { BlurTargetMode.BODY_REGION },
            amnGazeCensorStyle = BlurCensorStyle.entries.getOrElse(
                prefs.getInt(PREFS_KEY_BLUR_CENSOR_STYLE, BlurCensorStyle.MOSAIC.ordinal)
            ) { BlurCensorStyle.MOSAIC },
            amnGazeBlurSensitivity = prefs.getFloat(PREFS_KEY_BLUR_SENSITIVITY, 0.5f),
            amnGazeShowBadge = prefs.getBoolean(PREFS_KEY_BLUR_SHOW_BADGE, true),
            amnGazeShowBorder = prefs.getBoolean(PREFS_KEY_BLUR_SHOW_BORDER, true),
            amnGazeSkinHeuristic = prefs.getBoolean(PREFS_KEY_BLUR_SKIN_HEURISTIC, true),
            amnGazeModelType = ModerationModelType.entries.getOrElse(
                prefs.getInt(PREFS_KEY_MODEL_TYPE, ModerationModelType.DEFAULT_NSFW.ordinal)
            ) { ModerationModelType.DEFAULT_NSFW },
            amnGazeHardBlockEnabled = prefs.getBoolean(PREFS_KEY_HARD_BLOCK_ENABLED, false),
            amnGazeHardBlockThreshold = prefs.getFloat(PREFS_KEY_HARD_BLOCK_THRESHOLD, 0.85f)
        )
    )
    val state: StateFlow<AmnGazeState> = _state.asStateFlow()

    fun updateTheme(theme: AppTheme) {
        _state.update { it.copy(currentTheme = theme) }
    }

    fun completeSetup() {
        _state.update { it.copy(isSetupComplete = true) }
        prefs.edit().putBoolean(PREFS_KEY_SETUP_COMPLETE, true).apply()
    }

    fun toggleBackgroundPermission() {
        _state.update {
            val newValue = !it.permissionBackgroundDone
            it.copy(permissionBackgroundDone = newValue)
        }
    }

    fun toggleNotificationsPermission() {
        _state.update {
            val newValue = !it.permissionNotificationsDone
            it.copy(permissionNotificationsDone = newValue)
        }
    }

    fun toggleOverlayPermission() {
        _state.update {
            val newValue = !it.permissionOverlayDone
            it.copy(permissionOverlayDone = newValue)
        }
    }

    // ─── AmnGaze Blur configuration ──────────────────────────────────────────

    fun updateBlurMode(mode: BlurTargetMode) {
        _state.update { it.copy(amnGazeBlurMode = mode) }
        prefs.edit().putInt(PREFS_KEY_BLUR_TARGET_MODE, mode.ordinal).apply()
    }

    fun updateCensorStyle(style: BlurCensorStyle) {
        _state.update { it.copy(amnGazeCensorStyle = style) }
        prefs.edit().putInt(PREFS_KEY_BLUR_CENSOR_STYLE, style.ordinal).apply()
    }

    fun updateBlurSensitivity(value: Float) {
        _state.update { it.copy(amnGazeBlurSensitivity = value) }
        prefs.edit().putFloat(PREFS_KEY_BLUR_SENSITIVITY, value).apply()
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

    fun toggleSkinHeuristic() {
        val updated = !_state.value.amnGazeSkinHeuristic
        _state.update { it.copy(amnGazeSkinHeuristic = updated) }
        prefs.edit().putBoolean(PREFS_KEY_BLUR_SKIN_HEURISTIC, updated).apply()
    }

    fun updateModelType(type: ModerationModelType) {
        _state.update { it.copy(amnGazeModelType = type) }
        prefs.edit().putInt(PREFS_KEY_MODEL_TYPE, type.ordinal).apply()
    }

    fun toggleHardBlock(enabled: Boolean) {
        _state.update { it.copy(amnGazeHardBlockEnabled = enabled) }
        prefs.edit().putBoolean(PREFS_KEY_HARD_BLOCK_ENABLED, enabled).apply()
    }

    fun updateHardBlockThreshold(threshold: Float) {
        _state.update { it.copy(amnGazeHardBlockThreshold = threshold) }
        prefs.edit().putFloat(PREFS_KEY_HARD_BLOCK_THRESHOLD, threshold).apply()
    }
}
