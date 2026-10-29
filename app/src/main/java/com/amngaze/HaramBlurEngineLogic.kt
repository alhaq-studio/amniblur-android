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
    val isGridCell: Boolean = false,
    val previewPatch: Bitmap? = null
)

data class TrackedBlurRegion(
    val id: Int,
    val rectF: RectF,
    val gender: DetectedGender,
    val confidence: Float,
    val lastMatchedTimeMs: Long,
    val previewPatch: Bitmap? = null
)

/**
 * Pure filtering, bounding-box padding (+18%), and visual style calculations for Module 2
 * (HaramBlur Real-Time Regional Blurring).
 */
object HaramBlurFilterLogic {
    const val DEFAULT_FACE_PADDING_FRACTION = 0.18f
    const val EXTERIOR_STATIONARY_DIFF_THRESHOLD = 0.04f
    const val DEFAULT_REGION_TTL_MS = 350L
    const val DEFAULT_LERP_FACTOR = 0.65f
    const val MANDATORY_EXPLICIT_NUDITY_FLOOR = 0.42f

    /**
     * Evaluates whether a detected face/figure/skin candidate should be blurred based on:
     * 1. Mandatory Pornography / Explicit Nudity Rule: Always blurs hardcore pornography or
     *    explicit nudity ([CandidateBlurBox.explicitPornNudityConfidence]) for any gender.
     * 2. Body / unanchored region without verified face ([CandidateBlurBox.gender] == `UNKNOWN`):
     *    Blurs if `bodyVisualScore = explicitPornNudityConfidence + revealingSkinConfidence`
     *    exceeds threshold AND neural revealing skin (or explicit porn) is present.
     * 3. Strict Gender-Isolated Detection ([targetGender] = `FEMALE`, `MALE`, `BOTH`):
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

        // 2. Case 2: Grid Cell or Body Region Without a Face (isGridCell or gender == UNKNOWN)
        if (candidate.isGridCell || candidate.gender == DetectedGender.UNKNOWN) {
            val bodyVisualScore = candidate.explicitPornNudityConfidence + candidate.revealingSkinConfidence
            val triggerThreshold = max(0.42f, sensitivity.threshold)
            return bodyVisualScore >= triggerThreshold &&
                (candidate.revealingSkinConfidence >= 0.25f || candidate.explicitPornNudityConfidence >= 0.35f)
        }

        // 3. Case 1: Localized Face Anchor / Verified Subject (!candidate.isGridCell && candidate.gender != UNKNOWN)
        return when (targetGender) {
            HaramBlurTargetGender.FEMALE -> {
                when (candidate.gender) {
                    DetectedGender.MALE -> false // Strictly never blur male face/figure/skin in Female mode
                    DetectedGender.FEMALE -> {
                        val effectiveConf = max(
                            candidate.genderConfidence,
                            max(candidate.revealingSkinConfidence, candidate.nsfwConfidence)
                        )
                        effectiveConf >= max(0.62f, sensitivity.threshold)
                    }
                    DetectedGender.UNKNOWN -> false
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
                        effectiveConf >= max(0.62f, sensitivity.threshold)
                    }
                    DetectedGender.UNKNOWN -> false
                }
            }
            HaramBlurTargetGender.BOTH -> {
                val effectiveConf = max(
                    candidate.genderConfidence,
                    max(candidate.revealingSkinConfidence, candidate.nsfwConfidence)
                )
                effectiveConf >= max(0.58f, sensitivity.threshold)
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
     * Scales Effect Strength (`1..10`) linearly from `12dp` at 1 up to `38dp` at 10.
     */
    fun computePixelationBlockSizeDp(effectStrength: Int): Float {
        val clamped = effectStrength.coerceIn(1, 10)
        // 1 -> 12dp, 10 -> 38dp
        return 12f + ((clamped - 1) / 9f) * 26f
    }

    /**
     * Scales Effect Strength (`1..10`) to a Gaussian blur radius (`6f..42f` px).
     */
    fun computeBlurRadiusPx(effectStrength: Int): Float {
        val clamped = effectStrength.coerceIn(1, 10)
        return 6f + ((clamped - 1) / 9f) * 36f
    }

    /**
     * Maps Effect Strength (`1..10`) to an opacity alpha value (`255`).
     * Returns `255` (100% solid opaque, zero see-through) across all levels so underlying
     * windows never bleed raw pixels through.
     */
    fun computeOverlayAlpha(effectStrength: Int): Int {
        return 255
    }

    /**
     * Returns the user-facing solidness percentage (`100%`) for a given Effect Strength (`1..10`).
     */
    fun computeOverlaySolidnessPercent(effectStrength: Int): Int {
        return 100
    }

    /**
     * Maps Effect Strength (`1..10`) to a normalized density factor (`0.1f..1.0f`) controlling
     * multi-band frosted diffusion layers and mosaic tile packing density.
     */
    fun computeDensityFactor(effectStrength: Int): Float {
        val clamped = effectStrength.coerceIn(1, 10)
        return (clamped / 10f).coerceIn(0.1f, 1.0f)
    }

    /**
     * Returns the base ARGB color integer for the given [HaramBlurOverlayStyle] at 100% opacity.
     */
    fun overlayColorArgb(style: HaramBlurOverlayStyle): Int {
        return when (style) {
            HaramBlurOverlayStyle.BLUR -> 0xFF1E293B.toInt()
            HaramBlurOverlayStyle.PIXELATE -> 0xFF0F172A.toInt()
            HaramBlurOverlayStyle.BLACK -> 0xFF000000.toInt()
            HaramBlurOverlayStyle.GRAY -> 0xFF64748B.toInt()
            HaramBlurOverlayStyle.SKIN -> 0xFFD4A373.toInt()
        }
    }

    /**
     * Returns the strength-adjusted ARGB color integer where alpha is 100% solid opaque (`255`).
     */
    fun overlayColorArgb(style: HaramBlurOverlayStyle, effectStrength: Int): Int {
        val rgb = overlayColorArgb(style) and 0x00FFFFFF
        return (0xFF shl 24) or rgb
    }
}

/**
 * Eliminates the Self-Occlusion Flicker Loop in `MediaProjection` regional blurring:
 * - Samples a sparse `16x16` grid of pixels strictly OUTSIDE all active [TrackedBlurRegion] boxes.
 * - When `exteriorDiff < 0.04f` (the page outside the blur boxes is stationary) AND no scroll/window
 *   event has occurred, active [TrackedBlurRegion] boxes remain LOCKED even if the current frame's
 *   AI pass sees the rendered blur box itself and returns 0 candidates.
 * - Applies a `350ms` minimum persistence hold (`TTL`) and linear coordinate interpolation (`lerp`)
 *   so bounding boxes track smoothly in real time without jitter.
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
     * Shifts or retains tracked regions when Accessibility reports a scroll event.
     *
     * CRITICAL STAGE 3.4 ANTI-WIPEOUT RULE:
     * When `TYPE_VIEW_SCROLLED` reports `scrollDeltaY == 0f` (`abs(normalizedDeltaY) <= 0.001f`),
     * do NOT wipe `activeRegions`! Retain active regions in place and reset `previousExteriorSignature`
     * so a fast verification scan can re-evaluate smoothly without blinking.
     * Only clear all regions when a massive page jump (`abs(normalizedDeltaY) >= 0.45f`) occurs.
     */
    @Synchronized
    fun onScrollDetected(normalizedDeltaY: Float = 0f) {
        if (abs(normalizedDeltaY) <= 0.001f) {
            previousExteriorSignature = null
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
     * Updates tracked blur regions using:
     * 1. Exterior Pixel-Diff Lock (`exteriorDiff < 0.04f` and `!scrollOccurred` -> retain locked boxes).
     * 2. Linear coordinate interpolation (`lerp`) for matched boxes.
     * 3. `350ms` TTL persistence hold when `!scrollOccurred` and `exteriorDiff < 0.12f`.
     */
    @Synchronized
    fun updateRegions(
        candidates: List<CandidateBlurBox>,
        exteriorDiff: Float,
        scrollOccurred: Boolean,
        nowMs: Long,
        targetGender: HaramBlurTargetGender,
        sensitivity: HaramBlurSensitivity
    ): List<TrackedBlurRegion> {
        val matchedCandidates = candidates.filter { candidate ->
            HaramBlurFilterLogic.shouldBlurCandidate(candidate, targetGender, sensitivity)
        }

        // CRITICAL ANTI-FLICKER SAFEGUARD:
        // If we already have active blur boxes on screen, the exterior page pixels outside those
        // boxes have not changed (`exteriorDiff < 0.04f`), and no scroll event occurred,
        // keep existing boxes locked even if `matchedCandidates` is empty (due to self-occlusion)!
        if (activeRegions.isNotEmpty() && !scrollOccurred && exteriorDiff < stationaryExteriorDiffThreshold) {
            for (i in activeRegions.indices) {
                activeRegions[i] = activeRegions[i].copy(lastMatchedTimeMs = nowMs)
            }
            // Also merge in any newly discovered non-overlapping candidates
            for (cand in matchedCandidates) {
                val expanded = if (cand.isGridCell) {
                    cand.normalizedRect
                } else {
                    HaramBlurFilterLogic.expandFaceBoundingBox(cand.normalizedRect)
                }
                val existingIdx = activeRegions.indexOfFirst { iou(it.rectF, expanded) >= 0.25f }
                if (existingIdx >= 0) {
                    val existing = activeRegions[existingIdx]
                    activeRegions[existingIdx] = existing.copy(
                        rectF = lerpRect(existing.rectF, expanded, lerpFactor),
                        confidence = max(existing.confidence, max(cand.genderConfidence, cand.nsfwConfidence)),
                        lastMatchedTimeMs = nowMs,
                        previewPatch = cand.previewPatch ?: existing.previewPatch
                    )
                } else {
                    activeRegions.add(
                        TrackedBlurRegion(
                            id = nextRegionId++,
                            rectF = expanded,
                            gender = cand.gender,
                            confidence = max(cand.genderConfidence, cand.nsfwConfidence),
                            lastMatchedTimeMs = nowMs,
                            previewPatch = cand.previewPatch
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
            val expanded = if (cand.isGridCell) {
                cand.normalizedRect
            } else {
                HaramBlurFilterLogic.expandFaceBoundingBox(cand.normalizedRect)
            }
            val bestExisting = activeRegions
                .filter { it.id !in usedExistingIds }
                .maxByOrNull { iou(it.rectF, expanded) }

            if (bestExisting != null && iou(bestExisting.rectF, expanded) >= 0.25f) {
                usedExistingIds.add(bestExisting.id)
                updatedList.add(
                    bestExisting.copy(
                        rectF = lerpRect(bestExisting.rectF, expanded, lerpFactor),
                        gender = cand.gender,
                        confidence = max(cand.genderConfidence, cand.nsfwConfidence),
                        lastMatchedTimeMs = nowMs,
                        previewPatch = cand.previewPatch ?: bestExisting.previewPatch
                    )
                )
            } else {
                updatedList.add(
                    TrackedBlurRegion(
                        id = nextRegionId++,
                        rectF = expanded,
                        gender = cand.gender,
                        confidence = max(cand.genderConfidence, cand.nsfwConfidence),
                        lastMatchedTimeMs = nowMs,
                        previewPatch = cand.previewPatch
                    )
                )
            }
        }

        // Retain recently seen regions within the 350ms TTL if no scroll occurred and exterior scene did not jump
        if (!scrollOccurred && exteriorDiff < 0.12f) {
            for (existing in activeRegions) {
                if (existing.id !in usedExistingIds && (nowMs - existing.lastMatchedTimeMs) <= ttlMs) {
                    updatedList.add(existing)
                }
            }
        }

        activeRegions.clear()
        activeRegions.addAll(updatedList)
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

    private fun iou(a: RectF, b: RectF): Float {
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
