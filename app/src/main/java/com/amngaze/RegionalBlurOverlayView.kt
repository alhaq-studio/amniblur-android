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
    private val blurUpscalePaint = Paint(Paint.FILTER_BITMAP_FLAG).apply {
        isDither = true
    }
    private val pixelateUpscalePaint = Paint().apply {
        isFilterBitmap = false // nearest neighbor sampling for authentic mosaic blocks
        isDither = false
    }
    private val frostTintPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val clipPath = android.graphics.Path()
    private val screenRect = RectF()
    private val destRect = android.graphics.Rect()

    fun updateBoxes(
        regions: List<TrackedBlurRegion>,
        style: HaramBlurOverlayStyle,
        strength: Int
    ) {
        activeRegions.clear()
        activeRegions.addAll(regions)
        overlayStyle = style
        effectStrength = strength.coerceIn(1, 10)
        visibility = if (activeRegions.isEmpty()) GONE else VISIBLE
        invalidate()
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
                    // 100% solid opaque (alpha = 255) rounded rect at all strength levels 1..10
                    boxFillPaint.color = HaramBlurFilterLogic.overlayColorArgb(overlayStyle, effectStrength)
                    canvas.drawRoundRect(screenRect, cornerRadius, cornerRadius, boxFillPaint)
                }

                HaramBlurOverlayStyle.PIXELATE -> {
                    // 1. Solid opaque backing rect (alpha = 255, #FF0F172A)
                    boxFillPaint.color = Color.rgb(15, 23, 42)
                    canvas.drawRoundRect(screenRect, cornerRadius, cornerRadius, boxFillPaint)

                    val saveCount = canvas.save()
                    clipPath.reset()
                    clipPath.addRoundRect(screenRect, cornerRadius, cornerRadius, android.graphics.Path.Direction.CW)
                    canvas.clipPath(clipPath)

                    // 2. Sample region colors into a grid whose tile size scales with Effect Strength (1..10)
                    // (12dp at 1 up to 38dp at 10), clamp/desaturate skin tones into a neutral dark-slate palette,
                    // and draw every tile at alpha = 255 (100% opaque) with zero gaps (gap = 0f).
                    val patch = region.previewPatch
                    val hasValidPatch = patch != null && !patch.isRecycled && patch.width > 0 && patch.height > 0
                    val blockSizeDp = HaramBlurFilterLogic.computePixelationBlockSizeDp(effectStrength)
                    val blockSizePx = max(10f, blockSizeDp * density)
                    val regionW = screenRect.width().coerceAtLeast(1f)
                    val regionH = screenRect.height().coerceAtLeast(1f)

                    var row = 0
                    var y = screenRect.top
                    while (y < screenRect.bottom) {
                        var col = 0
                        var x = screenRect.left
                        val nextY = (y + blockSizePx).coerceAtMost(screenRect.bottom)
                        while (x < screenRect.right) {
                            val nextX = (x + blockSizePx).coerceAtMost(screenRect.right)
                            if (patch != null && hasValidPatch) {
                                val u = (((x + nextX) * 0.5f - screenRect.left) / regionW).coerceIn(0f, 0.999f)
                                val v = (((y + nextY) * 0.5f - screenRect.top) / regionH).coerceIn(0f, 0.999f)
                                val px = (u * patch.width).toInt().coerceIn(0, patch.width - 1)
                                val py = (v * patch.height).toInt().coerceIn(0, patch.height - 1)
                                val rawColor = patch.getPixel(px, py)
                                val rRaw = Color.red(rawColor)
                                val gRaw = Color.green(rawColor)
                                val bRaw = Color.blue(rawColor)
                                val luma = (0.299f * rRaw + 0.587f * gRaw + 0.114f * bRaw)
                                // Clamp/desaturate skin tones into a 100% opaque neutral dark-slate/frosted palette
                                val rClamped = (luma * 0.22f + 22f).toInt().coerceIn(15, 65)
                                val gClamped = (luma * 0.28f + 30f).toInt().coerceIn(23, 80)
                                val bClamped = (luma * 0.35f + 42f).toInt().coerceIn(40, 100)
                                mosaicBlockPaint.color = Color.rgb(rClamped, gClamped, bClamped)
                            } else {
                                mosaicBlockPaint.color = when ((row * 3 + col * 5) % 3) {
                                    0 -> Color.rgb(30, 41, 59) // #FF1E293B
                                    1 -> Color.rgb(51, 65, 85) // #FF334155
                                    else -> Color.rgb(39, 52, 73) // #FF273449
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
                }

                HaramBlurOverlayStyle.BLUR -> {
                    // 1. Solid opaque backing rect (alpha = 255)
                    boxFillPaint.color = Color.rgb(30, 41, 59) // #FF1E293B
                    canvas.drawRoundRect(screenRect, cornerRadius, cornerRadius, boxFillPaint)

                    val saveCount = canvas.save()
                    clipPath.reset()
                    clipPath.addRoundRect(screenRect, cornerRadius, cornerRadius, android.graphics.Path.Direction.CW)
                    canvas.clipPath(clipPath)

                    val patch = region.previewPatch
                    if (patch != null && !patch.isRecycled) {
                        destRect.set(
                            screenRect.left.toInt(),
                            screenRect.top.toInt(),
                            screenRect.right.toInt(),
                            screenRect.bottom.toInt()
                        )
                        canvas.drawBitmap(patch, null, destRect, blurUpscalePaint)
                        // Dark frosted tint overlay so no facial contours remain recognizable
                        frostTintPaint.color = Color.argb(215, 24, 34, 50)
                        canvas.drawRect(screenRect, frostTintPaint)
                    } else {
                        // Solid dark frosted fill at alpha 255
                        frostTintPaint.color = Color.rgb(24, 34, 50)
                        canvas.drawRect(screenRect, frostTintPaint)
                    }
                    canvas.restoreToCount(saveCount)
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
