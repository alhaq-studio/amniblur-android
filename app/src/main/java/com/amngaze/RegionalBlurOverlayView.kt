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
        color = Color.argb(110, 255, 255, 255)
    }
    private val screenRect = RectF()

    private val frostLayerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val clipPath = android.graphics.Path()

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
        val alpha = HaramBlurFilterLogic.computeOverlayAlpha(effectStrength)
        val densityFactor = HaramBlurFilterLogic.computeDensityFactor(effectStrength)

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
                    // Scales solidness vs. transparency from 1/10 (alpha=115) up to 9..10/10 (alpha=255, 100% solid)
                    boxFillPaint.color = HaramBlurFilterLogic.overlayColorArgb(overlayStyle, effectStrength)
                    canvas.drawRoundRect(screenRect, cornerRadius, cornerRadius, boxFillPaint)
                    if (alpha < 255) {
                        canvas.drawRoundRect(screenRect, cornerRadius, cornerRadius, borderPaint)
                    }
                }

                HaramBlurOverlayStyle.PIXELATE -> {
                    // 1. Solid backing fill scaled by alpha (reaches 255 = 100% opaque at 9..10/10 so nothing bleeds through)
                    boxFillPaint.color = Color.argb(alpha, 15, 23, 42)
                    canvas.drawRoundRect(screenRect, cornerRadius, cornerRadius, boxFillPaint)

                    // 2. Dense mosaic grid where higher strength increases tile opacity and packing density
                    val denseBlockDp = (26f - densityFactor * 14f).coerceIn(10f, 26f)
                    val blockSizePx = max(10f, denseBlockDp * density)
                    val saveCount = canvas.save()
                    clipPath.reset()
                    clipPath.addRoundRect(screenRect, cornerRadius, cornerRadius, android.graphics.Path.Direction.CW)
                    canvas.clipPath(clipPath)

                    var row = 0
                    var y = screenRect.top
                    while (y < screenRect.bottom) {
                        var col = 0
                        var x = screenRect.left
                        val nextY = (y + blockSizePx).coerceAtMost(screenRect.bottom)
                        while (x < screenRect.right) {
                            val nextX = (x + blockSizePx).coerceAtMost(screenRect.right)
                            mosaicBlockPaint.color = when ((row * 3 + col * 5) % 3) {
                                0 -> Color.argb(alpha, 30, 41, 59)
                                1 -> Color.argb(alpha, 51, 65, 85)
                                else -> Color.argb(alpha, 39, 52, 73)
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
                    // 1. Primary frosted fill scaled by Effect Strength (1..10 -> alpha 115..255).
                    //    At 9/10 and 10/10, alpha is 255 (100% solid opaque, zero see-through).
                    boxFillPaint.color = Color.argb(alpha, 24, 34, 50)
                    canvas.drawRoundRect(screenRect, cornerRadius, cornerRadius, boxFillPaint)

                    // 2. Multi-band frosted diffusion layers scaled by densityFactor (2..7 layers)
                    val saveCount = canvas.save()
                    clipPath.reset()
                    clipPath.addRoundRect(screenRect, cornerRadius, cornerRadius, android.graphics.Path.Direction.CW)
                    canvas.clipPath(clipPath)

                    val layerCount = 2 + (densityFactor * 5f).toInt()
                    val bandHeight = (screenRect.height() / layerCount.toFloat()).coerceAtLeast(6f)
                    val layerAlpha = (45 + (densityFactor * 95f).toInt()).coerceIn(45, 140)
                    for (i in 0 until layerCount) {
                        val bandTop = screenRect.top + i * bandHeight
                        val bandBottom = (bandTop + bandHeight).coerceAtMost(screenRect.bottom)
                        frostLayerPaint.color = if (i % 2 == 0) {
                            Color.argb(layerAlpha, 51, 65, 85)
                        } else {
                            Color.argb(layerAlpha, 30, 41, 59)
                        }
                        canvas.drawRect(screenRect.left, bandTop, screenRect.right, bandBottom, frostLayerPaint)
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
