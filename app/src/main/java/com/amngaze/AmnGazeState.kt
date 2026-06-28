package com.amngaze

enum class AppTheme {
    SUNSET_GLOW,
    EMERALD_CALM,
    COSMIC_NIGHT
}

/**
 * Determines which region of a detected person/scene to blur.
 */
enum class BlurTargetMode {
    BODY_REGION,  // Only exposed body-part patches (skin areas)
    FULL_PERSON,  // Entire person silhouette bounding box
    FULL_SCREEN   // Full-screen pixelation when content is detected
}

/**
 * Visual style used to censor each detected region.
 */
enum class BlurCensorStyle {
    MOSAIC,        // Dark pixel mosaic tiles (default)
    GAUSSIAN_BLUR, // Soft blur approximation via downscale/upscale
    BLACK_BAR,     // Solid semi-transparent black overlay
    FROSTED        // Frosted glass: white translucent fill + noise
}

enum class ModerationModelType {
    DEFAULT_NSFW,       // amn_gaze.tflite
    QUANTIZED_NSFW,     // nsfw_mobilenet_v2_140_224_quantized.tflite
    GENDER_FILTER       // model_lite_gender_q.tflite
}

// SharedPreferences keys
const val PREFS_NAME = "amngaze_prefs"
const val PREFS_KEY_SETUP_COMPLETE = "setup_complete"

// AmnGaze Blur configuration keys
const val PREFS_KEY_BLUR_TARGET_MODE = "blur_target_mode"
const val PREFS_KEY_BLUR_CENSOR_STYLE = "blur_censor_style"
const val PREFS_KEY_BLUR_SENSITIVITY = "blur_sensitivity"
const val PREFS_KEY_BLUR_SHOW_BADGE = "blur_show_badge"
const val PREFS_KEY_BLUR_SHOW_BORDER = "blur_show_border"
const val PREFS_KEY_BLUR_SKIN_HEURISTIC = "blur_skin_heuristic"
const val PREFS_KEY_MODEL_TYPE = "model_type"
const val PREFS_KEY_HARD_BLOCK_ENABLED = "hard_block_enabled"
const val PREFS_KEY_HARD_BLOCK_THRESHOLD = "hard_block_threshold"

data class AmnGazeState(
    // Onboarding
    val isSetupComplete: Boolean = false,
    val permissionBackgroundDone: Boolean = false,
    val permissionNotificationsDone: Boolean = false,
    val permissionOverlayDone: Boolean = false,

    // Active theme
    val currentTheme: AppTheme = AppTheme.SUNSET_GLOW,

    // AmnGaze Blur configuration
    val amnGazeBlurMode: BlurTargetMode = BlurTargetMode.BODY_REGION,
    val amnGazeCensorStyle: BlurCensorStyle = BlurCensorStyle.MOSAIC,
    /** Confidence threshold for detections. Range 0.2–0.95 */
    val amnGazeBlurSensitivity: Float = 0.5f,
    val amnGazeShowBadge: Boolean = true,
    val amnGazeShowBorder: Boolean = true,
    val amnGazeSkinHeuristic: Boolean = true,
    val amnGazeModelType: ModerationModelType = ModerationModelType.DEFAULT_NSFW,
    val amnGazeHardBlockEnabled: Boolean = false,
    val amnGazeHardBlockThreshold: Float = 0.85f
)
