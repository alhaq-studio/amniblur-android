# AmnGaze Engine Accuracy, 100% Opaque Rendering & Grid/Body Precision Walkthrough

> [!NOTE]
> All 28 unit test suites in `AmnGazeLogicTest.kt` plus `ExampleRobolectricTest`, `ExampleUnitTest`, and `GreetingScreenshotTest` (30 actionable Gradle test tasks) and `:app:assembleDebug` pass cleanly (`BUILD SUCCESSFUL`). All changes are committed to `main` (`a474018`).

---

## 1. Real-Device Failure Root Causes & Engineering Fixes

### Fix 1: 100% Solid Opaque Regional Blur With Real Pixelated/Blurred Patches
- **Root Cause on Physical Devices:** Drawing semi-transparent colors (`alpha < 255`) or vertical grid stripes on `RegionalBlurOverlayView` does not blur the underlying Android window—it acts as a translucent tint where faces and bodies remain visible underneath.
- **Changes Made:**
  - **[RegionalBlurOverlayView.kt](file:///home/habib/PROJECTS/Amni/AmnHaze-Android/app/src/main/java/com/amngaze/RegionalBlurOverlayView.kt):**
    - Removed all vertical stripe / jail-bar patterns and translucent alpha values (`< 255`), including setting `borderPaint.color` to 100% opaque `Color.argb(255, 71, 85, 105)`.
    - **`PIXELATE` Mode:** Samples clean frame colors from `region.previewPatch` into a gapless (`gap = 0f`) mosaic grid whose tile size scales from `12dp` (strength 1) to `38dp` (strength 10) over a `255` opaque `#FF0F172A` backing rect. Uses `ContentModerationEngine.isSkinPixel()` to clamp/desaturate skin tones into a dark-slate palette while preserving natural non-skin contrast.
    - **`BLUR` Mode:** Draws the downscaled `8x8` `previewPatch` upscaled with `Paint(Paint.FILTER_BITMAP_FLAG)` plus a dark frosted tint (`Color.argb(210, 20, 28, 42)`) over a `255` opaque `#FF1E293B` backing rect so no facial features or contours bleed through.
    - **`BLACK`, `GRAY`, `SKIN` Modes:** Renders 100% solid opaque (`alpha = 255`) rounded rectangles (`#FF000000`, `#FF64748B`, `#FFD4A373`) across all strength levels `1..10`.
    - **Bitmap Lifecycle Safety:** [`updateBoxes`](file:///home/habib/PROJECTS/Amni/AmnHaze-Android/app/src/main/java/com/amngaze/RegionalBlurOverlayView.kt#L47-L64) recycles an `oldPatch` only when `newBoxes.none { it.previewPatch === oldPatch }`, and [`onDraw`](file:///home/habib/PROJECTS/Amni/AmnHaze-Android/app/src/main/java/com/amngaze/RegionalBlurOverlayView.kt#L122-L209) checks `if (patch != null && !patch.isRecycled)` before accessing pixels or calling `canvas.drawBitmap()`.
  - **[HaramBlurProjectionService.kt](file:///home/habib/PROJECTS/Amni/AmnHaze-Android/app/src/main/java/com/amngaze/HaramBlurProjectionService.kt) & [HaramBlurEngineLogic.kt](file:///home/habib/PROJECTS/Amni/AmnHaze-Android/app/src/main/java/com/amngaze/HaramBlurEngineLogic.kt):**
    - Added `previewPatch: Bitmap? = null` to [`TrackedBlurRegion`](file:///home/habib/PROJECTS/Amni/AmnHaze-Android/app/src/main/java/com/amngaze/HaramBlurEngineLogic.kt#L29-L36) and [`replaceActiveRegions`](file:///home/habib/PROJECTS/Amni/AmnHaze-Android/app/src/main/java/com/amngaze/HaramBlurEngineLogic.kt#L318-L322) on `AntiFlickerRegionTracker`.
    - When attaching an `8x8` `previewPatch` in [`processLatestFrame`](file:///home/habib/PROJECTS/Amni/AmnHaze-Android/app/src/main/java/com/amngaze/HaramBlurProjectionService.kt#L384-L423), stationary locked regions (`reg.id in lockedRegionIds`) with a valid unrecycled `previewPatch` keep their existing clean patch so drawn overlays never overwrite the preview.
    - Clipped out all `currentLockedRects` via `clipOutRect` before updating `lastCleanFrameBitmap` so drawn overlay boxes are never burned into the clean reference frame.
  - **[WebViewModerationBridge.kt](file:///home/habib/PROJECTS/Amni/AmnHaze-Android/app/src/main/java/com/amngaze/WebViewModerationBridge.kt):**
    - Updated `applyOverlayStyle` and injected stylesheet so blurred DOM nodes use `filter: blur(28px) brightness(0.22) contrast(0.4)` paired with an opaque `[data-amngaze-verdict="BLURRED"]::before` backdrop (`#1E293B`).

---

### Fix 2: Stop `DEFAULT_NSFW` (`amn_gaze.tflite`) Poisoning & Eliminate UI/Text False Positives
- **Root Cause:** `amn_gaze.tflite` expects Caffe BGR `0..255` with VGG mean subtraction (`B - 104, G - 117, R - 123`). Feeding `[0.0f..1.0f]` RGB caused it to interpret blank/text screens as solid flesh color and output `>85%` NSFW. Additionally, MobileNetV2 outputs elevated `drawings [0]` and `hentai [1]` scores on 2D app UI and vector icons.
- **Changes Made in [ContentModerationEngine.kt](file:///home/habib/PROJECTS/Amni/AmnHaze-Android/app/src/main/java/com/amngaze/ContentModerationEngine.kt):**
  1. **Exclusive `QUANTIZED_NSFW` Routing:** [`resolveAdultBlockerModelType`](file:///home/habib/PROJECTS/Amni/AmnHaze-Android/app/src/main/java/com/amngaze/ContentModerationEngine.kt#L218-L220), [`classifyScreenMultiCrop`](file:///home/habib/PROJECTS/Amni/AmnHaze-Android/app/src/main/java/com/amngaze/ContentModerationEngine.kt#L755-L825), and [`evaluateNsfwAndSkinBreakdown`](file:///home/habib/PROJECTS/Amni/AmnHaze-Android/app/src/main/java/com/amngaze/ContentModerationEngine.kt#L1189-L1228) use `ModerationModelType.QUANTIZED_NSFW` exclusively, and the secondary `DEFAULT_NSFW` fallback pass is removed. (If `DEFAULT_NSFW` is invoked directly, `preprocessBitmapInternal` applies Caffe BGR mean subtraction `B - 104f, G - 117f, R - 123f`).
  2. **Pre-Inference Skin Short-Circuit:** [`classifySingleCropInternal`](file:///home/habib/PROJECTS/Amni/AmnHaze-Android/app/src/main/java/com/amngaze/ContentModerationEngine.kt#L868-L888) computes `val skinRatio = estimateSkinPixelRatio(cropBitmap)` first and immediately returns `SAFE (confidence = 0f)` when `skinRatio < 0.045f` without running LiteRT inference.
  3. **Hardened 5-Class Scoring ([`computeFiveClassNsfwScore`](file:///home/habib/PROJECTS/Amni/AmnHaze-Android/app/src/main/java/com/amngaze/ContentModerationEngine.kt#L76-L129)):**
     - **Skin & Media Presence Gate:** Returns `0.0f` (`SAFE`) if `skinRatio < 0.05f && pornProb < 0.82f`.
     - **Safe UI / Text Guard:** Returns `0.0f` (`SAFE`) if `(neutralProb + drawingsProb) >= 0.50f && pornProb < 0.82f`.
     - **False Hentai Spike Suppression:** Includes `hentaiProb` only when `hentaiProb >= 0.65f && neutralProb < 0.20f && skinRatio >= 0.08f`.
     - **Strict `includeSexyScore == false` Handling:** Requires `skinRatio >= 0.08f` when `pornProb < 0.82f`.
  4. **Updated Sensitivity Threshold Curve ([`confidenceThresholdForSensitivity`](file:///home/habib/PROJECTS/Amni/AmnHaze-Android/app/src/main/java/com/amngaze/ContentModerationEngine.kt#L59-L67)):**
     - `20%` -> `0.78f`, `25%` -> `0.74f`, `50%` -> `0.60f`, `75%` -> `0.48f`, `95%` -> `0.38f`.

---

### Fix 3: HaramBlur Unanchored Grid & Body Detection, Tight Skin Contours & Box Merging
- **Root Cause:** Running `model_lite_gender_q.tflite` (`GENDER_FILTER`) on all 9 cells of a `3x3` grid caused walls/curtains to be labeled `FEMALE` and blurred, while cropping only the top `5%` (`headCrop`) of face-free lingerie/body tiles caused `GENDER_FILTER` to guess `MALE`, which vetoed blurring in `FEMALE` mode (`DetectedGender.MALE -> false`).
- **Changes Made in [ContentModerationEngine.kt](file:///home/habib/PROJECTS/Amni/AmnHaze-Android/app/src/main/java/com/amngaze/ContentModerationEngine.kt) & [HaramBlurEngineLogic.kt](file:///home/habib/PROJECTS/Amni/AmnHaze-Android/app/src/main/java/com/amngaze/HaramBlurEngineLogic.kt):**
  1. **Never Run `GENDER_FILTER` on Face-Free Grid Cells (`isGridCell == true`):** [`classifyCropForHaramBlur`](file:///home/habib/PROJECTS/Amni/AmnHaze-Android/app/src/main/java/com/amngaze/ContentModerationEngine.kt#L1518-L1530) skips `GENDER_FILTER` and `headCrop` when `isGridCell == true`, evaluating the full grid cell with `QUANTIZED_NSFW` and `skinRatio`, and setting `gender = inheritedGender ?: DetectedGender.UNKNOWN`.
  2. **Dual-Case Candidate Policy ([`HaramBlurFilterLogic.shouldBlurCandidate`](file:///home/habib/PROJECTS/Amni/AmnHaze-Android/app/src/main/java/com/amngaze/HaramBlurEngineLogic.kt#L63-L117)):**
     - **Case 1 (Localized Face Anchor, `gender != UNKNOWN`):** Enforces strict gender isolation (`>= max(0.62f, sensitivity.threshold)` for `FEMALE`/`MALE`, `>= max(0.58f, sensitivity.threshold)` for `BOTH`).
     - **Case 2 (Unanchored Grid Cell or Body Region, `gender == UNKNOWN`):** Blurs when `bodyVisualScore = explicitPornNudityConfidence + revealingSkinConfidence >= max(0.42f, sensitivity.threshold)` **AND** (`revealingSkinConfidence >= 0.25f || explicitPornNudityConfidence >= 0.35f`).
  3. **Tight Skin-Contour Bounding Box Refinement ([`computeTightSkinBoundingBox`](file:///home/habib/PROJECTS/Amni/AmnHaze-Android/app/src/main/java/com/amngaze/ContentModerationEngine.kt#L251-L315)):** Computes the tight `[minX, minY, maxX, maxY]` skin/foreground contour on a `16x16` grid inside each triggered cell, padded by `+8%`.
  4. **Single-Person Adjacent Box Merging ([`mergeAdjacentPersonBoxes`](file:///home/habib/PROJECTS/Amni/AmnHaze-Android/app/src/main/java/com/amngaze/ContentModerationEngine.kt#L321-L398)):** Merges vertically or horizontally adjacent skin/body boxes (`IoU >= 0.15f` or shared edge overlap `>= 0.50f` when `gridCellsTriggered <= 3`) into one unified bounding box.

---

## 2. Verification Summary

- **Command Executed:**
  ```bash
  ./gradlew testDebugUnitTest assembleDebug
  ```
- **Result:** `BUILD SUCCESSFUL` — All unit tests in [AmnGazeLogicTest.kt](file:///home/habib/PROJECTS/Amni/AmnHaze-Android/app/src/test/java/com/example/AmnGazeLogicTest.kt) passed with 0 failures:
  - `testTextAndUiCropsNeverFalseTriggerWithZeroSkinRatio`
  - `testFiveClassNsfwScoringAndSafeUiGuard`
  - `testSensitivitySliderThresholdMapping`
  - `testFaceFreeGridCellsNeverRunGenderFilterAndBlurOnRevealingSkin`
  - `testTightSkinBoundingBoxAndAdjacentBoxMerging`
  - `testEffectStrengthSolidnessAndDensityCurveReachesOpaque255`
  - `testAutomaticAdultBlockerModelRoutingNeverUsesGenderModelStandalone`
- **Documentation Synced:**
  - Created [ARCHITECTURE.md](file:///home/habib/PROJECTS/Amni/AmnHaze-Android/ARCHITECTURE.md)
  - Updated [README.md](file:///home/habib/PROJECTS/Amni/AmnHaze-Android/README.md)
  - Updated [walkthrough.md](file:///home/habib/PROJECTS/Amni/AmnHaze-Android/walkthrough.md)
