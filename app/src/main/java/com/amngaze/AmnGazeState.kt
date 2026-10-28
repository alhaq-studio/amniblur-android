package com.amngaze

enum class AppTheme {
    SUNSET_GLOW,
    EMERALD_CALM,
    COSMIC_NIGHT
}

/**
 * Top-level operating mode selectable during Onboarding and anytime from Settings -> Protection Mode.
 */
enum class AppProtectionMode(val title: String, val subtitle: String) {
    IN_APP_PROTECTION(
        title = "In-app protection",
        subtitle = "Protect content only inside AmnGaze's built-in browser and connected web views."
    ),
    SYSTEM_WIDE_PROTECTION(
        title = "System-wide protection",
        subtitle = "Protect from sensitive content across supported apps and browsers."
    )
}

/**
 * Whole-screen protection enforcement mode used when a violation is detected in Module 1.
 */
enum class EnforcementMode {
    CURTAIN_SHIELD,
    HARD_EJECT
}

/**
 * Retained for backward compatibility with existing preferences and tests.
 */
enum class BlurTargetMode {
    BODY_REGION,
    FULL_PERSON,
    FULL_SCREEN
}

/**
 * Visual style used to render the full-screen TYPE_ACCESSIBILITY_OVERLAY curtain shield in Module 1.
 */
enum class BlurCensorStyle {
    MOSAIC,
    GAUSSIAN_BLUR,
    BLACK_BAR,
    FROSTED
}

enum class ModerationModelType {
    DEFAULT_NSFW,       // amn_gaze.tflite
    QUANTIZED_NSFW,     // nsfw_mobilenet_v2_140_224_quantized.tflite
    GENDER_FILTER       // model_lite_gender_q.tflite
}

/**
 * Module 2 (HaramBlur Mode) protection scope: all non-exempt apps vs selected Auto-Run apps only.
 */
enum class HaramBlurProtectionScope(val label: String, val description: String) {
    SYSTEM_WIDE(
        label = "System-Wide Protection",
        description = "Runs continuous real-time regional blurring across all non-exempt apps."
    ),
    SELECTED_APPS_ONLY(
        label = "Selected Apps Only (Auto-Run Mode)",
        description = "Only runs regional blurring inside your selected apps and pauses elsewhere."
    )
}

/**
 * Who to Blur selector used by both Module 2 (HaramBlur Regional Blur) and In-App Protection.
 */
enum class HaramBlurTargetGender(val displayName: String, val subtitle: String) {
    FEMALE("Female", "Blur detected female faces and revealing figures"),
    MALE("Male", "Blur detected male faces and figures"),
    BOTH("Both (Male & Female)", "Blur both female and male faces and figures")
}

/**
 * Three-tier sensitivity selector for HaramBlur regional blurring & In-App face/figure blurring.
 */
enum class HaramBlurSensitivity(
    val displayName: String,
    val shortLabel: String,
    val threshold: Float,
    val description: String
) {
    LOW(
        displayName = "Low Sensitivity",
        shortLabel = "Low",
        threshold = 0.70f,
        description = "High confidence threshold (70%) for fewer false positives."
    ),
    MEDIUM(
        displayName = "Medium Sensitivity",
        shortLabel = "Medium",
        threshold = 0.52f,
        description = "Balanced threshold (52%) for everyday browsing and social feeds."
    ),
    HIGH(
        displayName = "High Sensitivity",
        shortLabel = "High",
        threshold = 0.35f,
        description = "Aggressive threshold (35%) that blurs smaller faces and subtle figures."
    )
}

/**
 * Regional blur overlay style for Module 2 (HaramBlur) and In-App WebView blurring.
 */
enum class HaramBlurOverlayStyle(val displayName: String, val argbColor: Long) {
    BLUR("Blur", 0xD91E293B),
    PIXELATE("Pixelate", 0xE60F172A),
    BLACK("Black", 0xFF000000),
    GRAY("Gray", 0xFF8E8E93),
    SKIN("Skin", 0xFFE8BEAC)
}

/**
 * Content types protected inside In-App WebView Protection mode.
 */
enum class WhatToProtectItem(val displayName: String) {
    IMAGES("Images"),
    VIDEOS("Videos"),
    EXPLICIT_TEXT_URLS("Explicit Text & Adult URLs")
}

/**
 * Connected social/web platform entry managed in ConnectedPlatformsScreen and PlatformsBottomSheet.
 */
data class ConnectedPlatform(
    val id: String,
    val name: String,
    val url: String,
    val isBuiltIn: Boolean = true,
    val enabled: Boolean = true,
    val dailyLimitMinutes: Int = 0 // 0 = Unlimited
) {
    companion object {
        fun defaultPlatforms(): List<ConnectedPlatform> = listOf(
            ConnectedPlatform(
                id = "facebook",
                name = "Facebook",
                url = "https://m.facebook.com",
                isBuiltIn = true,
                enabled = true
            ),
            ConnectedPlatform(
                id = "instagram",
                name = "Instagram",
                url = "https://www.instagram.com",
                isBuiltIn = true,
                enabled = true
            ),
            ConnectedPlatform(
                id = "youtube",
                name = "YouTube",
                url = "https://m.youtube.com",
                isBuiltIn = true,
                enabled = true
            ),
            ConnectedPlatform(
                id = "google",
                name = "Google Search",
                url = "https://www.google.com",
                isBuiltIn = true,
                enabled = true
            ),
            ConnectedPlatform(
                id = "x_twitter",
                name = "X (Twitter)",
                url = "https://x.com",
                isBuiltIn = true,
                enabled = true
            ),
            ConnectedPlatform(
                id = "linkedin",
                name = "LinkedIn",
                url = "https://www.linkedin.com",
                isBuiltIn = true,
                enabled = true
            )
        )

        fun presetCatalog(): List<ConnectedPlatform> = listOf(
            ConnectedPlatform("reddit", "Reddit", "https://www.reddit.com", isBuiltIn = false, enabled = true),
            ConnectedPlatform("tiktok", "TikTok", "https://www.tiktok.com", isBuiltIn = false, enabled = true),
            ConnectedPlatform("pinterest", "Pinterest", "https://www.pinterest.com", isBuiltIn = false, enabled = true),
            ConnectedPlatform("twitch", "Twitch", "https://m.twitch.tv", isBuiltIn = false, enabled = true),
            ConnectedPlatform("threads", "Threads", "https://www.threads.net", isBuiltIn = false, enabled = true),
            ConnectedPlatform("tumblr", "Tumblr", "https://www.tumblr.com", isBuiltIn = false, enabled = true),
            ConnectedPlatform("quora", "Quora", "https://www.quora.com", isBuiltIn = false, enabled = true),
            ConnectedPlatform("duckduckgo", "DuckDuckGo", "https://duckduckgo.com", isBuiltIn = false, enabled = true),
            ConnectedPlatform("bing", "Bing", "https://www.bing.com", isBuiltIn = false, enabled = true)
        )
    }
}

/**
 * Feed Control configuration for hiding short-form/algorithmic feeds inside In-App WebView.
 */
data class FeedControlConfig(
    val hideYoutubeShorts: Boolean = true,
    val hideInstagramReels: Boolean = true,
    val hideAlgorithmicFeeds: Boolean = false,
    val hideComments: Boolean = false
)

// SharedPreferences keys
const val PREFS_NAME = "amngaze_prefs"
const val PREFS_KEY_SETUP_COMPLETE = "setup_complete"
const val PREFS_KEY_DISCLOSURE_ACCEPTED = "accessibility_disclosure_accepted"
const val PREFS_KEY_APP_THEME = "app_theme"
const val PREFS_KEY_APP_PROTECTION_MODE = "app_protection_mode"

// Module 1: AmnGaze 3-Layer Accessibility Explicit Blocker keys
const val PREFS_KEY_EXPLICIT_BLOCKER_ENABLED = "explicit_blocker_enabled"
const val PREFS_KEY_EXPLICIT_SETTINGS_EXPANDED = "explicit_settings_expanded"
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
const val PREFS_KEY_VIDEO_HEARTBEAT = "video_heartbeat_enabled"
const val PREFS_KEY_INCLUDE_SEXY_SCORE = "include_sexy_score_enabled"
const val PREFS_KEY_LOCKED_PACKAGES = "locked_packages_entries"
const val PREFS_KEY_TOTAL_DETECTIONS = "total_detections_count"

// Module 2: HaramBlur Real-Time Regional Blur keys
const val PREFS_KEY_HARAMBLUR_ENABLED = "haramblur_enabled"
const val PREFS_KEY_HARAMBLUR_SETTINGS_EXPANDED = "haramblur_settings_expanded"
const val PREFS_KEY_HARAMBLUR_SCOPE = "haramblur_protection_scope"
const val PREFS_KEY_HARAMBLUR_AUTO_RUN_PACKAGES = "haramblur_auto_run_packages"
const val PREFS_KEY_HARAMBLUR_TARGET_GENDER = "haramblur_target_gender"
const val PREFS_KEY_HARAMBLUR_SENSITIVITY = "haramblur_sensitivity"
const val PREFS_KEY_HARAMBLUR_OVERLAY_STYLE = "haramblur_overlay_style"
const val PREFS_KEY_HARAMBLUR_EFFECT_STRENGTH = "haramblur_effect_strength"

// In-App Protection & Parental Controls keys
const val PREFS_KEY_IN_APP_BLUR_STRENGTH_PX = "in_app_blur_strength_px"
const val PREFS_KEY_WHAT_TO_PROTECT = "in_app_what_to_protect"
const val PREFS_KEY_CONNECTED_PLATFORMS = "in_app_connected_platforms"
const val PREFS_KEY_ACTIVE_PLATFORM_ID = "in_app_active_platform_id"
const val PREFS_KEY_FEED_HIDE_SHORTS = "feed_hide_youtube_shorts"
const val PREFS_KEY_FEED_HIDE_REELS = "feed_hide_instagram_reels"
const val PREFS_KEY_FEED_HIDE_ALGORITHMIC = "feed_hide_algorithmic_feeds"
const val PREFS_KEY_FEED_HIDE_COMMENTS = "feed_hide_comments"
const val PREFS_KEY_PARENTAL_PIN_ENABLED = "parental_pin_enabled"
const val PREFS_KEY_PARENTAL_PIN_CODE = "parental_pin_code"
const val PREFS_KEY_PLATFORM_USAGE_SECONDS = "platform_daily_usage_seconds"
const val PREFS_KEY_SELECTED_LANGUAGE = "selected_language"

data class AmnGazeState(
    // Onboarding & Top-Level Protection Mode
    val isSetupComplete: Boolean = false,
    val hasAcceptedAccessibilityDisclosure: Boolean = false,
    val appProtectionMode: AppProtectionMode = AppProtectionMode.SYSTEM_WIDE_PROTECTION,
    val permissionBackgroundDone: Boolean = false,
    val permissionNotificationsDone: Boolean = false,
    val permissionOverlayDone: Boolean = false,

    // Active theme
    val currentTheme: AppTheme = AppTheme.SUNSET_GLOW,

    // Master Toggle 1: Explicit & Adult Content Blocker (3-Layer Accessibility Shield)
    val isExplicitBlockerEnabled: Boolean = true,
    val isExplicitSettingsExpanded: Boolean = true,
    val enforcementMode: EnforcementMode = EnforcementMode.CURTAIN_SHIELD,
    val amnGazeBlurMode: BlurTargetMode = BlurTargetMode.FULL_SCREEN,
    val amnGazeCensorStyle: BlurCensorStyle = BlurCensorStyle.FROSTED,
    /** User sensitivity slider (0.20..0.95). Maps to threshold 0.65..0.28 (50% -> 0.50). */
    val amnGazeBlurSensitivity: Float = 0.5f,
    val amnGazeShowBadge: Boolean = true,
    val amnGazeShowBorder: Boolean = true,
    val amnGazeModelType: ModerationModelType = ModerationModelType.DEFAULT_NSFW,
    val amnGazeHardBlockEnabled: Boolean = false,
    val amnGazeHardBlockThreshold: Float = 0.85f,
    val blockIncognitoEnabled: Boolean = true,
    val urlFastPathEnabled: Boolean = true,
    val videoHeartbeatEnabled: Boolean = true,
    val includeSexyInNsfwScore: Boolean = true,
    val activeLockedPackageCount: Int = 0,
    val totalDetectionsCount: Int = 0,

    // Master Toggle 2: Real-Time Gaze & Face/Figure Blur (HaramBlur Mode)
    val isHaramBlurEnabled: Boolean = false,
    val isHaramBlurSettingsExpanded: Boolean = false,
    val haramBlurProtectionScope: HaramBlurProtectionScope = HaramBlurProtectionScope.SYSTEM_WIDE,
    val autoRunPackages: Set<String> = emptySet(),
    val haramBlurTargetGender: HaramBlurTargetGender = HaramBlurTargetGender.FEMALE,
    val haramBlurSensitivity: HaramBlurSensitivity = HaramBlurSensitivity.MEDIUM,
    val haramBlurOverlayStyle: HaramBlurOverlayStyle = HaramBlurOverlayStyle.BLUR,
    val haramBlurEffectStrength: Int = 10, // 1..10

    // In-App Protected WebView Hub & Shared Personalization
    val inAppBlurStrengthPx: Int = 10, // 1..20px
    val whatToProtect: Set<WhatToProtectItem> = setOf(
        WhatToProtectItem.IMAGES,
        WhatToProtectItem.VIDEOS,
        WhatToProtectItem.EXPLICIT_TEXT_URLS
    ),
    val connectedPlatforms: List<ConnectedPlatform> = ConnectedPlatform.defaultPlatforms(),
    val activePlatformId: String = "google",
    val feedControlConfig: FeedControlConfig = FeedControlConfig(),
    val parentalPinEnabled: Boolean = false,
    val parentalPinCode: String = "",
    val platformDailyUsageSeconds: Map<String, Int> = emptyMap(),
    val selectedLanguage: String = "English"
) {
    val enabledPlatformsSummary: String
        get() = connectedPlatforms
            .filter { it.enabled }
            .joinToString(", ") { it.name }
            .ifEmpty { "None" }

    val whatToProtectSummary: String
        get() = whatToProtect
            .joinToString(", ") { it.displayName }
            .ifEmpty { "None" }

    val blurStyleSummary: String
        get() = "Smart (${haramBlurOverlayStyle.displayName}) - ${inAppBlurStrengthPx}px Strength"
}
