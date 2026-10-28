# AmnGaze — On-Device 3-Layer AI Accessibility Blocker & Full-Screen Curtain Shield

AmnGaze is an on-device Android content protection application powered by Android's `AccessibilityService` API and Google AI Edge LiteRT (`.tflite`). It eliminates the **Self-Occlusion Flicker Loop** and **60Hz–120Hz Scroll Drift** of legacy `MediaProjection` regional bounding-box overlays by evaluating the screen as binary (**Safe** vs. **Contaminated**) and enforcing protection in under **2ms** via a pre-allocated `TYPE_ACCESSIBILITY_OVERLAY` shield.

---

## Architecture Overview (3-Layer Defense Pipeline)

```
┌─────────────────────────────────────────────────────────────────────────┐
│                         Jetpack Compose UI Layer                        │
│  ┌─────────────────┐    ┌──────────────────┐    ┌────────────────────┐  │
│  │  WelcomeScreen  │───▶│ QuickSetupScreen │───▶│MainDashboardScreen │  │
│  └─────────────────┘    │ (Disclosure +    │    └─────────┬──────────┘  │
│                         │  A11y Settings)  │              │             │
└─────────────────────────┴──────────────────┴──────────────┼─────────────┘
                                                            │
                        StateFlow<AmnGazeState>             ▼
┌─────────────────────────────────────────────────────────────────────────┐
│                     AmnGazeViewModel & Persistence                      │
│     SharedPreferences (Theme, EnforcementMode, CensorStyle, ModelType,  │
│       Sensitivity, BlockIncognito, UrlFastPath, PackageLockouts)        │
└───────────────────────────────────────────────────────────┬─────────────┘
                                                            │
                   In-Memory Volatile Cache & SharedPrefs   ▼
┌─────────────────────────────────────────────────────────────────────────┐
│             AmnGazeAccessibilityService (AccessibilityService)          │
│  ┌──────────────────────────┐             ┌──────────────────────────┐  │
│  │ Layer 1: Fast-Path (<5ms)│             │ Layer 2: Scroll-Debounced│  │
│  │ - Package Lockout Check  │  If Safe &  │ takeScreenshot() API 30+ │  │
│  │ - URL Bar ID Inspection  ├────────────▶│ (180ms settle / 750ms max│  │
│  │ - Bounded BFS Text Scan  │  High-Risk  │  & 380ms/1050ms AOSP cap)│  │
│  └────────────┬─────────────┘     App     └─────────────┬────────────┘  │
│               │ Violation                               │ HardwareBuffer│
│               │                                         ▼               │
│               │                           ┌──────────────────────────┐  │
│               │                           │ ContentModerationEngine  │  │
│               │                           │ Zero-Leak 224x224 Copy + │  │
│               │                           │ LiteRT .tflite Inference │  │
│               │                           └─────────────┬────────────┘  │
│               │                                         │ Whole-Screen  │
│               ▼                                         ▼ Verdict       │
│  ┌───────────────────────────────────────────────────────────────────┐  │
│  │              Layer 3: Shield & Enforcement Manager                │  │
│  │ - Pre-allocated TYPE_ACCESSIBILITY_OVERLAY (<2ms View.VISIBLE)    │  │
│  │ - High-Opacity #F20F172A Fallback + API 31+ FLAG_BLUR_BEHIND      │  │
│  │ - Curtain Shield Mode (Frosted/Mosaic/Dark + 30s Unblur Whitelist)│  │
│  │ - Hard Eject Mode (GLOBAL_ACTION_BACK/HOME + 3-Strike 15m Lockout)│  │
│  └───────────────────────────────────────────────────────────────────┘  │
└─────────────────────────────────────────────────────────────────────────┘
```

### Layer 1: Zero-Latency UI Tree & URL Fast-Path (`<5ms`)
- **Active Cooldown Lockout Check**: Immediately blocks apps currently under a 15-minute 3-strike lockout (`StrikeLockoutManager`).
- **30-Second False-Positive Exemption**: Honors temporary 30-second package whitelists triggered when the user taps *"False Positive - Unblur for 30s"*.
- **Targeted Browser URL Bar Lookup**: Queries known browser URL bar resource IDs (`com.android.chrome:id/url_bar`, `com.brave.browser:id/url_bar`, `com.sec.android.app.sbrowser:id/location_bar_edit_text`, `org.mozilla.firefox:id/mozac_browser_toolbar_url_view`, `com.microsoft.emmx:id/url_bar`, `com.opera.browser:id/url_field`).
- **O(1) 80,000-Domain HashSet & Strict Keyword Scanner (`FastPathUrlAndKeywordScanner`)**:
  - `assets/nsfw.txt` domain lookups are restricted strictly to browser URL bar nodes (`isUrlBarNode == true`) or explicit `http://`, `https://`, or `www.` URLs, ignoring ultra-short `<7` char entries and allowlisted mainstream domains (`SAFE_DOMAIN_ALLOWLIST`).
  - Bounded 75-node UI text traversal runs only inside web browsers (`BROWSER_PACKAGES`) and matches 17 unambiguous standalone adult tokens (`pornhub`, `xvideos`, `xnxx`, `xhamster`, `youporn`, `spankbang`, `brazzers`, `eporner`, `chaturbate`, `redgifs`, `onlyfans`, `nhentai`, `hentai`, `porno`, `porn`, `sexchat`, `camgirl`) and 16 explicit domain markers (`pornhub.com`, `xvideos.com`, `xnxx.com`, `onlyfans.com`, `chaturbate.com`, `redgifs.com`, `xhamster.com`, `youporn.com`, `spankbang.com`, `brazzers.com`, `eporner.com`, `stripchat.com`, `cam4.com`, `rule34.xxx`, `nhentai.net`, `hanime.tv`). Ambiguous everyday words (`nude`, `nudes`, `naked`, `nsfw`, `xxx`) are excluded to prevent false triggers on cosmetics, fashion, masked numbers, or settings toggles.

### Layer 2: Multi-Crop `HardwareBuffer` Vision, Two-Stage Scroll & Video Heartbeat Pipeline (API 30+)
- **Dynamic `PackageManager` Browser & Media App Discovery**: Queries `PackageManager` (`MATCH_ALL`) on startup for all installed `http://` and `https://` browsers and monitors an expanded set of gallery, search, social, and video apps (`HIGH_RISK_MEDIA_PACKAGES`), while keeping all `CATEGORY_HOME` launchers (`com.alhaq.amniquist`, etc.) 100% immune.
- **Two-Stage Post-Scroll Scan (`180ms` + `900ms`)**:
  - **Stage 1 (`180ms` Settle)**: Captures crisp frames immediately after a scroll gesture pauses.
  - **Stage 2 (`+900ms` Verification)**: Automatically fires a follow-up scan `900ms` later to catch lazy-loaded web and feed images that finish downloading after scrolling stops.
  - **Continuous Scroll Ceiling (`750ms`)**: Forces a scan every `750ms` during continuous slow scrolling.
- **Active Media & Video Heartbeat (`1,500ms`)**: Periodically scans playing videos, Reels, GIFs, and lightbox images every `1,500ms` while an eligible app is active and `PowerManager.isInteractive` is `true`, stopping immediately when leaving the app or turning off the screen.
- **3-Crop Aspect-Ratio Extraction (`ContentModerationEngine`)**:
  - Copies `HardwareBuffer` once to a proportional `360px`-wide software `ARGB_8888` bitmap before closing `HardwareBuffer` in `finally`.
  - Extracts 3 square `224x224` crops from the active content region (trimming top 10% status/URL bar and bottom 10% navigation bar): **Crop 1 (Center Square)** with immediate short-circuit, **Crop 2 (Upper-Middle 10%–55%)**, and **Crop 3 (Lower-Middle 45%–90%)**.
- **Dynamic Tensor Inspection & 5-Class Scoring**:
  - Inspects `inputTensor` and `outputTensor` dynamically (`FLOAT32` RGB `[0.0f..1.0f]` vs `UINT8` RGB `[0..255]` + `quantizationParams`).
  - Combines `porn [3] + hentai [1] + (sexy [4] * 0.85f)` (configurable via dashboard toggle) with a `neutral [2] + drawings [0] > 0.78f` safe-UI guard and calibrated sensitivity thresholds (`25% -> 0.62`, `50% -> 0.50`, `75% -> 0.38`, `95% -> 0.28`).
- **Browser-Scoped Incognito / `FLAG_SECURE` Enforcement**: Enforces `ERROR_TAKE_SCREENSHOT_SECURE_WINDOW` strictly when inside a browser package (`BROWSER_PACKAGES`), avoiding false blocks on `FLAG_SECURE` banking apps or password managers.

### Layer 3: `<2ms` App-Scoped Curtain Shield & Mode Separation (`BlurOverlayManager`)
- **Strictly Scoped to the Triggering App (`shieldedPackage`)**: The overlay only applies to the monitored app that triggered it (`HIGH_RISK_MEDIA_PACKAGES`) and **automatically disappears the instant the user leaves that app** (swiping Home to `com.alhaq.amniquist` or any launcher, switching apps, or opening Settings/AmnGaze).
- **Non-Freezing Window Flags (`FLAG_NOT_FOCUSABLE | FLAG_NOT_TOUCH_MODAL`)**: System navigation bars (Home, Back, Recents gestures) and hardware keys remain 100% responsive at all times, while the overlay buttons remain clickable.
- **Complete Home Launcher Immunity**: Dynamically queries installed `CATEGORY_HOME` launchers via `PackageManager` and explicitly protects custom launchers (`com.alhaq.amniquist`, `nexuslauncher`, `lawnchair`, etc.) so launchers can never be scanned, locked, or covered by an overlay.
- **Strict Separation of the Two Enforcement Modes**:
  1. **Curtain Shield Mode (Default)**: Shows the app-scoped curtain over the triggering app with **"Unblur App for 30s"** (grants an immediate 30-second window) and **"Leave Page Safely"** (`GLOBAL_ACTION_BACK`). **Never** applies the 3-strike 15-minute lockout or forces `GLOBAL_ACTION_HOME`.
  2. **Bulldog Hard Eject + 3-Strike Lockout (`HARD_EJECT` only)**: Fires `GLOBAL_ACTION_BACK` on Strikes 1–2, and locks only that specific monitored app for **15 minutes** (`GLOBAL_ACTION_HOME`) on Strike 3 within a rolling 5-minute window, automatically dismissing the overlay as soon as the app exits to the Home Launcher.

---

## Bundled On-Device LiteRT Models (`app/src/main/assets/`)
- `amn_gaze.tflite` — Default MobileNetV2 NSFW classifier (`[0.0f, 1.0f]` RGB normalized, dynamically inspected)
- `nsfw_mobilenet_v2_140_224_quantized.tflite` — Fast MobileNetV2 NSFW classifier (5-class `[drawings, hentai, neutral, porn, sexy]`)
- `model_lite_gender_q.tflite` — Compact gender / revealing content classifier (`[0.0f, 1.0f]` RGB normalized)
- `nsfw.txt` — 79,961 adult domains loaded asynchronously into an in-memory O(1) `HashSet` (filtered to `>=7` chars and checked only on URL nodes)

---

## Roadmap & Implementation Status
- [x] **v1.0**: Initial Compose UI, themes (`Sunset Glow`, `Emerald Calm`, `Cosmic Night`), and LiteRT model integration.
- [x] **v2.0**: Replaced legacy `MediaProjection` regional bounding-box pipeline with `AmnGazeAccessibilityService` 3-Layer Curtain Shield & 3-Strike Lockout.
- [x] **v2.1**: False-positive calibration (removed ambiguous keywords, restricted `nsfw.txt` to URL nodes `>=7` chars).
- [x] **v2.2**: App-scoped overlay (`shieldedPackage`), non-freezing `FLAG_NOT_FOCUSABLE | FLAG_NOT_TOUCH_MODAL` window flags, Home Launcher immunity (`com.alhaq.amniquist`), and strict separation of `CURTAIN_SHIELD` from 3-strike lockouts.
- [x] **v2.3 (Current — Multi-Crop Vision, Lazy-Load Verification & Video Heartbeat)**:
  - Implemented dynamic tensor inspection (`inputTensor` / `outputTensor`) and MobileNetV2 RGB `[0.0f, 1.0f]` / `UINT8` preprocessing.
  - Added `sexy [4]` (`0.85x` weight, dashboard-configurable) + `neutral + drawings > 0.78f` safe-UI guard in 5-class NSFW scoring.
  - Replaced full-screen squishing with 3 square `224x224` active-content crops (Center, Upper-Middle, Lower-Middle) extracted from a proportional `360px` copy.
  - Recalibrated sensitivity thresholds (`25% -> 0.62`, `50% -> 0.50`, `75% -> 0.38`, `95% -> 0.28`).
  - Added two-stage post-scroll scanning (`180ms` + `900ms`) for lazy-loaded images and a `1,500ms` active video heartbeat scanner.
  - Added dynamic `PackageManager` browser discovery (`MATCH_ALL`) and expanded gallery/search/social media coverage.
