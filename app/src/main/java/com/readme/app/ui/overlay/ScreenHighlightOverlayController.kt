package com.readme.app.ui.overlay

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RectF
import android.os.Build
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.view.animation.AccelerateDecelerateInterpolator

/**
 * Overlay controller that renders a soft teal glowing highlight over the currently read
 * sentence on the screen.
 *
 * Characteristics:
 * - Completely non-interactive (FLAG_NOT_TOUCHABLE, FLAG_NOT_FOCUSABLE)
 * - Transparent canvas outside the highlighted sentence rectangles
 * - Supports multi-line sentence bounding boxes
 * - Soft animated breathing/pulse effect
 * - Immediate clean removal on speech stop, pause, document switch, or error
 */
class ScreenHighlightOverlayController(private val context: Context) {

    private val windowManager: WindowManager? =
        context.getSystemService(Context.WINDOW_SERVICE) as? WindowManager

    private var highlightView: View? = null
    private var isAdded = false
    private var currentRects: List<RectF> = emptyList()
    private var pulseAlpha: Float = 0.35f
    private var pulseAnimator: ValueAnimator? = null

    init {
        pulseAnimator = ValueAnimator.ofFloat(0.22f, 0.42f).apply {
            duration = 1100
            repeatMode = ValueAnimator.REVERSE
            repeatCount = ValueAnimator.INFINITE
            interpolator = AccelerateDecelerateInterpolator()
            addUpdateListener { animator ->
                pulseAlpha = animator.animatedValue as Float
                highlightView?.invalidate()
            }
        }
    }

    fun isShowing(): Boolean = isAdded && currentRects.isNotEmpty()

    /**
     * Updates the active highlight with the provided bounding rectangles.
     * If the list is empty, clears and hides the highlight overlay.
     */
    fun showHighlight(rectangles: List<RectF>) {
        val validRects = rectangles.filter { !it.isEmpty }
        if (validRects.isEmpty()) {
            clearHighlight()
            return
        }

        currentRects = validRects

        if (!isAdded) {
            attachOverlay()
        }

        if (pulseAnimator?.isRunning != true) {
            pulseAnimator?.start()
        }
        highlightView?.invalidate()
    }

    /**
     * Immediately clears and detaches the highlight overlay.
     */
    fun clearHighlight() {
        currentRects = emptyList()
        pulseAnimator?.cancel()
        if (isAdded) {
            try {
                highlightView?.let { windowManager?.removeView(it) }
            } catch (_: Exception) {
            } finally {
                isAdded = false
                highlightView = null
            }
        }
    }

    private fun attachOverlay() {
        val wm = windowManager ?: return

        val view = object : View(context) {
            private val fillPaint = Paint().apply {
                isAntiAlias = true
                style = Paint.Style.FILL
            }
            private val strokePaint = Paint().apply {
                isAntiAlias = true
                style = Paint.Style.STROKE
                strokeWidth = 4f
                color = Color.parseColor("#00B4D8") // Teal accent
            }
            private val glowPaint = Paint().apply {
                isAntiAlias = true
                style = Paint.Style.STROKE
                strokeWidth = 10f
                color = Color.parseColor("#4000B4D8")
            }

            override fun onDraw(canvas: Canvas) {
                super.onDraw(canvas)
                if (currentRects.isEmpty()) return

                val alphaInt = (pulseAlpha * 255).toInt().coerceIn(0, 255)
                fillPaint.color = Color.argb(alphaInt, 0, 180, 216) // Soft pulsing teal

                for (rect in currentRects) {
                    val padded = RectF(
                        rect.left - 4f,
                        rect.top - 2f,
                        rect.right + 4f,
                        rect.bottom + 2f
                    )
                    // Draw outer soft glow
                    canvas.drawRoundRect(padded, 8f, 8f, glowPaint)
                    // Draw translucent fill
                    canvas.drawRoundRect(padded, 8f, 8f, fillPaint)
                    // Draw subtle border
                    canvas.drawRoundRect(padded, 8f, 8f, strokePaint)
                }
            }
        }

        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
        }

        try {
            wm.addView(view, params)
            highlightView = view
            isAdded = true
        } catch (e: Exception) {
            isAdded = false
            highlightView = null
        }
    }

    fun destroy() {
        clearHighlight()
        pulseAnimator = null
    }
}
