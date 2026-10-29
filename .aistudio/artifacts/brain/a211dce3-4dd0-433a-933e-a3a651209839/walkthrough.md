# Real-Device Accuracy & Rendering Fix Pass — Walkthrough

## Summary of Fixes Implemented

### Fix 1: 100% Opaque Regional Blur With Real Pixel-Buffer Patches (`RegionalBlurOverlayView.kt`, `HaramBlurProjectionService.kt`, `WebViewModerationBridge.kt`)
- **Zero Translucent Bleed-Through (`alpha = 255`):** Removed all vertical stripe/grid-line patterns and translucent alphas (`< 255`) from `RegionalBlurOverlayView.kt` and `HaramBlurFilterLogic.computeOverlayAlpha()`. Every rendered region now uses a 100% solid opaque (`alpha = 255`) backing rect so underlying window pixels never bleed through on physical Android devices.
- **Real Frame Patch Sampling (`previewPatch`):** `HaramBlurProjectionService` extracts an `8x8` downscaled `previewPatch` from the clean `frameBitmap` for each active `TrackedBlurRegion` while excluding `currentLockedRects` when updating `lastUnoccludedFrame`:
  - **`PIXELATE`:** Samples `previewPatch` colors on a tile grid whose block size scales with `Effect Strength (1..10)` (`12dp` at 1 up to `38dp` at 10 via `computePixelationBlockSizeDp()`), clamps/desaturates skin tones into a neutral dark-slate palette, and draws every tile at `alpha = 255` with `gap = 0f` over an opaque `#FF0F172A` backing rect.
  - **`BLUR`:** Draws the heavily downscaled `8x8` `previewPatch` upscaled with bilinear filtering (`FILTER_BITMAP_FLAG`) plus a dark frosted tint overlay over a 100% opaque `#FF1E293B` backing rect.
  - **`BLACK`, `GRAY`, `SKIN`:** Renders 100% solid opaque rounded rectangles (`#FF000000`, `#FF64748B`, `#FFD4A373`) at all strength levels `1..10`.
- **In-App Protected Browser (`WebViewModerationBridge.kt`):** Updated `applyOverlayStyle` to apply `filter: blur(28px) brightness(0.22) contrast(0.4)` paired with an opaque `#0F172A` / `#1E293B` backdrop.

### Fix 2: Stop `DEFAULT_NSFW` Poisoning & Eliminate False Positives on Text/UI Pages (`ContentModerationEngine.kt`)
- **Exclusive `QUANTIZED_NSFW` 5-Class Scoring:** `resolveAdultBlockerModel()`, `classifyScreenMultiCrop()`, and `evaluateNsfwAndSkinBreakdown()` use `ModerationModelType.QUANTIZED_NSFW` (`nsfw_mobilenet_v2_140_224_quantized.tflite`) exclusively for 5-class NSFW scoring, removing the uncalibrated `DEFAULT_NSFW` fallback pass (and adding Caffe BGR mean subtraction `B - 104f, G - 117f, R - 123f` if `DEFAULT_NSFW` is ever called directly).
- **Skin-Ratio Gate & Neutral Dominance:**
  - `classifySingleCropInternal()` computes `estimateSkinPixelRatio(cropBitmap)` first and immediately short-circuits to `SAFE (confidence = 0f)` when `skinRatio < 0.045f`.
  - `computeFiveClassNsfwScore()` returns `0.0f` (`SAFE`) when `skinRatio < 0.05f && pornProb < 0.82f` or when `(neutralProb + drawingsProb) >= 0.50f`, and only includes `hentaiProb` when `hentaiProb >= 0.65f && neutralProb < 0.20f && skinRatio >= 0.08f`.
- **Calibrated Sensitivity Thresholds:** `confidenceThresholdForSensitivity()` maps `0.20f -> 0.78f`, `0.25f -> 0.74f`, `0.50f -> 0.60f`, `0.75f -> 0.48f`, and `0.95f -> 0.38f`.

### Fix 3: HaramBlur Grid & Body Detection Without Background False Positives (`ContentModerationEngine.kt`, `HaramBlurEngineLogic.kt`, `HaramBlurProjectionService.kt`)
- **No `GENDER_FILTER` on Unanchored Grid Cells:** Unanchored grid cells (`isGridCell = true`) never run `model_lite_gender_q.tflite` (`GENDER_FILTER`). Instead, they are evaluated with `QUANTIZED_NSFW` and `estimateSkinPixelRatio()` with `gender = inheritedGender ?: DetectedGender.UNKNOWN`.
- **Separated Face vs. Body/Grid Decision Rules (`HaramBlurFilterLogic.shouldBlurCandidate`):**
  - Localized face anchors (`!candidate.isGridCell && candidate.gender != UNKNOWN`) enforce strict gender isolation (`FEMALE` vs `MALE` at `>= max(0.62f, sensitivity.threshold)`, `BOTH` at `>= max(0.58f, sensitivity.threshold)`).
  - Grid cells and face-free body regions (`candidate.isGridCell || candidate.gender == UNKNOWN`) blur when `bodyVisualScore >= max(0.42f, sensitivity.threshold)` and `(revealingSkinConfidence >= 0.25f || explicitPornNudityConfidence >= 0.35f)`.
- **Tight Skin-Contour Bounding Boxes & Region Merging:** `computeTightSkinBoundingBox()` refines grid cells to the tight skin bounding box (`+8%` padding) and `mergeOverlappingCandidates()` merges adjacent/overlapping boxes (`IoU >= 0.18f` or center distance `< 0.22f`).

## Verification Results
- `compile_applet`: Succeeded with zero errors or warnings.
- `gradle :app:testDebugUnitTest --rerun-tasks`: All unit tests in `AmnGazeLogicTest` passed.
