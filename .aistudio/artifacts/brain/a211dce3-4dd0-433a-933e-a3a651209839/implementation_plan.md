# Real-Time LiteRT Inference Optimization, Gender-Isolated Detection, Solid Blur Intensity (`1..10`) & Dynamic Overlay Persistence Plan

## 1. Root-Cause Findings from Codebase & Model Investigation

### A. Why Blur Intensity / Effect Strength (`1..10`) Could Not Reach Solid (Zero See-Through)
1. **Hard-Capped Alpha Below `255` in `RegionalBlurOverlayView.kt`:**
   - **Blur Style (`drawBlurStyle`, line 99):** `val alpha = (175 + (effectStrength.coerceIn(1, 10) * 6)).coerceIn(180, 240)` hard-caps alpha at `240` (`~94%`), never reaching `255` (100% solid opaque). Even at `10/10`, underlying high-contrast colors/silhouettes bleed through the translucent region.
   - **Pixelate Style (`drawPixelateStyle`, lines 123–145):** Base fill alpha is hardcoded to `215` (`~84%`) and grid cells are hardcoded to `160..245` regardless of `effectStrength`. In addition, `computePixelationBlockSizeDp()` scaled blocks from `8dp` (`1/10`) up to `36dp` (`10/10`), making `10/10` *coarser/sparser* with gaps instead of denser and more solid.
   - **Solid Styles (`drawSolidStyle`, lines 170–174):** `GRAY` and `SKIN` cap alpha at `250` (`coerceIn(205, 250)`), never reaching `255` opaque, while `BLACK` starts at `223` at `1/10` rather than offering a smooth transparency-to-solidness range across `1..10`.
2. **In-App WebView CSS Blur Bleed-Through (`WebViewModerationBridge.kt`, lines 288–305):**
   - Applying CSS `filter: blur(Npx)` alone leaves low-frequency skin/color contours visible at high strength unless paired with progressive contrast compression and solid backdrop opacity scaling at higher strength levels (`8..10/10`).

### B. Why Selecting `Female` Still Blurred `Male` Faces, Figures & Skin (and Vice-Versa)
1. **Binary Face Classifier (`model_lite_gender_q.tflite`) Fed Non-Face / Full-Body Crops:**
   - Inspection of `app/src/main/assets/model_lite_gender_q.tflite` confirms input `input_4` is `[1, 128, 128, 3]` and output `Identity` is `[1, 2]` (`[0] = Male`, `[1] = Female`). Because its 2-class softmax always sums to `1.0` (`maleProb + femaleProb == 1.0`) with **no background or non-face class**, passing full-screen bands (`0.12..0.88`) or torso/background crops into `GENDER_FILTER` produces arbitrary `Male`/`Female` probabilities on non-face regions.
2. **Gender-Agnostic `sexy` Skin Score Overriding Target Gender:**
   - In `ContentModerationEngine.classifyCropForHaramBlur()` (line 884), `nsfwConfidence` was set to `femaleScore`, and in `WebViewModerationBridge.kt` (line 454), `val shouldBlur = nsfwVerdict.isViolation || shouldBlurGender`.
   - Because the 5-class `QUANTIZED_NSFW` model (`[0]=drawings, [1]=hentai, [2]=neutral, [3]=porn, [4]=sexy`) triggers `sexy` on **any** exposed skin (such as a shirtless man, male athlete, or male arms/chest), male exposed skin triggered `nsfwVerdict.isViolation` even when `Who to Blur = Female` and the detected face was `MALE`.

### C. LiteRT Inference & Frame-Processing Bottlenecks (Careful, Non-Destructive Optimization)
1. **Per-Crop Direct ByteBuffer & Bitmap Allocations (`ContentModerationEngine.kt`):**
   - `preprocessBitmap()` allocates a new `ByteBuffer.allocateDirect(...)`, `IntArray`, and scaled `Bitmap` on every crop (`9` to `16+` allocations per frame), causing GC pauses during continuous real-time scanning.
2. **Redundant Double Inference on Safe Crops (`ContentModerationEngine.kt`, lines 501–526):**
   - `classifySingleCropInternal()` runs a second full LiteRT `interpreter.run(...)` with `ALT_FLOAT` normalization whenever a crop is safe (`< threshold`), doubling inference time on every safe crop.
3. **Synchronous Full-WebView UI-Thread Rasterization (`WebViewModerationBridge.kt`, lines 387–393):**
   - Calling `webView.draw(canvas)` on a full-resolution `ARGB_8888` bitmap every `220ms` on `Dispatchers.Main` blocks scrolling in the In-App Protected Browser.

### D. Real-Time Regional Overlay Persistence Issues
1. **Overlay Self-Occlusion in `MediaProjection` (`HaramBlurProjectionService.kt`):**
   - Once `RegionalBlurOverlayView` draws over a detected face/body region, the next `MediaProjection` frame captures the *blur box itself* instead of the underlying face/skin.
2. **Scroll Events Wiping All Regions (`AmnGazeAccessibilityService.kt` & `HaramBlurEngineLogic.kt`):**
   - `TYPE_VIEW_SCROLLED` often reports `scrollDeltaY = 0f`, and `onScrollDetected(0f)` immediately executed `activeRegions.clear()`, causing overlays to blink off while scrolling.

---

## 2. Planned Architecture & Surgical Changes

### Step 1: Effect Strength (`1..10`) Solidness vs. Transparency & Density Curve
- **Files:** `HaramBlurEngineLogic.kt`, `RegionalBlurOverlayView.kt`, `WebViewModerationBridge.kt`, `HaramBlurDashboardSection.kt`, `InAppProtectionScreens.kt`, `AmnGazeViewModel.kt`
- **Unified Solidness & Density Curve (`HaramBlurFilterLogic`):**
  - Add `computeOverlayAlpha(effectStrength: Int): Int`:
    - `1/10` → `alpha = 115` (`~45%` solidness — light translucent veil)
    - `3/10` → `alpha = 158` (`~62%` solidness)
    - `5/10` → `alpha = 198` (`~78%` solidness — medium dense blur)
    - `7/10` → `alpha = 232` (`~91%` solidness — heavy dense blur)
    - `8/10` → `alpha = 246` (`~96%` solidness)
    - `9/10` → `alpha = 255` (`100%` solid opaque — zero see-through)
    - `10/10` → `alpha = 255` (`100%` solid opaque + maximum layer/tile density — zero see-through)
  - Add `computeDensityFactor(effectStrength: Int): Float` (`0.1f..1.0f`) to control internal layer count, frosted grid density, and pixelation tile packing density:
    - **Blur (`BLUR`):** Base fill uses `computeOverlayAlpha(effectStrength)` (reaching `alpha = 255` solid opaque at `9..10/10`), plus dense multi-band frosted diffusion layers that scale with `effectStrength` so `9..10/10` is completely solid with zero see-through.
    - **Pixelate (`PIXELATE`):** Base backing fill uses `computeOverlayAlpha(effectStrength)` (reaching `alpha = 255` at `9..10/10`), tile gaps shrink to `0f` at high intensity (`>= 8/10`), and tile alpha scales up to `255` so high strength produces a completely solid, dense mosaic with zero see-through.
    - **Solid (`BLACK`, `GRAY`, `SKIN`):** Alpha scales smoothly via `computeOverlayAlpha(effectStrength)` from `115` (`1/10`) all the way to `255` (`9..10/10` 100% opaque solid cover).
  - **In-App Browser Overlay Strength Sync (`WebViewModerationBridge.kt` & `AmnGazeViewModel.kt`):**
    - Sync `updateHaramBlurEffectStrength(1..10)` and `updateInAppBlurStrengthPx(1..20)` so adjusting Effect Strength in either Dashboard or Settings updates both consistently.
    - In `WebViewModerationBridge.applyOverlayStyle()`, scale both blur radius and solidness (contrasting/dimming backdrop opacity up to 100% opaque solid cover at `9..10/10` / `18..20px`) so high intensity leaves zero see-through silhouette.
  - **UI Clarity (`HaramBlurDashboardSection.kt` & `InAppProtectionScreens.kt`):**
    - Display the live solidness/density percentage alongside the `Effect Strength: X / 10` slider label (e.g., `Solidness: 100% (Solid)` at `9–10/10`).

### Step 2: Strict Gender-Isolated Detection + Mandatory Pornography/Nudity Blurring
- **Files:** `HaramBlurEngineLogic.kt`, `ContentModerationEngine.kt`, `WebViewModerationBridge.kt`
- **Two-Tier Detection Policy (`HaramBlurFilterLogic` & `CandidateBlurBox`):**
  1. **Mandatory Explicit Nudity / Pornography Rule (Always Active for Any Gender):**
     - Hardcore pornography/hentai (`pornScore + hentaiScore` from 5-class `QUANTIZED_NSFW`, or high-confidence explicit nudity `>= 0.72f` from `DEFAULT_NSFW`) is **always** blurred regardless of whether `Who to Blur` is `Female`, `Male`, or `Both`.
  2. **Gender-Specific Face, Figure & Exposed Skin Rule (`Female`, `Male`, `Both`):**
     - Face crops (`128x128` aspect-preserved head regions) are evaluated by `GENDER_FILTER` (`model_lite_gender_q.tflite`) only when skin/face presence is verified, with a clear confidence margin (`>= 0.58f`) so background tiles are never misclassified as a face.
     - Upper-body / torso / skin regions below or adjacent to a detected face inherit that person's verified gender (`FEMALE` or `MALE`).
     - When `Who to Blur = Female`:
       - Blurs detected **Female** faces, **Female** figures/bodies, and **Female** exposed skin (`sexy` / skin exposure score).
       - If a subject's face is classified as `MALE` (and confidence is below the mandatory hardcore porn/explicit nudity ceiling), **neither** his face **nor** his body/exposed skin is blurred.
     - When `Who to Blur = Male`:
       - Blurs detected **Male** faces, **Male** figures/bodies, and **Male** exposed skin, while never blurring non-explicit `FEMALE` faces or skin.
     - When `Who to Blur = Both`:
       - Blurs both `FEMALE` and `MALE` faces, figures, and exposed skin regions.

### Step 3: Careful, Non-Destructive LiteRT Pre-Processing & Tensor Reuse
- **File:** `ContentModerationEngine.kt`
- **Surgical Optimizations (Preserving All Public Signatures & Fallbacks):**
  - Keep all existing public methods, model loading fallbacks, and tensor shape inspections intact.
  - Replace per-crop allocations inside `preprocessBitmap()` with pre-allocated reusable buffers (`Canvas` + `Matrix` into a reusable target `Bitmap`, reusable `IntArray`, reusable direct `ByteBuffer` per model input size `128x128` and `224x224`, and a 256-entry `FLOAT_MINUS1_TO_1_LUT` lookup table) guarded by `inferenceLock`.
  - Remove the redundant second `ALT_FLOAT` inference pass on crops that already scored clearly safe on the primary normalized pass.

### Step 4: Real-Time Regional Tracking Without Self-Occlusion or Scroll Wipeouts
- **Files:** `HaramBlurProjectionService.kt`, `HaramBlurEngineLogic.kt`, `AmnGazeAccessibilityService.kt`
- **Unoccluded Reference Patch Restoration:**
  - Before applying overlays on a newly captured frame, `HaramBlurProjectionService` caches the unblurred pixels under each active bounding box (`lastCleanFrameBitmap`). On the next capture cycle, if an active overlay covers a region, the clean underlying patch is composite-restored into the analysis copy if the surrounding border pixels haven't changed, preventing the overlay from blinding the detector while immediately detecting when the subject moves or leaves.
- **Smooth Coordinate Tracking:**
  - Replace `activeRegions.clear()` on `scrollDeltaY == 0f` with fast scan scheduling (`45ms` cadence during scroll) and smooth bounding-box interpolation (`lerp` `0.65f` new / `0.35f` previous when IoU `>= 0.25f`).

### Step 5: Opt-In Setting to Pre-Blur / Hide Newly Appearing Media Until Scanned
- **Files:** `AmnGazeState.kt`, `AmnGazeViewModel.kt`, `HaramBlurDashboardSection.kt`, `InAppProtectionScreens.kt`, `WebViewModerationBridge.kt`, `MainActivity.kt`
- Add `preBlurNewMediaUntilScanned: Boolean = false` (persisted via `PREFS_KEY_PRE_BLUR_NEW_MEDIA`) to both System-Wide and In-App Settings so users can optionally pre-blur newly appearing `<img>`/`<video>` elements and incoming transitions until the AI scan confirms they are safe.

---

## 3. Verification Plan
1. **Unit & Logic Tests (`AmnGazeLogicTest.kt`):**
   - Verify `HaramBlurFilterLogic.computeOverlayAlpha(1..10)` scales from translucent (`115` at `1/10`) to **100% solid opaque (`255` at `9/10` and `10/10`)**.
   - Verify gender isolation: when `targetGender = FEMALE`, a `MALE` subject with exposed skin (`revealingSkinConfidence = 0.70f`, `explicitPornNudityConfidence = 0.10f`) returns `shouldBlur = false`, while a `FEMALE` subject returns `shouldBlur = true`, and hardcore explicit nudity (`explicitPornNudityConfidence = 0.85f`) returns `shouldBlur = true` for any gender.
   - Verify `AntiFlickerRegionTracker` does not wipe regions on `scrollDeltaY = 0f` and smoothly tracks moving boxes.
2. **Build & Test Execution:**
   - Run `gradle :app:testDebugUnitTest` and `compile_applet` to ensure zero build or runtime regressions.
