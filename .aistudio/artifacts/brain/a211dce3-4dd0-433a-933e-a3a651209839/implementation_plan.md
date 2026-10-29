# AmnGaze Content Blocker — Real-Device Accuracy & 100% Opaque Regional Blur Fix Plan

Delivers a targeted 3-stage accuracy and rendering upgrade to eliminate see-through regional blur stripes on physical Android devices, stop `DEFAULT_NSFW` (`amn_gaze.tflite`) Caffe BGR mean-subtraction false positives on text/UI screens, and prevent background grid walls from being blurred while ensuring face-free lingerie/body tiles are reliably detected and tightly bounded.

## User Review & Critical Decisions

> [!IMPORTANT]
> All requirements, thresholds, and rendering behaviors are specified in detail and ready for execution upon approval:

- **100% Opaque Regional Rendering (`alpha = 255`) with Real Sampled Patches**: Every regional blur overlay (`BLUR`, `PIXELATE`, `BLACK`, `GRAY`, `SKIN`) renders at `alpha = 255` (`100%` solid opaque) with zero vertical stripes or tile gaps (`gap = 0f`), using a real sampled color grid / downscaled `8x8` patch from the unoccluded frame so `Effect Strength (1..10)` controls pixelation block coarseness (`12dp..38dp`) and blur diffusion while never allowing raw underlying pixels to bleed through.
- **Exclusive `QUANTIZED_NSFW` 5-Class Scoring + Skin/UI Presence Gate**: Eliminates `DEFAULT_NSFW` (`amn_gaze.tflite`) fallback poisoning on `[0..1]` RGB crops, enforces a `skinRatio < 0.045f` short-circuit (`< 0.05f` inside `computeFiveClassNsfwScore` when `pornProb < 0.82f`), lowers the `neutral + drawings` safe UI guard to `0.50f`, suppresses false `hentaiProb` spikes on 2D UI, and raises strict sensitivity thresholds (`20% -> 0.78f`, `25% -> 0.74f`, `50% -> 0.60f`, `75% -> 0.48f`, `95% -> 0.38f`).
- **Face-Anchored Gender Classification + Unanchored Grid Body/Lingerie Detection**: `GENDER_FILTER` (`model_lite_gender_q.tflite`) is restricted strictly to localized face anchors (`isFaceAnchor == true`) and never runs on face-free grid cells (`isGridCell == true`). Unanchored grid cells and body crops evaluate full-crop `QUANTIZED_NSFW` + skin ratio (`gender = UNKNOWN`), blurring whenever `explicitPornNudityConfidence + revealingSkinConfidence >= max(0.42f, sensitivity.threshold)` and `revealingSkinConfidence >= 0.25f` (or `explicitPornNudityConfidence >= 0.35f`), with tight `16x16` skin-contour bounding box refinement (`+8%` padding) and adjacent box merging.

---

## 1. Overview & Core Concept

- **What It Does**:
  1. Replaces semi-transparent (`alpha < 255`) and striped regional overlays with **100% opaque (`alpha = 255`)** real pixel-sampled mosaic/frosted patches and high-attenuation WebView CSS filters (`filter: blur(28px) brightness(0.22) contrast(0.4)`).
  2. Eliminates false-positive full-screen blocks on text-only pages, settings screens, and vector UI chrome in Module 1 (Explicit Content Blocker).
  3. Fixes Module 2 (HaramBlur) 3x3 profile grids and Reels feeds so walls/curtains are never falsely labeled `FEMALE`, while headless lingerie/body photos are never falsely vetoed as `MALE`.
- **Target Audience / Persona**: Users running AmnGaze on physical Android devices across social feeds, profile grids, video streams, and web browsers who require zero see-through censorship and zero false triggers on text/UI screens.
- **Key Value**: True 100% opaque regional occlusion on hardware displays, immunity to text/UI false positives, and high-precision body/face regional bounding boxes.

---

## 2. User Experience & Visual Design

- **Key User Flows**:
  - **Real-Time Regional Blur (`BLUR` / `PIXELATE` / `BLACK` / `GRAY` / `SKIN`)**:
    - `PIXELATE`: Renders a 100% opaque (`#FF0F172A`) base rounded rect covered by an `8x8` sampled color mosaic from the unoccluded frame (desaturated and darkened into a safe slate palette) with `alpha = 255`, `gap = 0f`, and tile sizes scaling from `12dp` (`1/10`) to `38dp` (`10/10`).
    - `BLUR`: Renders a 100% opaque (`#FF1E293B`) base rounded rect with an `8x8` bilinearly upscaled (`FILTER_BITMAP_FLAG`) and darkened frosted patch at `alpha = 255` with zero vertical/horizontal stripes.
    - `BLACK` / `GRAY` / `SKIN`: Renders a 100% solid opaque (`alpha = 255`) rounded rect (`#FF000000`, `#FF64748B`, `#FFD4A373`) across all strength levels `1..10`.
  - **In-App Protected Browser**:
    - Blurred DOM images and videos apply `filter: blur(28px) brightness(0.22) contrast(0.4)` paired with an opaque backdrop color so underlying contours never bleed through.
- **Visual Identity & Theme**:
  - High-contrast dark slate privacy surfaces (`#FF0F172A`, `#FF1E293B`, `#FF64748B`) with smooth `14dp` rounded corners and zero jail-bar stripe artifacts.

---

## 3. Key Product Decisions & Trade-Offs

- **Decision 1: 100% Opaque (`alpha = 255`) Regional Rendering with Sampled Unoccluded Patches**
  - *Chosen Approach*: Always return `255` from `computeOverlayAlpha(1..10)` and attach an `8x8` sampled color grid (`sampledColors: IntArray`) / downscaled patch extracted from the unoccluded `frameBitmap` to each `TrackedBlurRegion`. Use `Effect Strength (1..10)` to control mosaic tile coarseness (`12dp..38dp`) and frosted diffusion intensity while maintaining `alpha = 255` and `gap = 0f`.
  - *Why*: An Android `TYPE_APPLICATION_OVERLAY` Canvas draws *on top* of the compositor surface rather than blurring the underlying window buffer; any `alpha < 255` or inter-tile gap acts like a tinted window and reveals the face/body underneath.
- **Decision 2: Exclusive `QUANTIZED_NSFW` + Skin/UI Presence Gate for Module 1**
  - *Chosen Approach*: Use `QUANTIZED_NSFW` (`nsfw_mobilenet_v2_140_224_quantized.tflite`) exclusively for 5-class NSFW scoring, short-circuiting crops with `skinRatio < 0.045f` before inference, returning `0.0f` when `skinRatio < 0.05f && pornProb < 0.82f` or `(neutralProb + drawingsProb) >= 0.50f`, and gating `hentaiProb` behind `hentaiProb >= 0.65f && neutralProb < 0.20f && skinRatio >= 0.08f`.If `DEFAULT_NSFW` (`amn_gaze.tflite`) is ever called directly, preprocess with Caffe BGR mean subtraction (`B - 104f, G - 117f, R - 123f`).
  - *Why*: Prevents `amn_gaze.tflite` from misinterpreting `[0..1]` RGB inputs as solid flesh tone and stops MobileNetV2 `drawings`/`hentai` activations on 2D app UI and text pages.
- **Decision 3: Face-Only `GENDER_FILTER` & Tight Skin-Contour Grid Bounding Boxes**
  - *Chosen Approach*: Never invoke `GENDER_FILTER` (`model_lite_gender_q.tflite`) on face-free grid cells (`isGridCell == true`). Evaluate unanchored grid cells with full-crop `QUANTIZED_NSFW` + skin ratio (`gender = UNKNOWN`), tighten triggered grid rectangles to the `16x16` skin/foreground contour (`+8%` padding), merge adjacent boxes (`IoU >= 0.15f` or shared edge overlap `>= 0.50f` when `<= 3` cells trigger), and update `lastUnoccludedFrame` only outside `currentLockedRects`.
  - *Why*: Eliminates false `FEMALE` labels on background walls/curtains, eliminates false `MALE` vetoes on headless lingerie/body tiles, and prevents drawn overlay boxes from being copied into the clean reference frame.

---

## 4. Technical Architecture & Data Strategy *(Technical Reference)*

```
┌──────────────────────────────────────────────────────────────────────────────┐
│                   MediaProjection / Accessibility Frame                      │
└──────────────────────────────────────┬───────────────────────────────────────┘
                                       │
                                       ▼
┌──────────────────────────────────────────────────────────────────────────────┐
│            Unoccluded Reference Frame Manager (Outside Active Rects)         │
│  • Restores clean pixels inside currentLockedRects when scene is stationary  │
│  • Updates lastUnoccludedFrame ONLY outside currentLockedRects               │
│  • Extracts 8x8 desaturated/darkened color grid per TrackedBlurRegion        │
└───────────────────┬──────────────────────────────────────┬───────────────────┘
                    │                                      │
                    ▼                                      ▼
┌──────────────────────────────────────┐  ┌────────────────────────────────────┐
│   Module 1: Explicit Content Blocker │  │  Module 2: HaramBlur Regional Scan │
│  • Skin Presence Gate (<4.5% -> SAFE)│  │  • Face Anchors -> GENDER_FILTER   │
│  • QUANTIZED_NSFW 5-Class Exclusive  │  │  • Unanchored Grid -> UNKNOWN +    │
│  • Safe UI Guard (neutral+draw>=0.50)│  │    QUANTIZED_NSFW Body/Skin Score  │
│  • Strict Thresholds (0.78f..0.38f)  │  │  • Tight 16x16 Skin-Contour + Merge│
└──────────────────────────────────────┘  └────────────────┬───────────────────┘
                                                           │
                                                           ▼
                                          ┌────────────────────────────────────┐
                                          │ 100% Opaque Regional Render Engine │
                                          │  • alpha = 255 across 1..10        │
                                          │  • PIXELATE: 12dp..38dp, gap = 0f  │
                                          │  • BLUR: 8x8 upscaled + dark frost │
                                          │  • BLACK / GRAY / SKIN: solid 255  │
                                          └────────────────────────────────────┘
```

### Data Model & State Updates
- **`CandidateBlurBox`**:
  - Adds `isGridCell: Boolean = false` and `skinRatio: Float = 0f` so `HaramBlurFilterLogic.shouldBlurCandidate` distinguishes localized face anchors (`!isGridCell && gender != UNKNOWN`) from unanchored grid/body cells (`isGridCell || gender == UNKNOWN`).
- **`TrackedBlurRegion`**:
  - Adds `sampledColors: IntArray? = null` (`8x8` = 64 opaque ARGB colors sampled and desaturated/darkened from the unoccluded `frameBitmap` region) so `RegionalBlurOverlayView` renders real pixelated/blurred patches at `alpha = 255` with zero vertical stripes and zero gaps.
- **Verification Gate**:
  - Unit tests in `AmnGazeLogicTest` covering:
    1. Zero false positives on text/UI crops (`skinRatio < 0.05f` or `neutral + drawings >= 0.50f`, even when `hentaiProb = 0.60f`).
    2. No `GENDER_FILTER` on face-free grid cells (`classifyCropForHaramBlur(cropBitmap, isGridCell = true)` returns `gender == DetectedGender.UNKNOWN` and `shouldBlurCandidate == false` on background tiles).
    3. Lingerie/body grid tile blurring (`isGridCell = true`, `gender = UNKNOWN`, `revealingSkinConfidence = 0.55f` returns `true` in `FEMALE` mode).
    4. 100% opaque overlay alpha (`computeOverlayAlpha(1..10) == 255`) and `computePixelationBlockSizeDp(1..10)` scaling (`12f..38f`).
