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

### Layer 2: Scroll-Settle Debounced `HardwareBuffer` Vision Pipeline (API 30+)
- **Scoped High-Risk Package Eligibility**: Layer 1 & Layer 2 scanning runs strictly on browsers and high-risk media/social packages (`HIGH_RISK_MEDIA_PACKAGES`), leaving banking apps, calculators, settings, notes, and system utilities untouched.
- **Hybrid Leading + Trailing Scroll Debouncer (`ScrollSettleDebounceController`)**:
  - **Trailing Settle Scan (`180ms`)**: Captures crisp, stationary frames once a fling or scroll settles.
  - **Continuous Scroll Ceiling (`750ms`)**: Forces a scan every `750ms` during continuous slow thumb-scrolling so explicit content cannot bypass detection.
- **Version-Aware AOSP Rate-Limit Compliance**: Enforces `1050ms` on Android 11 (API 30) and `380ms` on Android 12+ (API 31+), guarded by `AtomicBoolean` (`isScreenshotInFlight`).
- **Zero-Leak `HardwareBuffer` Lifecycle & Calibrated Model Preprocessing (`ContentModerationEngine`)**:
  - Wraps `HardwareBuffer`, downscales to `224x224`, and copies to a software `Bitmap.Config.ARGB_8888` **before** recycling intermediate bitmaps and closing `hardwareBuffer.close()` inside a `try ... finally` block.
  - Uses VGG BGR mean subtraction (`B - 104f, G - 117f, R - 123f`) for `amn_gaze.tflite`, 5-class probability summation (`porn [3] + hentai [1]`) for `nsfw_mobilenet_v2_140_224_quantized.tflite`, and a calibrated confidence threshold (`0.58f..0.90f`, `0.75f` at default `50%` sensitivity).
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
- `amn_gaze.tflite` — Default OpenNSFW Float32 classifier (VGG BGR mean subtraction `[104, 117, 123]`, 2-class `[SFW, NSFW]`)
- `nsfw_mobilenet_v2_140_224_quantized.tflite` — Fast MobileNet V2 NSFW classifier (5-class `[drawings, hentai, neutral, porn, sexy]`)
- `model_lite_gender_q.tflite` — Compact gender / revealing content classifier (`[0, 1]` RGB normalized)
- `nsfw.txt` — 79,961 adult domains loaded asynchronously into an in-memory O(1) `HashSet` (filtered to `>=7` chars and checked only on URL nodes)

---

## Roadmap & Implementation Status
- [x] **v1.0**: Initial Compose UI, themes (`Sunset Glow`, `Emerald Calm`, `Cosmic Night`), and LiteRT model integration.
- [x] **v2.0**: Replaced legacy `MediaProjection` regional bounding-box pipeline with `AmnGazeAccessibilityService` 3-Layer Curtain Shield & 3-Strike Lockout.
- [x] **v2.1**: False-positive calibration (removed ambiguous keywords, restricted `nsfw.txt` to URL nodes `>=7` chars, VGG BGR mean subtraction & 5-class output mapping).
- [x] **v2.2 (Current — App-Scoped Overlay, Launcher Immunity & Mode Separation)**:
  - Scoped `BlurOverlayManager` strictly to `shieldedPackage` and auto-dismissed the overlay whenever the user exits the triggering app.
  - Added `FLAG_NOT_FOCUSABLE | FLAG_NOT_TOUCH_MODAL` (removed `FLAG_FULLSCREEN`) so system navigation gestures and touch never freeze.
  - Added dynamic `CATEGORY_HOME` launcher detection + explicit protection for `com.alhaq.amniquist`, purging any non-eligible lockouts on startup.
  - Decoupled `CURTAIN_SHIELD` mode from the 3-strike 15-minute lockout so Curtain Shield Mode always gives the 30-second unblur option and never locks out apps.
