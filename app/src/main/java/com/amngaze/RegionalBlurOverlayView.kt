package com.amngaze

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RectF
import android.os.Build
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import kotlin.math.max

/**
 * Non-touch-blocking (`FLAG_NOT_FOCUSABLE or FLAG_NOT_TOUCHABLE or FLAG_LAYOUT_IN_SCREEN`)
 * custom overlay view that renders HaramBlur regional bounding boxes (`Blur`, `Pixelate`,
 * `Black`, `Gray`, `Skin`) scaled by `Effect Strength (1..10)`.
 */
class RegionalBlurOverlayView(context: Context) : View(context) {
    private val activeRegions = mutableListOf<TrackedBlurRegion>()
    private var overlayStyle: HaramBlurOverlayStyle = HaramBlurOverlayStyle.BLUR
    private var effectStrength: Int = 10

    private val boxFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val mosaicBlockPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2f * resources.displayMetrics.density
        color = Color.argb(255, 71, 85, 105)
    }
    private val screenRect = RectF()

    private val frostLayerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val clipPath = android.graphics.Path()

    private val filterBitmapPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)

    fun updateBoxes(
        newBoxes: List<TrackedBlurRegion>,
        style: HaramBlurOverlayStyle,
        strength: Int
    ) {
        for (old in activeRegions) {
            val oldPatch = old.previewPatch
            if (oldPatch != null && !oldPatch.isRecycled && newBoxes.none { it.previewPatch === oldPatch }) {
                oldPatch.recycle()
            }
        }
        activeRegions.clear()
        activeRegions.addAll(newBoxes)
        overlayStyle = style
        effectStrength = strength.coerceIn(1, 10)
        visibility = if (activeRegions.isEmpty()) GONE else VISIBLE
        invalidate()
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        for (reg in activeRegions) {
            val patch = reg.previewPatch
            if (patch != null && !patch.isRecycled) {
                patch.recycle()
            }
        }
        activeRegions.clear()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (activeRegions.isEmpty()) return

        val viewW = width.toFloat()
        val viewH = height.toFloat()
        if (viewW <= 0f || viewH <= 0f) return

        val density = resources.displayMetrics.density
        val cornerRadius = 14f * density

        for (region in activeRegions) {
            screenRect.set(
                (region.rectF.left * viewW).coerceIn(0f, viewW),
                (region.rectF.top * viewH).coerceIn(0f, viewH),
                (region.rectF.right * viewW).coerceIn(0f, viewW),
                (region.rectF.bottom * viewH).coerceIn(0f, viewH)
            )
            if (screenRect.width() < 8f || screenRect.height() < 8f) continue

            when (overlayStyle) {
                HaramBlurOverlayStyle.BLACK,
                HaramBlurOverlayStyle.GRAY,
                HaramBlurOverlayStyle.SKIN -> {
                    // 100% solid opaque (alpha = 255) rounded rect (#FF000000, #FF64748B, #FFD4A373)
                    boxFillPaint.color = HaramBlurFilterLogic.overlayColorArgb(overlayStyle, effectStrength)
                    canvas.drawRoundRect(screenRect, cornerRadius, cornerRadius, boxFillPaint)
                    canvas.drawRoundRect(screenRect, cornerRadius, cornerRadius, borderPaint)
                }

                HaramBlurOverlayStyle.PIXELATE -> {
                    // 1. Solid backing fill 100% opaque alpha = 255 (#FF0F172A)
                    boxFillPaint.color = 0xFF0F172A.toInt()
                    canvas.drawRoundRect(screenRect, cornerRadius, cornerRadius, boxFillPaint)

                    // 2. Mosaic grid whose tile size scales with Effect Strength (1..10) (12dp at 1 up to 38dp at 10)
                    //    Zero gaps (gap = 0f), drawn at alpha = 255
                    val blockSizeDp = HaramBlurFilterLogic.computePixelationBlockSizeDp(effectStrength)
                    val blockSizePx = max(10f, blockSizeDp * density)

                    val saveCount = canvas.save()
                    clipPath.reset()
                    clipPath.addRoundRect(screenRect, cornerRadius, cornerRadius, android.graphics.Path.Direction.CW)
                    canvas.clipPath(clipPath)

                    val patch = region.previewPatch
                    val hasValidPatch = patch != null && !patch.isRecycled
                    val patchW = if (hasValidPatch) patch!!.width else 1
                    val patchH = if (hasValidPatch) patch!!.height else 1
                    val rectW = screenRect.width().coerceAtLeast(1f)
                    val rectH = screenRect.height().coerceAtLeast(1f)

                    var row = 0
                    var y = screenRect.top
                    while (y < screenRect.bottom) {
                        var col = 0
                        var x = screenRect.left
                        val nextY = (y + blockSizePx).coerceAtMost(screenRect.bottom)
                        while (x < screenRect.right) {
                            val nextX = (x + blockSizePx).coerceAtMost(screenRect.right)
                            if (hasValidPatch) {
                                val u = ((x + nextX) * 0.5f - screenRect.left) / rectW
                                val v = ((y + nextY) * 0.5f - screenRect.top) / rectH
                                val px = (u * patchW).toInt().coerceIn(0, patchW - 1)
                                val py = (v * patchH).toInt().coerceIn(0, patchH - 1)
                                val sampleColor = try {
                                    patch!!.getPixel(px, py)
                                } catch (_: Throwable) {
                                    0xFF1E293B.toInt()
                                }

                                val r = (sampleColor shr 16) and 0xFF
                                val g = (sampleColor shr 8) and 0xFF
                                val b = sampleColor and 0xFF
                                val luma = (0.299f * r + 0.587f * g + 0.114f * b).toInt()
                                val isSkin = ContentModerationEngine.isSkinPixel(sampleColor)
                                val finalR: Int
                                val finalG: Int
                                val finalB: Int
                                if (isSkin) {
                                    // Desaturate/clamp skin tones into neutral dark-slate/frosted palette
                                    finalR = (22 + (luma * 0.20f)).toInt().coerceIn(15, 65)
                                    finalG = (32 + (luma * 0.24f)).toInt().coerceIn(22, 80)
                                    finalB = (48 + (luma * 0.30f)).toInt().coerceIn(35, 100)
                                } else {
                                    // Sample clean frame colors with slight desaturation
                                    finalR = ((r * 0.55f) + (luma * 0.45f)).toInt().coerceIn(15, 180)
                                    finalG = ((g * 0.55f) + (luma * 0.45f)).toInt().coerceIn(20, 180)
                                    finalB = ((b * 0.55f) + (luma * 0.45f)).toInt().coerceIn(25, 195)
                                }
                                mosaicBlockPaint.color = Color.argb(255, finalR, finalG, finalB)
                            } else {
                                mosaicBlockPaint.color = when ((row * 3 + col * 5) % 3) {
                                    0 -> Color.argb(255, 30, 41, 59)
                                    1 -> Color.argb(255, 51, 65, 85)
                                    else -> Color.argb(255, 39, 52, 73)
                                }
                            }
                            canvas.drawRect(x, y, nextX, nextY, mosaicBlockPaint)
                            x = nextX
                            col++
                        }
                        y = nextY
                        row++
                    }
                    canvas.restoreToCount(saveCount)
                    canvas.drawRoundRect(screenRect, cornerRadius, cornerRadius, borderPaint)
                }

                HaramBlurOverlayStyle.BLUR -> {
                    // 1. 255 opaque #FF1E293B backing rect
                    boxFillPaint.color = 0xFF1E293B.toInt()
                    canvas.drawRoundRect(screenRect, cornerRadius, cornerRadius, boxFillPaint)

                    val saveCount = canvas.save()
                    clipPath.reset()
                    clipPath.addRoundRect(screenRect, cornerRadius, cornerRadius, android.graphics.Path.Direction.CW)
                    canvas.clipPath(clipPath)

                    val patch = region.previewPatch
                    if (patch != null && !patch.isRecycled) {
                        // Heavily downscaled patch upscaled with FILTER_BITMAP_FLAG
                        canvas.drawBitmap(patch, null, screenRect, filterBitmapPaint)
                        // Dark frosted tint overlay so no facial features or contours are ever visible
                        frostLayerPaint.color = Color.argb(210, 20, 28, 42)
                        canvas.drawRect(screenRect, frostLayerPaint)
                    } else {
                        // Solid dark frosted texture without patch
                        frostLayerPaint.color = Color.argb(255, 30, 41, 59)
                        canvas.drawRect(screenRect, frostLayerPaint)
                    }
                    canvas.restoreToCount(saveCount)
                    canvas.drawRoundRect(screenRect, cornerRadius, cornerRadius, borderPaint)
                }
            }
        }
    }
}

/**
 * WindowManager wrapper that attaches [RegionalBlurOverlayView] with strict non-blocking touch flags:
 * `FLAG_NOT_FOCUSABLE or FLAG_NOT_TOUCHABLE or FLAG_LAYOUT_IN_SCREEN`.
 */
class RegionalBlurWindowManager(private val context: Context) {
    companion object {
        private const val TAG = "RegionalBlurWM"

        const val NON_BLOCKING_REGIONAL_WINDOW_FLAGS: Int =
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
    }

    private val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private var overlayView: RegionalBlurOverlayView? = null
    private var isAttached = false

    fun attachIfPermitted() {
        if (isAttached) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(context)) {
            Log.w(TAG, "Cannot attach regional blur overlay: SYSTEM_ALERT_WINDOW not granted.")
            return
        }
        val view = RegionalBlurOverlayView(context).apply {
            visibility = View.GONE
        }
        val windowType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            windowType,
            NON_BLOCKING_REGIONAL_WINDOW_FLAGS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
        }
        try {
            windowManager.addView(view, params)
            overlayView = view
            isAttached = true
        } catch (t: Throwable) {
            Log.e(TAG, "Failed to attach RegionalBlurOverlayView", t)
        }
    }

    fun renderRegions(
        regions: List<TrackedBlurRegion>,
        style: HaramBlurOverlayStyle,
        strength: Int
    ) {
        if (!isAttached && regions.isNotEmpty()) {
            attachIfPermitted()
        }
        overlayView?.updateBoxes(regions, style, strength)
    }

    fun hideAll() {
        overlayView?.updateBoxes(emptyList(), HaramBlurOverlayStyle.BLUR, 10)
    }

    fun detach() {
        val view = overlayView ?: return
        try {
            if (isAttached) {
                windowManager.removeViewImmediate(view)
            }
        } catch (_: Throwable) {
        } finally {
            overlayView = null
            isAttached = false
        }
    }
}
