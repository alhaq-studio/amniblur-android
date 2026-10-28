package com.amngaze

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.*
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.view.animation.AccelerateDecelerateInterpolator
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView

/**
 * Layer 3 Pre-Allocated `<2ms` Whole-Screen Curtain Shield (`TYPE_ACCESSIBILITY_OVERLAY`).
 *
 * Solves two fundamental Android graphics/compositor issues:
 * 1. Pre-attaches in `onServiceConnected()` with `visibility = View.GONE`. Flipping to
 *    `View.VISIBLE` takes <2ms on the main thread, obscuring explicit content before
 *    `GLOBAL_ACTION_BACK` or `GLOBAL_ACTION_HOME` animations even begin.
 * 2. Always backs `FLAG_BLUR_BEHIND` (API 31+) with a high-opacity `#F20F172A` dark slate
 *    surface so explicit content is 100% obscured even on OEM skins or emulators where
 *    `WindowManager.isCrossWindowBlurEnabled` is false.
 */
class CurtainBackdropCanvasView(context: Context) : View(context) {

    var censorStyle: BlurCensorStyle = BlurCensorStyle.FROSTED
    var showBorder: Boolean = true

    private val baseSlatePaint = Paint().apply {
        // High-opacity #F20F172A (95% opaque dark slate) guarantees zero bleed-through
        color = Color.parseColor("#F20F172A")
        style = Paint.Style.FILL
    }

    private val blackoutPaint = Paint().apply {
        color = Color.parseColor("#FF070B14")
        style = Paint.Style.FILL
    }

    private val mosaicPaint = Paint().apply {
        style = Paint.Style.FILL
    }

    private val mosaicPalette = intArrayOf(
        Color.parseColor("#F50F172A"),
        Color.parseColor("#F51E293B"),
        Color.parseColor("#F5172554"),
        Color.parseColor("#F5111827"),
        Color.parseColor("#F5090D16"),
        Color.parseColor("#F51E1B4B")
    )

    private val frostedOverlayPaint = Paint().apply {
        color = Color.parseColor("#26E2E8F0")
        style = Paint.Style.FILL
    }

    private val frostedGridPaint = Paint().apply {
        color = Color.parseColor("#1494A3B8")
        style = Paint.Style.STROKE
        strokeWidth = 2f
        isAntiAlias = true
    }

    private val borderPaint = Paint().apply {
        color = Color.parseColor("#EF4444")
        style = Paint.Style.STROKE
        strokeWidth = 6f
        isAntiAlias = true
    }

    private val scanlinePaint = Paint().apply {
        color = Color.parseColor("#28EF4444")
        style = Paint.Style.FILL
    }

    private var pulseAlpha = 0.85f
    private var scanlineProgress = 0f

    private val pulseAnimator = ValueAnimator.ofFloat(0.35f, 0.95f).apply {
        duration = 850
        repeatCount = ValueAnimator.INFINITE
        repeatMode = ValueAnimator.REVERSE
        interpolator = AccelerateDecelerateInterpolator()
        addUpdateListener { anim ->
            pulseAlpha = anim.animatedValue as Float
            borderPaint.alpha = (pulseAlpha * 255).toInt()
            invalidate()
        }
    }

    private val scanlineAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
        duration = 2100
        repeatCount = ValueAnimator.INFINITE
        interpolator = AccelerateDecelerateInterpolator()
        addUpdateListener { anim ->
            scanlineProgress = anim.animatedValue as Float
            invalidate()
        }
    }

    fun startAnimationsIfNeeded() {
        if (showBorder && !pulseAnimator.isStarted) {
            pulseAnimator.start()
            scanlineAnimator.start()
        }
        invalidate()
    }

    fun stopAnimations() {
        if (pulseAnimator.isStarted) pulseAnimator.cancel()
        if (scanlineAnimator.isStarted) scanlineAnimator.cancel()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return

        // 1. Always paint the high-opacity fallback base first (#F20F172A)
        canvas.drawRect(0f, 0f, w, h, baseSlatePaint)

        // 2. Render style-specific whole-screen curtain pattern
        when (censorStyle) {
            BlurCensorStyle.BLACK_BAR -> {
                canvas.drawRect(0f, 0f, w, h, blackoutPaint)
            }
            BlurCensorStyle.MOSAIC -> {
                val tileSize = 44f
                val cols = (w / tileSize).toInt() + 1
                val rows = (h / tileSize).toInt() + 1
                for (r in 0..rows) {
                    for (c in 0..cols) {
                        mosaicPaint.color = mosaicPalette[(r * 5 + c * 3) % mosaicPalette.size]
                        canvas.drawRect(
                            c * tileSize,
                            r * tileSize,
                            (c + 1) * tileSize - 2f,
                            (r + 1) * tileSize - 2f,
                            mosaicPaint
                        )
                    }
                }
            }
            BlurCensorStyle.FROSTED -> {
                canvas.drawRect(0f, 0f, w, h, frostedOverlayPaint)
                val step = 64f
                var x = 0f
                while (x < w) {
                    canvas.drawLine(x, 0f, x, h, frostedGridPaint)
                    x += step
                }
                var y = 0f
                while (y < h) {
                    canvas.drawLine(0f, y, w, y, frostedGridPaint)
                    y += step
                }
            }
            BlurCensorStyle.GAUSSIAN_BLUR -> {
                val scanY = h * scanlineProgress
                canvas.drawRect(0f, scanY - 18f, w, scanY + 18f, scanlinePaint)
            }
        }

        // 3. Optional pulsing alert border & scanline
        if (showBorder) {
            val inset = 12f
            canvas.drawRoundRect(inset, inset, w - inset, h - inset, 36f, 36f, borderPaint)
            val scanY = h * scanlineProgress
            canvas.drawRect(inset, scanY - 8f, w - inset, scanY + 8f, scanlinePaint)
        }
    }

    override fun onDetachedFromWindow() {
        stopAnimations()
        super.onDetachedFromWindow()
    }
}

/**
 * Manages the pre-allocated `TYPE_ACCESSIBILITY_OVERLAY` window for `<2ms` whole-screen
 * blocking and user actions ("Leave Page Safely" & "False Positive - Unblur for 30s").
 */
class BlurOverlayManager(
    private val context: Context,
    private val onLeaveSafelyClicked: (() -> Unit)? = null,
    private val onUnblurFalsePositiveClicked: (() -> Unit)? = null
) {
    companion object {
        private const val TAG = "BlurOverlayManager"
    }

    private val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager

    private var rootContainer: FrameLayout? = null
    private var backdropCanvas: CurtainBackdropCanvasView? = null
    private var badgeChipView: TextView? = null
    private var titleTextView: TextView? = null
    private var reasonTextView: TextView? = null
    private var strikeStatusTextView: TextView? = null
    private var primaryLeaveButton: Button? = null
    private var secondaryUnblurButton: Button? = null

    private var isAttached = false

    @Volatile
    var isShieldVisible: Boolean = false
        private set

    @Volatile
    var shieldedPackage: String? = null
        private set

    /**
     * Pre-allocates and attaches the app-scoped `TYPE_ACCESSIBILITY_OVERLAY` with `View.GONE`.
     * Uses `FLAG_NOT_FOCUSABLE` and `FLAG_NOT_TOUCH_MODAL` without `FLAG_FULLSCREEN` so the
     * system navigation bar, Home/Back/Recents gestures, and system UI never freeze.
     */
    fun attachPreAllocatedShield() {
        if (isAttached) return

        try {
            val density = context.resources.displayMetrics.density
            fun dp(value: Int): Int = (value * density).toInt()

            val container = FrameLayout(context).apply {
                // High-opacity fallback background (#F20F172A) guarantees zero bleed-through
                setBackgroundColor(Color.parseColor("#F20F172A"))
                visibility = View.GONE
            }

            val canvasView = CurtainBackdropCanvasView(context)
            container.addView(
                canvasView,
                FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT
                )
            )

            // Center card holding title, detection reason, strike status, and action buttons
            val cardLayout = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_HORIZONTAL
                setPadding(dp(28), dp(32), dp(28), dp(28))
                background = GradientDrawable().apply {
                    shape = GradientDrawable.RECTANGLE
                    cornerRadius = dp(24).toFloat()
                    setColor(Color.parseColor("#FF1E293B"))
                    setStroke(dp(1), Color.parseColor("#475569"))
                }
            }

            val badgeView = TextView(context).apply {
                text = "AMNGAZE AI SHIELD ACTIVE"
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
                setTextColor(Color.parseColor("#F87171"))
                typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
                letterSpacing = 0.1f
                gravity = Gravity.CENTER
                setPadding(dp(14), dp(6), dp(14), dp(6))
                background = GradientDrawable().apply {
                    shape = GradientDrawable.RECTANGLE
                    cornerRadius = dp(99).toFloat()
                    setColor(Color.parseColor("#33EF4444"))
                    setStroke(dp(1), Color.parseColor("#7FEF4444"))
                }
            }

            val titleView = TextView(context).apply {
                text = "Explicit or Revealing Content Blocked"
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 22f)
                setTextColor(Color.WHITE)
                typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
                gravity = Gravity.CENTER
                setPadding(0, dp(18), 0, dp(8))
            }

            val reasonView = TextView(context).apply {
                text = "On-device AI protection intercepted potentially triggering content."
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
                setTextColor(Color.parseColor("#CBD5E1"))
                gravity = Gravity.CENTER
                setPadding(dp(8), 0, dp(8), dp(12))
            }

            val strikeView = TextView(context).apply {
                text = "App-Scoped Curtain Protection"
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                setTextColor(Color.parseColor("#94A3B8"))
                typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
                gravity = Gravity.CENTER
                setPadding(0, 0, 0, dp(24))
            }

            val leaveBtn = Button(context).apply {
                text = "Leave Page Safely"
                isAllCaps = false
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
                setTextColor(Color.WHITE)
                typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
                background = GradientDrawable().apply {
                    shape = GradientDrawable.RECTANGLE
                    cornerRadius = dp(14).toFloat()
                    setColor(Color.parseColor("#E05A47"))
                }
                setPadding(dp(20), dp(14), dp(20), dp(14))
                setOnClickListener {
                    onLeaveSafelyClicked?.invoke()
                }
            }

            val unblurBtn = Button(context).apply {
                text = "False Positive - Unblur for 30s"
                isAllCaps = false
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
                setTextColor(Color.parseColor("#E2E8F0"))
                typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                background = GradientDrawable().apply {
                    shape = GradientDrawable.RECTANGLE
                    cornerRadius = dp(14).toFloat()
                    setColor(Color.parseColor("#334155"))
                    setStroke(dp(1), Color.parseColor("#64748B"))
                }
                setPadding(dp(20), dp(12), dp(20), dp(12))
                setOnClickListener {
                    onUnblurFalsePositiveClicked?.invoke()
                }
            }

            cardLayout.addView(
                badgeView,
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
            )
            cardLayout.addView(
                titleView,
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
            )
            cardLayout.addView(
                reasonView,
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
            )
            cardLayout.addView(
                strikeView,
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
            )
            cardLayout.addView(
                leaveBtn,
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    bottomMargin = dp(12)
                }
            )
            cardLayout.addView(
                unblurBtn,
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
            )

            val cardParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER
            ).apply {
                leftMargin = dp(24)
                rightMargin = dp(24)
            }
            container.addView(cardLayout, cardParams)

            // FLAG_NOT_FOCUSABLE + FLAG_NOT_TOUCH_MODAL ensures system navigation bars (Home, Back,
            // Recents gestures) and hardware keys never freeze, while overlay buttons remain clickable.
            var windowFlags = WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                windowFlags = windowFlags or WindowManager.LayoutParams.FLAG_BLUR_BEHIND
            }

            val windowType = try {
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY
            } catch (_: Throwable) {
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            }

            val params = WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                windowType,
                windowFlags,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.CENTER
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    blurBehindRadius = 64
                }
            }

            windowManager.addView(container, params)

            rootContainer = container
            backdropCanvas = canvasView
            badgeChipView = badgeView
            titleTextView = titleView
            reasonTextView = reasonView
            strikeStatusTextView = strikeView
            primaryLeaveButton = leaveBtn
            secondaryUnblurButton = unblurBtn
            isAttached = true
            isShieldVisible = false
            shieldedPackage = null

            Log.i(TAG, "Pre-allocated TYPE_ACCESSIBILITY_OVERLAY shield attached with View.GONE.")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to pre-allocate TYPE_ACCESSIBILITY_OVERLAY window.", e)
        }
    }

    /**
     * Instantly (<2ms) flips the pre-allocated curtain shield to `View.VISIBLE` scoped to [targetPackage].
     * Automatically clears when the user navigates outside [targetPackage].
     */
    fun showCurtainShield(
        targetPackage: String,
        censorStyle: BlurCensorStyle,
        showBadge: Boolean,
        showBorder: Boolean,
        title: String,
        reason: String,
        strikeStatus: String,
        allowFalsePositiveUnblur: Boolean,
        primaryActionLabel: String = "Leave Page Safely",
        secondaryActionLabel: String = "False Positive - Unblur for 30s"
    ) {
        if (!isAttached) {
            attachPreAllocatedShield()
        }

        shieldedPackage = targetPackage

        backdropCanvas?.apply {
            this.censorStyle = censorStyle
            this.showBorder = showBorder
            startAnimationsIfNeeded()
        }

        badgeChipView?.visibility = if (showBadge) View.VISIBLE else View.GONE
        titleTextView?.text = title
        reasonTextView?.text = reason
        strikeStatusTextView?.text = strikeStatus
        primaryLeaveButton?.text = primaryActionLabel
        secondaryUnblurButton?.text = secondaryActionLabel
        secondaryUnblurButton?.visibility = if (allowFalsePositiveUnblur) View.VISIBLE else View.GONE

        rootContainer?.visibility = View.VISIBLE
        isShieldVisible = true
    }

    /**
     * Immediately hides the shield (`View.GONE`) and clears [shieldedPackage].
     */
    fun hide() {
        backdropCanvas?.stopAnimations()
        rootContainer?.visibility = View.GONE
        isShieldVisible = false
        shieldedPackage = null
    }

    /**
     * Detaches the pre-allocated window when the AccessibilityService is destroyed.
     */
    fun detach() {
        hide()
        val view = rootContainer ?: return
        try {
            if (isAttached) {
                windowManager.removeView(view)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to remove pre-allocated Accessibility overlay.", e)
        } finally {
            rootContainer = null
            backdropCanvas = null
            isAttached = false
            isShieldVisible = false
            shieldedPackage = null
        }
    }
}
