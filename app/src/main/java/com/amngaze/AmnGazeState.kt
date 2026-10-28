package com.amngaze

enum class AppTheme {
    SUNSET_GLOW,
    EMERALD_CALM,
    COSMIC_NIGHT
}

/**
 * Whole-screen protection enforcement mode used when a violation is detected.
 * Replaces coordinate-based moving bounding boxes to eliminate self-occlusion flicker
 * and 60Hz-120Hz scroll drift.
 */
enum class EnforcementMode {
    /**
     * Covers the entire screen in <2ms via a pre-allocated TYPE_ACCESSIBILITY_OVERLAY
     * with actions to "Leave Page Safely" or "False Positive - Unblur for 30s".
     * Screenshot scanning pauses while the curtain is visible.
     */
    CURTAIN_SHIELD,

    /**
     * Classic Bulldog-style Hard Eject + Strike Counter:
     * Strikes 1 & 2 -> Instant <2ms shield + GLOBAL_ACTION_BACK.
     * Strike 3 (within 5 minutes) -> 15-minute package lockout + GLOBAL_ACTION_HOME.
     */
    HARD_EJECT
}

/**
 * Retained for backward compatibility with existing preferences and tests; maps to whole-screen
 * enforcement behavior in the 3-Layer Accessibility pipeline.
 */
enum class BlurTargetMode {
    BODY_REGION,
    FULL_PERSON,
    FULL_SCREEN
}

/**
 * Visual style used to render the full-screen TYPE_ACCESSIBILITY_OVERLAY curtain shield.
 */
enum class BlurCensorStyle {
    MOSAIC,        // Dark pixel mosaic tiles over #F20F172A opaque slate
    GAUSSIAN_BLUR, // Soft frosted blur + API 31+ FLAG_BLUR_BEHIND with #F20F172A fallback
    BLACK_BAR,     // Solid blackout shield (#FF0A0F1D)
    FROSTED        // Frosted glass translucent texture over #F20F172A opaque slate
}

enum class ModerationModelType {
    DEFAULT_NSFW,       // amn_gaze.tflite
    QUANTIZED_NSFW,     // nsfw_mobilenet_v2_140_224_quantized.tflite
    GENDER_FILTER       // model_lite_gender_q.tflite
}

// SharedPreferences keys
const val PREFS_NAME = "amngaze_prefs"
const val PREFS_KEY_SETUP_COMPLETE = "setup_complete"
const val PREFS_KEY_DISCLOSURE_ACCEPTED = "accessibility_disclosure_accepted"
const val PREFS_KEY_APP_THEME = "app_theme"

// AmnGaze 3-Layer Accessibility configuration keys
const val PREFS_KEY_ENFORCEMENT_MODE = "enforcement_mode"
const val PREFS_KEY_BLUR_TARGET_MODE = "blur_target_mode"
const val PREFS_KEY_BLUR_CENSOR_STYLE = "blur_censor_style"
const val PREFS_KEY_BLUR_SENSITIVITY = "blur_sensitivity"
const val PREFS_KEY_BLUR_SHOW_BADGE = "blur_show_badge"
const val PREFS_KEY_BLUR_SHOW_BORDER = "blur_show_border"
const val PREFS_KEY_MODEL_TYPE = "model_type"
const val PREFS_KEY_HARD_BLOCK_ENABLED = "hard_block_enabled"
const val PREFS_KEY_HARD_BLOCK_THRESHOLD = "hard_block_threshold"
const val PREFS_KEY_BLOCK_INCOGNITO = "block_incognito_enabled"
const val PREFS_KEY_URL_FAST_PATH = "url_fast_path_enabled"
const val PREFS_KEY_LOCKED_PACKAGES = "locked_packages_entries"
const val PREFS_KEY_TOTAL_DETECTIONS = "total_detections_count"

data class AmnGazeState(
    // Onboarding & Disclosure
    val isSetupComplete: Boolean = false,
    val hasAcceptedAccessibilityDisclosure: Boolean = false,
    val permissionBackgroundDone: Boolean = false,
    val permissionNotificationsDone: Boolean = false,
    val permissionOverlayDone: Boolean = false,

    // Active theme
    val currentTheme: AppTheme = AppTheme.SUNSET_GLOW,

    // Whole-Screen Enforcement & Curtain Configuration
    val enforcementMode: EnforcementMode = EnforcementMode.CURTAIN_SHIELD,
    val amnGazeBlurMode: BlurTargetMode = BlurTargetMode.FULL_SCREEN,
    val amnGazeCensorStyle: BlurCensorStyle = BlurCensorStyle.FROSTED,
    /** Confidence threshold for detections. Range 0.2-0.95 */
    val amnGazeBlurSensitivity: Float = 0.5f,
    val amnGazeShowBadge: Boolean = true,
    val amnGazeShowBorder: Boolean = true,
    val amnGazeModelType: ModerationModelType = ModerationModelType.DEFAULT_NSFW,
    val amnGazeHardBlockEnabled: Boolean = false,
    val amnGazeHardBlockThreshold: Float = 0.85f,

    // 3-Layer Pipeline Toggles
    val blockIncognitoEnabled: Boolean = true,
    val urlFastPathEnabled: Boolean = true,
    val activeLockedPackageCount: Int = 0,
    val totalDetectionsCount: Int = 0
)
