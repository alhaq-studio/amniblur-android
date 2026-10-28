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
    val nsfwConfidence: Float = 0f
)

data class TrackedBlurRegion(
    val id: Int,
    val rectF: RectF,
    val gender: DetectedGender,
    val confidence: Float,
    val lastMatchedTimeMs: Long
)

/**
 * Pure filtering, bounding-box padding (+18%), and visual style calculations for Module 2
 * (HaramBlur Real-Time Regional Blurring).
 */
object HaramBlurFilterLogic {
    const val DEFAULT_FACE_PADDING_FRACTION = 0.18f
    const val EXTERIOR_STATIONARY_DIFF_THRESHOLD = 0.04f
    const val DEFAULT_REGION_TTL_MS = 350L
    const val DEFAULT_LERP_FACTOR = 0.45f

    /**
     * Evaluates whether a detected face/figure candidate should be blurred based on
     * [targetGender] (`FEMALE`, `MALE`, `BOTH`) and [sensitivity] (`LOW=0.70`, `MEDIUM=0.52`, `HIGH=0.35`).
     */
    fun shouldBlurCandidate(
        candidate: CandidateBlurBox,
        targetGender: HaramBlurTargetGender,
        sensitivity: HaramBlurSensitivity
    ): Boolean {
        val matchesGender = when (targetGender) {
            HaramBlurTargetGender.FEMALE -> candidate.gender == DetectedGender.FEMALE
            HaramBlurTargetGender.MALE -> candidate.gender == DetectedGender.MALE
            HaramBlurTargetGender.BOTH ->
                candidate.gender == DetectedGender.FEMALE || candidate.gender == DetectedGender.MALE
        }
        if (!matchesGender && candidate.nsfwConfidence < sensitivity.threshold) {
            return false
        }
        val effectiveConfidence = if (matchesGender) {
            max(candidate.genderConfidence, candidate.nsfwConfidence)
        } else {
            candidate.nsfwConfidence
        }
        return effectiveConfidence >= sensitivity.threshold
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
     * Returns the exact ARGB color integer for the given [HaramBlurOverlayStyle].
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
}

/**
 * Eliminates the Self-Occlusion Flicker Loop in `MediaProjection` regional blurring:
 * - Samples a sparse `16x16` grid of pixels strictly OUTSIDE all active [TrackedBlurRegion] boxes.
 * - When `exteriorDiff < 0.04f` (the page outside the blur boxes is stationary) AND no scroll/window
 *   event has occurred, active [TrackedBlurRegion] boxes remain LOCKED even if the current frame's
 *   AI pass sees the rendered blur box itself and returns 0 candidates.
 * - Applies a `350ms` minimum persistence hold (`TTL`) and linear coordinate interpolation (`lerp`)
 *   so bounding boxes never jitter between frames.
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
     * Shifts or clears tracked regions immediately when Accessibility reports a scroll delta.
     */
    @Synchronized
    fun onScrollDetected(normalizedDeltaY: Float = 0f) {
        if (abs(normalizedDeltaY) <= 0.001f || abs(normalizedDeltaY) >= 0.45f) {
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
                val expanded = HaramBlurFilterLogic.expandFaceBoundingBox(cand.normalizedRect)
                val existingIdx = activeRegions.indexOfFirst { iou(it.rectF, expanded) >= 0.25f }
                if (existingIdx >= 0) {
                    val existing = activeRegions[existingIdx]
                    activeRegions[existingIdx] = existing.copy(
                        rectF = lerpRect(existing.rectF, expanded, lerpFactor),
                        confidence = max(existing.confidence, max(cand.genderConfidence, cand.nsfwConfidence)),
                        lastMatchedTimeMs = nowMs
                    )
                } else {
                    activeRegions.add(
                        TrackedBlurRegion(
                            id = nextRegionId++,
                            rectF = expanded,
                            gender = cand.gender,
                            confidence = max(cand.genderConfidence, cand.nsfwConfidence),
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
            val expanded = HaramBlurFilterLogic.expandFaceBoundingBox(cand.normalizedRect)
            val bestExisting = activeRegions
                .filter { it.id !in usedExistingIds }
                .maxByOrNull { iou(it.rectF, expanded) }

            if (bestExisting != null && iou(bestExisting.rectF, expanded) >= 0.20f) {
                usedExistingIds.add(bestExisting.id)
                updatedList.add(
                    bestExisting.copy(
                        rectF = lerpRect(bestExisting.rectF, expanded, lerpFactor),
                        gender = cand.gender,
                        confidence = max(cand.genderConfidence, cand.nsfwConfidence),
                        lastMatchedTimeMs = nowMs
                    )
                )
            } else {
                updatedList.add(
                    TrackedBlurRegion(
                        id = nextRegionId++,
                        rectF = expanded,
                        gender = cand.gender,
                        confidence = max(cand.genderConfidence, cand.nsfwConfidence),
                        lastMatchedTimeMs = nowMs
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
