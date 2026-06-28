package com.amngaze

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.*
import android.view.View
import android.view.WindowManager
import android.view.animation.AccelerateDecelerateInterpolator
import android.util.Log

/**
 * Premium AmnGaze Overlay View.
 *
 * Renders configurable censorship blocks over detected regions with:
 * - 4 censor styles: Mosaic, Gaussian Blur (approx), Black Bar, Frosted Glass
 * - Optional animated pulsing alert border
 * - Optional badge label ("AMNGAZE: BLOCKED")
 * - Smooth fade-in/fade-out alpha transitions
 */
class BlurOverlayView(context: Context) : View(context) {

    private var detectedBoxes: List<RectF> = emptyList()

    // Configurable options (updated via updateConfig)
    var censorStyle: BlurCensorStyle = BlurCensorStyle.MOSAIC
    var showBadge: Boolean = true
    var showBorder: Boolean = true
    var isHardBlock: Boolean = false

    init {
        // Software rendering required for BlurMaskFilter and custom shader effects
        setLayerType(LAYER_TYPE_SOFTWARE, null)
        initNoiseTexture()
    }

    // ─── Mosaic tile rendering ────────────────────────────────────────────────
    private val mosaicTileSize = 22f
    private val mosaicPaint = Paint().apply { style = Paint.Style.FILL }

    private val mosaicPalette = listOf(
        Color.parseColor("#1A1A2E"),
        Color.parseColor("#16213E"),
        Color.parseColor("#0F3460"),
        Color.parseColor("#1E1E3F"),
        Color.parseColor("#12122A"),
        Color.parseColor("#0A0A1E"),
        Color.parseColor("#252550")
    )

    private val vignettePaint = Paint()

    // ─── Gaussian Blur rendering ──────────────────────────────────────────────
    private val tinyBlurBitmap = Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888).apply {
        val tinyCanvas = Canvas(this)
        val paint = Paint().apply { style = Paint.Style.FILL }
        for (y in 0 until 8) {
            for (x in 0 until 8) {
                val colorIdx = (y * 7 + x * 3) % mosaicPalette.size
                paint.color = mosaicPalette[colorIdx]
                tinyCanvas.drawRect(x.toFloat(), y.toFloat(), (x + 1).toFloat(), (y + 1).toFloat(), paint)
            }
        }
    }

    private val blurPaint = Paint().apply {
        isFilterBitmap = true
        isAntiAlias = true
        maskFilter = BlurMaskFilter(6f, BlurMaskFilter.Blur.NORMAL)
    }

    private val blurOverlayPaint = Paint().apply {
        color = Color.parseColor("#55000020")
        style = Paint.Style.FILL
    }

    private val roundedPath = Path()

    // ─── Black bar rendering ──────────────────────────────────────────────────
    private val blackBarPaint = Paint().apply {
        color = Color.parseColor("#D9000000")
        style = Paint.Style.FILL
    }

    // ─── Frosted glass rendering ──────────────────────────────────────────────
    private val frostedFillPaint = Paint().apply {
        color = Color.parseColor("#CCFFFFFF")
        style = Paint.Style.FILL
    }
    private val frostedNoisePaint = Paint().apply {
        style = Paint.Style.FILL
    }
    private val frostedStrokePaint = Paint().apply {
        color = Color.parseColor("#40FFFFFF")
        style = Paint.Style.STROKE
        strokeWidth = 1.5f
        isAntiAlias = true
    }

    private fun initNoiseTexture() {
        val noiseBitmap = Bitmap.createBitmap(128, 128, Bitmap.Config.ARGB_8888)
        val random = java.util.Random()
        for (x in 0 until 128) {
            for (y in 0 until 128) {
                val gray = random.nextInt(80) + 160
                val alpha = random.nextInt(40) + 10
                noiseBitmap.setPixel(x, y, Color.argb(alpha, gray, gray, gray))
            }
        }
        val shader = BitmapShader(noiseBitmap, Shader.TileMode.REPEAT, Shader.TileMode.REPEAT)
        frostedNoisePaint.shader = shader
    }

    // ─── Animated border ──────────────────────────────────────────────────────
    private var borderAlpha: Float = 0.9f
    private val borderPaint = Paint().apply {
        color = Color.parseColor("#FF1744")
        style = Paint.Style.STROKE
        strokeWidth = 5f
        pathEffect = DashPathEffect(floatArrayOf(18f, 10f), 0f)
        isAntiAlias = true
        maskFilter = BlurMaskFilter(12f, BlurMaskFilter.Blur.OUTER)
    }

    private val solidBorderPaint = Paint().apply {
        color = Color.parseColor("#FF1744")
        style = Paint.Style.STROKE
        strokeWidth = 2.5f
        isAntiAlias = true
    }

    // ─── Badge / label ────────────────────────────────────────────────────────
    private val badgePaint = Paint().apply {
        color = Color.parseColor("#CC000000")
        style = Paint.Style.FILL
        isAntiAlias = true
    }

    private val badgeStrokePaint = Paint().apply {
        color = Color.parseColor("#FF1744")
        style = Paint.Style.STROKE
        strokeWidth = 1.8f
        isAntiAlias = true
    }

    private val labelPaint = Paint().apply {
        color = Color.WHITE
        textSize = 28f
        typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
        isAntiAlias = true
        textAlign = Paint.Align.CENTER
    }

    private val subLabelPaint = Paint().apply {
        color = Color.parseColor("#FF5252")
        textSize = 18f
        typeface = Typeface.create(Typeface.MONOSPACE, Typeface.NORMAL)
        isAntiAlias = true
        textAlign = Paint.Align.CENTER
    }

    // ─── Pulsing border animator ───────────────────────────────────────────────
    private val pulseAnimator = ValueAnimator.ofFloat(0.4f, 1.0f).apply {
        duration = 700
        repeatCount = ValueAnimator.INFINITE
        repeatMode = ValueAnimator.REVERSE
        interpolator = AccelerateDecelerateInterpolator()
        addUpdateListener { animator ->
            borderAlpha = animator.animatedValue as Float
            borderPaint.alpha = (borderAlpha * 255).toInt()
            solidBorderPaint.alpha = (borderAlpha * 200).toInt()
            invalidate()
        }
    }

    // ─── Scanline effect ───────────────────────────────────────────────────────
    private var scanlineOffset = 0f
    private val scanlineAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
        duration = 1800
        repeatCount = ValueAnimator.INFINITE
        interpolator = AccelerateDecelerateInterpolator()
        addUpdateListener { animator ->
            scanlineOffset = animator.animatedValue as Float
            invalidate()
        }
    }

    private val scanlinePaint = Paint().apply {
        color = Color.parseColor("#22FF1744")
        style = Paint.Style.FILL
    }

    fun updateRegions(boxes: List<RectF>) {
        detectedBoxes = boxes
        if (!pulseAnimator.isStarted && showBorder) {
            pulseAnimator.start()
            scanlineAnimator.start()
        }
        invalidate()
    }

    fun clearRegions() {
        detectedBoxes = emptyList()
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (detectedBoxes.isEmpty()) return

        if (isHardBlock) {
            canvas.drawColor(Color.BLACK)
            // Draw a single badge in the center of the screen
            if (showBadge) {
                drawBadge(canvas, RectF(0f, 0f, width.toFloat(), height.toFloat()))
            }
            return
        }

        for (rect in detectedBoxes) {
            // Render the chosen censor style
            when (censorStyle) {
                BlurCensorStyle.MOSAIC        -> drawMosaicBlock(canvas, rect)
                BlurCensorStyle.GAUSSIAN_BLUR -> drawGaussianBlurBlock(canvas, rect)
                BlurCensorStyle.BLACK_BAR     -> drawBlackBarBlock(canvas, rect)
                BlurCensorStyle.FROSTED       -> drawFrostedBlock(canvas, rect)
            }
            // Optional animated border
            if (showBorder) {
                drawAnimatedBorder(canvas, rect)
                drawScanline(canvas, rect)
            }
            // Optional badge
            if (showBadge) {
                drawBadge(canvas, rect)
            }
        }
    }

    // ─── STYLE: Mosaic ────────────────────────────────────────────────────────
    private fun drawMosaicBlock(canvas: Canvas, rect: RectF) {
        val cols = ((rect.width() / mosaicTileSize) + 1).toInt()
        val rows = ((rect.height() / mosaicTileSize) + 1).toInt()

        canvas.save()
        canvas.clipRect(rect)

        var tileIndex = 0
        for (row in 0..rows) {
            for (col in 0..cols) {
                val left = rect.left + col * mosaicTileSize
                val top = rect.top + row * mosaicTileSize
                val right = left + mosaicTileSize
                val bottom = top + mosaicTileSize
                val colorIdx = (row * 7 + col * 3 + tileIndex) % mosaicPalette.size
                mosaicPaint.color = mosaicPalette[colorIdx]
                canvas.drawRect(left, top, right, bottom, mosaicPaint)
                tileIndex++
            }
        }

        // Subtle dark vignette overlay (reusing pre-allocated vignettePaint)
        vignettePaint.shader = RadialGradient(
            rect.centerX(), rect.centerY(),
            maxOf(rect.width(), rect.height()) * 0.7f,
            intArrayOf(Color.TRANSPARENT, Color.parseColor("#AA000000")),
            null,
            Shader.TileMode.CLAMP
        )
        canvas.drawRect(rect, vignettePaint)
        canvas.restore()
    }

    // ─── STYLE: Gaussian Blur (downscale + upscale approximation) ────────────
    private fun drawGaussianBlurBlock(canvas: Canvas, rect: RectF) {
        canvas.save()
        roundedPath.reset()
        roundedPath.addRoundRect(rect, 8f, 8f, Path.Direction.CW)
        canvas.clipPath(roundedPath)
        
        // Draw pre-allocated tinyBlurBitmap scaled up
        canvas.drawBitmap(tinyBlurBitmap, null, rect, blurPaint)
        canvas.restore()

        // Overlay a semi-transparent dark layer to enhance the blur feel
        canvas.drawRoundRect(rect, 8f, 8f, blurOverlayPaint)
    }

    // ─── STYLE: Black Bar ─────────────────────────────────────────────────────
    private fun drawBlackBarBlock(canvas: Canvas, rect: RectF) {
        canvas.drawRoundRect(rect, 8f, 8f, blackBarPaint)
    }

    // ─── STYLE: Frosted Glass ─────────────────────────────────────────────────
    private fun drawFrostedBlock(canvas: Canvas, rect: RectF) {
        canvas.save()
        canvas.clipRect(rect)

        // White translucent fill
        canvas.drawRoundRect(rect, 12f, 12f, frostedFillPaint)

        // Hardware-accelerated Tiled Noise texture (no loops, no Math.random, zero allocation!)
        canvas.drawRoundRect(rect, 12f, 12f, frostedNoisePaint)

        // Subtle inner border stroke
        canvas.drawRoundRect(rect, 12f, 12f, frostedStrokePaint)
        canvas.restore()
    }

    // ─── Border & scanline ────────────────────────────────────────────────────
    private fun drawAnimatedBorder(canvas: Canvas, rect: RectF) {
        val rx = 24f
        canvas.drawRoundRect(rect, rx, rx, borderPaint)
        canvas.drawRoundRect(rect, rx, rx, solidBorderPaint)
    }

    private fun drawScanline(canvas: Canvas, rect: RectF) {
        val scanY = rect.top + rect.height() * scanlineOffset
        canvas.drawRect(rect.left, scanY - 6f, rect.right, scanY + 6f, scanlinePaint)
    }

    // ─── Badge ────────────────────────────────────────────────────────────────
    private fun drawBadge(canvas: Canvas, rect: RectF) {
        val cx = rect.centerX()
        val cy = rect.centerY()

        // Only draw badge if region is large enough to show text
        if (rect.width() < 120f || rect.height() < 60f) return

        val titleText = "AMNGAZE: BLOCKED"
        val subtitleText = "[CONTENT PROTECTED]"

        val badgeW = 280f
        val badgeH = 68f
        val badgeRect = RectF(
            cx - badgeW / 2f, cy - badgeH / 2f,
            cx + badgeW / 2f, cy + badgeH / 2f
        )
        canvas.drawRoundRect(badgeRect, 16f, 16f, badgePaint)
        canvas.drawRoundRect(badgeRect, 16f, 16f, badgeStrokePaint)
        canvas.drawText(titleText, cx, cy + 4f, labelPaint)
        canvas.drawText(subtitleText, cx, cy + 30f, subLabelPaint)
    }

    override fun onDetachedFromWindow() {
        pulseAnimator.cancel()
        scanlineAnimator.cancel()
        super.onDetachedFromWindow()
    }
}

/**
 * System Overlay Manager using Android WindowManager.
 * Attaches the persistent overlay canvas using TYPE_APPLICATION_OVERLAY parameters,
 * with smooth fade-in / fade-out transitions on show/hide.
 */
class BlurOverlayManager(private val context: Context) {
    private val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private var overlayView: BlurOverlayView? = null
    private var isShowing = false

    // Current config — applied to overlay view when it is created
    private var currentStyle = BlurCensorStyle.MOSAIC
    private var showBadge = true
    private var showBorder = true
    private var isHardBlock = false

    /**
     * Updates the censor style and visual toggle options.
     * If overlay is already showing, applies immediately.
     */
    fun updateConfig(style: BlurCensorStyle, badge: Boolean, border: Boolean, hardBlock: Boolean = false) {
        currentStyle = style
        showBadge = badge
        showBorder = border
        isHardBlock = hardBlock
        overlayView?.let {
            it.censorStyle = style
            it.showBadge = badge
            it.showBorder = border
            it.isHardBlock = hardBlock
            it.invalidate()
        }
    }

    /**
     * Spawns the transparent overlay into WindowManager with a fade-in.
     */
    fun show() {
        if (isShowing) return

        try {
            overlayView = BlurOverlayView(context).apply {
                alpha = 0f
                censorStyle = currentStyle
                showBadge = this@BlurOverlayManager.showBadge
                showBorder = this@BlurOverlayManager.showBorder
                isHardBlock = this@BlurOverlayManager.isHardBlock
            }

            val params = WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                        WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                        WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                        WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT
            )

            windowManager.addView(overlayView, params)
            isShowing = true

            overlayView?.animate()
                ?.alpha(1f)
                ?.setDuration(220)
                ?.start()

            Log.i("BlurOverlayManager", "AmnGaze overlay window attached.")
        } catch (e: Exception) {
            Log.e("BlurOverlayManager", "Failed to register overlay window.", e)
        }
    }

    /**
     * Updates the coordinates of explicit regions on the active canvas.
     */
    fun updateRegions(boxes: List<android.graphics.RectF>) {
        if (!isShowing) show()
        overlayView?.updateRegions(boxes)
    }

    /**
     * Clears all detected regions (keeps overlay attached but empty).
     */
    fun clearRegions() {
        overlayView?.clearRegions()
    }

    /**
     * Removes the overlay from screen with a fade-out animation.
     */
    fun hide() {
        if (!isShowing) return

        val view = overlayView ?: run {
            isShowing = false
            return
        }

        view.animate()
            .alpha(0f)
            .setDuration(250)
            .withEndAction {
                try {
                    windowManager.removeView(view)
                } catch (e: Exception) {
                    Log.e("BlurOverlayManager", "Error removing overlay view.", e)
                }
                overlayView = null
                isShowing = false
                Log.i("BlurOverlayManager", "AmnGaze overlay window removed.")
            }
            .start()
    }
}
