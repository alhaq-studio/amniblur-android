# AmnGaze — On-Device Image, Lazy-Loaded Frame & Video NSFW Detection Plan

This plan details the 6 root-cause upgrades to restore fast, accurate on-device image and video-frame NSFW detection using Google AI Edge LiteRT (`.tflite`) and Android's `AccessibilityService` screenshot pipeline, while preserving zero false-triggers on safe text UI and full Home Launcher immunity.

---

## User Review & Critical Decisions

> [!IMPORTANT]
> The following product and UX choices were confirmed during Phase 1 clarification and are integrated into the architecture below:

- **Confirmed Decision 1 — 1,500ms Active Media & Video Heartbeat Scanner with Dashboard Toggle**:
  - While an eligible browser or media app is in the foreground, the device screen is interactive (`PowerManager.isInteractive`), and the Curtain Shield is not visible, a periodic **1,500ms heartbeat scanner** captures frames so playing videos, Reels, GIFs, and stationary lightbox images are continuously evaluated even when the user is not scrolling.
  - A dedicated **"Continuous Video & Media Heartbeat (1.5s)"** toggle is added to the Main Dashboard so users can enable or pause continuous video scanning at any time (enabled by default).
- **Confirmed Decision 2 — Configurable Revealing (`sexy` 0.85x Weight) vs. Explicit-Only Scoring Toggle**:
  - In the 5-class MobileNetV2 NSFW model (`[0]=drawings, [1]=hentai, [2]=neutral, [3]=porn, [4]=sexy`), a **"Include Revealing / Swimwear (`Sexy` Class)"** toggle is added to the Main Dashboard (enabled by default):
    - When **enabled (Default)**: `combinedNsfwScore = pornProb + hentaiProb + (sexyProb * 0.85f)` and `finalScore = maxOf(pornProb + hentaiProb, combinedNsfwScore)`.
    - When **disabled (Explicit-Only)**: `finalScore = pornProb + hentaiProb`.
  - In both modes, if `neutralProb + drawingsProb > 0.78f` (such as a plain text webpage or settings screen), the frame is classified as **SAFE** to prevent UI chrome false positives.

---

## 1. Overview & Core Concept

- **What It Does**: Upgrades AmnGaze's Layer 2 on-device LiteRT vision engine from a single squished full-screen pass to a **3-crop aspect-ratio-preserving MobileNetV2 pipeline** paired with a **two-stage post-scroll scanner (`180ms` + `900ms`)** and a **1,500ms active video heartbeat**.
- **Target Audience / Persona**: Users who rely on AmnGaze for real-time protection across web browsers, social feeds, image galleries, and short-form video players without suffering false triggers on text pages or Home Launchers.
- **Key Value**:
  1. Eliminates the MobileNetV2 preprocessing bug where VGG BGR mean subtraction (`B - 104, G - 117, R - 123`) corrupted `[0.0f, 1.0f]` RGB MobileNetV2 inputs.
  2. Stops 9:20 tall-screen aspect-ratio squishing and browser chrome dilution by extracting **3 square `224x224` content crops** (Center, Upper-Middle, Lower-Middle).
  3. Catches both **lazy-loaded web images** (`+900ms` follow-up scan after scroll settle) and **playing videos** (`1,500ms` heartbeat while screen is interactive).

---

## 2. User Experience & Visual Design

- **Key User Flows**:
  1. **Scrolling Web & Social Feeds**: As the user scrolls a browser or social feed, the primary settle scan fires `180ms` after scrolling pauses, followed automatically by a `+900ms` verification scan to catch high-resolution images that finish downloading after the scroll stops.
  2. **Watching Videos / Reels / Stationary Media**: When the user watches a video without touching the screen, the `1,500ms` heartbeat scanner evaluates frames in the background and immediately stops the instant the user switches to their Home Launcher (`com.alhaq.amniquist`), locks the screen, or leaves the eligible app.
  3. **Dashboard Controls**: Inside the Main Dashboard's **3-Layer Pipeline & AI Vision** card, users can toggle:
     - **Include Revealing / Swimwear (`Sexy` 0.85x Weight)** vs. **Explicit-Only (`Porn + Hentai`)**
     - **Continuous Video & Media Heartbeat (1.5s)**
     - **Detection Sensitivity Slider (`20%–95%`)** calibrated so `25% -> 0.62`, `50% -> 0.50`, `75% -> 0.38`, and `95% -> 0.28`.
- **Visual Identity & Theme**:
  - Preserves the user-selectable Material 3 themes (`Sunset Glow`, `Emerald Calm`, `Cosmic Night`) with clear status badges and tactile toggle cards.

---

## 3. Key Product Decisions & Trade-Offs

- **Decision 1 — 3-Crop Aspect-Ratio Extraction vs. Single Full-Screen Downscale**:
  - *Chosen Approach*: Copy the `HardwareBuffer` once into an aspect-ratio-preserving software `ARGB_8888` bitmap (width `360px`, proportional height `~760px`), immediately close `HardwareBuffer` in `finally`, and extract 3 square `224x224` crops from the active content zone (trimming the top 10% status/URL bar and bottom 10% navigation bar):
    - **Crop 1 (Center Square)**: Centered vertically (`width x width`), evaluated first with immediate short-circuit if it exceeds the threshold.
    - **Crop 2 (Upper-Middle Square)**: Covering `10%` to `55%` vertical height.
    - **Crop 3 (Lower-Middle Square)**: Covering `45%` to `90%` vertical height.
  - *Why*: Capturing a 9:20 phone screen and squishing it directly to `224x224` compresses vertical anatomy by >50% and fills half the tensor with safe UI chrome. Three square crops preserve 1:1 proportions and focus directly on feed images and video players.
- **Decision 2 — Dynamic Tensor Inspection & RGB `[0.0f, 1.0f]` Normalization**:
  - *Chosen Approach*: Inspect `interpreter.getInputTensor(0)` and `interpreter.getOutputTensor(0)` dynamically for shape, `DataType` (`FLOAT32` vs `UINT8`/`INT8`), and `quantizationParams`, feeding `[0.0f, 1.0f]` RGB floats for `FLOAT32` MobileNetV2 models and `0..255` RGB bytes for `UINT8` quantized models, while logging raw class probabilities via `Log.d("AmnGazeAI", ...)`.
  - *Why*: Ensures both `amn_gaze.tflite` and `nsfw_mobilenet_v2_140_224_quantized.tflite` receive the exact tensor distribution they were trained on.
- **Decision 3 — Dynamic `PackageManager` Browser Discovery + Expanded Media Package Set**:
  - *Chosen Approach*: Query `PackageManager.queryIntentActivities(Intent(Intent.ACTION_VIEW, Uri.parse("https://www.example.com")), PackageManager.MATCH_ALL)` at service startup to automatically register every installed browser on the user's phone, and expand `HIGH_RISK_MEDIA_PACKAGES` to include gallery, search, and social media apps (`googlequicksearchbox`, `apps.photos`, `gallery3d`, `miui.gallery`, `pinterest`, `tumblr`, `twitch`, `vkontakte`, `challegram`, `vivaldi`, `kiwibrowser`, `via`, `cromite`), while keeping all `CATEGORY_HOME` launchers (`com.alhaq.amniquist`, etc.) 100% immune.

---

## 4. Technical Architecture & Data Strategy *(Technical Reference)*

### Architecture & Multi-Crop Vision Pipeline Diagram

```
┌──────────────────────────────────────────────────────────────────────────────┐
│                     AmnGazeAccessibilityService (API 30+)                    │
│                                                                              │
│  ┌────────────────────────────┐  ┌───────────────────────┐  ┌─────────────┐  │
│  │ Dynamic Package Discovery  │  │ Two-Stage Scroll Scan │  │ Video Media │  │
│  │ - PackageManager HTTP/HTTPS│  │ - Stage 1: +180ms     │  │ Heartbeat   │  │
│  │ - Expanded Gallery/Social  │  │ - Stage 2: +900ms     │  │ - Every 1.5s│  │
│  │ - HOME Launcher Immunity   │  │   (Lazy-Load Catch)   │  │   if Active │  │
│  └─────────────┬──────────────┘  └───────────┬───────────┘  └──────┬──────┘  │
│                └─────────────────────────────┼─────────────────────┘         │
│                                              ▼                               │
│                     takeScreenshot() -> HardwareBuffer                       │
└──────────────────────────────────────────────┬───────────────────────────────┘
                                               │
                                               ▼
┌──────────────────────────────────────────────────────────────────────────────┐
│                         ContentModerationEngine                              │
│                                                                              │
│  1. Safe HardwareBuffer Copy -> Proportional ARGB_8888 (width=360px)         │
│     (Close HardwareBuffer & recycle hwBitmap immediately in finally)         │
│                                                                              │
│  2. Multi-Crop Aspect-Ratio Extractor (Trim Top 10% & Bottom 10% Chrome):    │
│     ┌────────────────────┐  ┌────────────────────┐  ┌────────────────────┐   │
│     │ Crop 1: Center 1:1 │  │ Crop 2: Upper 1:1  │  │ Crop 3: Lower 1:1  │   │
│     │ (Short-Circuits if │─▶│ (10%..55% Height   │─▶│ (45%..90% Height   │   │
│     │  >= Threshold)     │  │  Scaled to 224x224)│  │  Scaled to 224x224)│   │
│     └────────────────────┘  └────────────────────┘  └────────────────────┘   │
│                                                                              │
│  3. Dynamic Tensor Inspection & Scoring:                                     │
│     - FLOAT32 RGB [0.0f..1.0f] / UINT8 RGB [0..255] + QuantizationParams     │
│     - 5-Class Scorer: Safe Guard if (neutral + drawings > 0.78f)             │
│       Else max(porn + hentai, porn + hentai + sexy * 0.85f)                  │
│     - Calibrated Threshold: 25% -> 0.62f | 50% -> 0.50f | 75% -> 0.38f       │
└──────────────────────────────────────────────────────────────────────────────┘
```

### Data Model & State Mapping
- **`AmnGazeState` & `SharedPreferences`**:
  - `videoHeartbeatEnabled: Boolean = true` (`PREFS_KEY_VIDEO_HEARTBEAT = "video_heartbeat_enabled"`)
  - `includeSexyInNsfwScore: Boolean = true` (`PREFS_KEY_INCLUDE_SEXY_SCORE = "include_sexy_score_enabled"`)
- **Calibrated Threshold Formula (`confidenceThresholdForSensitivity`)**:
  - Linear piecewise/smooth mapping across `sensitivity in 0.20f..0.95f`:
    - `0.25f -> 0.62f`
    - `0.50f -> 0.50f`
    - `0.75f -> 0.38f`
    - `0.95f -> 0.28f`
    - Formula: `(0.74f - (clamped * 0.48f)).coerceIn(0.28f, 0.65f)` (at `0.25` -> `0.62f`, at `0.50` -> `0.50f`, at `0.75` -> `0.38f`, at `0.95` -> `0.284f`).
- **Verification Gate**:
  - Unit & Robolectric tests in `AmnGazeLogicTest.kt` covering:
    1. 5-class probability scorer with `sexy [4]` (`0.85f` weight) and `neutral [2] + drawings [0] > 0.78f` safe guard.
    2. Sensitivity slider mapping (`25% -> ~0.62f`, `50% -> ~0.50f`, `75% -> ~0.38f`, `95% -> ~0.28f`).
    3. Multi-crop extractor generating 3 valid `224x224` square crops from a tall `1080x2400` portrait bitmap without out-of-bounds errors.
