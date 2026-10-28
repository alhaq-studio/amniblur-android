# Real-Time LiteRT Optimization, Strict Gender Isolation & Solid Effect Strength Walkthrough

## 1. LiteRT Inference Loop & Pre-Processing Optimization
- **Zero-Allocation Tensor Pre-Processing**: Added pre-computed `FLOAT_ZERO_TO_ONE_LUT` and `FLOAT_MINUS1_TO_1_LUT` lookup tables along with reusable per-dimension `ByteBuffer`, `IntArray`, and scaled `Bitmap` + `Canvas` buffers inside `ContentModerationEngine.preprocessBitmapInternal(..., reuseBuffers = true)`, eliminating per-crop heap and direct buffer allocations during real-time frame scanning.
- **Single-Pass Inference Fast-Path**: Restricted the fallback `[-1, 1]` normalization pass in `classifySingleCropInternal()` to borderline crops (`threshold * 0.58f .. threshold`) instead of executing two TFLite passes on every safe crop.
- **Aspect-Preserved `128x128` Gender Classification**: Replaced stretched `28x28` face resizing with aspect-preserved, center-padded `128x128` head crops in `classifyCropForHaramBlur()` to preserve facial proportions for accurate gender classification.

## 2. Real-Time Unoccluded Detection & Scroll Tracking
- **Self-Occlusion Elimination**: `HaramBlurProjectionService` now caches the last unoccluded capture frame (`lastUnoccludedFrame`) and restores unblurred pixels beneath active overlay regions when the exterior scene is steady (`exteriorDiff < 0.14f`), preventing `RegionalBlurOverlayView` from hiding the underlying face/skin from the detector on subsequent frames.
- **Scroll Delta Tracking**: `AmnGazeAccessibilityService` computes normalized vertical scroll deltas from `TYPE_VIEW_SCROLLED` events and shifts active regional boxes smoothly without wiping regions on zero-delta layout events.

## 3. Strict Gender-Isolated Detection & Mandatory Explicit Protection
- **Two-Tier NSFW Breakdown**: Separated `explicitPornNudityConfidence` (`porn + hentai`) from `revealingSkinConfidence` (`sexy * 0.85f` and HSV/YCbCr skin-ratio estimation) on `CandidateBlurBox`.
- **Subject-Linked Face & Skin Regions**: Upper-body and exposed skin regions below a detected face inherit that person's verified `DetectedGender`, and `HaramBlurFilterLogic.shouldBlurCandidate()` strictly blocks blurring of `DetectedGender.MALE` when `Female` is selected (and blocks `DetectedGender.FEMALE` when `Male` is selected), while still mandatorily blurring hardcore pornography or explicit nudity (`explicitPornNudityConfidence >= max(0.55f, sensitivity.threshold)`) for any gender.

## 4. Opt-In Pre-Blur for Newly Appearing Frames, Images & Photos
- Added `preBlurNewMediaUntilScanned` (`PREFS_KEY_PRE_BLUR_NEW_MEDIA`) to `AmnGazeState` and `AmnGazeViewModel`, exposed via toggles in both the Dashboard (`HaramBlurDashboardSection`) and Settings (`InAppProtectionScreens`), and wired into `WebViewModerationBridge` (`PREBLUR` state on newly inserted `<img>` and `<video>` elements until AI verification completes).

## 5. Effect Strength (`1..10`) Solidness vs. Transparency & Density Curve
- Implemented `computeOverlayAlpha(effectStrength)` (`115` at `1/10` up to **`255` 100% solid opaque** at `9/10` and `10/10`), `computeOverlaySolidnessPercent(effectStrength)` (`45%..100%`), and `computeDensityFactor(effectStrength)` (`0.0f..1.0f`).
- Updated `RegionalBlurOverlayView.onDraw()` (`BLUR`, `PIXELATE`, `BLACK`, `GRAY`, `SKIN`) and `WebViewModerationBridge` (`applyOverlayStyle`) so higher selections progressively increase opacity and internal mosaic/frosted density up to a completely solid, zero-see-through cover at `9/10` and `10/10`.
