package com.amngaze

import android.graphics.Bitmap
import android.graphics.RectF
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

enum class DetectedGender {
    FEMALE,
    MALE,
    UNKNOWN
}

data class CandidateBlurBox(
    val normalizedRect: RectF,
    val gender: DetectedGender,
    val genderConfidence: Float,
    val nsfwConfidence: Float = 0f,
    val explicitPornNudityConfidence: Float = 0f,
    val revealingSkinConfidence: Float = nsfwConfidence,
    val isGridCell: Boolean = false
)

data class TrackedBlurRegion(
    val id: Int,
    val rectF: RectF,
    val gender: DetectedGender,
    val confidence: Float,
    val lastMatchedTimeMs: Long
)

/**
 * Pure filtering, bounding-box padding (+18% for faces, +2% for grid cells), spatial NMS merging,
 * and visual style calculations for Module 2 (HaramBlur Real-Time Regional Blurring).
 */
object HaramBlurFilterLogic {
    const val DEFAULT_FACE_PADDING_FRACTION = 0.18f
    const val DEFAULT_GRID_CELL_PADDING_FRACTION = 0.015f
    const val EXTERIOR_STATIONARY_DIFF_THRESHOLD = 0.04f
    const val DEFAULT_REGION_TTL_MS = 350L
    const val DEFAULT_LERP_FACTOR = 0.65f
    const val MANDATORY_EXPLICIT_NUDITY_FLOOR = 0.55f
    const val MAX_CONCURRENT_REGIONS = 16
    const val GRID_IOU_MERGE_THRESHOLD = 0.55f

    /**
     * Evaluates whether a detected face/figure/skin candidate should be blurred based on:
     * 1. Mandatory Pornography / Explicit Nudity Rule: Always blurs hardcore pornography or
     *    explicit nudity ([CandidateBlurBox.explicitPornNudityConfidence]) for any gender.
     * 2. Strict Gender-Isolated Detection ([targetGender] = `FEMALE`, `MALE`, `BOTH`):
     *    - When `FEMALE` is selected, NEVER blurs verified `MALE` faces, figures, or exposed skin.
     *    - When `MALE` is selected, NEVER blurs verified `FEMALE` faces, figures, or exposed skin.
     *    - When `BOTH` is selected, blurs faces, figures, and exposed skin for both genders.
     */
    fun shouldBlurCandidate(
        candidate: CandidateBlurBox,
        targetGender: HaramBlurTargetGender,
        sensitivity: HaramBlurSensitivity
    ): Boolean {
        // 1. Mandatory blurring of hardcore pornography / explicit nudity for either or both genders
        val mandatoryExplicitThreshold = max(MANDATORY_EXPLICIT_NUDITY_FLOOR, sensitivity.threshold)
        if (candidate.explicitPornNudityConfidence >= mandatoryExplicitThreshold) {
            return true
        }

        // 2. Strict gender isolation for face, figure, and exposed skin
        return when (targetGender) {
            HaramBlurTargetGender.FEMALE -> {
                when (candidate.gender) {
                    DetectedGender.MALE -> false // Strictly never blur male face/figure/skin in Female mode
                    DetectedGender.FEMALE -> {
                        val effectiveConf = max(
                            candidate.genderConfidence,
                            max(candidate.revealingSkinConfidence, candidate.nsfwConfidence)
                        )
                        effectiveConf >= sensitivity.threshold
                    }
                    DetectedGender.UNKNOWN -> {
                        candidate.nsfwConfidence >= max(0.68f, sensitivity.threshold + 0.12f)
                    }
                }
            }
            HaramBlurTargetGender.MALE -> {
                when (candidate.gender) {
                    DetectedGender.FEMALE -> false // Strictly never blur female face/figure/skin in Male mode
                    DetectedGender.MALE -> {
                        val effectiveConf = max(
                            candidate.genderConfidence,
                            max(candidate.revealingSkinConfidence, candidate.nsfwConfidence)
                        )
                        effectiveConf >= sensitivity.threshold
                    }
                    DetectedGender.UNKNOWN -> {
                        candidate.nsfwConfidence >= max(0.68f, sensitivity.threshold + 0.12f)
                    }
                }
            }
            HaramBlurTargetGender.BOTH -> {
                val effectiveConf = max(
                    candidate.genderConfidence,
                    max(candidate.revealingSkinConfidence, candidate.nsfwConfidence)
                )
                effectiveConf >= sensitivity.threshold
            }
        }
    }

    /**
     * Expands a matched face/figure bounding box by [paddingFraction] (default +18% width/height)
     * clamped to normalized `[0f..1f]` coordinates so the hair/head silhouette is comfortably covered.
     */
    fun expandFaceBoundingBox(
        normalizedRect: RectF,
        paddingFraction: Float = DEFAULT_FACE_PADDING_FRACTION
    ): RectF {
        val w = (normalizedRect.right - normalizedRect.left).coerceAtLeast(0.01f)
        val h = (normalizedRect.bottom - normalizedRect.top).coerceAtLeast(0.01f)
        val padX = w * paddingFraction
        val padY = h * paddingFraction
        return RectF(
            (normalizedRect.left - padX).coerceIn(0f, 1f),
            (normalizedRect.top - padY).coerceIn(0f, 1f),
            (normalizedRect.right + padX).coerceIn(0f, 1f),
            (normalizedRect.bottom + padY).coerceIn(0f, 1f)
        )
    }

    /**
     * Expands a [CandidateBlurBox] using `+18%` padding for localized faces/figures and a tight
     * `+1.5%` padding for spatial grid tiles so adjacent safe grid cells are never occluded.
     */
    fun expandCandidateBoundingBox(candidate: CandidateBlurBox): RectF {
        val pad = if (candidate.isGridCell) {
            DEFAULT_GRID_CELL_PADDING_FRACTION
        } else {
            DEFAULT_FACE_PADDING_FRACTION
        }
        return expandFaceBoundingBox(candidate.normalizedRect, pad)
    }

    /**
     * Computes Intersection-over-Union (`0f..1f`) between two normalized or pixel [RectF] boxes.
     */
    fun computeIoU(a: RectF, b: RectF): Float {
        val interLeft = max(a.left, b.left)
        val interTop = max(a.top, b.top)
        val interRight = min(a.right, b.right)
        val interBottom = min(a.bottom, b.bottom)
        val interW = (interRight - interLeft).coerceAtLeast(0f)
        val interH = (interBottom - interTop).coerceAtLeast(0f)
        val interArea = interW * interH
        if (interArea <= 0f) return 0f
        val areaA = (a.right - a.left).coerceAtLeast(0f) * (a.bottom - a.top).coerceAtLeast(0f)
        val areaB = (b.right - b.left).coerceAtLeast(0f) * (b.bottom - b.top).coerceAtLeast(0f)
        val union = areaA + areaB - interArea
        return if (union > 0f) interArea / union else 0f
    }

    /**
     * Smart Non-Maximum Suppression (NMS) & Box Merger for Multi-Region / Grid Detection:
     * - Merges two violating candidate boxes ONLY when they overlap heavily (`IoU >= iouMergeThreshold`,
     *   default `0.55f`), keeping separate grid tiles (`2x2`, `2x3`, `3x3`) independent so safe tiles
     *   between them remain visible.
     * - Supports up to [maxRegions] (`16`) simultaneous independent blur regions.
     */
    fun mergeAndCapCandidates(
        candidates: List<CandidateBlurBox>,
        maxRegions: Int = MAX_CONCURRENT_REGIONS,
        iouMergeThreshold: Float = GRID_IOU_MERGE_THRESHOLD
    ): List<CandidateBlurBox> {
        if (candidates.isEmpty()) return emptyList()
        val sorted = candidates.sortedByDescending {
            max(
                it.genderConfidence,
                max(it.nsfwConfidence, max(it.explicitPornNudityConfidence, it.revealingSkinConfidence))
            )
        }
        val merged = ArrayList<CandidateBlurBox>(min(sorted.size, maxRegions))
        for (cand in sorted) {
            var mergedIntoExisting = false
            for (i in merged.indices) {
                val existing = merged[i]
                val overlap = computeIoU(existing.normalizedRect, cand.normalizedRect)
                if (overlap >= iouMergeThreshold) {
                    val unionRect = RectF(
                        min(existing.normalizedRect.left, cand.normalizedRect.left),
                        min(existing.normalizedRect.top, cand.normalizedRect.top),
                        max(existing.normalizedRect.right, cand.normalizedRect.right),
                        max(existing.normalizedRect.bottom, cand.normalizedRect.bottom)
                    )
                    val resolvedGender = if (existing.gender != DetectedGender.UNKNOWN) {
                        existing.gender
                    } else {
                        cand.gender
                    }
                    merged[i] = existing.copy(
                        normalizedRect = unionRect,
                        gender = resolvedGender,
                        genderConfidence = max(existing.genderConfidence, cand.genderConfidence),
                        nsfwConfidence = max(existing.nsfwConfidence, cand.nsfwConfidence),
                        explicitPornNudityConfidence = max(
                            existing.explicitPornNudityConfidence,
                            cand.explicitPornNudityConfidence
                        ),
                        revealingSkinConfidence = max(
                            existing.revealingSkinConfidence,
                            cand.revealingSkinConfidence
                        ),
                        isGridCell = existing.isGridCell && cand.isGridCell
                    )
                    mergedIntoExisting = true
                    break
                }
            }
            if (!mergedIntoExisting && merged.size < maxRegions) {
                merged.add(cand)
            }
        }
        return merged
    }

    /**
     * Scales Effect Strength (`1..10`) linearly from `8dp` at 1 up to `36dp` at 10.
     */
    fun computePixelationBlockSizeDp(effectStrength: Int): Float {
        val clamped = effectStrength.coerceIn(1, 10)
        // 1 -> 8dp, 10 -> 36dp
        return 8f + ((clamped - 1) / 9f) * 28f
    }

    /**
     * Scales Effect Strength (`1..10`) to a Gaussian blur radius (`6f..42f` px).
     */
    fun computeBlurRadiusPx(effectStrength: Int): Float {
        val clamped = effectStrength.coerceIn(1, 10)
        return 6f + ((clamped - 1) / 9f) * 36f
    }

    /**
     * Maps Effect Strength (`1..10`) to an opacity alpha value (`115..255`) controlling
     * transparency vs. solidness. At `9/10` and `10/10`, returns `255` (100% solid opaque,
     * zero see-through).
     */
    fun computeOverlayAlpha(effectStrength: Int): Int {
        return when (effectStrength.coerceIn(1, 10)) {
            1 -> 115
            2 -> 136
            3 -> 156
            4 -> 176
            5 -> 196
            6 -> 214
            7 -> 230
            8 -> 244
            9 -> 255
            else -> 255
        }
    }

    /**
     * Returns the user-facing solidness percentage (`45%..100%`) for a given Effect Strength (`1..10`).
     */
    fun computeOverlaySolidnessPercent(effectStrength: Int): Int {
        val alpha = computeOverlayAlpha(effectStrength)
        return ((alpha * 100 + 127) / 255).coerceIn(45, 100)
    }

    /**
     * Maps Effect Strength (`1..10`) to a normalized density factor (`0.0f..1.0f`) controlling
     * multi-band frosted diffusion layers and mosaic tile packing density.
     */
    fun computeDensityFactor(effectStrength: Int): Float {
        val clamped = effectStrength.coerceIn(1, 10)
        return ((clamped - 1) / 9f).coerceIn(0f, 1f)
    }

    /**
     * Returns the base ARGB color integer for the given [HaramBlurOverlayStyle].
     */
    fun overlayColorArgb(style: HaramBlurOverlayStyle): Int {
        return when (style) {
            HaramBlurOverlayStyle.BLUR -> 0xD91E293B.toInt()
            HaramBlurOverlayStyle.PIXELATE -> 0xE60F172A.toInt()
            HaramBlurOverlayStyle.BLACK -> 0xFF000000.toInt()
            HaramBlurOverlayStyle.GRAY -> 0xFF8E8E93.toInt()
            HaramBlurOverlayStyle.SKIN -> 0xFFE8BEAC.toInt()
        }
    }

    /**
     * Returns the strength-adjusted ARGB color integer where alpha is scaled by [effectStrength] (`1..10`),
     * reaching `0xFF` (`255` — 100% solid opaque) at strengths `9` and `10`.
     */
    fun overlayColorArgb(style: HaramBlurOverlayStyle, effectStrength: Int): Int {
        val alpha = computeOverlayAlpha(effectStrength)
        val rgb = overlayColorArgb(style) and 0x00FFFFFF
        return (alpha shl 24) or rgb
    }
}

/**
 * Eliminates the Self-Occlusion Flicker Loop in `MediaProjection` regional blurring:
 * - Supports up to [HaramBlurFilterLogic.MAX_CONCURRENT_REGIONS] (`16`) independent simultaneous
 *   [TrackedBlurRegion] boxes across multi-image/video grids.
 * - Monitors both the sparse `16x16` exterior pixel grid AND each region's own 4-pixel exterior
 *   border perimeter ([computeRegionPerimeterDiff]) so stationary blurred regions stay locked
 *   even while a video plays in an adjacent grid cell.
 * - Never wipes active regions when `onScrollDetected(0f)` (`abs(normalizedDeltaY) <= 0.001f`) is called.
 * - Applies a `350ms` minimum persistence hold (`TTL`) and linear coordinate interpolation
 *   (`0.65f` new / `0.35f` previous when `IoU >= 0.25f`).
 */
class AntiFlickerRegionTracker(
    val stationaryExteriorDiffThreshold: Float = HaramBlurFilterLogic.EXTERIOR_STATIONARY_DIFF_THRESHOLD,
    val ttlMs: Long = HaramBlurFilterLogic.DEFAULT_REGION_TTL_MS,
    val lerpFactor: Float = HaramBlurFilterLogic.DEFAULT_LERP_FACTOR
) {
    private var nextRegionId = 1
    private val activeRegions = mutableListOf<TrackedBlurRegion>()
    private var previousExteriorSignature: FloatArray? = null

    @Synchronized
    fun getActiveRegions(): List<TrackedBlurRegion> = activeRegions.toList()

    @Synchronized
    fun clearAll() {
        activeRegions.clear()
        previousExteriorSignature = null
    }

    /**
     * Shifts or clears tracked regions when Accessibility reports a real vertical scroll delta:
     * - When `abs(normalizedDeltaY) <= 0.001f` (zero-delta event during layout/video playback):
     *   retains `activeRegions` untouched so stationary overlays never blink off.
     * - When `abs(normalizedDeltaY) >= 0.45f` (massive page jump): clears `activeRegions`.
     * - When `0.001f < abs(normalizedDeltaY) < 0.45f`: shifts `activeRegions` by `normalizedDeltaY`.
     */
    @Synchronized
    fun onScrollDetected(normalizedDeltaY: Float = 0f) {
        if (abs(normalizedDeltaY) <= 0.001f) {
            return
        }
        if (abs(normalizedDeltaY) >= 0.45f) {
            activeRegions.clear()
            previousExteriorSignature = null
            return
        }
        val shifted = mutableListOf<TrackedBlurRegion>()
        for (region in activeRegions) {
            val newTop = region.rectF.top + normalizedDeltaY
            val newBottom = region.rectF.bottom + normalizedDeltaY
            if (newBottom > 0.05f && newTop < 0.95f) {
                shifted.add(
                    region.copy(
                        rectF = RectF(
                            region.rectF.left,
                            newTop.coerceIn(0f, 1f),
                            region.rectF.right,
                            newBottom.coerceIn(0f, 1f)
                        )
                    )
                )
            }
        }
        activeRegions.clear()
        activeRegions.addAll(shifted)
        previousExteriorSignature = null
    }

    /**
     * Computes the mean normalized luma diff (`0f..1f`) along the [borderPx]-pixel exterior
     * perimeter ring immediately outside [normRect] between [prevBitmap] and [currBitmap].
     *
     * Used for per-region independent anti-flicker: if a region's exterior 4px border perimeter
     * has not changed (`diff < 0.04f`), that specific region remains locked and its unoccluded
     * clean patch is restored, even while another grid cell on the same screen is playing a video.
     */
    fun computeRegionPerimeterDiff(
        prevBitmap: Bitmap?,
        currBitmap: Bitmap,
        normRect: RectF,
        borderPx: Int = 4
    ): Float {
        if (prevBitmap == null || prevBitmap.isRecycled || currBitmap.isRecycled) return 1.0f
        val w = currBitmap.width
        val h = currBitmap.height
        if (w <= 8 || h <= 8 || prevBitmap.width != w || prevBitmap.height != h) return 1.0f

        val leftPx = (normRect.left * w).toInt().coerceIn(0, w - 1)
        val topPx = (normRect.top * h).toInt().coerceIn(0, h - 1)
        val rightPx = (normRect.right * w).toInt().coerceIn(leftPx + 1, w)
        val bottomPx = (normRect.bottom * h).toInt().coerceIn(topPx + 1, h)

        val outerLeft = (leftPx - borderPx).coerceAtLeast(0)
        val outerTop = (topPx - borderPx).coerceAtLeast(0)
        val outerRight = (rightPx + borderPx).coerceAtMost(w - 1)
        val outerBottom = (bottomPx + borderPx).coerceAtMost(h - 1)

        var sumDiff = 0f
        var sampleCount = 0
        val stepX = ((outerRight - outerLeft) / 12).coerceAtLeast(1)
        val stepY = ((outerBottom - outerTop) / 12).coerceAtLeast(1)

        // Top and bottom exterior border strips
        var x = outerLeft
        while (x <= outerRight) {
            if (outerTop < topPx) {
                sumDiff += pixelLumaDiff(prevBitmap.getPixel(x, outerTop), currBitmap.getPixel(x, outerTop))
                sampleCount++
            }
            if (outerBottom >= bottomPx) {
                sumDiff += pixelLumaDiff(prevBitmap.getPixel(x, outerBottom), currBitmap.getPixel(x, outerBottom))
                sampleCount++
            }
            x += stepX
        }

        // Left and right exterior border strips
        var y = topPx
        while (y < bottomPx) {
            if (outerLeft < leftPx) {
                sumDiff += pixelLumaDiff(prevBitmap.getPixel(outerLeft, y), currBitmap.getPixel(outerLeft, y))
                sampleCount++
            }
            if (outerRight >= rightPx) {
                sumDiff += pixelLumaDiff(prevBitmap.getPixel(outerRight, y), currBitmap.getPixel(outerRight, y))
                sampleCount++
            }
            y += stepY
        }

        return if (sampleCount > 0) sumDiff / sampleCount.toFloat() else 0.0f
    }

    private fun pixelLumaDiff(pixelA: Int, pixelB: Int): Float {
        val rA = (pixelA shr 16) and 0xFF
        val gA = (pixelA shr 8) and 0xFF
        val bA = pixelA and 0xFF
        val lumaA = (0.299f * rA + 0.587f * gA + 0.114f * bA) / 255.0f

        val rB = (pixelB shr 16) and 0xFF
        val gB = (pixelB shr 8) and 0xFF
        val bB = pixelB and 0xFF
        val lumaB = (0.299f * rB + 0.587f * gB + 0.114f * bB) / 255.0f

        return abs(lumaA - lumaB)
    }

    /**
     * Computes a sparse `16x16` normalized luma signature (`0f..1f`) for pixels strictly OUTSIDE
     * all [activeNormalizedRects]. Points falling inside any active blur box are marked `-1f`
     * so self-occlusion from rendered blur boxes never pollutes the exterior diff.
     */
    fun computeExteriorLumaSignature(
        bitmap: Bitmap,
        activeNormalizedRects: List<RectF>
    ): FloatArray {
        val gridSize = 16
        val signature = FloatArray(gridSize * gridSize) { -1f }
        val w = bitmap.width
        val h = bitmap.height
        if (w <= 0 || h <= 0) return signature

        var idx = 0
        for (gy in 0 until gridSize) {
            val ny = (gy + 0.5f) / gridSize.toFloat()
            val py = (ny * (h - 1)).toInt().coerceIn(0, h - 1)
            for (gx in 0 until gridSize) {
                val nx = (gx + 0.5f) / gridSize.toFloat()
                val isInsideBlurBox = activeNormalizedRects.any { rect ->
                    nx >= rect.left && nx <= rect.right && ny >= rect.top && ny <= rect.bottom
                }
                if (!isInsideBlurBox) {
                    val px = (nx * (w - 1)).toInt().coerceIn(0, w - 1)
                    val pixel = bitmap.getPixel(px, py)
                    val r = (pixel shr 16) and 0xFF
                    val g = (pixel shr 8) and 0xFF
                    val b = pixel and 0xFF
                    // Rec. 601 luma normalized to 0f..1f
                    signature[idx] = (0.299f * r + 0.587f * g + 0.114f * b) / 255.0f
                }
                idx++
            }
        }
        return signature
    }

    /**
     * Computes mean absolute luma difference (`0f..1f`) across exterior sample points valid in both frames.
     */
    fun computeExteriorLumaDiff(sigA: FloatArray?, sigB: FloatArray?): Float {
        if (sigA == null || sigB == null || sigA.size != sigB.size) return 1.0f
        var sumDiff = 0f
        var validCount = 0
        for (i in sigA.indices) {
            val a = sigA[i]
            val b = sigB[i]
            if (a >= 0f && b >= 0f) {
                sumDiff += abs(a - b)
                validCount++
            }
        }
        return if (validCount > 0) sumDiff / validCount.toFloat() else 0.0f
    }

    /**
     * Updates tracked blur regions (up to [HaramBlurFilterLogic.MAX_CONCURRENT_REGIONS] = 16) using:
     * 1. Global Exterior Pixel-Diff Lock (`exteriorDiff < 0.04f` and `!scrollOccurred`) AND
     *    Per-Region Perimeter Lock ([lockedRegionIds] whose 4px exterior border diff is `< 0.04f`).
     * 2. Smart NMS merging (`IoU >= 0.55f`) so adjacent grid cells remain independent boxes.
     * 3. Smooth linear coordinate interpolation (`lerpFactor = 0.65f` when `IoU >= 0.25f`).
     * 4. `350ms` TTL persistence hold when `!scrollOccurred` and `exteriorDiff < 0.12f`.
     */
    @Synchronized
    fun updateRegions(
        candidates: List<CandidateBlurBox>,
        exteriorDiff: Float,
        scrollOccurred: Boolean,
        nowMs: Long,
        targetGender: HaramBlurTargetGender,
        sensitivity: HaramBlurSensitivity,
        lockedRegionIds: Set<Int> = emptySet()
    ): List<TrackedBlurRegion> {
        val policyMatched = candidates.filter { candidate ->
            HaramBlurFilterLogic.shouldBlurCandidate(candidate, targetGender, sensitivity)
        }
        val matchedCandidates = HaramBlurFilterLogic.mergeAndCapCandidates(
            candidates = policyMatched,
            maxRegions = HaramBlurFilterLogic.MAX_CONCURRENT_REGIONS,
            iouMergeThreshold = HaramBlurFilterLogic.GRID_IOU_MERGE_THRESHOLD
        )

        // CRITICAL ANTI-FLICKER SAFEGUARD:
        // If we already have active blur boxes on screen, the exterior page pixels outside those
        // boxes have not changed (`exteriorDiff < 0.04f`), and no scroll event occurred,
        // keep existing boxes locked even if `matchedCandidates` is empty (due to self-occlusion),
        // while still merging/adding any newly discovered candidates in other grid cells!
        if (activeRegions.isNotEmpty() && !scrollOccurred && exteriorDiff < stationaryExteriorDiffThreshold) {
            for (i in activeRegions.indices) {
                activeRegions[i] = activeRegions[i].copy(lastMatchedTimeMs = nowMs)
            }
            for (cand in matchedCandidates) {
                val expanded = HaramBlurFilterLogic.expandCandidateBoundingBox(cand)
                val existingIdx = activeRegions.indexOfFirst { iou(it.rectF, expanded) >= 0.25f }
                val candConf = max(
                    cand.genderConfidence,
                    max(cand.nsfwConfidence, max(cand.explicitPornNudityConfidence, cand.revealingSkinConfidence))
                )
                if (existingIdx >= 0) {
                    val existing = activeRegions[existingIdx]
                    activeRegions[existingIdx] = existing.copy(
                        rectF = lerpRect(existing.rectF, expanded, lerpFactor),
                        confidence = max(existing.confidence, candConf),
                        lastMatchedTimeMs = nowMs
                    )
                } else if (activeRegions.size < HaramBlurFilterLogic.MAX_CONCURRENT_REGIONS) {
                    activeRegions.add(
                        TrackedBlurRegion(
                            id = nextRegionId++,
                            rectF = expanded,
                            gender = cand.gender,
                            confidence = candConf,
                            lastMatchedTimeMs = nowMs
                        )
                    )
                }
            }
            return activeRegions.toList()
        }

        if (scrollOccurred && matchedCandidates.isEmpty()) {
            activeRegions.clear()
            return emptyList()
        }

        val updatedList = mutableListOf<TrackedBlurRegion>()
        val usedExistingIds = HashSet<Int>()

        for (cand in matchedCandidates) {
            if (updatedList.size >= HaramBlurFilterLogic.MAX_CONCURRENT_REGIONS) break
            val expanded = HaramBlurFilterLogic.expandCandidateBoundingBox(cand)
            val candConf = max(
                cand.genderConfidence,
                max(cand.nsfwConfidence, max(cand.explicitPornNudityConfidence, cand.revealingSkinConfidence))
            )
            val bestExisting = activeRegions
                .filter { it.id !in usedExistingIds }
                .maxByOrNull { iou(it.rectF, expanded) }

            if (bestExisting != null && iou(bestExisting.rectF, expanded) >= 0.25f) {
                usedExistingIds.add(bestExisting.id)
                updatedList.add(
                    bestExisting.copy(
                        rectF = lerpRect(bestExisting.rectF, expanded, lerpFactor),
                        gender = cand.gender,
                        confidence = candConf,
                        lastMatchedTimeMs = nowMs
                    )
                )
            } else {
                updatedList.add(
                    TrackedBlurRegion(
                        id = nextRegionId++,
                        rectF = expanded,
                        gender = cand.gender,
                        confidence = candConf,
                        lastMatchedTimeMs = nowMs
                    )
                )
            }
        }

        // Retain per-region locked boxes (whose 4px exterior perimeter did not change) and
        // recently seen regions within the 350ms TTL when no scroll occurred
        if (!scrollOccurred) {
            for (existing in activeRegions) {
                if (updatedList.size >= HaramBlurFilterLogic.MAX_CONCURRENT_REGIONS) break
                if (existing.id in usedExistingIds) continue
                val isPerRegionLocked = existing.id in lockedRegionIds
                val isWithinTtl = exteriorDiff < 0.12f && (nowMs - existing.lastMatchedTimeMs) <= ttlMs
                if (isPerRegionLocked || isWithinTtl) {
                    val refreshed = if (isPerRegionLocked) {
                        existing.copy(lastMatchedTimeMs = nowMs)
                    } else {
                        existing
                    }
                    updatedList.add(refreshed)
                    usedExistingIds.add(existing.id)
                }
            }
        }

        activeRegions.clear()
        activeRegions.addAll(updatedList.take(HaramBlurFilterLogic.MAX_CONCURRENT_REGIONS))
        return activeRegions.toList()
    }

    private fun lerpRect(current: RectF, target: RectF, alpha: Float): RectF {
        val a = alpha.coerceIn(0.1f, 1.0f)
        return RectF(
            current.left + (target.left - current.left) * a,
            current.top + (target.top - current.top) * a,
            current.right + (target.right - current.right) * a,
            current.bottom + (target.bottom - current.bottom) * a
        )
    }

    private fun iou(a: RectF, b: RectF): Float = HaramBlurFilterLogic.computeIoU(a, b)
}

/**
 * Evaluates whether opening a foreground app should trigger the HaramBlur Auto-Run reminder banner.
 * Strictly excludes Home Launchers (`com.alhaq.amniquist`, etc.) and system packages.
 */
class AutoRunReminderController(
    val reminderCooldownMs: Long = 60_000L
) {
    private val lastReminderTimeByPackage = HashMap<String, Long>()

    @Synchronized
    fun shouldEmitAutoRunReminder(
        foregroundPackage: String,
        autoRunPackages: Set<String>,
        isHaramBlurEnabled: Boolean,
        nowMs: Long,
        selfPackageName: String = "com.amngaze",
        detectedHomePackages: Set<String> = emptySet()
    ): Boolean {
        if (isHaramBlurEnabled) return false
        if (foregroundPackage.isBlank()) return false
        if (FastPathUrlAndKeywordScanner.isProtectedLauncherOrSystemPackage(
                pkg = foregroundPackage,
                selfPackageName = selfPackageName,
                detectedHomePackages = detectedHomePackages
            )
        ) {
            return false
        }
        if (foregroundPackage !in autoRunPackages) return false

        val lastTime = lastReminderTimeByPackage[foregroundPackage]
        if (lastTime != null && (nowMs - lastTime) < reminderCooldownMs) {
            return false
        }
        lastReminderTimeByPackage[foregroundPackage] = nowMs
        return true
    }

    @Synchronized
    fun resetCooldowns() {
        lastReminderTimeByPackage.clear()
    }
}

/**
 * Shared singleton runtime bus synchronizing `AmnGazeAccessibilityService`,
 * `HaramBlurProjectionService`, and the Compose UI.
 */
object HaramBlurRuntimeBus {
    private val _isProjectionRunning = MutableStateFlow(false)
    val isProjectionRunning: StateFlow<Boolean> = _isProjectionRunning.asStateFlow()

    private val _activeRegions = MutableStateFlow<List<TrackedBlurRegion>>(emptyList())
    val activeRegions: StateFlow<List<TrackedBlurRegion>> = _activeRegions.asStateFlow()

    private val _autoRunReminderPackage = MutableStateFlow<String?>(null)
    val autoRunReminderPackage: StateFlow<String?> = _autoRunReminderPackage.asStateFlow()

    private val _activeForegroundPackage = MutableStateFlow("")
    val activeForegroundPackage: StateFlow<String> = _activeForegroundPackage.asStateFlow()

    private val pendingScrollFlag = AtomicBoolean(false)

    @Volatile
    var lastScrollDeltaY: Float = 0f
        private set

    fun setProjectionRunning(running: Boolean) {
        _isProjectionRunning.value = running
        if (!running) {
            _activeRegions.value = emptyList()
        }
    }

    fun updateActiveRegions(regions: List<TrackedBlurRegion>) {
        _activeRegions.value = regions
    }

    fun onScrollDetected(deltaY: Float = 0f) {
        if (abs(deltaY) <= 0.001f) {
            return
        }
        lastScrollDeltaY = deltaY
        pendingScrollFlag.set(true)
    }

    fun consumeScrollFlag(): Boolean {
        return pendingScrollFlag.getAndSet(false)
    }

    fun onForegroundPackageChanged(
        packageName: String,
        isIgnoredLauncherOrSystem: Boolean
    ) {
        _activeForegroundPackage.value = packageName
        if (isIgnoredLauncherOrSystem) {
            _activeRegions.value = emptyList()
        }
    }

    fun postAutoRunReminder(packageName: String) {
        _autoRunReminderPackage.value = packageName
    }

    fun dismissAutoRunReminder() {
        _autoRunReminderPackage.value = null
    }
}
